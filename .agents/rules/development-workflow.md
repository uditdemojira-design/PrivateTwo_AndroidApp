# PrivateTwo Development & Release Workflow Rules

## 1. Incremental Code Changes (Default Mode)
- When the user asks for bug fixes, UI adjustments, or new features:
  - Modify the relevant code files.
  - Test or compile-check if necessary (`.\gradlew.bat compileDebugKotlin` or quick verify).
  - **DO NOT** increment `versionCode` or `versionName` in `app/build.gradle.kts`.
  - **DO NOT** run `.\gradlew.bat assembleRelease` or overwrite `release_artifacts/PrivateTwo-release.apk`.
  - Report the changes clearly so the user can review or request further changes.

## 2. Release & Version Bump (On-Demand Mode)
- **ONLY** trigger the full Release Workflow when the user explicitly asks with phrases like:
  - "Release Update"
  - "Build Release"
  - "/release"
  - "Ab release kardo" / "Ab APK update kardo"
  - "Publish version"
- When triggered, execute the 5-step Release Process:
  1. Increment `versionCode` (+1) and update `versionName` (e.g. `1.2.0`) in `app/build.gradle.kts`.
  2. Run `.\gradlew.bat assembleRelease`.
  3. Copy output APK from `C:\Users\asus\.gradle-build\PrivateTwo\app\outputs\apk\release\app-release.apk` to `release_artifacts/PrivateTwo-release.apk`.
  4. Update `app_version.json` with the new `versionCode`, `versionName`, and bullet points of all accumulated features/fixes since the last release.
  5. Present the user with the download link and a complete summary of the release.
