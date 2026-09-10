# Sigma Bridge Handoff Guide

This file is written for a developer, reviewer, or a new AI conversation taking ownership of Sigma Bridge without access to the previous private chat history.

## First rule

Do not assume that the current GitHub default branch represents the current Private Chat development state. The repository's default branch is `master`, while the documented Private Chat work is on a dedicated fix branch.

At this documentation point:

```text
Repository: EngMohamedAshraf1/sigma-bridge-android1
Private Chat development branch: private-chat-6bb07de-fix
Latest stable release tag: v0.8.7-private-chat-stable
Latest documented commit: 37054fa02f8a91d2f4e582b87756479013e73bb8
Application version in source: 0.8.7
versionCode: 7
```

The v0.8.7 release was published on 2026-09-10. Its official GitHub release asset is `sigma-bridge.apk` with SHA-256:

```text
5f671b7d99957cbfc411c72ee558cfdd0c0a22d3067d0719f8cf067864b004b0
```

The user has previously had local Git divergence problems. Prefer pulling a clean branch and validating the exact commit over attempting to merge uncertain local changes.

## What Sigma Bridge is

Sigma Bridge is an Android application containing two product areas:

```text
A) Private Chat
   - Google login
   - public Sigma identity
   - 1-to-1 conversations
   - encrypted messages
   - Delivered / Read receipts
   - local history and outbox
   - unread state
   - profiles and avatars
   - presence / last seen
   - automatic translation
   - background notifications
   - remote translation jobs

B) Telegram Bridge
   - legacy bot/translation subsystem
```

The current maintenance scope discussed in the project is Private Chat. Do not touch Telegram unless the task explicitly says to.

## Read the project history

`docs/PROJECT_HISTORY.md` preserves the development path reconstructed from an earlier project conversation: the original Private Chat/ntfy prototype, the move toward Supabase, the identity and conversation model, encryption, receipt and multi-conversation problems, translation decoupling, Telegram Gemini voice/key-rotation lessons, rejected approaches, and historical testing failures.

Treat it as historical context. Current source code, current tests, current Git history, and live Supabase evidence take precedence when they disagree with the old conversation.

## Current architecture in one minute

```text
Google
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
  +--> publicId
  +--> devicePublicId
  +--> deterministic conversation topic/key

Private Chats UI
  |
  v
ChatViewModel
  |
  +--> ChatHistoryStore
  +--> ChatOutboxStore
  +--> ChatUnreadStore
  +--> ChatConversationStore
  +--> ChatProfileRepository
  +--> ChatRepository
  +--> ChatTranslationService

ChatRepository -> Supabase
ChatProfileRepository -> Supabase Auth/PostgREST/Storage
ChatTranslationService -> Private Chat Gemini and/or translation relay

ChatNotificationService
  |
  +--> background inbox
  +--> background messages
  +--> pending-message retry
  +--> Delivered receipts
  +--> remote translation jobs
  +--> notifications
```

## Core invariants

### Message identity

A locally generated UUID is the `client_message_id`. Supabase assigns its own server message UUID. They are not interchangeable.

### Conversation identity

A conversation is determined by the pair of participant public IDs. The client derives a deterministic key/topic; Supabase owns the authoritative `conversation_id`.

### Device identity

A user and a device are different concepts. Device registration returns an authoritative device UUID used by the message transport.

### Translation identity

Translation belongs to an existing message. Translation must never create a replacement message that loses the original.

### Receipt identity

A Delivered/Read receipt belongs to a server message and receiving user. The current baseline resolves the server message inside the active conversation using the local client message ID. Background receipt handling resolves the message inside the partner-specific conversation without changing the active conversation identity.

## Current release behavior

The intended Private Chat UX is:

```text
incoming message
   |
   +--> display original immediately
   +--> send Read immediately
   +--> translate asynchronously
             |
             +--> success: replace display text with translation
             +--> failure: keep original
```

The v0.8.7 release extends the background transport path so stored conversations can be processed without replacing the currently selected partner in `ChatIdentity`.

## Current stable release

```text
Release: Sigma Bridge v0.8.7 — Private Chat Stable
Tag:     v0.8.7-private-chat-stable
Commit:  37054fa02f8a91d2f4e582b87756479013e73bb8
Version: versionName 0.8.7 / versionCode 7
APK:     sigma-bridge.apk
SHA-256: 5f671b7d99957cbfc411c72ee558cfdd0c0a22d3067d0719f8cf067864b004b0
```

This release is the current stable Private Chat reference point. It contains no intentional Telegram changes, no Supabase schema changes, and no deletion of real Supabase user, device, conversation, message, or receipt data.

## Known historical work

The important Private Chat simplification commits are:

```text
6a17acb  independent translation state
7af5676  read receipts decoupled from translation
6bb07de  original shown immediately, translation asynchronous
6624abf  receipt polling syntax correction
b2eea8d  application version aligned to 0.8.6
```

The v0.8.7 transport-isolation work was implemented after the 0.8.6 baseline and finalized in the release commit listed above.

## Current known limitations

The current repository is not a finished feature-complete messenger. It is an MVP. Features such as photos in chat, reactions, reply-to-message, and copy/share behavior are future UI/product work unless already implemented elsewhere.

The encryption design should not be described as Signal-style E2E with ratcheting or forward secrecy. It is an AES-GCM scheme based on the current identity-derived conversation key.

The Supabase SQL under `docs/supabase/` is a reference snapshot. Before production database changes, inspect the live database.

The update system currently depends on GitHub Releases and Android's package installer rather than a Play Store deployment channel.

## What to inspect first when taking a new task

```text
1. README.md
2. docs/ARCHITECTURE.md
3. docs/PROJECT_HISTORY.md
4. docs/PRIVATE_CHAT.md
5. docs/SUPABASE.md
6. docs/TRANSLATION.md
7. docs/UPDATE_SYSTEM.md
8. docs/DEVELOPMENT.md
9. docs/TROUBLESHOOTING.md
10. docs/TELEGRAM_BOUNDARY.md
```

Then inspect the exact files related to the task.

## Safe change checklist

Before coding:

- identify the exact branch and commit;
- identify whether the task is Private Chat or Telegram;
- identify the authoritative identifier involved;
- check whether background services also touch the same data;
- check whether asynchronous work can complete after navigation;
- check whether a failed network/translation request must preserve local data.

Before committing:

- build the app;
- run the relevant functional test;
- check `git diff`;
- check `git status`;
- update documentation when the architecture or behavior changes.

## What a new AI conversation should be given

A new AI conversation can be started with the repository plus this instruction:

> Work on Sigma Bridge using the repository's current Private Chat documentation as the source of truth. Read README.md, docs/ARCHITECTURE.md, docs/PROJECT_HISTORY.md, docs/PRIVATE_CHAT.md, docs/SUPABASE.md, docs/TRANSLATION.md, docs/UPDATE_SYSTEM.md, docs/DEVELOPMENT.md, and docs/TROUBLESHOOTING.md before proposing changes. Current scope is Private Chat only; do not modify Telegram. Do not delete or alter real Supabase user/message/conversation data unless explicitly instructed. Diagnose from actual code and database evidence rather than guessing. Preserve original message text, conversation isolation, and independent receipt/translation behavior.

That instruction is intentionally explicit because repository state and old chat history can disagree.

## Do not rely on memory

The repository documentation is the persistent handoff layer. When a new implementation changes an important architectural rule, update the relevant documentation in the same development cycle.
