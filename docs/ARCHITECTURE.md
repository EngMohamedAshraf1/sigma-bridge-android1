# Sigma Bridge Architecture

## Purpose

Sigma Bridge is a native Android application containing two distinct product areas:

1. **Private Chat** — a direct user-to-user chat with Google sign-in, Supabase-backed messaging, local history/outbox, delivery/read receipts, profiles, presence, notifications, and automatic translation.
2. **Telegram Bridge** — the older bot/translation pipeline. It is a separate subsystem and must remain isolated from Private Chat changes unless a task explicitly targets Telegram.

The current documentation is written against the `private-chat-6bb07de-fix` development branch and the `v0.8.6` workstream. The repository's historical README was written for an earlier Phase 7 state and should not be treated as the authoritative description of the current Private Chat implementation.

## Non-negotiable engineering rules

- Do not modify Telegram code while fixing or simplifying Private Chat.
- Do not delete users, devices, conversations, messages, receipts, profiles, or other Supabase data unless the owner explicitly requests deletion.
- Prefer diagnosis from current source code, Git history, and live database evidence over assumptions.
- Keep message transport, translation, receipts, and notifications as separate responsibilities.
- Preserve the original incoming message even when translation is delayed or fails.
- Treat the repository branch/commit selected for testing as the source of truth; do not mix files from stale local branches.

## High-level system

```text
                         Sigma Bridge Android
                                 |
                +----------------+----------------+
                |                                 |
         Private Chat                       Telegram Bridge
                |                                 |
       +--------+---------+             +---------+---------+
       |                  |             |                   |
   Foreground UI   Background worker   Telegram API      Gemini
       |                  |
       +--------+---------+
                |
            Supabase
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

## Private Chat responsibilities

### Authentication

`ChatAccountRepository` uses Supabase Auth and Google ID-token sign-in. Google is currently the only supported sign-in provider. An account is considered authenticated when the current Supabase user has an email; older anonymous sessions are not treated as signed-in Private Chat accounts.

### Identity

`ChatIdentity` owns the persistent user-facing `SB-...` identifier and a separate persistent device identifier. The public identifier is stored locally. The identity object also derives a deterministic conversation topic and a deterministic 256-bit conversation key from the two participant IDs.

The identity object can rotate the public ID only for a specific recovery case: Supabase reports `PUBLIC_ID_ALREADY_IN_USE` during registration. Normal operation must not rotate a user's ID.

### Profiles

`ChatProfileRepository` is responsible for reading and updating the current profile, looking up another user's profile, last-seen timestamps, user search, and avatar uploads. Profiles are associated with Supabase `auth.uid()` and a public Sigma Bridge ID.

### Conversations

A Private Chat conversation is logically a deterministic 1-to-1 relationship between two public identities. The client derives a stable conversation topic/key from the sorted pair of IDs, while Supabase provisions the authoritative `conversation_id` and membership records through `sigma_ensure_conversation`.

### Message transport

`ChatRepository` abstracts message delivery. The Supabase implementation sends encrypted ciphertext plus metadata through the `sigma_send_message` RPC. The client uses a locally generated UUID as `client_message_id`. Supabase assigns the authoritative message UUID and sequence number.

### Local history and delivery queue

`ChatHistoryStore` persists up to 200 messages per conversation. Its local key is a SHA-256-derived value from the conversation key, rather than a human-readable room code.

`ChatOutboxStore` persists messages waiting for successful remote delivery. Outbox state is conversation-scoped and survives process restarts.

### Unread state

`ChatUnreadStore` keeps unread message IDs separately from history. The UI clears unread state when messages become visible/read, while the background worker can add unread items for conversations that are not open.

### Conversation list

`ChatConversationStore` persists conversation metadata such as partner ID, display name, last message preview, timestamp, and avatar path. It keeps up to 100 conversations and sorts by recent activity.

### Foreground/background coordination

`ChatForegroundState.openPartnerId` is process-local state used only to tell background notification logic that a conversation is currently being viewed. It is not a durable source of truth and must never be treated as the remote conversation identity.

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

## Current baseline for v0.8.6 work

The code branch documented here has an app version of `0.8.6` / `versionCode 6`. The version metadata correction was committed after the original `v0.8.6` tag was created. Therefore a newly rebuilt APK from the current branch is the authoritative build for the corrected 0.8.6 metadata. Do not assume that every older APK attached to a historical release has identical embedded version metadata.
