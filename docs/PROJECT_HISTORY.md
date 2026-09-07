# Sigma Bridge Project History

This document captures the historical evolution of Sigma Bridge as reconstructed from an earlier project conversation. It is intentionally separate from the current implementation documents so that historical architecture is preserved without being mistaken for the current source of truth.

## Evidence policy

Historical statements use the following labels:

- **CONFIRMED** — explicitly verified in source code, GitHub, screenshots, or testing in that historical conversation.
- **REPORTED** — stated during the conversation but not sufficiently re-verified.
- **INFERRED** — a direct architectural inference, not an independently verified fact.
- **UNKNOWN** — insufficient evidence was available and no assumption was added.

A historical statement marked CONFIRMED means it was confirmed at that time. It does **not** override newer code, newer tests, or the current branch documentation.

## 1. Starting concept

Sigma Bridge began as an Android application intended to help two people communicate across a language barrier, with automatic translation built into the messaging experience. A separate Telegram Translation Bot existed alongside that idea.

From the beginning, the project therefore contained two product paths:

```text
Sigma Bridge
├── Telegram Translation
│   └── Telegram → Gemini → Translation → Telegram
└── Private Chat
    └── User A ↔ User B
        ├── encrypted transport
        ├── delivery/read receipts
        └── translation
```

**CONFIRMED historical architecture.**

The key product decision that survived the evolution is that Telegram and Private Chat are separate subsystems. Work on one must not silently modify the other.

## 2. Early Private Chat: ntfy prototype

The first Private Chat transport used **ntfy** because it was simple enough for rapid prototyping.

The historical path was effectively:

```text
Android
   ↓
 ntfy
   ↓
Partner device
```

Local state handled history, pending delivery, unread state, and other chat behavior around that transport.

**CONFIRMED historical state.**

### Why ntfy was eventually abandoned as the long-term path

Real-device testing exposed reliability limitations, including HTTP `429` and connectivity failures, with particularly problematic behavior reported on Nox. The project therefore stopped treating further ntfy tuning as the long-term solution and began moving Private Chat toward Supabase.

**CONFIRMED historical decision.**

The important lesson is not that ntfy was "bad" in the abstract; it was useful for the prototype, but it no longer matched the reliability requirements of the evolving Private Chat product.

## 3. The move to Supabase

The next architectural phase introduced Supabase as the remote persistence and coordination layer for Private Chat:

```text
Android
   ↓
Supabase
├── Auth
├── Postgres
├── RPC
└── Realtime
```

This provided a server-side conversation model, durable message persistence, controlled writes through RPCs, and realtime event delivery.

The historical Supabase v1 implementation contained a concrete `SupabaseChatRepository` and DTO models rather than being only a design document.

**CONFIRMED historical state.**

However, the historical Supabase branch was not considered production-ready. At one point it still failed Kotlin compilation with unresolved Supabase/PostgREST/serialization references, and end-to-end device-to-device verification had not yet been established in that historical snapshot.

**CONFIRMED historical state.**

The current repository has since advanced beyond that historical snapshot. See `docs/ARCHITECTURE.md`, `docs/PRIVATE_CHAT.md`, and `docs/HANDOFF.md` for the present baseline.

## 4. Identity model evolution

Private Chat introduced a public Sigma identity separate from the backend authentication identity.

### Public user identity

Users receive a persistent public identifier in the form:

```text
SB-XXXXXX-XXXXXX-XXXXXX
```

The historical implementation generated this ID from random bytes and persisted it rather than regenerating it on every startup.

### Device identity

Each device has a separate persistent identifier, historically shaped as:

```text
android-${UUID}
```

A device is not the same thing as a user.

### Partner identity

The selected partner ID was persisted locally as `partnerId`.

### Backend authentication identity

The architecture explicitly distinguished:

```text
auth.uid()
    ≠
SB-... public ID
```

This distinction remains important. A public ID is the chat-facing identity; the Supabase authenticated user is the backend identity.

**CONFIRMED historical model.**

## 5. Deterministic conversation identity

The project eventually stopped treating a chat as merely "messages between two IDs" and introduced an explicit conversation identity.

Both the topic and conversation key were derived deterministically from the two participant IDs after sorting them.

Conceptually:

