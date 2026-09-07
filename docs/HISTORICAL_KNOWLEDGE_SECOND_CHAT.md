# Historical Knowledge — Second Project Conversation

This document preserves project knowledge extracted from the second historical conversation. It supplements `docs/PROJECT_HISTORY.md` and must not override the current source code or newer tested behavior.

## Evidence rules

- **CONFIRMED** means the historical conversation described code, database inspection, screenshots, or tests that supported the statement at that time.
- **REPORTED** means it was stated but not sufficiently re-verified.
- **INFERRED** means a direct architectural conclusion.
- **UNKNOWN** means the historical material did not establish the fact.

Historical CONFIRMED does not mean current. When historical and current snapshots disagree, the newer current repository/test evidence wins.

## 1. Product evolution: from technical IDs toward Account → Profile → Username → Chat

The second conversation makes the product evolution more explicit than the first:

```text
Old technical model
SB ID → Device ID → Partner ID → Conversation ID → Conversation Key → Chat

Target UX model
Account → Profile → Username → Chat
```

The reason was usability. The user wanted the Private Chat experience to behave more like familiar messengers: find a person by username, send the first message, and let the recipient receive it without first searching for the sender or manually defining a Partner ID.

**CONFIRMED historical product direction.**

This is an important distinction: the simpler Account/Profile/Username flow is primarily a UX abstraction over the existing technical identity/conversation system, not proof that the underlying identifiers disappeared.

## 2. Inbox Discovery and first-contact messaging

A significant reliability/UX milestone was `ChatInboxRepository` with the RPC:

```text
sigma_get_undelivered_messages
```

The purpose was to let a recipient discover an incoming message even when the recipient had no locally saved Partner ID or Conversation yet.

Historical target behavior:

```text
Sender knows recipient username
        ↓
sends first message
        ↓
server stores message
        ↓
recipient background inbox discovers it
        ↓
conversation is created/added locally
        ↓
message appears
```

This solved the earlier UX defect where the recipient had to search for the sender before the first message could appear.

A real test was reported as passing for first-message discovery on Nox: the sender could send to Nox and Nox discovered the first message without first searching for the sender.

**CONFIRMED historical test.**

### Remaining issue at that time

The first discovered conversation could still display the sender's `SB-...` ID instead of the sender's profile/name because Inbox Discovery did not yet enrich the result with the matching profile. This was explicitly left open/partial in that snapshot.

## 3. Account layer and authentication transition

The second conversation captured an intermediate Account redesign that is important for understanding why current authentication files exist.

### Old path

Private Chat originally relied on Anonymous Supabase Authentication plus a locally persisted `SB-...` identity.

### Account v2 direction

The product was being changed toward a durable account layer so the user's profile and conversations could remain attached to the same account across updates and reinstall/recovery workflows.

The historical target flow was discussed as:

```text
Anonymous account
   ↓
link email
   ↓
verify email
   ↓
set password
   ↓
permanent account
```

A crucial architectural reason was to avoid creating a second Supabase Auth user simply because the user was moving from the legacy anonymous identity model to a permanent account.

**CONFIRMED historical product/architecture direction.**

### Email verification UX changed during development

The implementation at one point used a verification email link:

```text
send verification email
↓
user opens it
↓
returns to app
↓
presses "email verified"
```

The later requested UX was a five-digit OTP:

```text
send 5-digit code
↓
user copies code
↓
enter code in app
↓
verified
```

The OTP flow was explicitly described as a new request and not yet implemented in the last historical snapshot.

**CONFIRMED historical transition; OTP remained OPEN at that time.**

## 4. Google authentication versus Account v2 reality

Google Login remained a product goal in the broader project, but the second conversation records that the Account-v2 implementation being actively worked on was based on Email account creation/verification rather than a final Google-only flow.

Therefore the historical state must be represented as:

```text
Google login = product direction / supported concept
Account v2 under active development = Email verification + password
```

The exact final Google OAuth state in that historical snapshot remained unresolved.

## 5. Profile and username

The Profile layer was materially implemented during the second conversation.

Historical profile fields:

```text
first_name
last_name
username
```

The repository mentioned was `ChatProfileRepository`, with profile/search RPCs including:

```text
sigma_get_my_profile
sigma_update_profile
sigma_search_users
```

The desired UX was:

```text
New Chat
   ↓
Search
   ↓
@username
   ↓
User
   ↓
Chat
```

**CONFIRMED historical implementation direction and testing.**

## 6. Profile enrichment remained a real open bug

The first-contact Inbox Discovery path was technically able to find the sender, but profile enrichment was incomplete. In at least one test, the discovered contact was rendered as a raw Sigma ID such as:

```text
SB-X4DF...
```

instead of the user's display profile.

This is a useful historical clue: `Partner ID` discovery and `Profile lookup` are two separate operations and should not be assumed to be solved by the same RPC.

## 7. Background service and lifecycle fixes

The second conversation contains concrete reliability fixes that are broader than ordinary UI behavior.

### Supabase Auth lifecycle callback issue

A background notification failure was traced to Supabase Auth lifecycle callbacks. The configuration was changed to:

```kotlin
install(Auth) {
    enableLifecycleCallbacks = false
}
```

The historical result was reported as PASS for background notification behavior.

### Foreground/background state

A separate bug caused the application to consider the chat open when the Activity was merely present. The fix introduced `DefaultLifecycleObserver` with:

```text
onResume → conversation open
onPause  → conversation closed
```

This corrected notification suppression and read behavior tied to whether the chat was actually visible.

**CONFIRMED historical fixes.**

## 8. Background polling versus Realtime

Although Supabase Realtime infrastructure existed, the background chat service described in the second conversation relied on PostgREST polling for operational stability.

Historical cadence reported:

```text
Foreground chat polling ≈ 1 second
Background inbox polling ≈ 2 seconds
```

The term `observeRealtimeEvents()` therefore did not necessarily mean a live WebSocket was the actual background transport at that stage.

A reconnect delay of approximately five seconds was also recorded for the Realtime configuration.

**CONFIRMED historical implementation detail.**

## 9. Background service responsibilities

`ChatNotificationService` was described as doing more than notification display. Its responsibilities included:

- background inbox polling/discovery;
- unread processing;
- notification preview;
- Delivered/Read receipt handling;
- translation processing;
- outbox retry;
- network gating.

This explains why the service is a core Private Chat component rather than a cosmetic notification helper.

## 10. Multi-device and translation model

The second conversation provides clearer product semantics for Primary/Secondary devices:

```text
Primary Device
    owns Gemini credentials
    performs actual translation

Secondary Device
    does not need Gemini credentials
    uses remote translation jobs
```

The key design achievement was that a Secondary device could participate in translation without exposing Gemini API keys to that device.

The historical test result explicitly reported Arabic↔Russian translation working and a Secondary device working without a Gemini API key.

**CONFIRMED historical test.**

## 11. Translation jobs and persistence

A `translation_jobs` table was observed in the historical Supabase database. One snapshot showed 29 rows; that number was a live row count at that time, not a schema limit.

`ChatTranslationService` included operations conceptually equivalent to:

```text
translateIncoming(text, clientMessageId)
processPendingRemoteTranslationJobs()
```

The latter was intended primarily for the Primary device.

The historical conversation emphasized that a failed translation must not invalidate message delivery:

```text
Translation failed
    ↓
Message remains delivered/readable
```

**CONFIRMED architectural rule.**

## 12. Historical Supabase snapshot observed in the second conversation

The historical conversation reported the following database state at one inspection point:

```text
project: qcxorfsbwxprfhmsyzqu
name: sigma-bridge-android
region: eu-west-1
health: ACTIVE_HEALTHY

users                 40 rows
 devices               7 rows
 conversations         6 rows
 conversation_members 12 rows
 sessions               6 rows
 messages             128 rows
 message_receipts     114 rows
 translation_jobs      29 rows
 message_reactions      0 rows
```

These are historical observations only. They must never be presented as the current live database state without a new live query.

## 13. Historical database entities

The second conversation identified these major tables/concepts:

```text
users
devices
conversations
conversation_members
sessions
messages
message_receipts
translation_jobs
```

`message_reactions` existed but had zero rows in the observed snapshot and the feature had been abandoned.

The historical conversation explicitly questioned whether `message_translations` existed in production in that snapshot. It therefore remained **UNKNOWN** there even though translation ciphertext models existed in code/documentation.

## 14. Historical RPC surface

The following Private Chat RPCs were explicitly referenced in the second conversation:

```text
sigma_register_device
sigma_ensure_conversation
sigma_send_message
sigma_set_receipt
sigma_get_my_profile
sigma_update_profile
sigma_search_users
sigma_get_undelivered_messages
```

Translation RPC signatures were not fully established in the historical extraction.

The historical architecture treated RPCs as the controlled server-side write/read boundary for sensitive operations.

## 15. Identity conflict and stale-conversation debugging

One repeated bug involved the local public ID becoming associated with a different Auth user/session, producing:

```text
PUBLIC_ID_ALREADY_IN_USE
```

The recovery behavior was:

