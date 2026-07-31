# Sigma Bridge (Android)

Native Android rewrite of [sigma-bridge](https://github.com/EngMohamedAshraf1/sigma-bridge) (Python).
The app itself is the bot's server — no separate backend.

## Status: Phase 2 — Secure Settings + Health Placeholders

What Phase 1 established (still true):
- Gradle project skeleton (Kotlin DSL), single `:app` module, Clean Architecture package layout.
- Home screen rendering feature tiles from a list (`FEATURE_TILES`), only Voice Bridge enabled.
- `TelegramRepository` / `TranslationRepository` contracts, no implementation yet (Phase 3 / Phase 5).
- `Language` / `LanguagePair` as data classes, not hardcoded.

What Phase 2 adds:
- `SettingsRepository` (domain contract) + `SecureSettingsRepository` (data implementation): stores
  `BOT_TOKEN` and `GEMINI_API_KEY` in `EncryptedSharedPreferences`, whose data-encryption key is wrapped
  by an Android Keystore key (`MasterKey`, `AES256_GCM` scheme). Nothing is ever written to disk as
  plaintext. The prefs file is also excluded from Auto Backup / device transfer
  (`data_extraction_rules.xml`), since a restored copy would be undecryptable anyway.
- A minimal Settings screen (two password fields + Save) wired to a Hilt `SettingsViewModel`.
- Home screen now shows a **Status** section listing Telegram / Gemini / Internet / Bridge Service,
  each with a placeholder `HealthStatus` (`UNKNOWN`/`DISABLED` today). The shape
  (`ServiceHealth(component, status, detail)`) is final; only the values are placeholders until
  Phase 3 (Telegram), Phase 5 (Gemini), and Phase 7/8 (connectivity + service state) supply real ones.
- No visual polish — plain `Text`/`Row`/`Column`, no icons beyond the Settings gear, no color coding
  on health status. That's deliberately deferred.

No networking, no Telegram/Gemini code, no Foreground Service yet — those are Phase 3, 5, and 7.

## Package layout

```
com.sigmabridge.app/
├── domain/
│   ├── model/        Language, LanguagePair, TranslationMode, TranslationRequest/Result,
│   │                 BridgeServiceState, HealthStatus, HealthComponent, ServiceHealth
│   └── repository/    TelegramRepository, TranslationRepository, SettingsRepository (interfaces)
├── data/
│   └── settings/       SecureSettingsRepository (EncryptedSharedPreferences + Keystore)
├── di/                 RepositoryModule (Hilt @Binds for SettingsRepository)
├── presentation/
│   ├── MainActivity.kt
│   ├── navigation/     SigmaBridgeDestination, SigmaBridgeNavGraph
│   ├── home/           HomeScreen (feature tiles + Status section), FeatureTile
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
