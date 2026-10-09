#!/usr/bin/env python3
"""Sign a local release candidate. Never publishes or edits update metadata."""

import argparse
import hashlib
import json
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import BadZipFile, ZipFile

PACKAGE = "chat.hunmeng.console"
CERTIFICATE_SHA256 = "d052c3aa276facde5f2bab22713ae76e31fc1b3a1262bc7e6b284df5522a554a"
NATIVE_MACHINES = {"arm64-v8a": 183, "x86_64": 62}
MAX_APK_BYTES = 157_286_400


class PreparationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise PreparationError(message)


def run_tool(arguments, *, allow_failure=False):
    """Tool output may contain sensitive diagnostics. Never echo it on failure."""
    try:
        result = subprocess.run(arguments, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        raise PreparationError(f"Tool unavailable or timed out: {Path(arguments[0]).name}") from None
    if result.returncode and not allow_failure:
        raise PreparationError(f"Tool failed: {Path(arguments[0]).name}")
    return result


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def parse_badging(text):
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", text, re.M)
    sdk = re.search(r"^minSdkVersion:'([0-9]+)'", text, re.M)
    target = re.search(r"^targetSdkVersion:'([0-9]+)'", text, re.M)
    require(package and sdk and target, "APK metadata unavailable")
    require(package[1] == PACKAGE, "Unexpected package")
    require("application-debuggable" not in text.splitlines(), "Debug APK cannot be a release candidate")
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.]+)?", package[3]), "Invalid version name")
    require(int(package[2]) > 0 and int(sdk[1]) == 26 and int(target[1]) >= 36, "Unexpected version or SDK")
    return {"package": package[1], "version_code": int(package[2]), "version_name": package[3],
            "min_sdk": int(sdk[1]), "target_sdk": int(target[1]), "debuggable": False}


def check_elf(data, machine):
    require(len(data) >= 64 and data[:6] == b"\x7fELF\x02\x01", "Invalid native library")
    require(struct.unpack_from("<HH", data, 16) == (3, machine), "Unexpected native architecture")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    require(entry_size == 56 and 0 < count <= 128 and offset + count * entry_size <= len(data), "Invalid ELF headers")
    loads = 0
    for index in range(count):
        header = struct.unpack_from("<IIQQQQQQ", data, offset + index * entry_size)
        if header[0] != 1:
            continue
        loads += 1
        alignment = header[7]
        require(alignment >= 16_384 and alignment & (alignment - 1) == 0, "Native library lacks 16 KB alignment")
        require(header[2] % alignment == header[3] % alignment and header[2] + header[5] <= len(data), "Invalid ELF load segment")
    require(loads > 0, "Native library has no load segments")


def inspect_archive(path):
    require(path.is_file() and 0 < path.stat().st_size <= MAX_APK_BYTES, "Invalid APK size")
    libraries = {}
    with ZipFile(path) as archive:
        infos = archive.infolist()
        require(len(infos) <= 50_000 and len({i.filename for i in infos}) == len(infos), "Invalid archive entries")
        require(sum(i.file_size for i in infos) <= 512 * 1024 * 1024, "Archive too large")
        require(archive.testzip() is None, "APK CRC mismatch")
        for abi, machine in NATIVE_MACHINES.items():
            name = f"lib/{abi}/libtdjsonjava.so"
            require(name in archive.namelist(), "TDLib architecture missing")
            require(archive.getinfo(name).file_size <= 100 * 1024 * 1024, "Native library too large")
            data = archive.read(name)
            check_elf(data, machine)
            libraries[abi] = hashlib.sha256(data).hexdigest()
        for name in ("assets/licenses/TDLib-LICENSE.txt", "assets/licenses/OpenSSL-LICENSE.txt"):
            require(name in archive.namelist() and archive.getinfo(name).file_size > 0, "License missing")
    return libraries


def private_signing_files(directory):
    directory = directory.resolve()
    require(directory.is_dir() and not stat.S_IMODE(directory.stat().st_mode) & 0o077, "Signing directory must be private")
    paths = {}
    for name in ("release.jks", "store-password"):
        path = directory / name
        require(path.is_file() and not path.is_symlink() and not stat.S_IMODE(path.stat().st_mode) & 0o077,
                "Signing files must be private regular files")
        paths[name] = path
    certificate = directory / "release-cert.der"
    require(certificate.is_file() and not certificate.is_symlink() and digest(certificate) == CERTIFICATE_SHA256,
            "Signing certificate does not match the permanent key")
    return paths


