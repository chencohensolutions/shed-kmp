# shed

A Kotlin Multiplatform storage library shared by the owner's apps: **Audio Logs** (transcriptor-kmp) first, the
**journal** app (journal-client-kmp) next. An app hands shed its finished files and reads files back; shed keeps the
upload queue, the cache and the list of what the storage holds, and talks to the storage through a backend module.
The plan, with the owner's decisions, is the Claude Doc "Shared storage hub plan" (Nov 16, 2026).

Apps work without any storage: until the user pairs one, nothing goes out.

## Modules

- `core` (Kotlin Multiplatform: JVM for Android, iOS): no network code.
  - `Shed`: the API below.
  - `UploadQueue` (`uploads.json`): files sent in pieces, resumed where the storage got to, kept across restarts.
  - `Cache` (`cache/`): 1 MiB blocks of files read by range, and whole files; least recently used go first, pinned
    files stay.
  - `RemoteList` (`remote.json`): the storage's files, kept up to date from its change list.
  - `ShedBackend`: what a storage module implements.
- `backend-journal`: the journal server (journal-server-go) as storage. `JournalBackend` pairs by the server's
  enrollment link (`journal://enroll?…&fingerprint=…`), sends with `startUpload` and `PUT /upload/{sha256}`, reads
  with `GET /media/{sha256}` and Range, lists with `changes`, checks with `/verify`, and finds the server again in the
  DHT when its address changes ("Finding the server"). `OkHttpJournalClients` (JVM and
  Android) pins the server's key or its own CA and presents the device's certificate.
- `android` (built only where the Android SDK is): `AndroidDeviceKeys` (the Android Keystore, StrongBox when there is
  one), `AndroidShed` (shed in the app's `files/shed/`), `ShedUploads` (WorkManager: the queue runs when the network
  allows, again after failures and restarts).

Later: a local-folder backend, other storage (WebDAV, Google Drive, a Syncthing-protocol client), previews made on the
phone, and watching folders and the photo library for the journal app (`source-photos`).

## The API

```kotlin
val shed = Shed(backend, FileSystem.SYSTEM, folder, keepListed = { it.about.kind == FileKind.AUDIO }, locator = …)
val sha256 = shed.put(file, FileAbout("Class.flac", "audio/flac", FileKind.AUDIO, takenAt = "…", source = "recording"), ref = momentId, from = fileSystem)
shed.runUploads()           // from the app's background work: Done, Offline (try later) or Unauthorized (pair again)
shed.uploads                // StateFlow: each file waiting, done or refused, with bytes sent
shed.open(sha256).read(position, length)   // range reads for a player, cached by block
shed.fetch(sha256) { got, of -> }          // the whole file on the phone, with progress
shed.sync(); shed.remote    // the storage's files
shed.verify(sha256)         // the storage holds it whole: the phone may delete its copy
```

A file handed over with a `ref` (the app's id for it) may move before it is sent: the app's `FileLocator` says where
it is when shed sends it (Audio Logs moves finished moments to a folder the user picked).

## Pairing

Each app pairs as a device of its own: Android ties a Keystore key to the app that made it, so two apps can't share
one. Shed sends the app's name (`app` in `enrollDevice`: `audio-logs`, `journal`); the server records it and lets
Audio Logs see sound recordings, which it hides from other devices by default. Servers before Nov 16, 2026 don't know
the field: shed enrolls again without it.

### One enrollment per phone

The user enrolls a phone once (owner's request, Nov 16, 2026), in whichever app comes first. Another app built with
shed finds it (`ShedSiblings.find`) and asks it (`ShedSiblings.ask`, started for a result); the paired app's
`SharePairingActivity` (in shed's manifest, so every shed app has it) asks the user, naming the app that asks, and
answers with `JournalBackend.siblingLink(app)`: an enrollment link whose code the server made for that app only, for 2
minutes. The asking app pairs with it like any link, gets its own key and certificate, and the server keeps it as one
more app of the same phone (`siblingEnrollmentCode`; revoked together).

## Finding the server

There is no DNS name and no relay. When the server stops answering where it was (its address changed), the journal
backend finds it the way the journal app does: it tries the server's other addresses (from the enrollment link and
`serverInfo.discovery`), then looks the server up in the BitTorrent DHT under the key the link names (`key=`), opens its
sealed record with the server's fingerprint, and tries the addresses it names (`discovery/`, from journal-client-kmp's
`discovery` package). The clients pin the server's key, so an address that answers is the server. The address that
answers becomes `server` in `pairing.json`, and the request is sent again there. The DHT is asked at most every 2
minutes; pairing falls back to it too when none of the link's addresses answers.

## Using it from an app

Add this repository as a git submodule (`shed/`) and `includeBuild("shed")` in the app's `settings.gradle.kts`; then
depend on `com.chencohensolution.shed:core:0.1.0`, `:backend-journal:0.1.0` and, in the Android app,
`:android:0.1.0`. The app's `Application` implements `ShedHost` so the upload work finds its `AndroidShed`.

## Commands

```sh
./gradlew :core:jvmTest :backend-journal:jvmTest   # the tests on the JVM (no Android SDK needed)
./scripts/e2e.sh                                   # against a real journal server built from ../journal-server-go
```

`scripts/e2e.sh` builds the server, makes a throwaway server folder (`journal-server setup --no-downloads`), starts it,
makes an enrollment code, and runs `JournalEndToEndTest`: pair as Audio Logs, upload through the queue, read ranges
back, find the file in the change list, verify, put it again (nothing sent), unpair.

## iOS

`core` and `backend-journal` build for iOS. Still to write: `DeviceKeys` in the Secure Enclave, a `JournalClients` on
Ktor's Darwin engine (pinning the server's key, presenting the Secure Enclave identity), and background uploads.
