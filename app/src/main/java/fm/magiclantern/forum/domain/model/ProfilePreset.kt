package fm.magiclantern.forum.domain.model

/**
 * Sealed class representing all available image profile presets.
 * Values are copied from image_profiles.c (native C code).
 *
 * Each preset bundles: gamut, tonemap function, transfer function,
 * and whether creative adjustments are allowed.
 *
 * Using the "Kotlin shadows C" approach since these profiles change rarely.
 */
sealed class ProfilePreset(
    val id: Int,
    val displayName: String,
    val gamut: Int,
    val tonemapFunction: Int,
    val transferFunction: String,
    val allowCreativeAdjustments: Boolean
) {
    data object Standard : ProfilePreset(
        id = 0,
        displayName = "Standard",
        gamut = 0, // GAMUT_Rec709
        tonemapFunction = 0, // TONEMAP_None
        transferFunction = "pow(x, 1/3.15)",
        allowCreativeAdjustments = true
    )

    data object Tonemapped : ProfilePreset(
        id = 1,
        displayName = "Tonemapped",
        gamut = 0, // GAMUT_Rec709
        tonemapFunction = 1, // TONEMAP_Reinhard
        transferFunction = "(x < 0.0) ? 0 : pow(x / (1.0 + x), 1/3.15)",
        allowCreativeAdjustments = true
    )

    data object Film : ProfilePreset(
        id = 2,
        displayName = "Film",
        gamut = 0, // GAMUT_Rec709
        tonemapFunction = 2, // TONEMAP_Tangent
        transferFunction = "pow(atan(x) / atan(8.0), 1/3.465)",
        allowCreativeAdjustments = true
    )

    data object AlexaLogC : ProfilePreset(
        id = 3,
        displayName = "Alexa Log-C",
        gamut = 6, // GAMUT_AlexaWideGamutRGB
        tonemapFunction = 3, // TONEMAP_AlexaLogC
        transferFunction = "(x > 0.010591) ? (0.247190 * log10(5.555556 * x + 0.052272) + 0.385537) : (5.367655 * x + 0.092809)",
        allowCreativeAdjustments = false
    )

    data object CineonLog : ProfilePreset(
        id = 4,
        displayName = "Cineon Log",
        gamut = 6, // GAMUT_AlexaWideGamutRGB
        tonemapFunction = 4, // TONEMAP_CineonLog
        transferFunction = "((log10(x * (1.0 - 0.0108) + 0.0108)) * 300.0 + 685.0) / 1023.0",
        allowCreativeAdjustments = false
    )

    data object SonySLog3 : ProfilePreset(
        id = 5,
        displayName = "Sony S-Log3",
        gamut = 7, // GAMUT_SonySGamut3
        tonemapFunction = 5, // TONEMAP_SonySLog
        transferFunction = "(x >= 0.01125000) ? (420.0 + log10((x + 0.01) / (0.18 + 0.01)) * 261.5) / 1023.0 : (x * (171.2102946929 - 95.0) / 0.01125000 + 95.0) / 1023.0",
        allowCreativeAdjustments = false
    )

    data object Linear : ProfilePreset(
        id = 6,
        displayName = "Linear",
        gamut = 0, // GAMUT_Rec709
        tonemapFunction = 0, // TONEMAP_None
        transferFunction = "x",
        allowCreativeAdjustments = false
    )

    data object SRGB : ProfilePreset(
        id = 7,
        displayName = "sRGB",
        gamut = 0, // GAMUT_Rec709
        tonemapFunction = 6, // TONEMAP_sRGB
        transferFunction = "x < 0.0031308 ? x * 12.92 : (1.055 * pow(x, 1.0 / 2.4)) - 0.055",
        allowCreativeAdjustments = false
    )

    data object Rec709 : ProfilePreset(
        id = 8,
        displayName = "Rec. 709",
        gamut = 0, // GAMUT_Rec709
        tonemapFunction = 7, // TONEMAP_Rec709
        transferFunction = "(x <= 0.018) ? (x * 4.5) : 1.099 * pow( x, (0.45) ) - 0.099",
        allowCreativeAdjustments = false
    )

    data object DavinciWGIntermediate : ProfilePreset(
        id = 9,
        displayName = "Davinci WG/Intermediate",
        gamut = 8, // GAMUT_DavinciWideGamut
        tonemapFunction = 9, // TONEMAP_DavinciIntermediate
        transferFunction = "(x <= 0.00262409) ? (x * 10.44426855) : (log10(x + 0.0075) / log10(2) + 7.0) * 0.07329248",
        allowCreativeAdjustments = false
    )

    data object FujiFLog : ProfilePreset(
        id = 10,
        displayName = "Fuji F-Log",
        gamut = 1, // GAMUT_Rec2020
        tonemapFunction = 0, // TONEMAP_None
        transferFunction = "(x < 0.00089) ? (8.735631 * x + 0.092864) : (0.344676 * log10(0.555556 * x + 0.009468) + 0.790453)",
        allowCreativeAdjustments = false
    )

    data object CanonLog : ProfilePreset(
        id = 11,
        displayName = "Canon Log",
        gamut = 10, // GAMUT_Canon_Cinema
        tonemapFunction = 11, // TONEMAP_CanonLog
        transferFunction = "(0.529136 * (log10 ( 10.1596 * x + 1 ))) + 0.0730597",
        allowCreativeAdjustments = false
    )

    data object PanasonicVLog : ProfilePreset(
        id = 12,
        displayName = "Panasonic V-Log",
        gamut = 11, // GAMUT_PanasonivV
        tonemapFunction = 12, // TONEMAP_PanasonicVLog
        transferFunction = "(x >= 0.01) ? (0.241514 * log10(x + 0.00873) + 0.598206) : (5.6 * x + 0.125)",
        allowCreativeAdjustments = false
    )

    companion object {
        /** All available presets, ordered by ID (matching desktop comboBoxProfile) */
        val all: List<ProfilePreset> = listOf(
            Standard, Tonemapped, Film,
            AlexaLogC, CineonLog, SonySLog3,
            Linear, SRGB, Rec709,
            DavinciWGIntermediate, FujiFLog,
            CanonLog, PanasonicVLog
        )

        /** Find preset by native ID */
        fun fromId(id: Int): ProfilePreset? = all.find { it.id == id }
    }
}
