package fm.magiclantern.forum.nativeInterface

/**
 * Native interface for Raw Correction functionality
 * Corresponds to librtprocess wrapper functions from desktop version
 */
object RawCorrectionNative {

    /** Restore the complete low-level RAW receipt as one render-serialized update. */
    external fun applyRawCorrectionSettings(
        mlvObjectPtr: Long,
        enabled: Boolean,
        verticalStripes: Int,
        focusPixels: Int,
        fpiMethod: Int,
        badPixels: Int,
        bpsMethod: Int,
        bpiMethod: Int,
        chromaSmooth: Int,
        patternNoise: Boolean,
        deflickerTarget: Int,
        dualIso: Int,
        dualIsoForced: Boolean,
        dualIsoPattern: Int,
        dualIsoMatchMethod: Int,
        dualIsoEvCorrection: Float,
        dualIsoBlackDelta: Int,
        dualIsoInterpolation: Int,
        dualIsoAliasMap: Boolean,
        dualIsoFrBlending: Boolean,
        rawBlackLevel: Int,
        rawWhiteLevel: Int,
        darkFrameMode: Int
    ): Int

    /**
     * Enable/disable all raw corrections
     * @param mlvObjectPtr Native pointer to MLV object
     * @param enable true to enable raw corrections
     */
    external fun setRawCorrectionEnabled(mlvObjectPtr: Long, enable: Boolean)

    /**
     * Set dark frame subtraction mode
     * @param mlvObjectPtr Native pointer to MLV object
     * @param fd File descriptor of dark frame file
     */
    external fun setDarkFrameFile(mlvObjectPtr: Long, fd: Int): Boolean

    /**
     * Set dark frame subtraction mode
     * @param mlvObjectPtr Native pointer to MLV object
     * @param mode 0=Off, 1=External, 2=Internal
     */
    external fun setDarkFrameMode(mlvObjectPtr: Long, mode: Int): Boolean

    /**
     * Set focus dots fix mode
     * @param mlvObjectPtr Native pointer to MLV object
     * @param mode 0=Off, 1=On, 2=CropRec
     * @param interpolation 0=Method1, 1=Method2, 2=Method3
     */
    external fun setFocusDotsMode(mlvObjectPtr: Long, mode: Int, interpolation: Int)

    /**
     * Set bad pixels fix mode
     * @param mlvObjectPtr Native pointer to MLV object
     * @param mode 0=Off, 1=Auto, 2=Force, 3=Map
     * @param searchMethod 0=Normal, 1=Aggressive, 2=Edit
     * @param interpolation 0=Method1, 1=Method2, 2=Method3
     */
    external fun setBadPixelsMode(
        mlvObjectPtr: Long,
        mode: Int,
        searchMethod: Int,
        interpolation: Int
    )

    /**
     * Set chroma smoothing mode
     * @param mlvObjectPtr Native pointer to MLV object
     * @param mode 0=Off, 1=2x2, 2=3x3, 3=5x5
     */
    external fun setChromaSmoothMode(mlvObjectPtr: Long, mode: Int)

    /**
     * Set vertical stripes fix mode
     * @param mlvObjectPtr Native pointer to MLV object
     * @param mode 0=Off, 1=Normal, 2=Force
     */
    external fun setVerticalStripesMode(mlvObjectPtr: Long, mode: Int)

    /**
     * Atomically apply the complete Dual ISO state. Pattern/matching changes
     * must be serialized together so native auto-detection can be re-armed
     * without exposing a partially updated frame.
     *
     * @param mlvObjectPtr Native pointer to MLV object
     * @param mode 0=Off, 1=HQ; legacy 2 is normalized to HQ
     * @param pattern 0=Auto, 1..4=fixed row pattern, 5=Auto every frame
     * @param matchMethod 1=ISO metadata, 2=histogram
     * @param evCorrection 1.0=Auto, otherwise -6.0..0.0 EV
     * @param blackDelta -1=Auto, otherwise 0..100
     */
    external fun configureDualIso(
        mlvObjectPtr: Long,
        mode: Int,
        forced: Boolean,
        pattern: Int,
        matchMethod: Int,
        evCorrection: Float,
        blackDelta: Int,
        interpolation: Int,
        aliasMap: Boolean,
        fullResBlending: Boolean
    )

    /**
     * Snapshot the last successfully applied frame as
     * [applied, pattern, match method, EV correction, black delta].
     */
    external fun getDualIsoState(mlvObjectPtr: Long): FloatArray?

    /** Compatibility setter for callers that only toggle the HQ mode. */
    external fun setDualIsoMode(
        mlvObjectPtr: Long,
        mode: Int
    )

    external fun setDualIsoForced(
        mlvObjectPtr: Long,
        isForced: Boolean
    )

    external fun setDualIsoInterpolation(
        mlvObjectPtr: Long,
        interpolation: Int
    )

    external fun setDualIsoAliasMap(
        mlvObjectPtr: Long,
        isEnabled: Boolean
    )

    /**
     * Set pattern noise reduction mode
     * @param mlvObjectPtr Native pointer to MLV object
     * @param enable true to enable pattern noise fix
     */
    external fun setPatternNoise(mlvObjectPtr: Long, enable: Boolean)

