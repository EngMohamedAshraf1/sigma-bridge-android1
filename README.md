# Sigma Bridge (Android)

Native Android rewrite of [sigma-bridge](https://github.com/EngMohamedAshraf1/sigma-bridge) (Python).
The app itself is the bot's server — no separate backend.

## Status: Phase 7 — Foreground Service

Four requirements for this phase, all applied:

1. **Only the Foreground Service, nothing else**: no `BootReceiver`, no `WorkManager`, no
   battery-optimization-exemption code anywhere in this commit. Manifest only gained what a Foreground
   Service itself needs to run and show its mandatory notification (`FOREGROUND_SERVICE`,
   `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`) — no `RECEIVE_BOOT_COMPLETED`.
2. **BridgeOrchestrator untouched**: zero diff on `domain/pipeline/BridgeOrchestrator.kt` this phase
   (verified via `git diff`). All retry logic still lives in `GeminiTranslationRepository`/
   `TelegramRepositoryImpl`; the notification is built entirely inside `BridgeForegroundService`, never
   passed into or built by the orchestrator.
3. **Service does exactly three things**: `BridgeForegroundService.onStartCommand` calls
   `bridgeOrchestrator.start()` (with `startForeground()` for the mandatory notification), stays alive
   as a normal foreground service process, and on `ACTION_STOP` calls `bridgeOrchestrator.stop()` then
   `stopForeground()`/`stopSelf()`. `START_NOT_STICKY` is deliberate — if the OS kills the process, it
   does **not** come back on its own; that's explicitly deferred, not silently half-solved.
4. **BridgeControlScreen now controls the real Service**: `BridgeControlViewModel.start()`/`stop()` send
   Intents to `BridgeForegroundService` (`ContextCompat.startForegroundService` / `startService`) instead
   of calling `BridgeOrchestrator` directly. Reading `state` still goes straight to `BridgeOrchestrator`
   (a Hilt `@Singleton` — the same instance the Service drives), since observing state isn't "controlling"
   the bridge. The screen dropped its "(internal)" label — it's the real control surface now, not a
   stand-in. **No automatic startup** — nothing starts the service without the user tapping Start.

What Phase 7 adds on top of that:
- `BridgeForegroundService` — orchestration-only wrapper: notification channel + notification, delegates
  everything else to `BridgeOrchestrator`.
- A `POST_NOTIFICATIONS` runtime permission request on API 33+ before starting (the service still works
  without it — the notification is just invisible). This is baseline correct behavior for showing any
  foreground-service notification on modern Android, not a "reliability feature" like the ones deferred
  to Phase 8.

Explicitly NOT in this phase (Phase 8, once the app has been built and tested): boot-time auto-start,
battery-optimization exemption prompts, WorkManager health-check, OEM-specific guidance.

**Post-Phase-7 fix**: the project was missing `res/mipmap-*`/launcher-icon resources entirely (an
oversight from Phase 1 — `AndroidManifest.xml` referenced `@mipmap/ic_launcher` but nothing ever
created it), which broke resource linking in Android Studio. Fixed with an adaptive icon
(`mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`, vector `drawable/ic_launcher_background.xml`
+ `ic_launcher_foreground.xml`, `values/colors.xml`). Since `minSdk = 26` and adaptive icons are supported
on every API level this app targets, this is the *only* mipmap qualifier needed — no legacy
per-density PNG fallback. The foreground glyph is placeholder abstract art; swap it for real branding
in Phase 9.

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
└── service/            BridgeForegroundService (Start/keep-alive/Stop only, notification is local to this class)
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
