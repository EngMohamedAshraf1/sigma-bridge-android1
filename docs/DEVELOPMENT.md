# Development Guide

## Baseline

The current Private Chat development baseline is the `private-chat-6bb07de-fix` branch.

The branch history used for the 0.8.6 work is:

```text
6a17acb  Simplify Private Chat: add independent translation state
7af5676  Simplify Private Chat: decouple read receipts from translation
6bb07de  Simplify Private Chat: show original immediately and translate asynchronously
6624abf  Fix Private Chat receipt polling syntax on 6bb07de
b2eea8d  Set app version to 0.8.6
```

The current branch head is `b2eea8d0d4fdb94827ee72476b01d08d1e954a88` at the time this documentation was written.

The older experimental message-centric receipt commit is not part of this baseline.

## Requirements

The current Android project is configured for:

```text
compileSdk 35
targetSdk 35
minSdk 26
JDK 17
Java source/target 17
Kotlin JVM target 17
```

The project uses Kotlin, Jetpack Compose, AndroidX, Hilt, Supabase Kotlin libraries, Ktor, OkHttp, and kotlinx.serialization.

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

### Test H: update system

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

For v0.8.6, the source branch has been corrected to `versionCode=6` and `versionName=0.8.6` after the first APK/release was created with stale embedded version metadata.

## Documentation rule

Every architectural change should update the corresponding document under `docs/` in the same development cycle. The purpose is to make the repository independently understandable without relying on private chat history.
