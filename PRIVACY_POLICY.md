# Privacy Policy — Lumina HDR Media Player

**Effective Date:** November 12, 2026  
**Application Package:** `app.lumina.player`

Lumina ("the App") is built on a strict privacy-first architecture. **Lumina does not collect, transmit, store on external servers, sell, or share any personal data, usage analytics, or device identifiers.**

---

## 1. Zero Data Collection & Zero Telemetry
- **No Analytics or Tracking SDKs:** Lumina contains no third-party analytics, crash-reporting telemetry, behavioral tracking, or advertising SDKs.
- **No Accounts or Cloud Profiles:** Lumina does not require registration, sign-in, or cloud synchronization.
- **No Advertising ID:** Lumina never requests or accesses `com.google.android.gms.permission.AD_ID`.

---

## 2. How Permissions & Local Data Are Used

All media indexing, playback history, equalizer profiles, and preferences remain **100% local on your device** inside Android's sandboxed application storage (`Room` database and `Jetpack DataStore`):

| Permission / API | Purpose | Scope |
| :--- | :--- | :--- |
| `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` (Android 13+) & `READ_EXTERNAL_STORAGE` (Android 8–12) | Discover and play video and audio files stored on your device via Android Scoped Storage (`MediaStore`). | Processed strictly on-device; file names and metadata never leave your device. |
| Storage Access Framework (`OpenDocument` / `OpenDocumentTree`) | Open user-selected video files, external `.srt`/`.vtt`/`.ass` subtitles, SD cards, or USB drives without broad filesystem permissions. | Restricted exclusively to files and folders you explicitly grant access to. |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | Stream user-configured network media URLs (`HTTPS`, `HLS`, `RTSP`, `SMB`, `FTP`) and discover local-network `UPnP` / `DLNA` / `SMB` media servers on your LAN when initiated by you. | Network connections are made solely to media servers or stream URLs you enter or browse on your local network. |
| `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Continue audio/PiP media playback cleanly with system `MediaSession` transport controls while Lumina is backgrounded or in Picture-in-Picture mode. | Active only during media playback. |
| Google Play In-App Updates (`com.google.android.play:app-update`) | Check for and install Google Play Protect verified app updates through the official Google Play Store client. | Handled directly by Google Play Services under Google Play's terms. |

> **Strict Google Play Protect Compliance:** Lumina **never** requests `MANAGE_EXTERNAL_STORAGE` (All Files Access) or `REQUEST_INSTALL_PACKAGES` (sideloading APKs).

---

## 3. Data Retention & Deletion
- Resume timestamps, favorites, mounted folder URIs, saved network stream entries, and user preferences are stored locally in Lumina's private app storage.
- You can remove individual items at any time inside the App, or erase all stored state permanently by clearing App Storage or uninstalling Lumina in Android Settings.

---

## 4. Google Play Data Safety Summary
- **Data Collected:** None
- **Data Shared with Third Parties:** None
- **Tracking Across Apps/Websites:** None

---

## 5. Contact
For questions regarding this Privacy Policy or open-source licensing compliance, please open an issue in the official Lumina GitHub repository.
