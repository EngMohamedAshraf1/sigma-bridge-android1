package com.sigmabridge.app.presentation.home

import androidx.annotation.StringRes
import com.sigmabridge.app.R
import com.sigmabridge.app.domain.model.TranslationMode

/**
 * One tile on the Home "platform" grid. Home renders a list of these
 * instead of hand-laid-out Start/Stop buttons, so adding OCR/Photos/PDF
 * later is "add an entry to FEATURE_TILES", not a Home screen rewrite.
 */
data class FeatureTile(
    val mode: TranslationMode,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val isEnabled: Boolean
)

val FEATURE_TILES: List<FeatureTile> = listOf(
    FeatureTile(
        mode = TranslationMode.VOICE,
        titleRes = R.string.feature_voice_bridge,
        subtitleRes = R.string.feature_voice_bridge_subtitle,
        isEnabled = true
    ),
    FeatureTile(
        mode = TranslationMode.OCR,
        titleRes = R.string.feature_ocr,
        subtitleRes = R.string.feature_coming_soon,
        isEnabled = false
    ),
    FeatureTile(
        mode = TranslationMode.PHOTO,
        titleRes = R.string.feature_photos,
        subtitleRes = R.string.feature_coming_soon,
        isEnabled = false
    ),
    FeatureTile(
        mode = TranslationMode.PDF,
        titleRes = R.string.feature_pdf,
        subtitleRes = R.string.feature_coming_soon,
        isEnabled = false
    )
)
