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
- NEXT-UPDATE.md records prepared v0.0.4beta changes. BUILD-RESULT.md records the tested v0.0.3beta APK until an actual new build is verified.