```text
myId + partnerId
      ↓
    sort
      ↓
join("|")
      ↓
   SHA-256
```

The historical topic then used a `sigma-bridge-` prefix plus a truncated hash. The conversation key used the full SHA-256 digest.

This was a deliberate isolation boundary for:

- messages
- receipts
- unread state
- local history
- translation

**CONFIRMED historical design.**

The authoritative remote `conversation_id` was still owned by Supabase and obtained through the conversation RPC.

## 6. Encryption model

Private Chat introduced local encryption before remote storage/transmission.

Historical implementation details included:

```text
Android
   ↓
ChatCrypto.encrypt()
   ↓
ciphertext + nonce
   ↓
Supabase
```

Incoming data followed the reverse direction:

```text
Supabase Realtime
   ↓
ciphertext
   ↓
ChatCrypto.decrypt()
   ↓
ChatEvent.Message
```

AES-GCM was used for message encryption in the historical Private Chat implementation. SHA-256 was used for deterministic conversation key/topic derivation.

A historical code comment also clarified that the conversation key was derived from the two Sigma identities. This is why the security documentation must not describe this design as a Signal-style asymmetric ratchet with forward secrecy.

**CONFIRMED historical security model.**

## 7. Server-side data model that emerged

The historical Supabase design used concepts corresponding to:

```text
auth user
  ↓
users
  ↓
devices
  ↓
conversations
  ↓
conversation_members
  ↓
messages
  ├── message_receipts
  └── message_translations
```

The following message fields were visible in the historical DTO model:

- `id`
- `conversation_id`
- `sender_user_id`
- `sender_device_id`
- `client_message_id`
- `sequence_number`
- `ciphertext`
- `nonce`
- `message_version`
- `created_at`
- `server_received_at`

Receipt DTOs included `message_id`, `user_id`, `device_id`, `delivered_at`, and `read_at`.

Translation storage used a `translated_ciphertext` field alongside source and target language.

**CONFIRMED historical DTO/data contract.**

The exact final SQL constraints, indexes, RLS policies, grants, and foreign keys were not fully verified in that historical conversation. They must therefore never be reconstructed from this document by assumption. Inspect the current `docs/supabase/` SQL and, for live database questions, inspect the actual Supabase project.

## 8. RPC-driven writes

The historical Supabase implementation established a controlled write path through server-side RPCs.

The main RPCs visible at that time were:

```text
sigma_register_device
sigma_ensure_conversation
sigma_send_message
sigma_set_receipt
```

Their historical responsibilities were:

- `sigma_register_device` — register a public identity/device with the authenticated backend user.
- `sigma_ensure_conversation` — locate or establish the 1-to-1 conversation.
- `sigma_send_message` — write the encrypted message and assign server-side ordering information.
- `sigma_set_receipt` — write Delivered/Read state.

A translation RPC/data path also existed conceptually, but its exact final function name was not established in that historical extraction.

**CONFIRMED for the four core RPC names; translation RPC name was UNKNOWN at that time.**

## 9. Realtime plus historical bootstrap

Private Chat did not rely on Realtime alone.

The historical Supabase repository:

1. initialized the conversation;
2. loaded existing messages from Postgres;
3. sorted them by `sequence_number`;
4. attached a Realtime listener for later inserts.

Receipt events were also observed through Realtime.

The resulting principle was:

```text
Postgres history = bootstrap
Realtime         = live updates
```

**CONFIRMED historical behavior.**

## 10. Local persistence architecture

The project deliberately split local state into focused stores rather than one monolithic database/cache.

Historical components included:

```text
ChatHistoryStore
ChatOutboxStore
ChatUnreadStore
ChatConversationStore
ChatSyncCursorStore
ChatLanguagePreferences
```

Their roles were approximately:

- **History** — durable local message history.
- **Outbox** — outgoing messages waiting for successful remote delivery.
- **Unread** — unread message IDs/state independent from history.
- **Conversation store** — conversation-list metadata and previews.
- **Sync cursor** — replay/resynchronization bookkeeping.
- **Language preferences** — local translation settings.

**CONFIRMED historical architecture.**

## 11. Message state evolution

The Private Chat message lifecycle developed into explicit transport states:

```text
PENDING
   ↓
SENT
   ↓
DELIVERED
   ↓
READ
```

