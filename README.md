# Sigma Bridge (Android)

Sigma Bridge is a native Android application with two intentionally separated product areas:

- **Private Chat** — a 1-to-1 user messaging system backed by Supabase, with Google sign-in, profiles, encrypted messages, delivery/read receipts, local history/outbox, background notifications, and automatic translation.
- **Telegram Bridge** — a separate bot/translation subsystem. It remains isolated from Private Chat and is maintained explicitly when a Telegram task is requested.

## Current baseline

**Current development branch:** `private-chat-performance-fix`

**Current documented source baseline:** `7173af8225da11c670b567716cb3ec116d99ae4f`

**Latest release:** `v0.8.14-telegram-audio-reliability`

**Application version in source:** `0.8.14` (`versionCode 14`)

This documentation describes the current repository state, including the Telegram audio-reliability work shipped in v0.8.14 and the current Private Chat architecture.

This documentation describes the code as it exists in the repository, not an earlier project plan.

## What to read first

A new developer or a new AI-assisted development session should read these files in order:

1. [`docs/HANDOFF.md`](docs/HANDOFF.md) — how to take ownership without previous chat history.
2. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — system boundaries and component responsibilities.
3. [`docs/PRIVATE_CHAT.md`](docs/PRIVATE_CHAT.md) — message, conversation, identity, receipt, and background behavior.
4. [`docs/SUPABASE.md`](docs/SUPABASE.md) — database-facing contracts and RPCs.
5. [`docs/TRANSLATION.md`](docs/TRANSLATION.md) — Gemini and remote translation behavior.
6. [`docs/UPDATE_SYSTEM.md`](docs/UPDATE_SYSTEM.md) — GitHub Release checking and APK update flow.
7. [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) — local setup, testing, Git workflow, and release procedure.
8. [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) — evidence-first debugging guide.

The existing [`docs/supabase/`](docs/supabase/) directory contains SQL snapshots/reference scripts for the Private Chat database layer.

## Private Chat at a glance

```text
Google Sign-In
      |
      v
Supabase Auth
      |
      v
ChatAccountRepository
      |
      v
ChatIdentity
      |
      +---- public ID (SB-...)
      +---- device ID
      +---- deterministic conversation topic/key
      |
      v
ChatViewModel
      |
      +---- ChatHistoryStore
      +---- ChatOutboxStore
      +---- ChatUnreadStore
      +---- ChatConversationStore
      +---- ChatProfileRepository
      +---- ChatRepository
      +---- ChatTranslationService
      |
      +--------------------+
                           |
                           v
                  Supabase / Gemini

ChatNotificationService
      |
      +---- undelivered inbox
      +---- background messages
      +---- pending-message retries
      +---- Delivered receipts
      +---- remote translation jobs
      +---- notifications
```

## Core behavioral contract

The current Private Chat user experience is deliberately simple:

```text
Incoming message
      |
      +--> display original immediately
      +--> send Read independently
      +--> translate asynchronously
                |
                +--> success: display translated text
                +--> failure: keep original text
```

An incoming message must never disappear just because translation is delayed or unavailable.

Outgoing messages enter local history and the persistent outbox before remote delivery. This allows retry after transient failures.

Delivery state is independent of translation state:

```text
Delivery:    PENDING -> SENT -> DELIVERED -> READ
Translation: PENDING -> COMPLETED / FAILED
```

## Identity and conversation model

The user-facing public identity is an `SB-...` identifier persisted locally. A separate device identifier represents the installation.

A 1-to-1 conversation is derived symmetrically from the pair of participant public IDs. Sorting the IDs before hashing ensures both sides derive the same topic and conversation key.

Supabase owns the authoritative `conversation_id`. The Android client must not confuse:

```text
client_message_id  !=  server message UUID  !=  conversation_id  !=  device_id
```

Each identifier has a different purpose and lifecycle.

## Encryption

Private Chat retains the legacy `sb2:` payload for compatibility, but the current v2 transport uses `sb3:` encrypted messages.

In v2, the authoritative conversation is a Supabase conversation UUID and the encryption key is a random 256-bit conversation key recovered by authenticated members through Supabase and cached locally by conversation ID. The v2 key is not derived from public account identifiers.

Both generations use AES-GCM with a random 12-byte IV. The current design must not be described as Signal-style ratcheting E2E, forward secrecy, or an asymmetric key-exchange protocol because those mechanisms are not present.

## Supabase contract

Private Chat communicates with Supabase primarily through authenticated RPCs and controlled reads.

