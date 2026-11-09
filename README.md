# Duplicate Cleaner (native Android, Kotlin + Jetpack Compose)

Scans shared storage for byte-identical files of **any type** (photos, videos, audio,
documents, downloads, archives…) and lets you delete the extras. Detection is by
content (size → 64 KB hash → full SHA-256), so renamed copies are caught. One copy per
group is always kept; the oldest is kept by default.

Skipped: files under 1 KB, hidden files/folders (names starting with `.`), and `/Android`.

## Permissions
- Android 11+: **All files access** (`MANAGE_EXTERNAL_STORAGE`), granted by the user in Settings.
- Android 8–10: `READ/WRITE_EXTERNAL_STORAGE`.

> Google Play restricts `MANAGE_EXTERNAL_STORAGE`. Publishing needs the "All files access"
> declaration in Play Console and approval is not guaranteed. If it is rejected, switch to
> a MediaStore-only scan (media files, no special permission).

## Build locally
Open in Android Studio (JDK 17) and run. CI installs Gradle 8.7 itself; no wrapper is committed.

## CI: APK and AAB (`.github/workflows/build.yml`)
Runs on every push to `main`, on `v*` tags, on PRs, and manually.

| Artifact | Use |
|---|---|
| `app-debug-apk` | Install on a device for testing |
| `app-release-aab` | Upload to Play Console (signed if secrets below are set) |
| `app-release-apk` | Signed release APK (only when secrets are set) |

Tagging `v1.0.0` also attaches the debug APK and AAB to a GitHub Release.
`versionCode` is the workflow run number, so each upload to Play is higher than the last.

### Signing secrets (Settings → Secrets and variables → Actions)
Create an upload key once:

```
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 upload.jks     # macOS: base64 -i upload.jks
```

Add these repository secrets:
`KEYSTORE_BASE64` (output above), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (e.g. `upload`), `KEY_PASSWORD`.

Keep `upload.jks` backed up and never commit it (it is in `.gitignore`). Enrol in Play App
Signing when you create the app in Play Console.
