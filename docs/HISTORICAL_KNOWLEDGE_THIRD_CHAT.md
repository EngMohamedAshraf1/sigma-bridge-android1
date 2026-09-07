# Historical Knowledge — Third Project Conversation

This document preserves the third historical Sigma Bridge conversation as a source of project history. It is intentionally separate from the current implementation baseline. A statement being confirmed in this historical conversation means it was verified at that time; it does not override newer source code, live database evidence, tests, or later commits.

## Evidence discipline

Historical facts from this conversation are retained with the same distinction used elsewhere:

- **CONFIRMED (historical):** supported by code, SQL, GitHub, database inspection, or testing in that conversation.
- **REPORTED:** stated but not independently established.
- **INFERRED:** direct architectural interpretation.
- **UNKNOWN:** not established by the available evidence.

## 1. Product evolution

The Private Chat product evolved from a simple translated 1-to-1 chat into a user/account/device/conversation system. The historical target became:

```text
Account
  -> Profile
     -> Username
        -> Chat
```

The original technical concepts remained underneath this UX abstraction:

```text
Supabase Auth user
Public Sigma ID
Device ID
Partner ID
Conversation ID
Conversation Key
Conversation Topic
```

This was a gradual migration rather than a clean rewrite. **CONFIRMED historical.**

The product goal was a familiar messaging flow: share/search a username, start a conversation, and allow the recipient to receive a first message without having to search for the sender first.

## 2. Concrete historical Supabase evidence

The conversation included direct inspection of a Supabase project identified as:

```text
project: sigma-bridge-android
project ref: qcxorfsbwxprfhmsyzqu
region: eu-west-1
status at inspection: ACTIVE_HEALTHY
```

The historical snapshot reported approximately:

```text
users               40 rows
devices              7 rows
conversations        6 rows
conversation_members 12 rows
sessions              6 rows
messages            128 rows
message_receipts    114 rows
translation_jobs     29 rows
message_reactions     0 rows
```

These are historical observations from that inspection, not current database counts and not fixed limits.

## 3. Real-account identity evidence

The conversation verified that Google-authenticated users existed in Supabase Auth and were linked to `public.users`.

One inspected Google account was non-anonymous and its Auth UUID matched the corresponding `public.users.id`. Other historical test accounts included a user referred to as `test3`.

The important architectural fact is:

```text
auth.uid() / Auth UUID
        !=
SB-... public identity
```

The public ID is a user-facing Sigma Bridge identifier; the Supabase UUID is the backend identity.

## 4. Device model

The historical `public.devices` model contained user linkage, an identity public key field, and timestamps. Multiple devices could belong to one user.

The conversation explicitly observed a user with two registered devices and another test identity with multiple registered devices. This made receipt and background behavior a real multi-device concern rather than merely a theoretical future feature.

## 5. Conversation and membership model

The inspected historical schema included:

```text
conversations
conversation_members
```

Membership carried at least:

```text
conversation_id
user_id
device_id
role
joined_at
```

with roles such as `MEMBER` and `OWNER` appearing in the historical schema discussion.

The remote `conversation_id` remained authoritative, while the client continued to derive deterministic conversation key/topic values from participant identities.

## 6. Message data model

The historical `messages` table included:

```text
id
conversation_id
sender_user_id
sender_device_id
client_message_id
sequence_number
ciphertext
nonce
key_version
message_version
created_at
server_received_at
```

Historical unique constraints reported were:

```text
(conversation_id, client_message_id)
(conversation_id, sequence_number)
```

This is important when explaining why a local client UUID and the authoritative server message UUID are two related but distinct identifiers.

## 7. Receipt design — a critical historical discovery

The historical `message_receipts` model included:

```text
message_id
user_id
device_id
delivered_at
read_at
```

The historical database snapshot reported a primary key of:

```text
(message_id, device_id)
```

This made receipts explicitly device-level in the database design.

At the same time, parts of the client/domain logic treated receipts in a more user-centric way. The conversation identified this device-vs-user semantic mismatch as a major area requiring careful investigation.

This historical finding is particularly important for debugging later read-receipt problems. It must not be "simplified" without checking the live schema and the deployed RPC definition.

