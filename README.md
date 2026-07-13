# Tom Riddle Diary App

<p align="center">
  <strong>A stylus-first, private AI diary designed to feel like ink on parchment.</strong>
</p>

<p align="center">
  <a href="https://github.com/sameerakhtari/riddle/actions/workflows/android.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/sameerakhtari/riddle/android.yml?branch=main&label=build"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-MIT-b08d57"></a>
  <a href="android/"><img alt="Current platform" src="https://img.shields.io/badge/current%20platform-Android-3DDC84"></a>
  <a href="https://github.com/sameerakhtari/riddle/tree/development"><img alt="Development branch" src="https://img.shields.io/badge/branch-development-6f4e37"></a>
</p>

Tom Riddle Diary is a cross-platform diary project built around handwriting, local memory, and optional AI. The current production client is a native Android app for Samsung Galaxy devices with S Pen support. The product name is intentionally platform-neutral: iOS and other editions can live in this repository without changing the identity of the app.

## The experience

- Write naturally with an S Pen on a full-screen parchment surface.
- Keep finger input disabled for palm rejection, or enable it for testing.
- Use pressure-aware fountain-pen ink, eraser actions, undo, redo, autosave, and S Pen button gestures.
- Receive a complete answer only after generation finishes; the reply then appears as settled ink rather than streaming chat text.
- Continue conversations with local history and optional cross-conversation memory.
- Choose writing-only mode, an on-device model, a private proxy, a LAN model server, or a direct OpenAI-compatible endpoint.
- Download compatible LiteRT-LM models in the background and inspect bounded diagnostic logs from Settings.

## Current platforms

| Platform | Status | Location |
| --- | --- | --- |
| Android / Samsung S Pen | Active | [`android/`](android/) |
| Optional private AI proxy | Active | [`backend/`](backend/) |
| iOS | Planned | `ios/` when development begins |
| Original reMarkable implementation | Preserved for reference | [`remarkable/`](remarkable/) |

## Quick start

### Android Studio

1. Clone the repository.
2. Open the [`android`](android/) directory in Android Studio.
3. Use JDK 17 and install Android SDK 36 when prompted.
4. Connect the phone with USB debugging enabled.
5. Select the `app` configuration and press **Run**.

```bash
git clone https://github.com/sameerakhtari/riddle.git
cd riddle
git switch main
```

No `.env` file is required to build or run the Android app. Provider URLs, keys, models, memory, privacy, and S Pen behavior are configured inside the app after installation. `backend/.env` is used only for the optional private proxy.

See [`android/README.md`](android/README.md) for platform setup and [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) for the complete development workflow.

## AI modes

| Mode | Page leaves the phone? | Best for |
| --- | --- | --- |
| Writing only | No | Persistent handwritten notebook |
| On-device LiteRT-LM | No | Offline/private replies on supported hardware |
| Local server | LAN only | A stronger model running on another machine |
| Private proxy | Yes, to your proxy | Protecting a billed provider key |
| Direct compatible API | Yes, to the configured provider | Fast testing and personal use |

The same response layer applies factual-answer rules, memory context, answer length, answer style, and user-supplied instruction files across all providers.

## Repository layout

```text
.
├── android/               Native mobile client currently in production
├── backend/               Optional provider-key proxy
├── docs/                  Architecture, workflow, releases, and platform plans
├── remarkable/            Preserved original reMarkable implementation
├── .github/workflows/     Product CI
├── CHANGELOG.md
├── CONTRIBUTING.md
├── PRIVACY_POLICY.md
└── README.md
```

## Branch model

- `main` contains the latest reviewed, build-tested stable state.
- `development` is the permanent integration branch for ongoing work.
- `feature/<name>` and `fix/<name>` branches start from `development`.
- Feature work merges into `development`.
- Release-ready changes move from `development` to `main` only after the Android debug and release builds pass.
- Work in this fork is not proposed to the upstream reMarkable repository.

Detailed commands and release rules are in [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).

## Documentation

- [Android client](android/README.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Development and branching](docs/DEVELOPMENT.md)
- [Platform roadmap](docs/PLATFORMS.md)
- [Release process](docs/RELEASES.md)
- [Privacy policy](PRIVACY_POLICY.md)
- [Security](SECURITY.md)
- [Contributing](CONTRIBUTING.md)

## Status

The app is under active development. Debug and unsigned release APKs are produced by GitHub Actions. A public store release still requires a permanent signing key, a signed app bundle, a hosted privacy-policy URL, store assets, support contact details, real-device testing, and a review of third-party naming and artwork rights.

> **Branding note:** This is an unofficial fan-made project. It is not affiliated with, endorsed by, or sponsored by J. K. Rowling, Warner Bros., or their affiliates.

This repository was originally forked from [MaximeRivest/riddle](https://github.com/MaximeRivest/riddle); the original reMarkable implementation is preserved under [`remarkable/`](remarkable/), while this fork independently develops the multi-platform Tom Riddle Diary App and does not submit these changes upstream.
