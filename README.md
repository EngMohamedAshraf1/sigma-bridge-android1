# Sigma Bridge (Android)

Native Android rewrite of [sigma-bridge](https://github.com/EngMohamedAshraf1/sigma-bridge) (Python).
The app itself is the bot's server — no separate backend.

## Status: Phase 1 — Project Initialization

What exists right now:
- Gradle project skeleton (Kotlin DSL), single `:app` module, Clean Architecture package layout.
- Empty Compose UI shell: a Home screen rendering feature tiles from a list (`FEATURE_TILES`), with
  only Voice Bridge enabled and OCR/Photos/PDF shown as "coming soon". No screen redesign needed to
  turn those on later — just flip `isEnabled` and add a nav destination.
- Domain contracts only, no implementations yet:
  - `TelegramRepository` — `start()` / `stop()` / `restart()` + `state: StateFlow<BridgeServiceState>`.
    A future Foreground Service will call these three methods and observe `state`; it will never
    contain the polling loop itself.
  - `TranslationRepository` — `translate(request: TranslationRequest): Result<TranslationResult>`.
  - `Language` / `LanguagePair` — data classes, not an enum, not hardcoded strings. MVP wires
    `LanguagePair.DEFAULT_MVP_PAIR` (Russian → Arabic), but every call site takes a `LanguagePair`
    parameter.
- No networking, no Telegram/Gemini code, no Foreground Service, no settings storage yet — those are
  Phase 3, 5, 7, and 2 respectively.

## Package layout

```
com.sigmabridge.app/
├── domain/
│   ├── model/        Language, LanguagePair, TranslationMode, TranslationRequest/Result, BridgeServiceState
│   └── repository/    TelegramRepository, TranslationRepository  (interfaces only)
├── data/              (empty — Phase 3 / Phase 5)
├── di/                (empty — Hilt bindings once implementations exist)
├── presentation/
│   ├── MainActivity.kt
│   ├── navigation/     SigmaBridgeDestination, SigmaBridgeNavGraph
│   ├── home/           HomeScreen, FeatureTile
│   └── theme/          SigmaBridgeTheme
└── service/           (empty — Phase 7 Foreground Service)
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
