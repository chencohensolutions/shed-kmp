# shed (Kotlin Multiplatform storage library)

Shared by Audio Logs (`chencohensolutions/transcriptor-kmp`, as its `shed/` submodule) and, next, the journal app
(`chencohensolutions/journal-client-kmp`); the storage is the owner's journal server (`journal-server-go`) first.
**Read [README.md](README.md) first.** The plan and the owner's decisions are in the Claude Doc "Shared storage hub
plan" (Nov 16, 2026).

## Conventions

- `core` has no network code; each storage is a backend module implementing `ShedBackend`.
- Apps work without storage: nothing goes out until the user pairs.
- Previews are never uploaded to storage (owner's call, Nov 16, 2026).
- Files shed keeps are versioned (`"v": 1`), written to a temp file and renamed; a damaged one is set aside, never
  fatal.
- Tests: `core` with a fake backend on okio's FakeFileSystem; `backend-journal` against a real server
  (`scripts/e2e.sh`). Don't mock the server where the end-to-end test can run it.
- The Android module is built only where the Android SDK is (settings.gradle.kts); the cloud has none and can't reach
  Google's Maven, so the Android Gradle plugin is on the classpath only then (root `build.gradle.kts`).

## Commands

```sh
./gradlew :core:jvmTest :backend-journal:jvmTest
./scripts/e2e.sh          # needs Go and ../journal-server-go (or JOURNAL_SERVER=<path>)
```