## 8. Receipt failure investigation

Read receipts were the most important unresolved problem in this historical conversation.

Observed behavior included:

```text
message arrives
sender sees one check
expected delivered/read double check
but state does not reliably advance
```

The problem reproduced across more than one peer, including a separate test account, which weakened the hypothesis that one specific account or VPN was the root cause.

A key suspect was:

```kotlin
private var cachedConversationId: String? = null
```

inside the Supabase repository. Historical `setReceipt()` logic used that cached conversation and then resolved the message by:

```text
conversation_id + client_message_id
```

The conversation explicitly stopped short of calling this the sole proven cause. The status remained **OPEN / PARTIALLY EXPLAINED**.

This historical issue directly motivated later work on conversation-cache isolation and the eventual message-centric receipt experiment.

## 9. Multi-conversation isolation bug

A concrete multi-conversation bug was reproduced with two partners (including Lidia and `test3`). A message from one conversation could appear as though it belonged to another when global partner state was overwritten.

Supabase data showed that the message sender and conversation were correct; the client-side state was the confusing layer.

A dedicated fix branch and commits were created around:

```text
fix/private-chat-multi-conversation-isolation
36e9543c4d88282ba94704c5f78de13e657b38d1
c04dd7c2b8ccdfa2294a34b5100336007c27aed5
Private Chat: isolate multiple conversations
```

The core fix was to stop background processing from changing the globally selected `identity.partnerId` when it processed another conversation.

**CONFIRMED historical bug and fix.**

The broader lesson was stronger than the individual patch:

> Background or delayed work must carry explicit conversation/partner context and must not mutate the foreground-selected conversation merely because an event for another conversation arrived.

## 10. Inbox Discovery

`ChatInboxRepository` and the RPC `sigma_get_undelivered_messages` were introduced so a recipient could discover an incoming message even when no local conversation or partner entry existed yet.

The desired flow became:

```text
sender sends first message
        ↓
server knows recipient
        ↓
recipient inbox discovers message
        ↓
local conversation is created
        ↓
notification / message appears
```

This was a major UX improvement because the recipient no longer had to search for the sender before the first message could arrive.

A historical bug remained in profile enrichment: the first conversation could temporarily show the sender's `SB-...` ID instead of the sender's display name/profile.

## 11. Background execution model

`ChatNotificationService` was described as a Private Chat foreground service handling:

- identity registration;
- inbox polling;
- background message observation;
- pending outgoing message retry;
- delivery/read receipts;
- translation jobs;
- notifications.

The historical background path relied on PostgREST polling rather than a separate WebSocket consumer, despite Realtime infrastructure existing elsewhere in the application.

Recorded intervals from the inspected implementation included approximately:

```text
background inbox polling: 2 seconds
partner checking:         3 seconds
Realtime reconnect:       5 seconds
```

Other retry windows were also present; these values are historical implementation observations, not universal protocol requirements.

## 12. Foreground lifecycle correctness

A real bug occurred because the system could incorrectly treat an Activity as if a chat were still open.

The fix introduced lifecycle-aware tracking using `DefaultLifecycleObserver`, conceptually:

```text
onResume -> conversation considered open
onPause  -> conversation considered closed
```

This mattered because notification suppression and read behavior must reflect the actual visible conversation, not just whether the app process/activity exists.

## 13. Supabase Auth lifecycle discovery

A particularly important historical fix was setting the Supabase Auth lifecycle integration so it did not interfere with background work:

```kotlin
enableLifecycleCallbacks = false
```

This was associated with restoring background notification behavior during testing.

The historical lesson is that Supabase client/Auth lifecycle callbacks can interact with Android service/background execution and must be treated as an explicit integration point rather than assumed harmless defaults.

## 14. Translation system

The Private Chat translation system remained independent from message transport.

Historical runtime behavior supported:

```text
Arabic
Russian
English
```

with Arabic/Russian being the actual primary use case.

The translation service used simple language detection (`ar`, `ru`, `en`) and selected a target language from local preferences.

A local Gemini repository used a Chat-specific Gemini model. When a device lacked the necessary Gemini credentials, it used the remote translation-job path.

