# Development Guide

## Baseline

The current development baseline is the `private-chat-performance-fix` branch.

Current source head:

```text
15f33eefcecd9371340bcaf5b1d35e0fec3d3cc6
```

The build toolchain is aligned for API 36 compatibility: AGP 8.9.3 with Gradle 8.11.1 and `compileSdk 36`. Media3 Transformer remains the primary video-audio extraction path. For inputs whose audio track is already AAC, the extractor now falls back to direct `MediaExtractor` + Media3 `AacMuxer` extraction if Transformer fails; FFmpeg is not used. Media3 1.11.1 is built with Kotlin 2.2.0, so the project uses Kotlin 2.2.0 with KSP 2.2.0-2.0.2 and Hilt 2.57.2. `targetSdk 35`, `minSdk 26`, JDK 17, and the application version `0.8.14` / `versionCode 14` are intentionally unchanged.

Latest release: `v0.8.14-telegram-audio-reliability`.
The release code commit is `0602db25e9c9134f0cc1258222cf38ef74c4f9bd`; the branch subsequently records the version-metadata alignment to `versionName 0.8.14` / `versionCode 14`.

The branch history used for the 0.8.6 work is:

```text
6a17acb  Simplify Private Chat: add independent translation state
7af5676  Simplify Private Chat: decouple read receipts from translation
6bb07de  Simplify Private Chat: show original immediately and translate asynchronously
6624abf  Fix Private Chat receipt polling syntax on 6bb07de
b2eea8d  Set app version to 0.8.6
```

The 0.8.6 historical baseline was anchored at `b2eea8d0d4fdb94827ee72476b01d08d1e954a88`; this is not the current branch head.

The older experimental message-centric receipt commit is not part of this baseline.

## Requirements

The current Android project is configured for:

```text
compileSdk 36
targetSdk 35
minSdk 26
JDK 17
Java source/target 17
Kotlin JVM target 17
```

The project uses Kotlin, Jetpack Compose, AndroidX, Hilt, Supabase Kotlin libraries, Ktor, OkHttp, and kotlinx.serialization.

## Telegram audio functional tests

### Test I: Voice translation

- send a Telegram Voice message;
- verify the voice is downloaded;
- verify Gemini returns a translated text reply;
- verify the temporary local file is deleted.

### Test J: transient Gemini recovery

- reproduce a controlled transient Gemini 503/5xx condition;
- verify retry/backoff occurs;
- verify the fallback audio model is attempted for eligible server-side failures;
- verify the generic Sorry reply occurs only after the recovery path is exhausted.

### Test K: Telegram Audio MIME handling

Verify the current handler accepts MP3, AAC, OGG, FLAC, WAV, and AIFF and rejects unsupported formats.

### Test L: larger audio / Files API

- use a Telegram Audio file above the 12 MiB inline threshold but within Telegram's download ceiling;
- verify the Files API upload path is used;
- verify file status reaches ACTIVE;
- verify remote Gemini file cleanup is attempted.

## Telegram video functional tests

### Test M: normal video is ignored

- send a Telegram Video/VideoNote without `@sigma_bridge_bot`;
- verify no download, extraction, Gemini request, or reply is triggered.

### Test N: explicit video translation

- send a Video/VideoNote with `@sigma_bridge_bot` in its caption, or reply to a Video/VideoNote with a message containing `@sigma_bridge_bot`;
- verify the target video is downloaded;
- verify only the audio track is extracted;
- verify the extracted AAC enters the existing Gemini audio translation path;
- verify the final translated text is sent as a reply to the original video.

### Test O: video without audio

- use a video that has no audio track;
- verify a specific extraction failure is returned;
- verify no Gemini request is attempted.

### Test P: oversized video

- use a video whose declared Telegram size is over 20 MB;
- verify the handler rejects it before download.

### Test Q: Voice/Audio regression

- send existing Voice and Audio messages;
- verify their handlers and translation behavior remain unchanged.

## Local configuration

The project reads environment-like values from `local.properties` through Gradle. The checked-in repository should contain only the example file and never real credentials.

Do not commit:

