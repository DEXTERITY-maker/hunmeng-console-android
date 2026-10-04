# Hunmeng Console Android

- Native Kotlin/Compose project; keep versionName 0.0.4-beta, versionCode 2 and UI v0.0.4beta until owner requests version bump.
- Use HTTPS Bot API directly, normal TLS; no redirects or automatic sendMessage network retries.
- Tokens stay only in process memory. Never read local Telegram credential files for building/testing, print tokens or add them to code, CI, SavedStateHandle, rememberSaveable, files, logs or backups.
- Mock Telegram in tests. Do not send real messages, modify webhook or bot configuration during development.
- Run ./gradlew testDebugUnitTest lintDebug assembleDebug on Ubuntu x64 using the checked-in GitHub Actions workflow. ARM Termux has only API 36 platform, no official native Build Tools.
- Keep command/polling cancellation, duplicate-cycle guards, offset, retry_after, Unicode limits and unknown delivery behavior covered by meaningful tests.
- Preserve RU/EN, user-custom greeting on language switch, all 150 memory events and explicit manual send/webhook confirmations.
- Foreground MVP only. Do not add a persistent background service.
- README.md and BUILD-RESULT.md record real builds and limitations. Never claim APK/device validation without evidence.

- Project location: /storage/emulated/0/Hunmeng Console. Run shell scripts via sh on shared Android storage. Invoke git with -c safe.directory="/storage/emulated/0/Hunmeng Console"; do not change global trust configuration. Keep Git fileMode disabled locally there; CI clones preserve tracked executable modes.
- The owner supplied design/app-icon-source.png. Use tools/PrepareLauncherIcon.java for deterministic resizing/padding. Do not replace this artwork with generated designs or the old favicon.
- NEXT-UPDATE.md records implemented web feature synchronization and pending owner device checks. BUILD-RESULT.md records the tested v0.0.4beta functional APK (release tag v0.0.4-beta-sync); reports/build-v0.0.3-beta.md and reports/build-v0.0.4-beta-icon.md preserve prior reports. Keep diagnostics allowlisted, command events argument-free, event error flags structured and all tabs attached to one in-memory ConsoleController. HTTP 403 is a permissions error, not a rejected token.

- v0.0.5 work is in codex/v005-account-session (draft PR #1), not a released version. The owner explicitly chose persistent encrypted Telegram account sessions on the device, deleted on logout, and approved the label/coverage “Доступные каналы и группы”. Bot API tokens remain memory only. Account session storage uses Android Keystore and noBackupFilesDir. Instrumentation must use the dedicated test vault alias/filename, never production session files. Native source/provenance and in-progress checks are recorded under reports/v0.0.5-*.md.
- CI also builds unsigned release APKs; permanent signing stays local with tools/prepare_android_release.py. Read tools/RELEASE-PREPARATION.md and reports/v0.0.5-release-candidate.md. Latest candidate from run 37174913542 compiles public Login Client ID 8883240190; backend config/begin/cancel succeeded, but Telegram Android assetlinks returned HTTP 404 and real Login/device checks remain pending. Candidate metadata is separate from updates/android.json; never treat it as the published v0.0.5 release. For ADB in the Codex process, env -u LD_LIBRARY_PATH -u LD_PRELOAD adb devices -l avoids its incompatible bundled C++ library; latest successful probe had no connected device.