## 15. Primary / Secondary translation model

The historical architecture defined:

```text
Primary
= device that has local Gemini credentials

Secondary
= device that does not have Gemini credentials
```

This was an application behavior distinction, not a formal database role field.

The remote flow was:

```text
Secondary
   ↓
sigma_request_translation
   ↓
translation_jobs (PENDING)
   ↓
Primary claims jobs
   ↓
Gemini
   ↓
sigma_complete_translation_job
   ↓
encrypted translation result
   ↓
Secondary polls/gets translation
```

The historical conversation verified that the Secondary device could translate through this mechanism without possessing a Gemini key.

## 16. Translation job schema and RPCs

The historical `translation_jobs` table included fields such as:

```text
id
message_id
requested_by_user_id
target_language
status
attempts
translated_ciphertext
translated_nonce
last_error
created_at
updated_at
```

Historical statuses were:

```text
PENDING
PROCESSING
COMPLETED
FAILED
```

The RPC family documented in the historical conversation was:

```text
sigma_request_translation
sigma_claim_translation_jobs
sigma_complete_translation_job
sigma_fail_translation_job
sigma_get_translation
```

Notable historical authorization rule: the sender/owner of the original message was treated as the translation worker in the claim/complete path.

The request RPC used the message's `client_message_id` plus target language and avoided duplicate jobs through a unique relationship involving message, requester, and target language.

The results were stored as encrypted translation ciphertext plus nonce rather than plain translated text.

## 17. Translation ordering nuance

A subtle historical inconsistency appeared in background notification processing.

One stage used:

```text
save raw message
→ show raw notification
→ translate later
```

Later changes tried to translate before persistence in some paths, but a subsequent `v0.8.5` code state again contained a save-first/background-translation flow.

Therefore the historical conclusion was:

> Translation ordering must be checked against the exact current code path instead of assuming all foreground/background paths behave identically.

This is a useful historical warning and is not a directive to change the current implementation automatically.

## 18. Google Sign-In and identity migration

The historical account system used Android Credential Manager, Google ID tokens, a cryptographic nonce, and Supabase Auth.

The conceptual sequence was:

```text
secure random nonce
        ↓
SHA-256(nonce) for provider request
        ↓
Google ID token
        ↓
Supabase Auth validation using token + original nonce
```

This nonce mechanism is independent of the conversation encryption key.

The project also spent time migrating away from anonymous sessions. An important constraint was never to create a replacement Auth identity for a real user merely to make a bug disappear.

## 19. Identity conflict recovery

A historical registration failure returned:

```text
PUBLIC_ID_ALREADY_IN_USE
```

The recovery path was deliberately narrow:

```text
register device
   ↓
PUBLIC_ID_ALREADY_IN_USE
   ↓
regenerate public ID
   ↓
retry registration
```

This is a recovery mechanism, not normal identity rotation.

## 20. Profile, username, avatar, and presence

Private Chat v2 added a profile/account layer with fields including:

```text
first_name
last_name
username
avatar_path
```

The historical profile RPCs included:

```text
sigma_get_my_profile
sigma_get_profile_by_public_id
sigma_get_last_seen
sigma_touch_last_seen
sigma_update_profile
sigma_search_users
sigma_update_avatar
```

The historical UI goal was to move discovery from `SB-...` IDs toward username-based search.

Presence used `last_seen_at`; one inspected implementation considered users online within roughly 45 seconds and refreshed every roughly 15 seconds. These numbers are historical implementation details and should be rechecked before being treated as current behavior.

## 21. Avatar/storage observations

The historical conversation observed avatar objects associated with some test accounts and confirmed that profile/avatar data existed in the project. It also noted that an account with stored messages could not necessarily be deleted safely because message foreign keys could retain it.

Do not infer from this history that any account cleanup is complete or that historical object counts remain current.

## 22. Database deletion behavior discovered historically

A direct schema inspection found deletion dependencies resembling:

```text
devices             → CASCADE
conversation_members → CASCADE
messages            → RESTRICT
message_receipts    → CASCADE
translation_jobs    → CASCADE
message_reactions   → CASCADE
```

