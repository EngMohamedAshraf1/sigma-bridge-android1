# Sigma Bridge Architecture

## Purpose

Sigma Bridge is a native Android application containing two distinct product areas:

1. **Private Chat** — a direct user-to-user chat with Google sign-in, Supabase-backed messaging, local history/outbox, delivery/read receipts, profiles, presence, notifications, and automatic translation.
2. **Telegram Bridge** — the older bot/translation pipeline. It is a separate subsystem and must remain isolated from Private Chat changes unless a task explicitly targets Telegram.

The current documentation is written against the `private-chat-performance-fix` branch, source baseline `7173af8225da11c670b567716cb3ec116d99ae4f`, and the published `v0.8.14-telegram-audio-reliability` release. Older checkpoints remain historical context and must not be treated as the current source of truth.

For the historical evolution that led to the present architecture, see `docs/PROJECT_HISTORY.md`. That document is historical context, not a replacement for current source code or current database evidence.

## Non-negotiable engineering rules

- Do not modify Telegram code while fixing or simplifying Private Chat unless the task explicitly targets Telegram. v0.8.14 is an example of an explicit Telegram reliability task.
- Do not delete users, devices, conversations, messages, receipts, profiles, or other Supabase data unless the owner explicitly requests deletion.
- Prefer diagnosis from current source code, Git history, and live database evidence over assumptions.
- Keep message transport, translation, receipts, and notifications as separate responsibilities.
- Preserve the original incoming message even when translation is delayed or fails.
- Treat the repository branch/commit selected for testing as the source of truth; do not mix files from stale local branches.

## Architecture evolution

The major historical transition was from a lightweight notification prototype to a database-backed Private Chat.

```text
EARLY PRIVATE CHAT
Android → ntfy → partner device

                 ↓ reliability problems
                 │ HTTP 429 / connectivity issues
                 ↓

SUPABASE PHASE
Android → Supabase Auth / Postgres / RPC / Realtime
```

The old ntfy architecture was useful for proving the concept, but the project intentionally stopped treating ntfy as the long-term reliability solution. The Supabase phase introduced explicit server-side conversation/message/receipt concepts while keeping Telegram separate.

The identity/conversation model also became more explicit over time: persistent public IDs, separate device IDs, deterministic conversation keys/topics, encrypted payloads, local history/outbox/unread stores, and finally independent translation state.

For the full historical timeline and the rejected alternatives, see `docs/PROJECT_HISTORY.md`.

## High-level system

```text
                         Sigma Bridge Android
                                 |
                +----------------+----------------+
                |                                 |
         Private Chat                       Telegram Bridge
                |                                 |
       +--------+---------+             +---------+----------------+
       |                  |             |                          |
   Foreground UI   Background worker   Telegram API          Telegram Gemini
       |                  |             |                          |
       +--------+---------+             +------------+-------------+
                |                                    |
            Supabase                           translated text
                |
     +----------+-----------+
     | Auth / Profiles      |
     | Conversations        |
     | Messages             |
     | Receipts             |
     | Translation jobs     |
     +----------------------+
```

The application is a client plus several background components. Supabase is the remote persistence/coordination layer for Private Chat; it is not merely an optional cache.

## Telegram Bridge architecture

The Telegram subsystem is separate from Private Chat, but it is an active product path.

```text
Telegram getUpdates
      |
      v
TelegramRepositoryImpl
      |
      v
UpdateDispatcher
      |
      +--> VoiceMessageHandler
      |       |
      |       +--> TelegramDownloadRepository
      |
      +--> AudioMessageHandler
              |
              +--> MIME validation/normalization
              +--> TelegramDownloadRepository
      |
      v
GeminiTranslationRepository
      |
      +--> small audio: inlineData
      +--> larger audio: Files API
      |
      v
Gemini audio understanding + translation
      |
      v
SendTelegramMessageUseCase
      |
      v
Telegram translated-text reply
```

Telegram Voice uses OGG audio. The current Telegram Audio handler accepts MP3, AAC, OGG, FLAC, WAV, and AIFF. M4A and video-to-audio extraction are not yet part of the released path.

### Telegram audio reliability

v0.8.14 retries transient Gemini HTTP 408/500/503/504 failures with exponential backoff and jitter. If server-side transient failures persist, the repository retries the same audio request with gemini-3.5-flash. If the recovery window is exhausted, the outer Telegram translation loop can move to another configured key before returning the generic error.

Gemini Files API upload streams the local file from disk instead of first creating a full in-memory byte array.

The Telegram path does not run Whisper, FFmpeg, or a separate STT stage. Gemini receives the audio and returns translated text directly.

## Private Chat responsibilities

### Authentication

`ChatAccountRepository` uses Supabase Auth and Google ID-token sign-in. Google is currently the only supported sign-in provider. An account is considered authenticated when the current Supabase user has an email; older anonymous sessions are not treated as signed-in Private Chat accounts.

### Identity and account ownership