The visible UI mapped these to the familiar single/double-check behavior.

Historically verified points included successful display of the Sent state and Delivered state. Read/bright double-check behavior went through several iterations and should be treated as a historical debugging thread rather than assumed to be identical to the current implementation.

**CONFIRMED historical testing with some read-state behavior remaining transitional.**

## 12. Receipt architecture and a recurring source of bugs

Receipts became a distinct event stream rather than being bundled into the message itself.

The historical domain model exposed:

```text
ChatEvent.Message
ChatEvent.Delivered
ChatEvent.Read
```

This was an important simplification because a message could be successfully delivered even when translation was still pending.

At the historical Supabase repository stage, receipt submission still resolved the server message using the active conversation plus the local `client_message_id`. That approach was later recognized as a potential source of cross-conversation coupling and was the subject of an experimental message-centric simplification.

The experimental receipt change was not treated as the stable historical baseline.

**CONFIRMED historical development direction.**

## 13. Multi-conversation isolation

A major class of bugs came from accidentally coupling asynchronous work to the currently selected partner.

The historical project went through a stage where listeners and callbacks were too tightly associated with one conversation. The architecture was widened to account for multiple saved conversations and to deduplicate event/message IDs.

This led to a continuing design rule:

> Any delayed operation must carry explicit conversation/partner context and must not blindly apply its result to whatever chat happens to be open now.

This rule later became especially important for translation, background inbox processing, and receipts.

**CONFIRMED historical problem and architectural lesson.**

## 14. Translation became intentionally independent

One of the most important architectural decisions was to separate message delivery from translation.

The intended flow became:

```text
message received
      ↓
show original immediately
      ↓
translation pending
      ↓
translation success/failure
```

Translation was therefore no longer allowed to block the fundamental message path.

The historical codebase contained two Chat-specific translation concepts:

```text
ChatGeminiTranslationRepository
ChatTranslationRelayRepository
```

These were separate from the Telegram translation repository.

**CONFIRMED historical separation.**

## 15. Telegram translation evolution

Telegram remained a separate legacy path.

Its historical architecture was:

```text
Telegram Bot
   ↓
Long Polling
   ↓
Telegram Repository
   ↓
message/voice handling
   ↓
Gemini
   ↓
Telegram response
```

The stable Telegram baseline identified in the historical conversation was:

```text
v0.2-phase11
```

Telegram also had a foreground-service architecture.

**CONFIRMED historical state.**

### Telegram voice path

Voice translation initially relied on Gemini Files API steps similar to:

```text
uploadFile
   ↓
getFile
   ↓
poll ACTIVE
   ↓
generateContent(fileUri)
   ↓
deleteFile
```

A later hybrid path used inline audio for smaller payloads and Files API for larger payloads.

The historical threshold recorded in the conversation was:

```text
audio <= 15 MiB  → inline Gemini
 audio > 15 MiB  → Files API
```

**CONFIRMED historical Telegram implementation.**

### Gemini key handling in Telegram

Five Gemini keys were used, and the project explicitly noted that they belonged to different Google Cloud projects.

The historical key-state rules were:

```text
429       → QUOTA_EXCEEDED
401/403   → INVALID
network   → retry same key before rotating
```

A specific bug caused the key cursor to advance after a network error. The fix was to retry the same key before rotation.

Historical fixing commit:

```text
5311016ce4666e2df5e13804727bb6e409fb734c
```

**CONFIRMED historical behavior and fix.**

## 16. What was explicitly rejected

Several tempting designs were discussed and rejected:

### Personal phone as relay

Rejected because a phone is not a dependable backend: it must stay reachable and suffers from battery, network, and OEM lifecycle constraints.

### Endless ntfy tuning

Deferred after the observed `429` and connectivity issues. The project direction became Supabase rather than indefinite ntfy optimization.

### Mixing Telegram and Chat

Rejected architecturally. The two systems have independent responsibilities and failure domains.

### Treating every Gemini failure as an invalid key

Rejected. Network failures are transient conditions, not proof of invalid credentials.

### Immediate Gemini key rotation on network error

Recognized as a bug and fixed by retrying the same key first.

### Voice calling / real-time audio stack in Private Chat

