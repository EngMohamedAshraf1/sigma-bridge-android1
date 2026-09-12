# Google Alternative Translation — Private Chat

This feature is a manual fallback for Private Chat only. It does not modify Telegram.

## Runtime flow

1. The message is received normally through the existing Private Chat transport.
2. Automatic Gemini translation remains unchanged.
3. The user can tap a message and choose **Alternative translation**.
4. The Android client invokes the `google-alternative-translation` Supabase Edge Function.
5. The Edge Function calls Google Cloud Translation LLM and returns only the translated text.

The fallback does not depend on the primary phone being online.

## Supabase function

Function name: `google-alternative-translation`

The function requires JWT authentication and therefore must be invoked by an authenticated Private Chat user.

## Required Supabase secrets

Set these secrets in the Supabase project:

- `GOOGLE_TRANSLATION_API_KEY`
- `GOOGLE_CLOUD_PROJECT_ID`

The Google API key must have Cloud Translation API enabled. Keep the key server-side; never put it in the Android APK.

## Google model

The function requests Google's `general/translation-llm` model through the Cloud Translation Basic REST API. Arabic, Russian, and English are supported by the Translation LLM.

## Safety limits

The function rejects messages over 4,000 characters and only accepts language codes in the form `xx` or `xx-XX`.

## Important privacy note

The fallback translation is a server-side translation path. The plaintext used for the translation request therefore reaches the translation service. This is intentionally separate from the existing encrypted message storage/transport path and should not be described as end-to-end encrypted translation.
