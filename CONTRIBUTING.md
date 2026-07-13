# Contributing

This fork is the home of the multi-platform Tom Riddle Diary App.

## Where work goes

- Start from `development`.
- Use a focused `feature/`, `fix/`, or `docs/` branch.
- Merge working branches into `development`.
- Promote `development` to `main` only after CI and device testing.
- Do not open contribution pull requests against the upstream reMarkable repository.

## Expectations

- Preserve installed user data and the Android package ID unless a migration is included.
- Keep API keys and tokens out of source and logs.
- Avoid blocking model work on the Android main thread.
- Keep memory optional, visible, and removable.
- Maintain writing-only and on-device privacy guarantees.
- Update documentation when behavior changes.
- Include error logging for device-specific failures without recording secrets.

## Build

```bash
python -m py_compile backend/app/main.py
cd android
./gradlew :app:assembleDebug :app:assembleRelease
```
