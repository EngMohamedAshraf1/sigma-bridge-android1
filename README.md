# Sigma Bridge (Android)

Native Android rewrite of [sigma-bridge](https://github.com/EngMohamedAshraf1/sigma-bridge) (Python).
The app itself is the bot's server — no separate backend.

## Status: Phase 5 — Gemini Translation (REST) + Internal Test Screen

Four requirements for this phase, all applied:

1. **TranslationRepository stays an interface**, one implementation: `GeminiTranslationRepository`.
   Nothing else in the app is allowed to import a Gemini-specific type directly — everything goes
   through the interface (bound in `RepositoryModule`).
2. **Same retry strategy as the Python project**: only HTTP 503 is retried
   (`withRetryOnTransientFailure` in `GeminiTranslationRepository`), exponential backoff starting at
   2s, capped at 4 attempts — same numbers as `gemini_service.py`. Any other failure (4xx, malformed
   response, network error) propagates immediately, unretried.
3. **Same prompt philosophy, one request only**: `buildPrompt()` asks Gemini to listen to the audio and
   output *only* the translation — no separate transcription step, no intermediate STT pipeline. Exactly
   one `generateContent` call per translation, same as the Python version's single combined
   transcribe+translate call.
4. **Internal Gemini test screen**: `GeminiTestScreen` — pick a local `.ogg` file (system file picker) →
   calls `TranslationRepository.translate()` directly → shows the Arabic text. Entirely bypasses
   `TelegramRepository`/`DownloadRepository` so Gemini's behavior can be validated in isolation before
   Phase 6 wires the full pipeline. Reachable only via a plain "Gemini Test (internal)" text button on
   Home — not a feature tile, not part of the platform surface.

What Phase 5 adds on top of that:
- `GeminiApiClient` — raw REST calls only (OkHttp + kotlinx.serialization), **no `google-genai` or any
  other SDK dependency**: resumable file upload (Google's two-step upload protocol), poll-until-`ACTIVE`,
  `generateContent`, best-effort delete. Same stack Telegram already uses in Phase 3/4.
- `GeminiTranslationRepository` — orchestrates upload → poll → single generate call → clean response
  text → delete remote file in a `finally` block (mirrors `gemini_service.py`'s `finally` cleanup exactly).
- Voice ingestion (Phase 4) and translation (this phase) are still **not wired together** — that's
  Phase 6. Right now the only way to exercise `GeminiTranslationRepository` is the internal test screen.

No Foreground Service, no Telegram replies yet.

## Package layout

```
com.sigmabridge.app/
├── domain/
│   ├── model/        Language, LanguagePair, TranslationMode, TranslationRequest/Result,
│   │                 BridgeServiceState, HealthStatus, HealthComponent, ServiceHealth,
│   │                 StoredCredentials, TelegramUpdate, TemporaryVoiceFile, IncomingVoiceMessage
│   ├── repository/    TelegramRepository, TranslationRepository, SettingsRepository,
│   │                 ConnectivityRepository, DownloadRepository (interfaces)
│   ├── cache/          CacheManager (interface)
│   └── usecase/        SaveSettingsUseCase, ObserveStoredCredentialsUseCase, ObserveHealthUseCase,
│                       ObserveIncomingVoiceUseCase
├── data/
│   ├── settings/       SecureSettingsRepository (EncryptedSharedPreferences + Keystore)
│   ├── connectivity/    AndroidConnectivityRepository (ConnectivityManager → Flow<Boolean>)
│   ├── cache/           FileCacheManager (only class touching Context.cacheDir)
│   ├── telegram/        TelegramApiClient, TelegramFileApiClient, TelegramRepositoryImpl,
│   │                    TelegramDownloadRepository, dto/, mapper
│   └── gemini/           GeminiApiClient, GeminiTranslationRepository, dto/
├── di/                 RepositoryModule, NetworkModule (Hilt bindings)
├── presentation/
│   ├── MainActivity.kt
│   ├── navigation/     SigmaBridgeDestination, SigmaBridgeNavGraph
│   ├── home/           HomeScreen, HomeViewModel, FeatureTile
│   ├── settings/        SettingsScreen, SettingsViewModel
│   ├── gemini_test/      GeminiTestScreen, GeminiTestViewModel (internal-only)
│   └── theme/          SigmaBridgeTheme
└── service/            (empty — Phase 7 Foreground Service)
```

## Requirements to open

- Android Studio (Ladybug or newer)
- JDK 17
- Android SDK 35

## Roadmap

| Phase | Scope |
|---|---|
| 1 | Project init (this) |
| 2 | Settings screen: Bot Token + Gemini Key storage |
| 3 | TelegramRepository implementation (long polling) |
| 4 | Voice message ingestion + download |
| 5 | TranslationRepository implementation (Gemini) |
| 6 | Wire pipeline end-to-end |
| 7 | Foreground Service (orchestration only) |
| 8 | Stability: battery exemption, boot receiver, WorkManager health-check |
| 9 | Status/settings UI polish |
| 10 | Release prep |
