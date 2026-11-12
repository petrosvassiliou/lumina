# Google Play Store Publishing Guide — Lumina (`app.lumina.player`)

This repository includes everything required to publish **Lumina – HDR Media Player** to **Google Play** (Smartphones, Foldables, Tablets, and **Googlebook OS**) as well as signed per-ABI **GitHub Releases**.

---

## 1. Ready-to-Use Store Assets in `fastlane/metadata/android/`

All store metadata follows the standard Google Play / Fastlane directory layout across **6 languages** (`en-US`, `de-DE`, `it-IT`, `fr-FR`, `es-ES`, `el-GR`):

| Asset / Metadata | Path | Specification |
| :--- | :--- | :--- |
| **Hi-Res App Icon** | `fastlane/metadata/android/en-US/images/icon.png` | `512 × 512` px, 32-bit PNG (`< 1 MB`), full-bleed OLED Smoked Crystal prism |
| **Feature Graphic** | `fastlane/metadata/android/en-US/images/featureGraphic.png` | `1024 × 500` px, 24-bit RGB PNG (`< 1 MB`, no alpha), Smoked Crystal & Glacier Ice |
| **Localized Titles** | `fastlane/metadata/android/<locale>/title.txt` | `≤ 30` characters per locale |
| **Short Descriptions** | `fastlane/metadata/android/<locale>/short_description.txt` | `≤ 80` characters per locale |
| **Full Descriptions** | `fastlane/metadata/android/<locale>/full_description.txt` | `≤ 4,000` characters per locale |
| **Release Changelogs** | `fastlane/metadata/android/<locale>/changelogs/10000.txt` | `≤ 500` characters (`versionCode = 10000`, `1.0.0`) |
| **Privacy Policy URL** | [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | `https://github.com/petrosvassiliou/lumina/blob/main/PRIVACY_POLICY.md` |

---

## 2. Generating Your Production Upload Keystore & Signing Releases

Never commit `.jks` or `.keystore` files to Git. Generate your private 4096-bit RSA upload keystore on your local machine:

```bash
chmod +x scripts/generate-upload-keystore.sh
./scripts/generate-upload-keystore.sh
```

Then open **[GitHub Repository Secrets](https://github.com/petrosvassiliou/lumina/settings/secrets/actions)** and add:

1. `LUMINA_KEYSTORE_BASE64` — contents of `lumina-upload-keystore.jks.base64.txt`
2. `LUMINA_KEYSTORE_PASSWORD` — your keystore password
3. `LUMINA_KEY_ALIAS` — `lumina-upload`
4. `LUMINA_KEY_PASSWORD` — your key password
5. *(Optional for automatic Play Console push)* `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` — Google Cloud Service Account JSON granted **Release Manager** access in Google Play Console.

Once configured, pushing any tag (`git tag v1.0.1 && git push origin v1.0.1`) or running **Actions → Publish Release & Google Play Bundle → Run workflow** automatically:
- Runs `testDebugUnitTest` and `lintDebug` (`abortOnError = true`).
- Builds and signs `Lumina-<tag>-playstore-signed.aab` (R8 minified + resource shrunk).
- Builds signed per-ABI APKs (`arm64-v8a`, `x86_64` for Googlebook OS, `armeabi-v7a`, and `universal`) + `SHA256SUMS.txt`.
- Publishes the GitHub Release and (if `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` is set) uploads the `.aab` straight to Google Play Console.

---

## 3. Google Play Console App Content & Policy Declarations

Use the exact declarations below inside **Google Play Console → Policy and programs → App content** for instant Play Protect & Android 15 (`targetSdk = 35`) review approval:

### A. Privacy Policy
- **Privacy Policy URL:** `https://github.com/petrosvassiliou/lumina/blob/main/PRIVACY_POLICY.md`

### B. Data Safety Questionnaire
- **Does your app collect or share any of the required user data types?** → **No**
- *Rationale:* Lumina indexes media locally in an on-device Room database and stores playback preferences in local Jetpack DataStore. Zero analytics SDKs, zero advertising IDs, zero cloud accounts, and zero telemetry servers exist in the app.

### C. Foreground Service Permission (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`)
- **Declared Permission:** `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` (in `LuminaPlaybackService`)
- **Task Type:** `Media playback (Audio / Video)`
- **Console Justification Text (copy & paste):**
  > Lumina is a dedicated video and lossless audio media player. When the user plays local or network audio/video media and switches apps, enters Picture-in-Picture (PiP), minimizes the Googlebook OS window, or locks the screen for background audio listening, `LuminaPlaybackService` runs a visible `MediaStyle` system notification bound to Android `MediaSession` so the user can pause, skip, seek, or stop active playback at any time. The service starts strictly upon user-initiated playback and stops immediately when playback stops.
- **Video Walkthrough Tip for Reviewers:** Record a 20-second screen capture opening a video/audio file in Lumina, pressing Home / locking the screen while audio continues playing, pulling down the Android Notification Shade to show the active Lumina `MediaSession` playback controls, and tapping Pause/Stop.

### D. Scoped Storage & Play Protect Compliance
- **All Files Access (`MANAGE_EXTERNAL_STORAGE`):** **Not requested** (uses Android 13+ `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`, Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED`, and Android Storage Access Framework `ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE`).
- **Install Unknown Apps (`REQUEST_INSTALL_PACKAGES`):** **Not requested** (uses the official Google Play In-App Updates library `com.google.android.play:app-update-ktx`).

### E. Form Factors & Large Screens Opt-In
Under **Test and release → Setup → Advanced settings → Form factors**, opt in to:
- **Phones & Tablets / Foldables** (`FoldingFeature` Tabletop Mode + Navigation Rail built in).
- **Google Play Games on PC / ChromeOS / Googlebook OS Desktop** (`android.hardware.touchscreen` `required="false"`, `android.hardware.type.pc` `required="false"`, `resizeableActivity="true"`, freeform window `<layout>` bounds, mouse hover states, right-click context menu, and hardware keyboard shortcuts built in).
