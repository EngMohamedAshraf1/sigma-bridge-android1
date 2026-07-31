# Sigma Bridge (Android)

Native Android rewrite of [sigma-bridge](https://github.com/EngMohamedAshraf1/sigma-bridge) (Python).
The app itself is the bot's server — no separate backend.

## Status: Phase 6 — Full Pipeline (Telegram → Download → Translate → Reply)

Four requirements for this phase, all applied:

1. **Full pipeline wired**: `TelegramRepository` (updates) → `DownloadRepository` (voice download) →
   `TranslationRepository` (Gemini) → `SendTelegramMessageUseCase` (reply), all inside
   `VoiceMessageHandler`. `BridgeOrchestrator` is the missing link that actually starts polling *and*
   consumes the `updates` stream — without it, Phase 3-5's pieces existed but nothing ever ran them
   together.
2. **SendTelegramMessageUseCase**: the sole caller of `TelegramRepository.sendMessage()`. Neither
   `BridgeControlScreen`/`BridgeControlViewModel` (UI) nor any Service touches `sendMessage()` directly
   — only `VoiceMessageHandler` does, and only through this use case.
3. **UpdateDispatcher, not hardcoded voice handling**: `TelegramRepositoryImpl`'s polling loop still only
   emits raw `TelegramUpdate` (unchanged since Phase 3) — it has no idea voice messages exist.
   `UpdateDispatcher` holds a Hilt-multibound `Set<UpdateHandler>` and routes each update to whichever
   handler claims it (`canHandle`). `VoiceMessageHandler` is the only registered handler right now; a
   future text/photo/PDF handler is "implement `UpdateHandler` + add one `@Binds @IntoSet` line in
   `DispatchModule`" — `UpdateDispatcher` and the polling loop stay untouched.
4. **Kept minimal, on purpose**: no Foreground Service, no notification, no boot receiver, no battery
   work — all Phase 7/8. `BridgeControlScreen` is a temporary manual Start/Stop button (only reachable
   internally from Home) so the pipeline can be exercised end-to-end right now; it goes away once a
   real Service exists.

Housekeeping: Phase 4's `ObserveIncomingVoiceUseCase`/`IncomingVoiceMessage` were removed —
`VoiceMessageHandler` now owns that full download step itself as part of the complete pipeline, so the
Phase 4 use case became dead code rather than a layer anything still calls.

What still doesn't exist: any notion of the bridge surviving the app being backgrounded/killed. Starting
it from `BridgeControlScreen` only keeps running while the process is alive — expected until Phase 7.

## Package layout

```
com.sigmabridge.app/
├── domain/
│   ├── model/        Language, LanguagePair, TranslationMode, TranslationRequest/Result,
│   │                 BridgeServiceState, HealthStatus, HealthComponent, ServiceHealth,
│   │                 StoredCredentials, TelegramUpdate, TemporaryVoiceFile
│   ├── repository/    TelegramRepository (+ sendMessage), TranslationRepository, SettingsRepository,
│   │                 ConnectivityRepository, DownloadRepository (interfaces)
│   ├── cache/          CacheManager (interface)
│   ├── dispatch/        UpdateHandler, UpdateDispatcher, VoiceMessageHandler
│   ├── pipeline/         BridgeOrchestrator (starts polling + consumes/dispatches updates)
│   └── usecase/        SaveSettingsUseCase, ObserveStoredCredentialsUseCase, ObserveHealthUseCase,
│                       SendTelegramMessageUseCase
├── data/
│   ├── settings/       SecureSettingsRepository (EncryptedSharedPreferences + Keystore)
│   ├── connectivity/    AndroidConnectivityRepository (ConnectivityManager → Flow<Boolean>)
│   ├── cache/           FileCacheManager (only class touching Context.cacheDir)
│   ├── telegram/        TelegramApiClient (+ sendMessage), TelegramFileApiClient, TelegramRepositoryImpl,
│   │                    TelegramDownloadRepository, dto/, mapper
│   └── gemini/           GeminiApiClient, GeminiTranslationRepository, dto/
├── di/                 RepositoryModule, NetworkModule, DispatchModule (Hilt bindings)
├── presentation/
│   ├── MainActivity.kt
│   ├── navigation/     SigmaBridgeDestination, SigmaBridgeNavGraph
│   ├── home/           HomeScreen, HomeViewModel, FeatureTile
│   ├── settings/        SettingsScreen, SettingsViewModel
│   ├── gemini_test/      GeminiTestScreen, GeminiTestViewModel (internal-only)
│   ├── bridge_control/    BridgeControlScreen, BridgeControlViewModel (internal-only)
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
