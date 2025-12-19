package fm.magiclantern.forum.nativeInterface

/**
 * Native interface for Raw Correction functionality
 * Corresponds to librtprocess wrapper functions from desktop version
 */
object RawCorrectionNative {

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
    external fun setDarkFrameFile(mlvObjectPtr: Long, fd: Int)

    /**
     * Set dark frame subtraction mode
     * @param mlvObjectPtr Native pointer to MLV object
     * @param mode 0=Off, 1=External, 2=Internal
     */
    external fun setDarkFrameMode(mlvObjectPtr: Long, mode: Int)

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
     * Set dual ISO mode
     * @param mlvObjectPtr Native pointer to MLV object
     * @param mode 0=Off, 1=On, 2=Preview
     */
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
}