    /**
     * Set RAW black level
     * @param mlvObjectPtr Native pointer to MLV object
     * @param level Black level value
     */
    external fun setRawBlackLevel(mlvObjectPtr: Long, level: Int)

    /**
     * Set RAW white level
     * @param mlvObjectPtr Native pointer to MLV object
     * @param level White level value
     */
    external fun setRawWhiteLevel(mlvObjectPtr: Long, level: Int)

    /**
     * Set White Balance Temperature (Kelvin)
     * @param mlvObjectPtr Native pointer to MLV object
     * @param kelvin Temperature in Kelvin
     */
    external fun setWhiteBalanceTemperature(mlvObjectPtr: Long, kelvin: Int)

    /**
     * Set White Balance Tint
     * @param mlvObjectPtr Native pointer to MLV object
     * @param tint Tint value
     */
    external fun setWhiteBalanceTint(mlvObjectPtr: Long, tint: Float)

    external fun setWhiteBalance(mlvObjectPtr: Long, kelvin: Int, tint: Int)

    /** Finds and returns [temperatureKelvin, tint] for the sampled source area. */
    external fun pickWhiteBalance(
        mlvObjectPtr: Long,
        frameIndex: Int,
        x: Int,
        y: Int,
        mode: Int
    ): IntArray?

    /**
     * Set exposure stops
     * @param mlvObjectPtr Native pointer to MLV object
     * @param exposure Exposure in stops
     */
    external fun setExposureStops(mlvObjectPtr: Long, exposure: Float)

    /** Apply the complete desktop Processing-panel state in one native update. */
    external fun applyProcessingSettings(
        mlvObjectPtr: Long,
        exposure: Float,
        contrast: Int,
        pivot: Int,
        temperature: Int,
        tint: Int,
        clarity: Int,
        vibrance: Int,
        saturation: Int,
        darkStrength: Int,
        darkRange: Int,
        lightStrength: Int,
        lightRange: Int,
        lightening: Int,
        shadows: Int,
        highlights: Int,
        highlightReconstruction: Boolean,
        allowCreativeAdjustments: Boolean,
        profileIndex: Int,
        tonemap: Int,
        transferFunction: String,
        gamut: Int,
        camMatrixUsed: Int,
        exrMode: Boolean,
        agx: Boolean
    )

    external fun setContrast(mlvObjectPtr: Long, contrast: Int)

    external fun setPivot(mlvObjectPtr: Long, pivot: Int)

    external fun setClarity(mlvObjectPtr: Long, clarity: Int)

    external fun setVibrance(mlvObjectPtr: Long, vibrance: Int)

    external fun setSaturation(mlvObjectPtr: Long, saturation: Int)

    external fun setDarkStrength(mlvObjectPtr: Long, strength: Int)

    external fun setDarkRange(mlvObjectPtr: Long, range: Int)

    external fun setLightStrength(mlvObjectPtr: Long, strength: Int)

    external fun setLightRange(mlvObjectPtr: Long, range: Int)

    external fun setLightening(mlvObjectPtr: Long, lightening: Int)

    external fun setShadows(mlvObjectPtr: Long, shadows: Int)

    external fun setHighlights(mlvObjectPtr: Long, highlights: Int)

    external fun setHighlightReconstruction(mlvObjectPtr: Long, enabled: Boolean)

    /**
     * Set Tonemapping Function
     * @param mlvObjectPtr Native pointer to MLV object
     * @param tonemap Tonemap function index
     */
    external fun setTonemappingFunction(mlvObjectPtr: Long, tonemap: Int)

    /**
     * Set Transfer Function
     * @param mlvObjectPtr Native pointer to MLV object
     * @param function Transfer function expression string
     */
    external fun setTransferFunction(mlvObjectPtr: Long, function: String)

    /**
     * Set Gamut
     * @param mlvObjectPtr Native pointer to MLV object
     * @param gamut Gamut index
     */
    external fun setGamut(mlvObjectPtr: Long, gamut: Int)

    /**
     * Set image profile preset (applies gamut, tonemap, transfer function as a bundle)
     * @param mlvObjectPtr Native pointer to MLV object
     * @param profileIndex Profile index (0=Standard, 1=Tonemapped, ... matches image_profiles.c)
     */
    external fun setImageProfile(mlvObjectPtr: Long, profileIndex: Int)

    /**
     * Set camera matrix mode
     * @param mlvObjectPtr Native pointer to MLV object
     * @param mode 0=Don't use, 1=Use Camera Matrix, 2=Uncolorscience Fix (Danne)
     */
    external fun setCamMatrixMode(mlvObjectPtr: Long, mode: Int)

    /**
     * Set creative adjustments allowed
     * @param mlvObjectPtr Native pointer to MLV object
     * @param allow true to allow creative adjustments (sliders, curves, etc.)
     */
    external fun setCreativeAdjustments(mlvObjectPtr: Long, allow: Boolean)

    /**
     * Set EXR mode (Cyan Highlight Fix)
     * @param mlvObjectPtr Native pointer to MLV object
     * @param enable true to enable cyan highlight fix
     */
    external fun setExrMode(mlvObjectPtr: Long, enable: Boolean)

    /**
     * Set AgX rendering transform
     * @param mlvObjectPtr Native pointer to MLV object
     * @param enable true to enable AgX
     */
    external fun setAgX(mlvObjectPtr: Long, enable: Boolean)
}
