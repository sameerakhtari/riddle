# Development workflow

## Long-lived branches

### `main`

Stable, reviewed, and build-tested. Installable artifacts should always be reproducible from this branch.

### `development`

Permanent integration branch. New work starts here and returns here before release.

## Working branches

Create a focused branch from `development`:

```bash
git fetch origin
git switch development
git pull origin development
git switch -c feature/model-library-search
```

Use:

- `feature/<name>` for new behavior;
- `fix/<name>` for defects;
- `docs/<name>` for documentation-only work;
- `release/<version>` only when a larger release needs stabilization.

Push and merge the working branch into `development`. Do not target the upstream reMarkable repository.

## Promoting to stable

When `development` is ready:

1. pull the latest `main` into `development` if required;
2. run the Android debug and release builds;
3. validate backend Python syntax;
4. test the APK on a physical device;
5. update `CHANGELOG.md` and the version;
6. open a pull request from `development` to `main`;
7. merge only when CI passes;
8. move `development` forward to the resulting `main` commit.

## Local checks

```bash
python -m py_compile backend/app/main.py
cd android
./gradlew :app:assembleDebug :app:assembleRelease
```

## Commit style

Use short imperative subjects:

```text
Improve local model warm-up
Fix malformed reply parsing
Document release signing
```

Keep unrelated changes in separate commits.

## Repository policy

The repository remains a GitHub fork for visible provenance. Product development occurs only in `sameerakhtari/riddle`. No pull request is opened against the upstream repository unless the owner explicitly changes that policy.
