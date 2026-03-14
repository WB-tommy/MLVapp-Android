package fm.magiclantern.forum.features.grading.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.magiclantern.forum.domain.model.ClipDetails
import fm.magiclantern.forum.domain.model.ClipGradingData
import fm.magiclantern.forum.domain.model.DebayerAlgorithm
import fm.magiclantern.forum.domain.model.ProfilePreset
import fm.magiclantern.forum.domain.session.ActiveClipHolder
import fm.magiclantern.forum.nativeInterface.NativeLib
import fm.magiclantern.forum.nativeInterface.RawCorrectionNative
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing all color grading and processing states per clip.
 *
 * Observes ActiveClipHolder for the current clip and exposes grading state.
 * All native JNI calls are made from here when user changes settings.
 */
@HiltViewModel
class GradingViewModel @Inject constructor(
    private val activeClipHolder: ActiveClipHolder
) : ViewModel() {

    // Per-clip grading storage (in-memory)
    private val clipGradingStates = mutableMapOf<Long, ClipGradingData>()

    // Current clip's grading (exposed to UI)
    private val _currentGrading = MutableStateFlow(ClipGradingData())
    val currentGrading: StateFlow<ClipGradingData> = _currentGrading

    // Expose active clip metadata for UI to access bitDepth, dualISO, etc.
    val activeClip: StateFlow<ClipDetails?> = activeClipHolder.activeClip
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Derived properties for common metadata access
    val bitDepth: StateFlow<Int> = activeClipHolder.activeClip
        .map { it?.bitDepth ?: 14 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 14)

    val dualIsoValid: StateFlow<Boolean> = activeClipHolder.activeClip
        .map { it?.dualISO ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Original levels from clip metadata - used to initialize UI when clip is loaded
    val originalBlackLevel: StateFlow<Int> = activeClipHolder.activeClip
        .map { it?.metadata?.originalBlackLevel ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val originalWhiteLevel: StateFlow<Int> = activeClipHolder.activeClip
        .map { it?.metadata?.originalWhiteLevel ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val originalWhiteBalanceKelvin: StateFlow<Int> = activeClipHolder.activeClip
        .map { it?.metadata?.whiteBalanceKelvin ?: 6500 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 6500)

    val originalWhiteBalanceTint: StateFlow<Int> = activeClipHolder.activeClip
        .map { it?.metadata?.whiteBalanceTint ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Check if a clip is currently loaded
    val hasClipLoaded: StateFlow<Boolean> = activeClipHolder.activeClip
        .map { it != null && it.nativeHandle != 0L }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Native handle for JNI calls
    private val clipHandle: Long
        get() = activeClipHolder.activeClip.value?.nativeHandle ?: 0L

    private val clipGUID: Long
        get() = activeClipHolder.activeClip.value?.guid ?: 0L

    init {
        // Observe clip changes - update UI state when clip changes
        viewModelScope.launch {
            activeClipHolder.activeClip.collectLatest { details ->
                if (details != null) {
                    loadClipGradingState(
                        details.guid,
                        details.metadata.whiteBalanceKelvin,
                        details.metadata.whiteBalanceTint,
                        details.grading
                    )
                }
            }
        }
    }

    /**
     * Load grading state for UI display only
     */
    private fun loadClipGradingState(guid: Long, kelvin: Int, tint: Int, seededGrading: ClipGradingData) {
        val grading = clipGradingStates.getOrPut(guid) {
            seededGrading.copy(
                colorGrading = seededGrading.colorGrading.copy(
                    temperature = kelvin,
                    tint = tint
                )
            )
        }
        _currentGrading.value = grading
        
        // Sync receipt debayer mode to ActiveClipHolder for PlayerViewModel
        activeClipHolder.setReceiptDebayerMode(grading.debayerMode)
        // Sync cut marks to ActiveClipHolder for PlayerViewModel playback bounds
        activeClipHolder.setCutMarks(grading.cutIn, grading.cutOut)
    }

    /**
     * Update grading settings - stores in memory only
     */
    fun updateGrading(updater: (ClipGradingData) -> ClipGradingData) {
        val currentGuid = clipGUID
        if (currentGuid == 0L) return

        val newGrading = updater(_currentGrading.value)
        clipGradingStates[currentGuid] = newGrading
        _currentGrading.value = newGrading

        // Notify player to redraw with new settings
        activeClipHolder.notifyProcessingChanged()
    }


    // ==================== Raw Correction Functions ====================

    fun setRawCorrectionEnabled(enabled: Boolean) {
        val handle = clipHandle
        if (handle == 0L) {
            Log.w("GradingViewModel", "Cannot toggle raw correction - no clip loaded")
            return
        }

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(enabled = enabled))
        }

        try {
            RawCorrectionNative.setRawCorrectionEnabled(handle, enabled)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to toggle raw correction: ${e.message}", e)
        }
    }

    fun setDebayerMode(mode: DebayerAlgorithm) {
        val handle = clipHandle
        if (handle == 0L) {
            Log.w("GradingViewModel", "Cannot set debayer mode - no clip loaded")
            return
        }

        updateGrading {
            it.copy(debayerMode = mode)
        }
        
        // Share with ActiveClipHolder so PlayerViewModel can access it
        activeClipHolder.setReceiptDebayerMode(mode)

        try {
            NativeLib.setDebayerMode(handle, mode.nativeId)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set debayer mode: ${e.message}", e)
        }
    }

    fun setDualISO(mode: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIso = mode))
        }

        try {
            RawCorrectionNative.setDualIsoMode(handle, mode)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set Dual ISO: ${e.message}", e)
        }
    }

    fun setDualISOForced(isForced: Boolean) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIsoForced = isForced))
        }

        try {
            RawCorrectionNative.setDualIsoForced(handle, isForced)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set Dual ISO forced: ${e.message}", e)
        }
    }

    fun setDualISOInterpolation(interpolation: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIsoInterpolation = interpolation))
        }

        try {
            RawCorrectionNative.setDualIsoInterpolation(handle, interpolation)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set Dual ISO interpolation: ${e.message}", e)
        }
    }

    fun setDualISOAliasMap(isEnabled: Boolean) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIsoAliasMap = isEnabled))
        }

        if (_currentGrading.value.rawCorrection.dualIso > 0) {
            try {
                RawCorrectionNative.setDualIsoAliasMap(handle, isEnabled)
            } catch (e: Exception) {
                Log.e("GradingViewModel", "Failed to set Dual ISO alias map: ${e.message}", e)
            }
        }
    }

    fun setDarkFrameFile(context: Context, uri: Uri) {
        val handle = clipHandle
        if (handle == 0L) return

        val fileName = DocumentFile.fromSingleUri(context, uri)?.name ?: "Unknown"

        updateGrading {
            it.copy(
                rawCorrection = it.rawCorrection.copy(
                    darkFrameFileName = fileName,
                    darkFrameEnabled = 1  // Enable external dark frame
                )
            )
        }

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                RawCorrectionNative.setDarkFrameFile(handle, pfd.fd)
            }
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set dark frame: ${e.message}", e)
        }
    }

    fun setDarkFrameMode(mode: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(darkFrameEnabled = mode))
        }

        try {
            RawCorrectionNative.setDarkFrameMode(handle, mode)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set dark frame mode: ${e.message}", e)
        }
    }

    fun setFocusDotsMode(mode: Int, interpolation: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(
                rawCorrection = it.rawCorrection.copy(
                    focusPixels = mode,
                    fpiMethod = interpolation
                )
            )
        }

        try {
            RawCorrectionNative.setFocusDotsMode(handle, mode, interpolation)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set focus dots: ${e.message}", e)
        }
    }

    fun setBadPixelsMode(mode: Int, searchMethod: Int, interpolation: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(
                rawCorrection = it.rawCorrection.copy(
                    badPixels = mode,
                    bpsMethod = searchMethod,
                    bpiMethod = interpolation
                )
            )
        }

        try {
            RawCorrectionNative.setBadPixelsMode(handle, mode, searchMethod, interpolation)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set bad pixels: ${e.message}", e)
        }
    }

    fun setChromaSmoothMode(mode: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(chromaSmooth = mode))
        }

        try {
            RawCorrectionNative.setChromaSmoothMode(handle, mode)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set chroma smooth: ${e.message}", e)
        }
    }

    fun setVerticalStripesMode(mode: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(verticalStripes = mode))
        }

        try {
            RawCorrectionNative.setVerticalStripesMode(handle, mode)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set vertical stripes: ${e.message}", e)
        }
    }

    fun setPatternNoise(enable: Boolean) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(patternNoise = if (enable) 1 else 0))
        }

        try {
            RawCorrectionNative.setPatternNoise(handle, enable)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set pattern noise: ${e.message}", e)
        }
    }

    fun setRawBlackLevel(level: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIsoBlack = level))
        }

        try {
            RawCorrectionNative.setRawBlackLevel(handle, level)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set raw black level: ${e.message}", e)
        }
    }

    fun setRawWhiteLevel(level: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIsoWhite = level))
        }

        try {
            RawCorrectionNative.setRawWhiteLevel(handle, level)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set raw white level: ${e.message}", e)
        }
    }

    fun setExposure(exposure: Float) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(exposure = exposure))
        }

        try {
            RawCorrectionNative.setExposureStops(handle, exposure)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set exposure: ${e.message}", e)
        }
    }

    fun setTemperature(kelvin: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        val clampedKelvin = kelvin.coerceIn(2000, 10000)

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(temperature = clampedKelvin))
        }

        try {
            RawCorrectionNative.setWhiteBalanceTemperature(handle, clampedKelvin)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set temperature: ${e.message}", e)
        }
    }

    fun setTint(tint: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(tint = tint))
        }

        try {
            RawCorrectionNative.setWhiteBalanceTint(handle, tint.toFloat())
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set tint: ${e.message}", e)
        }
    }

    fun setTonemap(tonemap: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(
                tonemap = tonemap,
                profileIndex = 0 // Manual tweak invalidates preset
            ))
        }

        try {
            RawCorrectionNative.setTonemappingFunction(handle, tonemap)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set tonemap: ${e.message}", e)
        }
    }

    fun setTransferFunction(function: String) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(
                transferFunction = function,
                profileIndex = 0 // Manual tweak invalidates preset
            ))
        }

        try {
            RawCorrectionNative.setTransferFunction(handle, function)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set transfer function: ${e.message}", e)
        }
    }

    fun setGamut(gamut: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(
                gamut = gamut,
                profileIndex = 0 // Manual tweak invalidates preset
            ))
        }

        try {
            RawCorrectionNative.setGamut(handle, gamut)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set gamut: ${e.message}", e)
        }
    }

    // ==================== Profile Preset Functions ====================

    /**
     * Apply an image profile preset.
     * Atomically updates gamut, tonemap, transfer function, creative adjustments,
     * and profileIndex. Then calls the native engine to apply the bundle.
     * Matches desktop: on_comboBoxProfile_currentIndexChanged
     */
    fun applyProfilePreset(preset: ProfilePreset) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(
                colorGrading = it.colorGrading.copy(
                    profileIndex = preset.id + 1, // +1 because 0 = "Select Preset..."
                    gamut = preset.gamut,
                    tonemap = preset.tonemapFunction,
                    transferFunction = preset.transferFunction,
                    allowCreativeAdjustments = if (preset.allowCreativeAdjustments) 1 else 0
                )
            )
        }

        try {
            RawCorrectionNative.setImageProfile(handle, preset.id)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to apply profile preset: ${e.message}", e)
        }
    }

    /**
     * Set camera matrix mode.
     * Mode: 0=Don't use, 1=Use Camera Matrix, 2=Uncolorscience Fix (Danne)
     * Side-effect: re-applies white balance when matrix changes (matches desktop).
     */
    fun setCameraMatrix(mode: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(camMatrixUsed = mode))
        }

        try {
            RawCorrectionNative.setCamMatrixMode(handle, mode)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set camera matrix: ${e.message}", e)
        }
    }

    /**
     * Set creative adjustments allowed.
     * When disabled, processing sliders (contrast, saturation, curves, etc.) have no effect.
     */
    fun setCreativeAdjustments(allow: Boolean) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(
                allowCreativeAdjustments = if (allow) 1 else 0
            ))
        }

        try {
            RawCorrectionNative.setCreativeAdjustments(handle, allow)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set creative adjustments: ${e.message}", e)
        }
    }

    /**
     * Set EXR mode (Cyan Highlight Fix).
     */
    fun setExrMode(enable: Boolean) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(
                exrMode = if (enable) 1 else 0
            ))
        }

        try {
            RawCorrectionNative.setExrMode(handle, enable)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set EXR mode: ${e.message}", e)
        }
    }

    /**
     * Set AgX rendering transform.
     */
    fun setAgX(enable: Boolean) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(
                agx = if (enable) 1 else 0
            ))
        }

        try {
            RawCorrectionNative.setAgX(handle, enable)
        } catch (e: Exception) {
            Log.e("GradingViewModel", "Failed to set AgX: ${e.message}", e)
        }
    }

    // ==================== Cut In / Cut Out Functions ====================

    /**
     * Set Cut In mark at the given 1-based frame number.
     * Validates that cutIn does not exceed the current cutOut.
     */
    fun setCutIn(frame: Int) {
        val currentCutOut = _currentGrading.value.cutOut
        // If cutOut is set (> 0), don't allow cutIn beyond it
        if (currentCutOut > 0 && frame > currentCutOut) return
        if (frame < 1) return

        updateGrading {
            it.copy(cutIn = frame)
        }
        activeClipHolder.setCutMarks(frame, _currentGrading.value.cutOut)
    }

    /**
     * Set Cut Out mark at the given 1-based frame number.
     * Validates that cutOut is not before the current cutIn.
     */
    fun setCutOut(frame: Int) {
        val currentCutIn = _currentGrading.value.cutIn
        if (frame < currentCutIn) return
        if (frame < 1) return

        updateGrading {
            it.copy(cutOut = frame)
        }
        activeClipHolder.setCutMarks(_currentGrading.value.cutIn, frame)
    }

    /**
     * Reset Cut In to the first frame.
     */
    fun clearCutIn() {
        updateGrading {
            it.copy(cutIn = 1)
        }
        activeClipHolder.setCutMarks(1, _currentGrading.value.cutOut)
    }

    /**
     * Reset Cut Out to "not set" (0 = use last frame).
     */
    fun clearCutOut() {
        updateGrading {
            it.copy(cutOut = 0)
        }
        activeClipHolder.setCutMarks(_currentGrading.value.cutIn, 0)
    }

    // ==================== Clip State Management ====================

    fun removeClipGrading(guid: Long) {
        clipGradingStates.remove(guid)
    }

    fun getAllGradingForExport(): Map<Long, ClipGradingData> {
        return clipGradingStates.toMap()
    }

    fun initializeClipGrading(guid: Long, grading: ClipGradingData) {
        clipGradingStates[guid] = grading
    }
}