```text
regenerateMyId()
↓
register device again
```

Another reliability bug involved a stale `cachedConversationId` after changing Partner ID. The historical fix was to ensure the repository/conversation cache was refreshed when the active partner changed.

This is particularly relevant because the later current work made conversation cache isolation explicit.

## 16. Reply protocol evolution

Reply was implemented in the Private Chat evolution.

A compatibility defect was discovered when newer Reply payloads reached older devices: the old device could render raw JSON instead of a normal reply.

The historical result was:

```text
same app/protocol version → PASS
older device/version → raw JSON compatibility limitation
```

Therefore Reply was not purely a UI feature; it created a protocol-version compatibility concern.

## 17. Reactions were explicitly abandoned

Reactions were attempted on multiple branches. The observed failure was:

```text
reaction appears briefly
↓
disappears
```

After repeated failures, the project explicitly stopped Reactions development.

Do not revive that feature from the historical branches without an explicit product request.

## 18. Automatic update system

The second conversation provides the clearest historical description of the GitHub Releases update system.

The desired flow was:

```text
older installed APK
    ↓
new GitHub Release
    ↓
update prompt/banner
    ↓
Android installer
    ↓
new version installed
    ↓
old update notification does not repeat
```

A concrete historical test used `v0.7.2-test2`, with:

```text
versionName = 0.7.2
versionCode = 2
```

An earlier APK carried stale internal version `0.7.1` and caused an Update Loop. The fix was to align embedded Android version metadata with the release version.

**CONFIRMED historical test and fix.**

## 19. Stable safety base from the second conversation

A historical tag was identified as:

```text
v0.3-chat-reliability-stable
87568c824f29c9ecefff9c26aab3868bf0ff066a
```

It was explicitly treated as a safety base and not to be modified directly.

Another earlier stable tag was:

```text
v0.2-chat-supabase-stable
037daecb7bc7493e62d368637bb0361ebf537b41
```

These tags belong to historical development context. They do not supersede the current dedicated Private Chat branch.

## 20. Historical Private Chat v2 foundation branch

The second conversation identified:

```text
phase/private-chat-v2-foundation
```

as the branch used to develop the Account → Profile → Username → Chat simplification.

A number of commits were listed in that development stream, but the historical extraction did not provide a reliable patch-level description for each one. Therefore they should not be turned into detailed changelog claims without Git inspection.

## 21. Current product philosophy clarified by the second conversation

The desired Private Chat experience became:

```text
Share username
      ↓
Search username
      ↓
Send
      ↓
Receiver gets message automatically
```

The user should not need to understand or manually enter technical identifiers such as conversation keys, topics, or Partner IDs.

The technical architecture may still use those identifiers internally, but they are implementation details rather than the intended user-facing model.

## 22. Open issues at the end of the second conversation

The historical open list included:

- final Account UX;
- five-digit OTP verification rather than email-link verification;
- account recovery flow;
- fully automatic profile creation/association;
- profile enrichment for first-contact conversations;
- reducing UX dependence on visible SB IDs;
- final username/account relationship;
- final Google-login decision;
- final avatar behavior;
- final online/last-seen UI;
- security review of the current AES-GCM identity-derived design;
- deciding whether the legacy crypto model should remain;
- reducing polling dependence over time.

These are historical status items, not claims about the current branch.

## 23. Lessons that should remain in the permanent documentation

### Lesson A — technical IDs are internal infrastructure

`SB-...`, device IDs, conversation IDs, keys, and topics solved early architectural problems but made the user experience more complicated. Account/Profile/Username was introduced specifically to hide that complexity.

### Lesson B — first contact must not depend on local preconfiguration

Inbox Discovery exists because a sender should be able to initiate a conversation without the recipient first creating a local Partner entry.

### Lesson C — UI lifecycle is a real correctness boundary

Whether a chat is actually visible must be determined by lifecycle state, not merely by whether an Activity/process exists.

### Lesson D — background processing must be conversation-explicit

The project repeatedly encountered bugs caused by using the currently selected partner for asynchronous work. Every background/late result should carry enough context to update the correct conversation.

### Lesson E — translation is a secondary concern

Message delivery, receipts, and readability must remain functional when translation is slow or unavailable.

### Lesson F — version metadata is part of release correctness

An APK's internal `versionName`/`versionCode` must correspond to the GitHub Release metadata, otherwise the app can enter an update loop.

## Final note

This file intentionally preserves historical knowledge from the second conversation. Before acting on any of these points, compare them with the current code, current Git branch, current tests, and live Supabase state. Historical database row counts and historical branch status must never be presented as current facts without re-verification.