Important RPCs include:

```text
sigma_register_device
sigma_ensure_conversation
sigma_send_message
sigma_set_receipt
sigma_store_translation
sigma_request_translation
sigma_get_translation
sigma_claim_translation_jobs
sigma_complete_translation_job
sigma_fail_translation_job
```

The client-facing row contracts include message IDs, conversation IDs, sender/user/device identifiers, sequence numbers, ciphertext, nonce, and timestamps.

The database SQL under `docs/supabase/` is reference material. Before modifying production database objects, verify the live schema, RLS policies, grants, indexes, constraints, and function definitions.

## Background messaging

Private Chat has a dedicated `ChatNotificationService`. It can discover undelivered messages independently from the conversation currently open in the UI. It uses explicit partner context when processing background messages so that a notification for one conversation cannot accidentally use another conversation's encryption/history context.

An authenticated user can also have the service restarted after device boot by `ChatBootReceiver`.

## Translation

Private Chat uses a dedicated Gemini translation repository and a separate translation relay abstraction. Telegram has its own translation runtime and does not use the Private Chat translation service.

### Telegram audio

Telegram Voice and supported Telegram Audio messages use direct Gemini audio understanding and translation in one request. Small files use inline audio; larger files use the Gemini Files API. v0.8.14 adds transient-error recovery with exponential backoff/jitter and a fallback audio model.

Telegram Voice/Audio returns translated text as a Telegram reply. There is no separate Whisper/STT stage.

The currently implemented Telegram Audio MIME set is MP3, AAC, OGG, FLAC, WAV, and AIFF. M4A and video-to-audio extraction are not yet implemented.

The Private Chat supported language catalog currently includes English, Russian, Arabic, French, German, Spanish, Italian, Portuguese, Turkish, Simplified Chinese, Japanese, Korean, Hindi, Ukrainian, Polish, and Auto-detect. The MVP default pair is Russian -> Arabic.

## Update system

The Android app checks the GitHub `releases/latest` endpoint and compares its `tag_name` with `BuildConfig.VERSION_NAME`.

The current published release is `v0.8.14-telegram-audio-reliability`, which normalizes to `0.8.14`. The distributed APK is versionName `0.8.14` / versionCode `14`, so an installed v0.8.14 app does not advertise the same release again.

The v0.8.6 work exposed the original same-version update-loop failure mode, so release engineering must continue to verify the embedded APK version before publishing.

See [`docs/UPDATE_SYSTEM.md`](docs/UPDATE_SYSTEM.md) for the full flow and release checklist.

## Development requirements

```text
Android Studio: Ladybug or newer
JDK: 17
Android SDK: 35
minSdk: 26
targetSdk: 35
compileSdk: 35
```

The project uses Kotlin, Jetpack Compose, Hilt, Supabase Kotlin libraries, Ktor, OkHttp, and kotlinx.serialization.

Build on Windows:

```powershell
.\gradlew.bat assembleDebug
```

For a release build:

```powershell
.\gradlew.bat assembleRelease
```

## Repository safety rules

- Keep **Private Chat and Telegram changes task-scoped**. Do not modify Telegram while addressing a Private Chat defect unless the task explicitly targets Telegram. v0.8.14 is an example of an explicit Telegram reliability task.
- Never delete real users, devices, conversations, messages, receipts, profiles, or other Supabase data as a debugging shortcut.
- Do not commit secrets or private credentials.
- Use actual code/database evidence before changing architecture.
- Prefer clean feature/fix branches based on known commits.
- Update documentation when an architectural contract changes.

## Release history

| Release | State | Notes |
|---|---|---|
| `v0.8.5` | previous baseline | Stable baseline before the Private Chat simplification work. |
| `v0.8.6` | historical | Private Chat stability/syntax fixes plus aligned application version metadata. |
| `v0.8.7` | historical stable | Private Chat background transport-isolation improvements. |
| `v0.8.12` | historical | Private Chat reactions work. |
| `v0.8.13` | historical stable | Private Chat delivery/background-worker stability. |
| `v0.8.14` | current release | Telegram audio translation reliability and Gemini transient-failure recovery. |

## Current limitations

Sigma Bridge is an MVP rather than a complete general-purpose messenger. The repository should therefore be extended deliberately, preserving conversation isolation, message identity, translation separation, and the established Supabase authorization path.

For the detailed handoff, start with [`docs/HANDOFF.md`](docs/HANDOFF.md).
