# Riddle Diary Android app

Native full-screen Android diary for Samsung S-series / Ultra phones with an S Pen.

## Visual and S Pen experience

- Black-leather full-screen frame with a rough-edged aged parchment page.
- Pressure, speed, and stroke-direction-aware fountain-pen ink.
- Stylus-only mode by default for palm rejection.
- Touch eraser, S Pen button eraser, undo, redo, clear, and configurable hover gestures.
- The AI answer is completed in the background first, then slowly revealed as settled wet ink; no token-by-token chat typing is shown.
- Unfinished writing is autosaved and never cleared by a failed request.
- Screenshots and screen recording are allowed by default, with an optional secure-window toggle.

## Conversations and memory

History is organized by complete conversations rather than separate page messages. Tap the menu button to open, continue, or delete a conversation, and use the plus button for a clean session. Legacy pages are migrated into a **Previous pages** conversation.

Current-conversation exchanges provide follow-up context. Optional cross-session memory can include compact durable facts and selected recent context. Memory is visible, editable, removable, and can be disabled independently of conversation history.

## Provider-independent diary voice

The same answer wrapper is applied to every provider:

- enchanted but factual;
- plain direct;
- scholarly journal;
- warm companion.

The wrapper prioritizes accuracy, does not impersonate a named fictional character, and does not invent magical claims. Answer length is also configurable.

## AI provider modes

All choices are configured after installation in **Settings**:

1. **Writing only** — no AI and no page network request.
2. **Private Riddle backend** — safest option for a billed cloud API key.
3. **Direct OpenAI-compatible API** — runtime URL/key/model fields, with `/models` discovery.
4. **Local/OpenAI-compatible server** — vision model on the LAN, with `/models` discovery.
5. **On-device model** — compatible LiteRT-LM vision model running on the phone.

The Android build needs no `.env`. `backend/.env` is only for the optional private backend service.

## On-device model library

Open **Settings → Model library**.

- Search registered models and filter for vision support.
- Refresh an HTTPS JSON catalog.
- Download in the background with WorkManager and partial-file resume.
- Add arbitrary compatible HTTPS `.litertlm` URLs.
- Import a previously downloaded `.litertlm` file through Android Files.
- Choose CPU or GPU inference.
- Select an optional visible folder and open it from the app.

A catalog entry is not a guarantee of runtime compatibility. The package must match the bundled LiteRT-LM version and include image input for handwritten pages.

## Privacy and diagnostics

Settings contains a high-contrast readable privacy policy, local-data deletion, memory controls, screenshot protection, and bounded diagnostic logs. Logs automatically truncate between 256 KB and 4 MB and do not deliberately include keys or tokens.

## Open on macOS

1. Clone or pull the repository.
2. Open the repository's `android` directory in Android Studio.
3. Use JDK 17 and install Android SDK 36 if prompted.
4. Connect the Samsung phone with USB debugging enabled.
5. Click **Run**.

GitHub Actions validates backend syntax and builds both `app-debug.apk` and a minified unsigned release APK.

## Play Store preparation

Before public release, create and protect a permanent signing key, generate a signed Android App Bundle, add a store listing and screenshots, publish the privacy policy at a stable URL, add a support contact, complete the Data safety form, test on real devices, and review rights for all names and artwork.
