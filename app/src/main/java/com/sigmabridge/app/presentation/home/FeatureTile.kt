package com.sigmabridge.app.presentation.home

import com.sigmabridge.app.domain.model.TranslationMode

/**
 * One tile on the Home "platform" grid. Home renders a list of these
 * instead of hand-laid-out Start/Stop buttons, so adding OCR/Photos/PDF
 * later is "add an entry to FEATURE_TILES", not a Home screen rewrite.
 */
data class FeatureTile(
    val mode: TranslationMode,
    val title: String,
    val subtitle: String,
    val isEnabled: Boolean
)

val FEATURE_TILES: List<FeatureTile> = listOf(
    FeatureTile(
        mode = TranslationMode.VOICE,
        title = "Voice Bridge",
        subtitle = "Russian voice \u2192 Arabic text via Telegram",
        isEnabled = true
    ),
    FeatureTile(
        mode = TranslationMode.OCR,
        title = "OCR",
        subtitle = "Coming soon",
        isEnabled = false
    ),
    FeatureTile(
        mode = TranslationMode.PHOTO,
        title = "Photos",
        subtitle = "Coming soon",
        isEnabled = false
    ),
    FeatureTile(
        mode = TranslationMode.PDF,
        title = "PDF",
        subtitle = "Coming soon",
        isEnabled = false
    )
)
