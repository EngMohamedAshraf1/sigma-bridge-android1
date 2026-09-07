# Telegram Boundary

## Why this document exists

Sigma Bridge contains an older Telegram bot/translation subsystem alongside the newer Private Chat subsystem. The two areas share the same Android application but are intentionally different products inside it.

The current engineering rule for the Private Chat workstream is simple: **do not modify Telegram code while fixing Private Chat.**

## Legacy Telegram side

The repository contains Telegram-specific components such as:

- Telegram API client(s)
- Telegram file download handling
- Telegram repository implementation
- Telegram DTO/mapper code
- the existing Gemini translation pipeline used by the Telegram bridge
- `BridgeOrchestrator`
- the legacy `BridgeForegroundService`

These components have their own responsibilities, retry behavior, configuration, and long-running execution model.

## Private Chat side

Private Chat has its own components:

- Google/Supabase authentication
- `ChatIdentity`
- `ChatCrypto`
- `ChatRepository`
- `SupabaseChatRepository`
- `ChatProfileRepository`
- `ChatHistoryStore`
- `ChatOutboxStore`
- `ChatUnreadStore`
- `ChatConversationStore`
- `ChatGeminiTranslationRepository`
- `ChatTranslationRelayRepository`
- `ChatNotificationService`
- `ChatBootReceiver`
- Private Chat Compose screens/view models

A class having the word “Gemini” or “translation” in its name does not make it a shared Telegram/Private Chat implementation. Read the package and dependency direction before changing it.

## Do not cross the boundary casually

Examples of changes that require an explicit Telegram task:

```text
changing TelegramRepositoryImpl
changing TelegramApiClient
changing BridgeOrchestrator
changing Telegram Gemini prompt/retry logic
changing Telegram service behavior
changing Telegram-specific settings
```

Examples of normal Private Chat work:

```text
fixing ChatViewModel
fixing ChatIdentity
fixing SupabaseChatRepository
fixing ChatNotificationService
fixing Private Chat translation
fixing chat profiles/presence
fixing Private Chat receipts
```

## Shared Android infrastructure

Some Android-level classes may be used by both product areas, especially application startup, navigation, configuration, or dependency injection. Before changing a shared class, inspect all callers and ensure that a Private Chat fix does not regress Telegram.

## Testing rule

A Private Chat-only change should be tested with the Private Chat flows first. Telegram should remain untouched and should not be used as a reason to broaden a Private Chat patch.

If a compiler error appears in Telegram code during a Private Chat change, do not “clean it up” opportunistically. Determine whether it predates the task and record it separately.

## Historical note

The repository's original README was written around the Telegram/Phase-7 architecture and became stale as Private Chat was introduced. The documentation under `docs/` now treats the two systems as separate product areas and identifies Private Chat as the current focused workstream.
