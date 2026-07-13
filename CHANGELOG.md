# Changelog

## 0.5.2 — Reliable local replies

- Separated on-device handwriting transcription from answer generation.
- Blocked structured metadata, repeated `REPLY:` labels, and echoed questions from the visible page.
- Added fresh-conversation repair and factual verification for short value, date, and unit answers.
- Added parser regression tests based on real S22 Ultra failures.
- Applied the same visible-answer safety gate to proxy and OpenAI-compatible providers.

## 0.5.1 — Product-first repository

- Rebranded the product as Tom Riddle Diary.
- Introduced permanent `main` and `development` branch roles.
- Reorganized the repository around the multi-platform app.
- Preserved the inherited reMarkable implementation under `remarkable/`.
- Added product architecture, platform, development, release, contribution, and security documentation.
- Updated Android labels and documentation without changing the package ID.

## 0.5.0

- Added direct factual answer styles and structured response repair.
- Added deterministic local memory and imported custom instructions.
- Added conversation continuity, model catalog fallback, cached ink rendering, and debounced draft saves.
