import importlib.util
import struct
import subprocess
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("release_preparation", Path(__file__).with_name("prepare_android_release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


def elf(machine=183, alignment=16_384):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<HH", data, 16, 3, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
    return bytes(data)


def badging(**options):
    return (f"package: name='{options.get('package', release.PACKAGE)}' versionCode='2' versionName='0.0.4-beta'\n"
            "minSdkVersion:'26'\ntargetSdkVersion:'36'\n" + ("application-debuggable\n" if options.get("debug") else ""))


class PreparationTests(unittest.TestCase):
    def test_metadata_is_read_from_apk(self):
        self.assertEqual(release.parse_badging(badging())["version_code"], 2)
        self.assertFalse(release.parse_badging(badging())["debuggable"])

    def test_debug_foreign_package_and_missing_metadata_are_rejected(self):
        for text in (badging(debug=True), badging(package="other.app"), "missing"):
            with self.assertRaises(release.PreparationError):
                release.parse_badging(text)

    def test_native_architecture_and_load_alignment(self):
        release.check_elf(elf(), 183)
        for data in (elf(alignment=4096), elf(machine=62), elf()[:80], b"not ELF"):
            with self.assertRaises(release.PreparationError):
                release.check_elf(data, 183)

    def test_archive_requires_both_native_libraries_and_licenses(self):
        with tempfile.TemporaryDirectory() as working:
            path = Path(working) / "fixture.apk"
            with ZipFile(path, "w") as archive:
                for abi, machine in release.NATIVE_MACHINES.items():
                    archive.writestr(f"lib/{abi}/libtdjsonjava.so", elf(machine))
                for license_name in ("TDLib-LICENSE.txt", "OpenSSL-LICENSE.txt"):
                    archive.writestr(f"assets/licenses/{license_name}", "synthetic fixture")
            self.assertEqual(set(release.inspect_archive(path)), set(release.NATIVE_MACHINES))
            with ZipFile(path, "w") as archive:
                archive.writestr("lib/arm64-v8a/libtdjsonjava.so", elf())
            with self.assertRaises(release.PreparationError):
                release.inspect_archive(path)

    def test_exact_single_permanent_signer_required(self):
        valid = f"Number of signers: 1\nSigner #1 certificate SHA-256 digest: {release.CERTIFICATE_SHA256}\n"
        release.verify_signer(valid)
        for text in (valid.replace("signers: 1", "signers: 2"), valid.replace(release.CERTIFICATE_SHA256, "0" * 64), valid + valid):
            with self.assertRaises(release.PreparationError):
                release.verify_signer(text)

    def test_tool_failure_does_not_expose_output(self):
        secret = "synthetic-sensitive-value"
        result = subprocess.CompletedProcess(["apksigner"], 1, secret, secret)
        with patch.object(release.subprocess, "run", return_value=result) as runner:
            with self.assertRaises(release.PreparationError) as caught:
                release.run_tool(["apksigner", "sign"])
            self.assertNotIn(secret, str(caught.exception))
            self.assertEqual(runner.call_args.kwargs["stdin"], subprocess.DEVNULL)
            self.assertEqual(release.run_tool(["apksigner", "verify"], allow_failure=True).returncode, 1)

    def test_public_or_wrong_signing_material_is_rejected(self):
        with tempfile.TemporaryDirectory() as working:
            directory = Path(working)
            directory.chmod(0o700)
            for name in ("release.jks", "store-password", "release-cert.der"):
                (directory / name).write_bytes(b"synthetic fixture")
                (directory / name).chmod(0o600)
            with self.assertRaises(release.PreparationError):
                release.private_signing_files(directory)
            with patch.object(release, "digest", return_value=release.CERTIFICATE_SHA256):
                self.assertEqual(len(release.private_signing_files(directory)), 2)
                (directory / "store-password").chmod(0o644)
                with self.assertRaises(release.PreparationError):
                    release.private_signing_files(directory)

    def test_candidate_is_checked_and_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as working:
            directory = Path(working)
            source = directory / "input.apk"
            source.write_bytes(b"synthetic APK")
            output = directory / "candidate"
            args = Namespace(apk=source, output_dir=output, source_commit="a" * 40, ci_run_id="123",
                             client_id="12345", app_id="54321", signing_dir=directory / "signing", version_code=2, version_name="0.0.4-beta")
            signing = {"release.jks": directory / "fake.jks", "store-password": directory / "fake-password"}

            def tool(arguments, **options):
                text = ""
                if arguments[0] == "aapt2":
                    text = badging() if arguments[2] == "badging" else 'A: android:host(0x1)="app54321-login.tg.dev"'
                if arguments[0] == "apksigner" and "--print-certs" in arguments:
                    text = f"Number of signers: 1\nSigner #1 certificate SHA-256 digest: {release.CERTIFICATE_SHA256}\n"
                if arguments[0] == "apksigner" and "--out" in arguments:
                    self.assertEqual(arguments.count("--ks-pass"), 1)
                    self.assertNotIn("--key-pass", arguments)
                    self.assertTrue(arguments[arguments.index("--ks-pass") + 1].startswith("file:"))
                    Path(arguments[arguments.index("--out") + 1]).write_bytes(b"synthetic signed APK")
                return subprocess.CompletedProcess(arguments, 1 if options.get("allow_failure") else 0, text, "")

            with patch.object(release, "run_tool", side_effect=tool), patch.object(release, "inspect_archive", return_value={"fixture": "hash"}), \
                    patch.object(release, "private_signing_files", return_value=signing):
                record = release.prepare(args)
                self.assertFalse(record["published"])
                self.assertFalse(record["installed_on_device"])
                self.assertEqual(record["login_configuration"], "requires_live_verification")
                self.assertEqual(record["telegram_login_client_id"], "12345")
                self.assertEqual(record["telegram_login_app_id"], "54321")
                self.assertTrue((output / "candidate.json").is_file())
                self.assertEqual(release.digest(output / record["filename"]), record["apk_sha256"])
                with self.assertRaises(release.PreparationError):
                    release.prepare(args)

    def test_login_mismatch_creates_no_candidate(self):
        with tempfile.TemporaryDirectory() as working:
            directory = Path(working)
            args = Namespace(apk=directory / "input.apk", output_dir=directory / "candidate", source_commit="a" * 40,
                             ci_run_id="123", client_id="0", app_id="0", signing_dir=directory / "signing", version_code=2, version_name="0.0.4-beta")
            with patch.object(release, "run_tool", side_effect=[subprocess.CompletedProcess([], 0, badging()),
                    subprocess.CompletedProcess([], 0, 'A: android:host(0x1)="app99999-login.tg.dev"')]):
                with self.assertRaises(release.PreparationError):
                    release.prepare(args)
            self.assertFalse(args.output_dir.exists())

    def test_failure_removes_only_new_candidate_directory(self):
        with tempfile.TemporaryDirectory() as working:
            directory = Path(working)
            source = directory / "input.apk"
            source.write_bytes(b"synthetic unsigned APK")
            args = Namespace(apk=source, output_dir=directory / "candidate", source_commit="a" * 40,
                             ci_run_id="123", client_id="0", app_id="0", signing_dir=directory / "signing", version_code=2, version_name="0.0.4-beta")
            results = [subprocess.CompletedProcess([], 0, badging()),
                       subprocess.CompletedProcess([], 0, 'A: android:host(0x1)="app0-login.tg.dev"'),
                       subprocess.CompletedProcess([], 1, ""), release.PreparationError("Synthetic alignment failure")]
            with patch.object(release, "run_tool", side_effect=results), patch.object(release, "inspect_archive", return_value={}), \
                    patch.object(release, "private_signing_files", return_value={}):
                with self.assertRaises(release.PreparationError):
                    release.prepare(args)
            self.assertFalse(args.output_dir.exists())
            self.assertEqual(source.read_bytes(), b"synthetic unsigned APK")


if __name__ == "__main__":
    unittest.main()