The important practical consequence was:

```text
messages.sender_user_id
        ↓
public.users.id
        ↓
ON DELETE RESTRICT
```

So deleting an Auth/user row without first dealing with message ownership can fail. This became a critical reason to stop treating deletion as a casual cleanup operation.

These are historical schema findings; before any real deletion or migration, inspect the live deployed schema and current RPCs.

## 23. Reactions

Reactions were implemented experimentally but repeatedly failed to persist reliably. A reaction could appear momentarily and then disappear.

The project explicitly abandoned the Reactions development path. Historical branches mentioned included:

```text
phase/chat-reactions-optimistic-v1
phase/chat-ux-v1
```

Reactions should not be revived unless explicitly requested.

## 24. Reply and protocol compatibility

Reply was implemented and tested successfully between compatible versions. Older devices could display raw JSON when they did not understand the newer payload format.

Historical lesson:

> Any protocol-level message extension requires compatibility testing across the minimum supported version; a newer sender cannot assume an older receiver understands the payload.

## 25. Update system history

The application developed a GitHub Releases based update system with:

```text
GitHubUpdateChecker
UpdateManager / AppUpdateManager
UpdateBanner
REQUEST_INSTALL_PACKAGES
```

A historical update loop occurred because a release tag/version did not match the APK's embedded version metadata. This was fixed by aligning `versionName` and `versionCode` and then verifying that the same update was not repeatedly offered after installation.

Important historical releases included `v0.8.2`, `v0.8.3`, `v0.8.4`, and `v0.8.5`.

## 26. Historical crypto position

The historical implementation used `ChatCrypto` and AES-GCM-style encrypted payloads, with deterministic conversation-derived key material. The conversation explicitly recognized that this is not a modern Signal protocol with asymmetric ratcheting and forward secrecy.

An `identity_public_key` field existed in the device model, but the historical evidence did not establish that it formed a modern per-message E2EE key-exchange protocol.

Therefore the safe architectural description remains:

```text
encrypted Private Chat transport/storage
≠
Signal-style ratcheting E2EE
```

## 27. Historical stable and release references

The third conversation recorded these important historical refs:

```text
v0.8.2  — Chat Foundation
v0.8.3  — persistent update system work
v0.8.4  — multi-conversation isolation
v0.8.5  — stable update identity
```

The historical `v0.8.4` isolation commit was:

```text
c04dd7c2b8ccdfa2294a34b5100336007c27aed5
```

The historical `v0.8.5` release commit was:

```text
db8968359b6a26f6622eea584bc07012daa76462
```

These are historical references; the current development branch and its newer commits are authoritative for present work.

## 28. Historical engineering lessons that remain relevant

1. Never use the foreground-selected partner as implicit context for background work.
2. Treat `SB-...`, Auth UUID, device UUID, conversation UUID, conversation key, and conversation topic as distinct identifiers.
3. A message existing in `messages` does not prove its receipts work.
4. A receipt row in the database does not by itself prove the UI is interpreting it correctly.
5. Device-level receipt storage and user-level receipt interpretation must be reconciled deliberately.
6. Do not assume the current code path matches a historical foreground/background path.
7. Verify the live Supabase schema and live RPC definitions before modifying receipts or deletion behavior.
8. Keep translation independent of message transport.
9. Keep Telegram isolated from Private Chat.
10. Avoid replacing real user accounts as a shortcut for fixing identity problems.
11. Do not resurrect abandoned Reactions or add live-audio complexity without explicit scope.

## 29. Relationship to the current 0.8.6 work

This document explains why several later 0.8.6 changes were necessary.

The historical chain is roughly:

```text
simple chat
   ↓
ntfy prototype
   ↓
Supabase migration
   ↓
conversation / identity / device model
   ↓
multiple conversations
   ↓
Inbox Discovery
   ↓
Account/Profile/Username UX
   ↓
background reliability fixes
   ↓
receipt debugging
   ↓
translation decoupling
   ↓
0.8.6 simplification work
```

The current branch must still be treated as the source of truth for what is actually implemented today. This historical document exists to preserve the reasons, failures, and decisions that led there.