`ChatIdentity` owns the persistent user-facing `SB-...` identifier, a stable installation/device identifier, the selected partner account, the selected Supabase conversation UUID, and the local device role.

In the current account-identity v2 path, the canonical `SB-...` identity belongs to the authenticated Supabase account and can be restored on a fresh device. The server also registers a stable device record for each installation.

Legacy v1 helpers still expose deterministic topic/key derivation for compatibility, but the current v2 transport does not derive its encryption key from public IDs.

### Profiles

`ChatProfileRepository` is responsible for reading and updating the current profile, looking up another user's profile, last-seen timestamps, user search, and avatar uploads. Profiles are associated with Supabase `auth.uid()` and a public Sigma Bridge ID.

### Conversations

A Private Chat conversation is logically a deterministic 1-to-1 relationship between two public identities. The client derives a stable conversation topic/key from the sorted pair of IDs, while Supabase provisions the authoritative `conversation_id` and membership records through `sigma_ensure_conversation`.

### Message transport

`ChatRepository` abstracts message delivery. The current v2 Supabase path uses the authoritative conversation UUID and `sigma_send_message_v2`. The client still uses a locally generated UUID as `client_message_id`; Supabase assigns the authoritative server message UUID and sequence number.

### Local history and delivery queue

`ChatHistoryStore` persists up to 200 messages per conversation. Its local key is a SHA-256-derived value from the conversation key, rather than a human-readable room code.

`ChatOutboxStore` persists messages waiting for successful remote delivery. Outbox state is conversation-scoped and survives process restarts.

### Unread state

`ChatUnreadStore` keeps unread message IDs separately from history. The UI clears unread state when messages become visible/read, while the background worker can add unread items for conversations that are not open.

### Conversation list

`ChatConversationStore` persists conversation metadata such as partner ID, display name, last message preview, timestamp, and avatar path. It keeps up to 100 conversations and sorts by recent activity.

### Foreground/background coordination

`ChatForegroundState.openPartnerId` is process-local UI state only. The current background path is scoped by explicit account/conversation context and v2 conversation keys, so background processing does not depend on whichever partner happens to be open on screen.

`ChatNotificationService` handles background Private Chat work: inbox discovery, message observation, retries for pending outgoing messages, remote translation jobs, delivery receipts, and notifications. It supports more than one conversation and therefore uses explicit partner/conversation keys when processing background events.

## Message lifecycle

```text
User types message
       |
       v
ChatViewModel.send()
       |
       +--> append local message as PENDING
       +--> save to ChatHistoryStore
       +--> add to ChatOutboxStore
       |
       v
chatRepository.send()
       |
       v
sigma_send_message()
       |
       v
Supabase messages row
       |
       v
remove from outbox
       |
       v
mark local message SENT
       |
       +------------------------------+
                                      |
                                  peer device
                                      |
                                      v
                              decrypt message
                                      |
                                      v
                         emit ChatEvent.Message
                                      |
                     +----------------+----------------+
                     |                                 |
              save/show original                  send Read receipt
                     |
                     v
             translate asynchronously
                     |
            +--------+--------+
            |                 |
        success            failure
            |                 |
            v                 v
   replace displayed text   keep original text
```

## Translation lifecycle

Incoming translation is intentionally decoupled from message transport.

When a message arrives, `ChatViewModel` immediately creates a `ChatMessage` with:

- `originalText` = the decrypted incoming message
- `translatedText` = `null`
- `translationStatus` = `PENDING`
- `text` = the original message initially

The UI and local history are updated immediately. Read receipt submission is also triggered immediately and does not wait for translation.

Translation runs in a separate coroutine. On success, `text` and `translatedText` become the translated value and the status becomes `COMPLETED`. On failure, the original text remains visible and the status becomes `FAILED`.

This separation prevents a slow or unavailable translator from making an otherwise successfully delivered message disappear.

## Receipt lifecycle

The domain exposes only two receipt events:

- `ChatEvent.Delivered`
- `ChatEvent.Read`

The current `SupabaseChatRepository` on the documented baseline resolves the server message for a receipt by combining `conversation_id` with `client_message_id`. The later experimental change that removed conversation lookup from receipt submission is **not part of this baseline**.

Remote receipt state is represented in `message_receipts`. Supabase's `sigma_set_receipt` RPC verifies that the authenticated user can access the message and then upserts a receipt.

The client upgrades delivery state monotonically:

```text
PENDING -> SENT -> DELIVERED -> READ
```

A lower state must never overwrite a higher state.

## Background inbox behavior

The Private Chat background service can discover undelivered messages without the conversation being currently selected. This is important for first-contact messages and for receiving messages while the user is outside the chat screen.

For such messages, the service derives the history key and decrypts using the explicit sender/partner identity rather than mutating the currently selected global partner. This is one of the key rules that prevents cross-conversation leakage.

## Security model

