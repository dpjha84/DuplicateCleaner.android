# Duplicate Cleaner (native Android, Kotlin + Jetpack Compose)

Scans shared storage for byte-identical files of **any type** (photos, videos, audio,
documents, downloads, archives…) and lets you delete the extras. Detection is by
content (size → 64 KB hash → full SHA-256), so renamed copies are caught. One copy per
group is always kept; the oldest is kept by default.

Skipped: files under 1 KB, hidden files/folders (names starting with `.`), `/Android`, and the app's own trash.

## Previewing before you decide
Tap any file in a group to open a full-screen preview: pinch-zoom for images, tap-to-play video,
play/pause for audio, page-by-page PDFs, and the start of text files. Other types (Word, Excel…)
show details and an "Open in another app" button. Previous/Next copy flips through the group, and
you can mark a copy for trash or keep it right from the preview.

## Choosing what to remove
- Everything except one copy per group is pre-selected. **Smart** is the default and picks the copy to keep
  by scoring where it lives (organised folders beat Downloads / WhatsApp / Telegram / cache folders),
  its name ("report (1).pdf" and "Copy of report.pdf" lose), and how many files sit beside it. Ties go
  to the oldest copy. Each group shows why that copy was chosen.
- When the scores are too close, the group is flagged **"Not sure about this one"** and listed first.
  Tap **Looks good** to accept, or change it; the "Show only these" filter lists just the uncertain groups.
  Smart is a heuristic and can be wrong, which is why everything goes to the trash first.
- Other rules: **Oldest**, **Newest**, **Shortest** path, or **Keep here** on a folder from the
  "Where the duplicates are" summary. You can still tick or untick individual files.
- Tuning: weights and the confidence margin are in `data/SmartKeeper.kt`.
- Files are **moved to a trash folder** (`DuplicateCleaner Trash` on internal storage, keeps the
  original folder structure, hidden from gallery apps via `.nomedia`), not deleted.
  **Restore all** puts them back; **Empty trash** deletes them permanently and frees the space.

## How scanning scales
1. Files are listed in parallel (top-level folders are scanned side by side).
2. Files are grouped by exact size; unique sizes are dropped immediately.
3. Same-size files get a sampled hash (start, middle and end, 64 KB each) in parallel. Files up
   to 192 KB are hashed fully here, so small-file matches are final.
4. Only files whose samples match get a full SHA-256, in parallel, with byte-based progress.

A pool of 2–4 workers is used (based on CPU cores) to speed things up without overheating the
phone. The app must stay open during a scan; there is no background service yet.

## Business model (freemium + light ads)
- **Free:** unlimited scanning, previews and trash/restore, plus **1 GB of cleanup**.
- **Rewarded ad:** +500 MB of free cleanup each time (free users only).
- **Pro, one-time in-app purchase** (`pro_unlock`): unlimited cleanup, no ads. No subscription.
- **Ads:** one banner on the results list and the rewarded ad. Never during scanning, previews or deleting.
- Tune `Entitlements.FREE_BYTES` / `REWARD_BYTES` in `billing/Entitlements.kt`.
- The allowance and Pro flag are enforced on the device, which is fine for a launch but can be bypassed on a
  rooted phone. Add server-side purchase verification if abuse appears.
- Setup steps for AdMob, Play Billing and the store forms: `docs/PLAY_STORE_CHECKLIST.md`.
  Permission declaration text: `docs/ALL_FILES_ACCESS_DECLARATION.md`. Future features: `ROADMAP.md`.

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

Optional, for real ads in release builds: `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `ADMOB_REWARDED_ID`.
Without them, Google's test ad IDs are used (safe for testing; no revenue). Debug builds always use test ads.

Keep `upload.jks` backed up and never commit it (it is in `.gitignore`). Enrol in Play App
Signing when you create the app in Play Console.

## UI revamp (v1.0)
- Bottom navigation: Home, Trash (with badge), Settings.
- Home: scan-type chips (photos, videos, audio, documents, archives, apps, other), lifetime stats.
- Results: smart keep with reasons, per-group Keep all / Clean all / Reset, type filters, "Which copy to keep?" sheet.
- Trash: thumbnails, preview, selective restore, delete forever, empty trash.
- Settings: plan and usage, min file size, trash vs delete, theme, privacy policy.
- New adaptive launcher icon; store assets in docs/store/ (icon-512.png, feature-graphic.png).
