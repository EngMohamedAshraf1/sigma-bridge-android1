# Sigma Bridge (Android)

Native Android rewrite of [sigma-bridge](https://github.com/EngMohamedAshraf1/sigma-bridge) (Python).
The app itself is the bot's server — no separate backend.

## Status: Phase 4 — Voice Ingestion (Recognize + Download)

Five adjustments requested before this phase, all applied:

1. **DownloadRepository, separate from TelegramRepository**: `TelegramRepository` still only owns
   polling lifecycle + the raw `updates` stream — it never gained file-download responsibility.
   `DownloadRepository` (new interface) + `TelegramDownloadRepository` (impl) own `getFile` +
   file download entirely on their own.
2. **CacheManager abstraction**: `domain/cache/CacheManager.kt` defines `createTempVoice()` /
   `delete()` / `cleanup()`. `FileCacheManager` is the *only* class in the app that touches
   `Context.cacheDir` or constructs a `java.io.File` for a temp voice location — `TelegramDownloadRepository`
   never sees `cacheDir` directly.
3. **UUID filenames**: `FileCacheManager.createTempVoice()` names every file `UUID.randomUUID()`,
   never the Telegram `file_id` — nothing on disk can be correlated back to a specific Telegram file.
4. **TemporaryVoiceFile domain model**: `TranslationRequest.sourceFile` and the whole download/ingestion
   path use `TemporaryVoiceFile(id, path)` instead of passing `java.io.File` through business logic.
   Only `FileCacheManager` and `TelegramFileApiClient` (both data-layer, both doing literal disk/network
   I/O) ever construct an actual `File`.
5. **Gemini stays SDK-independent**: no `google-genai` or any Gemini SDK dependency has been added.
   Phase 5 will use `OkHttpClient` + `kotlinx.serialization` — the same stack `TelegramApiClient` and
   `TelegramFileApiClient` already use — for Gemini's REST API directly.

What Phase 4 adds on top of that:
- `TelegramFileApiClient` — `getFile` (resolve `file_id` → `file_path`) and a streaming file download
  (never buffers the whole voice note in memory), same OkHttp-only posture as Phase 3.
- `ObserveIncomingVoiceUseCase` — filters `TelegramRepository.updates` down to voice messages and
  downloads each one via `DownloadRepository`, emitting `IncomingVoiceMessage(chatId, voiceFile)`.
- **Nothing consumes `IncomingVoiceMessage` yet.** No Gemini call, no reply, no Foreground Service
  driving this use case. Those are Phase 5, 6, and 7. A download failure currently just drops that one
  update silently — proper error surfacing back to the user only makes sense once Phase 6 wires up
  replying, so it's deferred there rather than half-built now.

No translation code, no Foreground Service yet.

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
│   └── telegram/        TelegramApiClient, TelegramFileApiClient, TelegramRepositoryImpl,
│                       TelegramDownloadRepository, dto/, mapper
├── di/                 RepositoryModule, NetworkModule (Hilt bindings)
├── presentation/
│   ├── MainActivity.kt
│   ├── navigation/     SigmaBridgeDestination, SigmaBridgeNavGraph
│   ├── home/           HomeScreen, HomeViewModel, FeatureTile
│   ├── settings/        SettingsScreen, SettingsViewModel
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