Private Chat currently uses AES-GCM encryption with a key derived deterministically from the two participant IDs. Encrypted values use the `sb2:` payload prefix and contain a protocol version byte, a random 12-byte IV, and a 128-bit authentication tag.

This provides authenticated encryption for the current protocol, but the design should **not** be described as a modern asymmetric end-to-end key-exchange protocol. The conversation key is derived from application identities; there is no documented X25519-style ratchet or per-message asymmetric key exchange in the current implementation.

Do not place API secrets, service-role credentials, real user data, or private authentication material in source control or documentation.

## Android execution model

The app has two relevant long-running concepts:

### Legacy Telegram bridge service

`BridgeForegroundService` is the legacy Telegram bridge wrapper. It starts/stops `BridgeOrchestrator` and owns its own foreground notification. It uses `START_NOT_STICKY` in the current implementation.

### Private Chat background service

`ChatNotificationService` is the Private Chat background worker/service. Its responsibility is substantially broader: persistent message observation, inbox processing, pending-message retries, remote translation jobs, delivery receipts, and notifications. It is started for authenticated Private Chat use and can also be restarted after boot by `ChatBootReceiver`.

These two services must not be casually merged. They belong to different product areas.

## Package map

```text
com.sigmabridge.app/
├── data/auth/
│   └── GoogleSignInManager.kt
├── data/chat/
│   ├── ChatAccountRepository.kt
│   ├── ChatConversationStore.kt
│   ├── ChatCrypto.kt
│   ├── ChatForegroundState.kt
│   ├── ChatGeminiTranslationRepository.kt
│   ├── ChatHistoryStore.kt
│   ├── ChatIdentity.kt
│   ├── ChatInboxRepository.kt
│   ├── ChatLanguagePreferences.kt
│   ├── ChatNetworkState.kt
│   ├── ChatOutboxStore.kt
│   ├── ChatProfileModels.kt
│   ├── ChatProfileRepository.kt
│   ├── ChatTranslationRelayRepository.kt
│   ├── ChatUnreadStore.kt
│   ├── NtfyChatRepository.kt
│   ├── SupabaseChatModels.kt
│   ├── SupabaseChatRepository.kt
│   └── SupabaseSessionManager.kt
├── domain/chat/
│   ├── ChatEvent.kt
│   ├── ChatMessage.kt
│   └── ChatRepository.kt
├── presentation/chat/
│   ├── ChatViewModel.kt
│   └── chat/conversation screens and view models
├── data/update/
│   ├── GitHubUpdateChecker.kt
│   └── UpdateManager.kt
├── presentation/update/
│   └── UpdateBanner.kt
└── service/
    ├── BridgeForegroundService.kt
    ├── ChatNotificationService.kt
    └── ChatBootReceiver.kt
```

## Dependency direction

UI should depend on view models/use cases/repositories through interfaces rather than directly embedding Supabase or HTTP calls.

The important boundary is:

```text
Compose UI
   -> ViewModel
      -> Domain repository/service abstraction
         -> Supabase / Gemini / Android system APIs
```

Storage classes such as history/outbox/unread stores are local persistence helpers and should remain independent from UI widgets.

## Historical debugging lessons

The most important recurring failure class has been **implicit dependence on the currently selected conversation**. A delayed translation, receipt, inbox item, or background event must carry enough explicit context to update the correct conversation rather than whichever chat is open when the asynchronous operation completes.

Another recurring lesson came from the Telegram Gemini path: HTTP status and network transport failures must be classified separately. A transient network error is not evidence that an API key is invalid or out of quota.

The full historical record, including the old ntfy phase, key-rotation bug, rejected relay designs, and testing failures, is preserved in `docs/PROJECT_HISTORY.md`.

## On-demand Telegram video translation

Telegram Video and VideoNote are separate from the automatic Voice/Audio handlers. They are never translated merely because a video arrives.

A video is processed only when `@sigma_bridge_bot` is explicitly mentioned in the video caption, or when a message containing `@sigma_bridge_bot` replies to a Video/VideoNote. The video is downloaded, only its audio track is extracted locally with Media3, and the extracted AAC is passed to the existing Telegram Gemini audio translation path.

```text
Video / VideoNote
      |
      | explicit /translate
      v
VideoMessageHandler
      |
      v
TelegramVideoDownloadRepository
      |
      v
temporary video
      |
      v
MediaAudioExtractor -> Media3AudioExtractor
      |
      v
temporary AAC audio
      |
      v
existing GeminiTranslationRepository
      |
      v
translated text reply
```

The video frames are never sent to Gemini. Normal Voice and Audio updates continue to use their existing handlers and download paths.

## Current baseline for v0.8.6 work

The code branch documented here has an app version of `0.8.6` / `versionCode 6`. The version metadata correction was committed after the original `v0.8.6` tag was created. Therefore a newly rebuilt APK from the current branch is the authoritative build for the corrected 0.8.6 metadata. Do not assume that every older APK attached to a historical release has identical embedded version metadata.