- Supabase service-role keys
- Gemini API keys
- Telegram bot tokens
- Google OAuth secrets that are not intended to be public
- real production user data

## Build

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

For a release build:

```powershell
.\gradlew.bat assembleRelease
```

The release build is minified in the current Gradle configuration.

## Branching strategy

Prefer short-lived feature/fix branches based on a known clean commit. When the local working tree becomes unreliable, create or select a clean GitHub branch and reset the local branch to its remote counterpart rather than manually copying individual files.

This project has previously suffered from local/remote divergence and manual conflict resolution. For future work:

```text
known-good commit
       |
       +--> feature branch
       |
       +--> build/test
       |
       +--> commit
```

Avoid mixing unrelated local modifications into a bug-fix branch.

## Safe Git recovery

When a pull fails because local changes would be overwritten:

```text
1. Stop.
2. Inspect git status and git diff.
3. Preserve the local work with a commit or stash if it matters.
4. Do not use reset --hard until the desired remote baseline is explicitly identified.
5. Prefer a clean branch from the intended GitHub commit.
```

If a rebase is already in progress and the goal is to abandon it:

```powershell
git rebase --abort
```

Only use `git reset --hard <known-remote-ref>` when you intentionally want the local working tree to become that exact remote snapshot.

## Android Studio workflow

Recommended workflow:

```text
Git fetch
  -> verify branch
  -> verify commit
  -> sync Gradle
  -> build
  -> install on test device
  -> functional test
  -> inspect git diff/status
```

Do not use Android Studio's AGP upgrade assistant as part of routine application bug fixing. Dependency upgrades are separate engineering changes and should be isolated from feature debugging.

## Private Chat functional test matrix

### Test A: authentication

- fresh install/sign-in
- Google sign-in succeeds
- authenticated profile appears
- Sigma public ID remains stable after restart

### Test B: outgoing message

- type text
- message appears locally immediately
- delivery state starts at PENDING
- successful remote send removes the message from the outbox
- state becomes SENT

### Test C: incoming message

- send from peer device
- message appears using original text before translation completes
- original is preserved in history
- translation eventually replaces display text on success

### Test D: translation failure

- make translation unavailable
- incoming message must remain readable in its original language
- delivery/read behavior must still operate independently

### Test E: receipts

- peer receives message
- sender eventually sees DELIVERED
- opening/read state produces READ
- receipt processing must not wait for translation

### Test F: conversation isolation

- use two separate partner IDs when test data allows
- open conversation A
- open conversation B
- receive a delayed translation for A
- verify it updates A history, not B UI
- verify background message processing uses explicit partner context

### Test G: restart

- send while receiver is closed
- reopen receiver
- verify inbox/background worker retrieves the message
- verify unread/read behavior

### Test R: update system

- install an older APK
- publish a newer release
- launch and verify banner
- install new APK
- verify the installed version equals the release version
- reopen app and verify the same version is not repeatedly advertised as an update

## Code-review rules

A Private Chat change should answer:

1. Which layer owns this responsibility?
2. Which identifier is authoritative?
3. Can the operation accidentally use the currently selected partner instead of the explicit conversation?
4. What happens if the network fails?
5. What happens if translation fails?
6. What happens if the app is backgrounded or restarted?
7. Does the change alter Telegram code? If yes, stop unless explicitly requested.

## Logging and debugging

When diagnosing a chat issue, prefer structured facts:

```text
app version
branch/commit
current partner ID
message client ID
server message ID
conversation ID
sequence number
delivery/read timestamps
translation status
```

Never paste secret credentials into logs or issue reports.

## Release process

The intended release sequence is:

```text
1. select known-good branch
2. increment versionCode/versionName
3. build APK
4. test APK on real device(s)
5. create tag
6. create GitHub Release
7. attach exact APK built from that tag/version
8. verify update checker
9. record release notes
```

For v0.8.6, stale embedded metadata caused a same-version update loop and was corrected. For v0.8.14, the distributed APK is versionName 0.8.14/versionCode 14 and the release tag normalizes to 0.8.14.

## Documentation rule

Every architectural change should update the corresponding document under `docs/` in the same development cycle. The purpose is to make the repository independently understandable without relying on private chat history.
