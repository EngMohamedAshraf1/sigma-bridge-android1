# Google Alternative Translation — Private Chat

This feature is a manual fallback for Private Chat only. It does not modify Telegram.

## Runtime flow

1. The message is received normally through the existing Private Chat transport.
2. Automatic Gemini translation remains unchanged.
3. The user can tap a message and choose **Alternative translation**.
4. The Android client invokes the `google-alternative-translation` Supabase Edge Function.
5. The Edge Function calls Google Cloud Translation **NMT** and returns only the translated text.

The fallback does not depend on the primary phone being online.

## Supabase function

Function name: `google-alternative-translation`

The function requires JWT authentication and therefore must be invoked by an authenticated Private Chat user.

## Required Supabase secret

Set this secret in the Supabase project:

- `GOOGLE_TRANSLATION_API_KEY`

The API key must have the Google Cloud Translation API enabled. Keep the key server-side; never put it in the Android APK.

## Google model

The function uses Google Cloud Translation's standard **Neural Machine Translation (NMT)** model through the Cloud Translation Basic REST API. The v2 endpoint accepts the `nmt` model explicitly; NMT is also the default model for Basic. Google currently includes the first 500,000 characters per month in the free tier for Cloud Translation NMT.

## Safety limits

The function rejects messages over 4,000 characters and only accepts language codes in the form `xx` or `xx-XX`.

## Privacy note

This fallback is a server-side translation path. The plaintext sent for translation reaches the Google translation service. This is intentionally separate from the encrypted message storage/transport path and must not be described as end-to-end encrypted translation.