Explicitly out of scope for the relevant phase. `AudioRecord`, `AudioTrack`, live WebSocket audio, and similar transport complexity were not to be added merely to solve chat reliability.

**CONFIRMED historical decisions.**

## 17. Historical testing and failures that matter

The following failures were specifically observed during the historical development:

| Area | Observed result | Historical significance |
|---|---|---|
| ntfy | HTTP 429 | Main reason to move the chat transport toward Supabase |
| ntfy on Nox | connectivity failures | Demonstrated weakness of the prototype transport |
| Telegram voice | intermittent network/file-path failures | Drove hybrid audio handling and retry semantics |
| Gemini key rotation | network error advanced the key | Exposed a state-machine bug; later fixed |
| Android editor | large number of red errors while Gradle could still build | Demonstrated that IDE diagnostics must not be treated as build truth |
| Historical Supabase branch | unresolved Kotlin/PostgREST references | Supabase v1 was not build-clean at that snapshot |

**CONFIRMED historical evidence.**

## 18. Product philosophy that emerged

The project repeatedly favored:

```text
simple UX
small, targeted changes
clear ownership of responsibilities
real device testing
explicit evidence
```

over speculative rewrites.

The UI reference was repeatedly described as simple and familiar, closer to Telegram/WhatsApp than to a complex custom messenger.

**CONFIRMED product direction.**

## 19. Historical invariants

The following rules emerged as the durable project constraints:

1. Do not touch Telegram while working on Private Chat.
2. Do not delete Supabase users, conversations, messages, receipts, or other records without explicit authorization.
3. Diagnose from actual code/data instead of guessing.
4. Keep Chat transport separate from Telegram transport.
5. Keep translation separate from message delivery.
6. Keep `SB-...` identity separate from `auth.uid()`.
7. Keep device identity separate from user identity.
8. Keep conversation key/topic deterministic.
9. Encrypt message payloads before remote storage/transmission.
10. Do not treat network failure as Gemini quota exhaustion.
11. Retry transient network failures before key rotation in the Telegram Gemini path.
12. Do not treat discussed/planned work as implemented work.
13. Do not treat an editor error count as a build failure unless Gradle confirms it.
14. Avoid introducing voice-call or live-audio complexity into Private Chat without an explicit requirement.
15. Test a change before calling it fixed.

**CONFIRMED historical engineering rules.**

## 20. Historical roadmap snapshot

At the time of the extracted conversation, the rough status was:

### Done or established

- Telegram stable baseline
- Private Chat prototype
- public Sigma identities
- Partner ID
- deterministic conversation topic/key
- encrypted message transport
- local history/outbox/unread architecture
- basic message states
- Telegram voice hybrid path
- Gemini network retry fix

### Partial / evolving

- Read receipts
- multi-conversation behavior
- translation persistence
- background reliability
- profiles
- Supabase v1
- Google login

### Deferred

- complete ntfy replacement
- complete production Supabase rollout
- advanced background reliability
- complete profile/avatar system

This roadmap is historical only. The current branch may already have completed, replaced, or removed several of these items.

## 21. Why this history matters now

The purpose of preserving this history is not to revive old architecture. It is to explain why the current code contains seemingly redundant pieces, why some repositories are deliberately separate, and why several bugs repeatedly centered around identity, conversation isolation, and asynchronous work.

When the historical record conflicts with current code:

```text
current tested source
        >
current live database evidence
        >
current Git history
        >
historical conversation
```

The older conversation is a source of design intent and debugging history, not authority over newer implementation.

## 22. Current handoff rule

For the present project state, read this document together with:

- `docs/ARCHITECTURE.md` — current system architecture
- `docs/PRIVATE_CHAT.md` — current Private Chat behavior
- `docs/SUPABASE.md` — current backend/data contract
- `docs/TRANSLATION.md` — current translation architecture
- `docs/DEVELOPMENT.md` — current build/test workflow
- `docs/TROUBLESHOOTING.md` — known debugging paths
- `docs/HANDOFF.md` — current takeover instructions
- `docs/TELEGRAM_BOUNDARY.md` — explicit Telegram/Chat boundary

This document should be updated only when an additional historical source materially changes the project timeline, explains a previously unexplained design, or records a significant rejected/failed approach.
