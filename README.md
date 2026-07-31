# Sigma Bridge (Android)

Native Android rewrite of [sigma-bridge](https://github.com/EngMohamedAshraf1/sigma-bridge) (Python).
The app itself is the bot's server — no separate backend.

## Status: Phase 3 — TelegramRepository (Long Polling)

Four adjustments requested before this phase, all applied:

1. **SaveSettingsUseCase**: `SettingsViewModel` no longer touches `SettingsRepository` at all — it
   calls `ObserveStoredCredentialsUseCase` (read) and `SaveSettingsUseCase` (write), both in
   `domain/usecase/`.
2. **Validation before saving**: `SaveSettingsUseCase` rejects empty values and obviously malformed
   tokens/keys (regex format checks) before ever calling the repository. Returns
   `SaveSettingsResult.ValidationFailed(errors)`; `SettingsScreen` renders those inline.
3. **Real Internet status on Home**: `ConnectivityRepository` / `AndroidConnectivityRepository` wrap
   `ConnectivityManager.NetworkCallback` in a `callbackFlow` — the only callback-based Android API in
   the app, contained to one class. `ObserveHealthUseCase` combines it with the still-placeholder
   Telegram/Gemini/Service rows; `HomeViewModel` exposes the combined list as a `StateFlow`.
4. **TelegramRepository, coroutines + Flow only**: `TelegramRepositoryImpl` runs the long-polling loop
   as a suspend function inside a scoped coroutine, exposes `state: StateFlow<BridgeServiceState>` and
   `updates: SharedFlow<TelegramUpdate>`. No `Call.enqueue()`/listener-style API anywhere in its public
   surface — `TelegramApiClient` uses OkHttp's synchronous `execute()` inside `withContext(Dispatchers.IO)`,
   which behaves as an ordinary suspending call.

What Phase 3 adds on top of that:
- `TelegramApiClient` — thin wrapper around Telegram's `getUpdates` endpoint (OkHttp + kotlinx.serialization).
- `TelegramRepositoryImpl` — the actual long-polling loop: tracks `update_id` offset, emits parsed
  updates on `updates`, retries network/parse errors with exponential backoff (2s → 30s cap) instead of
  crashing the loop, mirrors the resilience posture of `gemini_service.py`'s retry logic.
- Voice-specific handling (recognizing a voice message, downloading the file) is **not** here — that's
  Phase 4. This phase only proves updates can be pulled from Telegram reliably and pushed out as a Flow.
- No Foreground Service yet (Phase 7) — `start()`/`stop()`/`restart()` can be exercised directly for now
  (e.g. from a debug button) but nothing calls them automatically yet.

No Gemini/translation code yet — that's Phase 5.

## Package layout

```
com.sigmabridge.app/
├── domain/
│   ├── model/        Language, LanguagePair, TranslationMode, TranslationRequest/Result,
│   │                 BridgeServiceState, HealthStatus, HealthComponent, ServiceHealth,
│   │                 StoredCredentials, TelegramUpdate
│   ├── repository/    TelegramRepository, TranslationRepository, SettingsRepository,
│   │                 ConnectivityRepository (interfaces)
│   └── usecase/        SaveSettingsUseCase, ObserveStoredCredentialsUseCase, ObserveHealthUseCase
├── data/
│   ├── settings/       SecureSettingsRepository (EncryptedSharedPreferences + Keystore)
│   ├── connectivity/    AndroidConnectivityRepository (ConnectivityManager → Flow<Boolean>)
│   └── telegram/        TelegramApiClient, TelegramRepositoryImpl, dto/, mapper
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
