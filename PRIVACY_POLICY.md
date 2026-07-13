# Tom Riddle Diary Privacy Policy

**Effective date: 13 July 2026**

Tom Riddle Diary is designed to keep users in control of handwritten pages, conversations, model files, provider credentials, memory, and diagnostics.

## Data stored on the device

The Android app can store unfinished S Pen strokes, rendered page images, conversation transcripts, AI replies, conversation titles, compact memory facts, settings, downloaded model files, and bounded diagnostic logs. These files remain in app-private storage unless the user explicitly exports a log, selects a visible model folder, clears app data, or uninstalls the app.

## When data leaves the device

- **Writing only** and **On-device model** modes do not send diary pages to an AI endpoint.
- **Private backend**, **Direct API**, and **Local server** modes send the current page image and relevant conversation context to the endpoint configured by the user so that it can generate an answer.
- The selected provider or server has its own privacy and retention terms. Tom Riddle Diary does not silently choose an endpoint.
- The model catalog and model-download features contact the HTTPS URLs displayed or entered by the user.

## Credentials

Direct API keys, backend tokens, local-server tokens, and Hugging Face tokens are encrypted at rest using Android Keystore. A rooted or compromised device can weaken mobile security, so the private backend is recommended for keys with meaningful billing access.

## Conversation history and memory

History is grouped into conversations. Cross-session memory is optional, visible, editable, and removable in Settings. When enabled, relevant stored facts and earlier exchanges may be included with a request. Disabling cross-session memory does not delete conversation history.

## Diagnostics

Diagnostic logging is local, optional, and automatically truncated at the selected size limit. The app does not deliberately write API keys or tokens into logs. Users can view, export, clear, or disable logs.

## Screenshots and recordings

Screenshots and screen recording are allowed by default. The privacy toggle can enable Android secure-window protection.

## Advertising and sale of data

The app contains no advertising SDK and does not sell personal data. This policy must be updated before adding analytics, advertising, accounts, cloud sync, or materially different data handling.

## Retention and deletion

Local data remains until the user deletes a conversation, clears memory or logs, uses the in-app local-data deletion control, clears Android app storage, or uninstalls the app. Downloaded model files can be removed from Model Library.

## Permissions

- Internet access is used for configured AI endpoints, model catalogs, and model downloads.
- Notification permission can be used for background model-download progress.
- File access uses Android's system document picker and only the files or folders selected by the user.

## Contact

Before a public Play Store release, replace this section with a valid support email address or website and host this policy at a stable public URL. The Play Console Data safety form must match the providers and optional features included in the released build.