def verify_signer(text):
    certificates = re.findall(r"certificate SHA-256 digest: ([a-fA-F0-9]{64})", text)
    require(certificates and {c.lower() for c in certificates} == {CERTIFICATE_SHA256} and
            re.findall(r"^Number of signers: ([0-9]+)$", text, re.M) == ["1"], "Unexpected APK signer")


def prepare(args):
    require(re.fullmatch(r"[a-f0-9]{40}", args.source_commit), "Full source commit required")
    require(re.fullmatch(r"[0-9]+", args.ci_run_id), "CI run ID required")
    require(args.client_id == "0" or re.fullmatch(r"[1-9][0-9]{4,15}", args.client_id), "Invalid public Client ID")
    require(args.app_id == "0" or re.fullmatch(r"[1-9][0-9]{4,15}", args.app_id), "Invalid public native App URL ID")
    require((args.client_id == "0") == (args.app_id == "0"), "Both Login identifiers are required")
    source = args.apk.resolve()
    metadata = parse_badging(run_tool(["aapt2", "dump", "badging", str(source)]).stdout)
    require(metadata["version_code"] == args.version_code and metadata["version_name"] == args.version_name,
            "APK version differs from expected version")
    manifest = run_tool(["aapt2", "dump", "xmltree", str(source), "--file", "AndroidManifest.xml"]).stdout
    hosts = re.findall(r'android:host[^\n]*="([^"\n]+)"', manifest)
    require(hosts == [f"app{args.app_id}-login.tg.dev"], "APK Login host differs from registered native App URL")
    libraries = inspect_archive(source)
    require(run_tool(["apksigner", "verify", str(source)], allow_failure=True).returncode != 0, "Unsigned release input required")
    signing = private_signing_files(args.signing_dir)
    output = args.output_dir.resolve()
    require(output != source.parent and output != args.signing_dir.resolve(), "Separate output directory required")
    require(not output.exists(), "Output directory exists; refusing to overwrite")
    output.mkdir(parents=True)
    try:
        with tempfile.TemporaryDirectory(prefix="prepare-", dir=output) as working:
            aligned = Path(working) / "aligned.apk"
            signed = Path(working) / "signed.apk"
            run_tool(["zipalign", "-P", "16", "4", str(source), str(aligned)])
            run_tool(["apksigner", "sign", "--ks", str(signing["release.jks"]), "--ks-key-alias", "hunmeng-console-release",
                      "--ks-pass", f"file:{signing['store-password']}",
                      "--v1-signing-enabled", "false", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                      "--v4-signing-enabled", "false", "--out", str(signed), str(aligned)])
            verify_signer(run_tool(["apksigner", "verify", "--verbose", "--print-certs", str(signed)]).stdout)
            run_tool(["zipalign", "-c", "-P", "16", "4", str(signed)])
            require(parse_badging(run_tool(["aapt2", "dump", "badging", str(signed)]).stdout) == metadata,
                    "Signing changed APK metadata")
            require(inspect_archive(signed) == libraries, "Signing changed native libraries")
            filename = f"Hunmeng-Console-{metadata['version_name']}-candidate.apk"
            record = {"status": "candidate", "platform": "android", **metadata, "source_commit": args.source_commit,
                      "ci_run_id": args.ci_run_id, "filename": filename, "size_bytes": signed.stat().st_size,
                      "apk_sha256": digest(signed), "certificate_sha256": CERTIFICATE_SHA256,
                      "telegram_login_client_id": args.client_id,
                      "telegram_login_app_id": args.app_id,
                      "telegram_login_redirect_uri": f"https://app{args.app_id}-login.tg.dev/tglogin",
                      "login_configuration": "not_configured" if args.client_id == "0" else "requires_live_verification",
                      "native_sha256": libraries, "published": False, "installed_on_device": False}
            signed.replace(output / filename)
            (output / f"{filename}.sha256").write_text(f"{record['apk_sha256']}  {filename}\n", encoding="utf-8")
            (output / "candidate.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return record
    except BaseException:
        shutil.rmtree(output)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--ci-run-id", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--client-id", default="0", help="Public BotFather Client ID only")
    parser.add_argument("--app-id", default="0", help="Public numeric ID from BotFather native App URL")
    parser.add_argument("--signing-dir", type=Path, default=Path.home() / ".config/hunmeng-console/signing")
    try:
        record = prepare(parser.parse_args())
        print(json.dumps(record, ensure_ascii=False, indent=2))
    except PreparationError as error:
        print(f"Release preparation failed: {error}", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, struct.error, BadZipFile):
        print("Release preparation failed; no tool diagnostics or secrets are printed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
