# Release process

## Versioning

The Android client uses `versionCode` and `versionName` in `android/app/build.gradle.kts`.

- Increment `versionCode` for every installable release.
- Use semantic `versionName` values such as `0.5.1`.
- Record user-visible changes in `CHANGELOG.md`.

## Validation

Before promoting `development` to `main`:

```bash
python -m py_compile backend/app/main.py
cd android
./gradlew clean :app:assembleDebug :app:assembleRelease
```

Test on a physical Samsung device:

- first launch and book-opening animation;
- S Pen input, palm rejection, button actions, undo, redo, and eraser;
- draft recovery;
- each configured provider mode;
- model download, resume, import, selection, and deletion;
- conversation history and memory;
- screenshots and privacy toggle;
- diagnostics and exported logs;
- long-page responsiveness.

## Store release

GitHub Actions currently produces a debug APK and an unsigned minified release APK. A public release additionally requires:

- a permanent protected signing keystore;
- a signed Android App Bundle;
- a stable hosted privacy-policy URL;
- store listing, screenshots, and support contact;
- Data Safety declarations that match the configured providers;
- content rating and device testing;
- a branding and intellectual-property review.

Do not commit signing keys or production secrets.
