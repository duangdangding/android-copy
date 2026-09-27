# ClipDitto (共享剪贴-卢)

An Android local clipboard history tool inspired by Ditto on Windows: floating bubble + automatic clipboard recording + one-tap paste via accessibility + LAN multi-device sync + LAN file sharing.

Companion PC project: [pc-lscopy (copy-pc)](https://github.com/duangdangding/pc-lscopy) — syncs clips and exchanges files with this app.

## Features

1. **Floating bubble**: after starting the service, a draggable bubble sits at the screen edge; tap to expand/collapse the clipboard panel. The panel never steals focus, can be moved and resized (position and size are remembered), and includes fuzzy search plus pause/resume recording.
2. **Auto recording**: copied text, images, videos, audio and files (including screenshots and screen recordings) are saved automatically; media is copied into the app's private directory for long-term storage. Optional Shizuku-based distraction-free reading (see "Technical notes").
3. **Tap to paste**: with the cursor in an input field, tapping a text entry in the floating panel pastes it directly (requires the accessibility service); multiple entries can be pasted in a row. Images/files/videos are put back on the system clipboard for manual pasting.
4. **Entry management**: fuzzy search (subsequence matching — "剪板" matches "剪贴板"), favorites pinned to top (★), detail view, per-entry deletion; configurable entry cap (100/300/500/1000/custom) that auto-purges the oldest non-favorite entries.
5. **Share-token recognition**: detects e-commerce / short-video share tokens (Douyin, Kuaishou, Pinduoduo, JD, Taobao, …) and offers an "Open in app" button; entries containing links get a browser shortcut.
6. **Time-range deletion**: last hour / today / last 7 days / last 30 days / custom date range / clear all, with media files cleaned up together; a second confirmation is shown when favorites are included.
7. **Backup & restore**: export to a zip (`clips.json` + media files); importing merges entries and restores media without overwriting existing data.
8. **LAN multi-device sync**: pair devices with a pairing code and sync clipboard history between them; manual sync with selectable range (latest N / a given day / all) or automatic incremental sync (every 30 s by default). Configurable content types (text/image/media/other), size cap (20 MB default), storage directory (`Download/ClipDitto` by default) and encrypted transfer (ECDH ephemeral keys + AES/GCM, off by default). Offline devices can be removed manually; synced entries from a specific device can be deleted separately.
9. **File sharing**: send any files to devices on the LAN without pairing — independent from clipboard sync. Online devices are discovered automatically (including the PC copy-pc client), or enter an IP manually; incoming files can be confirmed per-transfer or auto-accepted; transfer history supports search, opening files and batch deletion.
10. **In-app updates**: the About page shows the current version and can check GitHub Releases for new versions. No confirmation dialog and no changelog — tap "立即更新" to download directly (with progress, cancellable); after the SHA-256 check passes, the system installer is launched. The main screen only shows a lightweight toast when a new version is found.

## Build

Open this directory in Android Studio (Hedgehog or newer) and run after Gradle Sync finishes.

- AGP 8.5.0 / Kotlin 1.9.24 / Gradle 8.7 / JDK 17
- compileSdk 34, minSdk 26, targetSdk 34
- Database: Room 2.6.1 (KSP)

## Release

```powershell
.\release.ps1 -Version 4.5 -Message "what changed"
```

The script bumps versionCode, verifies a local build (`-SkipBuild` to skip), commits, tags and pushes. Pushing a tag triggers GitHub Actions to build a signed release APK, producing `lscopy_v<tag>.apk` plus a SHA-256 checksum file uploaded to Releases.

## Usage

1. Open the app and tap "开启监听+悬浮球" (start listening + floating bubble); grant the **overlay permission** when prompted.
2. Tap "开启无障碍粘贴" and enable "共享剪贴-卢" in system accessibility settings — required for tap-to-paste.
3. (Optional) Authorize Shizuku for completely distraction-free clipboard reading.
4. Copying anything in any app is recorded automatically; tap the bubble → tap an entry → the content is pasted into the current input field.

## Technical notes & known limitations

- **Android 10+ background clipboard restriction**: background apps may not read the clipboard. The default workaround adds a temporary 1 px transparent, focusable overlay window to grab focus when a change is detected, reads, then removes it immediately — the overlay permission must stay granted.
- **Shizuku distraction-free reading**: with Shizuku authorized, reads are forwarded to a shell process, never grabbing focus and with no UI side effects (no IME flicker, no biometric interruptions); it falls back to focus-grabbing automatically after repeated failures. Shizuku must be reactivated after every reboot.
- **Text pasting**: content is written to the system clipboard first, then the accessibility service performs `ACTION_PASTE` on the focused editable node. Without accessibility it degrades to "copied — long-press to paste manually".
- **Media pasting**: files in the private directory are exposed as URIs via `FileProvider` on the system clipboard; whether the target app accepts the paste is up to that app.
- **Screenshots / screen recordings**: these are image/video files at the system level — copying them (or copying in a file manager) stores them as media entries.
- Boot auto-start: with the boot permission granted, listening resumes after reboot (`BootReceiver`).

## Project layout

```
app/src/main/java/com/clipditto/app/
├── App.kt                        # Application + notification channels
├── data/                         # Room entity / DAO / database / repository (media on disk)
├── service/
│   ├── ClipboardService.kt       # Core: clipboard listener + bubble + panel + paste
│   ├── PasteAccessibilityService.kt  # Accessibility pasting
│   ├── ShizukuClipboard.kt       # Shizuku bridge reading (distraction-free)
│   ├── ClipboardShellService.kt  # Shizuku user service (shell privileges)
│   └── BootReceiver.kt           # Boot auto-start
├── sync/                         # LAN sync: discovery / server / client / crypto / settings
├── share/                        # File sharing: discovery / transfer / storage (independent)
├── backup/BackupManager.kt       # Zip backup / import
├── ui/
│   ├── MainActivity.kt           # History list, search, time-range deletion, backup, permission guide
│   ├── DevicesActivity.kt        # LAN devices and sync settings
│   ├── FileShareActivity.kt      # File sharing send/receive
│   ├── AboutActivity.kt          # About page and in-app update
│   └── HistoryAdapter.kt         # Shared list adapter for main UI and floating panel
└── util/                         # Fuzzy search / storage stats / token recognition / update checker
```
