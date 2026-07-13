# Architecture

Tom Riddle Diary is organized as a platform-neutral product with independent clients and shared service concepts.

## Components

### Android client

`android/` contains the current production application.

Main responsibilities:

- capture and render stylus strokes;
- persist drafts, conversations, memory, and settings;
- export a clean parchment image for multimodal models;
- execute on-device LiteRT-LM inference;
- call OpenAI-compatible endpoints;
- manage model downloads and imports;
- display complete answers with a separate ink animation;
- provide privacy, diagnostics, and user guidance.

### Optional backend

`backend/` is a small authenticated proxy. It stores a provider key outside the mobile application, validates requests, calls the configured compatible API, and returns the structured answer.

It is optional. The Android application can also use an on-device model, a LAN server, a direct API, or writing-only mode.

### Preserved reMarkable implementation

`remarkable/` contains the original Rust/reMarkable project inherited from the upstream repository. It is kept for attribution, reference, and historical continuity. It is not the active product root and its old CI definitions are archived beneath that directory.

## Data flow

```text
S Pen strokes
    ↓
Normalized local stroke model
    ↓
Draft and conversation persistence
    ↓
Parchment-only image export
    ↓
Selected provider
    ↓
Structured answer parser and memory update
    ↓
Complete reply rendered as ink
```

## Product boundaries

- Platform code stays in a platform directory.
- Provider secrets never belong in committed source.
- App storage is local-first.
- Cross-conversation memory is optional and user-editable.
- Network behavior depends entirely on the provider selected in Settings.
- `main` must remain buildable; integration work belongs on `development`.
