---
name: release-apk
description: Bump app version, compile release APK, copy to release_artifacts, and update app_version.json for In-App Auto-Updater. Trigger ONLY when user explicitly asks to release the update.
---

# Release APK Skill

This skill packages all accumulated features and bug fixes into a new production release.

## When to Use
- User types `/release`, "Release Update", "Build Release", "Ab APK release kardo", or similar explicit commands.
- DO NOT run this automatically after individual code edits.

## Step-by-Step Procedure
1. **Determine New Version:**
   - Read current `versionCode` and `versionName` from `app/build.gradle.kts`.
   - Increment `versionCode` by 1.
   - Increment `versionName` (e.g. from `1.1.0` to `1.2.0`).
2. **Update `app/build.gradle.kts`:**
   - Update `versionCode` and `versionName`.
3. **Assemble Release APK:**
   - Run `.\gradlew.bat assembleRelease`.
4. **Copy to Artifacts:**
   - Copy `C:\Users\asus\.gradle-build\PrivateTwo\app\outputs\apk\release\app-release.apk` to `release_artifacts/PrivateTwo-release.apk`.
5. **Update In-App Manifest (`app_version.json`):**
   - Update `versionCode`, `versionName`, and compile all user-requested bullet points in `releaseNotes`.
6. **Report to User:**
   - Provide clickable link to `release_artifacts/PrivateTwo-release.apk`.
   - Explain that all installed apps will now receive the update prompt automatically via In-App Updater.
