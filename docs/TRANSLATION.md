# Translation System

## Separation from Telegram

Private Chat translation is implemented as its own path. It must not be assumed to share Telegram's translation runtime simply because both products use Gemini.

The Private Chat side contains a dedicated `ChatGeminiTranslationRepository` and a `ChatTranslationRelayRepository`.

## Language model

`Language` is a simple data class containing:

```text
code
 displayName
```

`LanguagePair` represents source -> target independently of the catalog.

The single source of truth for supported languages is `LanguageCatalog`. Current entries include English, Russian, Arabic, French, German, Spanish, Italian, Portuguese, Turkish, Simplified Chinese, Japanese, Korean, Hindi, Ukrainian, Polish, and Auto-detect.

The MVP default pair is:

```text
Russian (ru) -> Arabic (ar)
```

Do not create a second list of supported languages in a UI or repository. Add languages to `LanguageCatalog` and let the rest of the application resolve them from that source.

## Message-level translation state

Each `ChatMessage` keeps the original and translated values separately:

```text
originalText
translatedText
translationStatus
text
```

`text` is the display value. On receipt it initially equals the original text. When translation succeeds it becomes the translated text.

This is an intentional compatibility/UI simplification: older consumers can render `text`, while newer code can reason explicitly about original versus translated content.

## Translation state machine

```text
                 +----------------+
                 |    PENDING     |
                 +--------+-------+
                          |
             +------------+------------+
             |                         |
             v                         v
       translation success      translation failure
             |                         |
             v                         v
     +---------------+         +---------------+
     |   COMPLETED   |         |    FAILED     |
     +---------------+         +---------------+
```

The failure state never means the message itself failed. It means only the translation attempt failed.

## Incoming translation flow

The current `ChatViewModel` performs these steps:

1. Receive `ChatEvent.Message`.
2. Ignore it if the same message ID is already displayed.
3. Create a local message with `originalText` set to the received plaintext.
4. Set `translatedText = null` and status `PENDING`.
5. Immediately add it to `_messages`.
6. Immediately save it to `ChatHistoryStore`.
7. Immediately update the conversation preview.
8. Immediately send the Read receipt.
9. Start translation asynchronously.
10. Replace the display text only when translation succeeds.
11. Keep the original visible when translation fails.

This guarantees that translation latency cannot block basic messaging.

## Gemini implementation

`ChatGeminiTranslationRepository` owns the Private Chat Gemini runtime.

It has its own configured-key cursor and invalid-key tracking. The implementation rotates through configured API keys and applies API-specific handling:

- HTTP 429: try another configured key.
- HTTP 401/403: mark that key invalid for the runtime and try another key.
- other Gemini API errors: fail the request.
- overall timeout: fail the request and let the UI preserve the original.

The current implementation uses the model:

```text
gemini-3.1-flash-lite
```

The configured prompt tells the model to behave as a professional interpreter and return only the translation while preserving names, URLs, email addresses, phone numbers, numbers, emojis, symbols, intent, tone, and register.

Do not add conversational explanations around translations unless the product explicitly changes its translation contract.

## Key management

The Private Chat Gemini repository reads the configured Gemini API keys from the application's settings repository. The exact key values must never be written into source control or documentation.

The code supports multiple keys to improve resilience when an individual key is rate-limited or rejected.

## Remote/secondary translation relay

`ChatTranslationRelayRepository` provides a second path for translation jobs through Supabase.

### Request

A client requests translation using:

```text
sigma_request_translation
```

with:

```text
p_client_message_id
p_target_language
```

### Polling

`awaitTranslation` calls the translation retrieval RPC and polls every two seconds until completion or failure, with a 30-second timeout.

### Worker path

A translation worker can:

1. claim pending jobs with `sigma_claim_translation_jobs`;
2. decrypt the source message using the appropriate conversation context;
3. translate it;
4. encrypt the translated text;
5. call `sigma_complete_translation_job`;
6. or call `sigma_fail_translation_job` with a translation failure reason.

The remote relay stores encrypted translated ciphertext. It is a coordination mechanism, not a replacement for normal chat message storage.

## Translation and receipts are independent

One of the most important design decisions is:

```text
Message received
   |
   +--> Read receipt immediately
   |
   +--> Translation asynchronously
```

Never move receipt submission into the translation callback. Doing so turns a translation outage into a messaging/receipt outage.

## Translation and persistence

The original encrypted message is stored as the authoritative message content. Translation is additional data/state.

The current Supabase SQL snapshot contains `message_translations` and `sigma_store_translation`, which explicitly preserve the original message while inserting/updating encrypted translated ciphertext.

## Translation failures

The UI must preserve the received text on failure. The user should never see an empty bubble simply because Gemini timed out, returned 429, or was unavailable.

The current error sanitizer distinguishes translation errors and presents a user-facing message indicating that the translation is unavailable while the original remains available.

## Retry considerations

The current message-level UI launches one translation coroutine for the incoming event. Persistent retry of translation jobs is handled separately by the background/relay architecture when configured.

A future retry design must preserve these properties:

- original message ID remains unchanged;
- original text remains unchanged;
- translation retries update the same logical translation record/state;
- late results must be scoped to the correct conversation/message;
- a late translation must never overwrite another conversation's UI.

## Conversation switching safety

The translation coroutine captures the conversation `historyKey`. After completion it writes to that conversation's history. It updates the visible list only if that same history key is still the active conversation and the message still exists in the visible state.

This is essential because translation can finish seconds after the user navigates away.

## Changing the translation system

When modifying translation behavior:

1. preserve `originalText`;
2. preserve stable `message.id` / `client_message_id`;
3. keep translation state independent of delivery state;
4. keep Private Chat Gemini code separate from Telegram Gemini code;
5. test both successful and failed translations;
6. test a conversation switch while translation is pending;
7. test network/API-key failure without losing the original message.
