# Tom Riddle Diary — Android client

The Android client is the current production platform for Tom Riddle Diary. It is optimized for Samsung Galaxy devices with an S Pen while remaining installable on other Android devices that provide stylus or touch input.

## Core features

- Immersive leather-and-parchment writing surface.
- Stylus-only palm rejection by default.
- Pressure, speed, and direction-aware fountain-pen rendering.
- Pen, eraser, undo, redo, clear, S Pen button actions, and hover gestures.
- Local draft autosave and recovery.
- Conversation-based history and optional cross-conversation memory.
- Imported custom response instructions.
- Writing-only, on-device, local-server, private-proxy, and direct API modes.
- Background model downloads, manual model import, CPU/GPU inference selection, and bounded diagnostics.
- Full reply generation in the background followed by an ink-reveal animation.

## Open in Android Studio

1. Open the repository's `android/` directory.
2. Use JDK 17.
3. Install Android SDK Platform 36 and Build Tools 36.0.0 when prompted.
4. Connect an Android phone with USB debugging enabled.
5. Select the `app` configuration and press **Run**.

Command-line build:

```bash
cd android
./gradlew :app:assembleDebug :app:assembleRelease
```

Outputs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

## Configuration

No `.env` file is required for the app. All runtime configuration is available under Settings:

- AI provider mode
- endpoint URL, token, key, and model
- on-device model library
- answer style and length
- custom instruction file
- memory controls
- S Pen controls
- appearance and reply animation
- privacy and screenshot protection
- diagnostics and log limits

The Android package ID remains `com.sameerakhtari.riddle` so updates preserve installed data and downloaded models.

## On-device AI

The app uses LiteRT-LM packages with image input. A model appearing in a catalog does not guarantee compatibility with every phone or runtime version. On the Galaxy S22 Ultra, start with a smaller vision-capable E2B model and CPU inference before testing GPU.

Model files are downloaded with WorkManager, can resume from partial files, and may be imported manually through Android Files.

## Data

Handwriting, drafts, conversations, memory, logs, and model metadata are stored locally. The selected network provider receives the page and relevant context only when a network-backed mode is active. See the repository [privacy policy](../PRIVACY_POLICY.md).

## Release

The release build is minified but unsigned. Publishing requires a protected release keystore and a signed Android App Bundle. See [`../docs/RELEASES.md`](../docs/RELEASES.md).
