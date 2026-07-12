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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@OptIn(ExperimentalCoroutinesApi::class)
class GradingViewModel @Inject constructor(
    private val activeClipHolder: ActiveClipHolder
) : ViewModel() {
    private val nativeDispatcher = Dispatchers.Default.limitedParallelism(1)

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

        launchNativeUpdate("toggle raw correction") {
            RawCorrectionNative.setRawCorrectionEnabled(handle, enabled)
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

        launchNativeUpdate("set debayer mode") {
            NativeLib.setDebayerMode(handle, mode.nativeId)
        }
    }

    fun setDualISO(mode: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIso = mode))
        }

        launchNativeUpdate("set Dual ISO") {
            RawCorrectionNative.setDualIsoMode(handle, mode)
        }
    }

    fun setDualISOForced(isForced: Boolean) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIsoForced = isForced))
        }

        launchNativeUpdate("set Dual ISO forced") {
            RawCorrectionNative.setDualIsoForced(handle, isForced)
        }
    }

    fun setDualISOInterpolation(interpolation: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIsoInterpolation = interpolation))
        }

        launchNativeUpdate("set Dual ISO interpolation") {
            RawCorrectionNative.setDualIsoInterpolation(handle, interpolation)
        }
    }

    fun setDualISOAliasMap(isEnabled: Boolean) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIsoAliasMap = isEnabled))
        }

        if (_currentGrading.value.rawCorrection.dualIso > 0) {
            launchNativeUpdate("set Dual ISO alias map") {
                RawCorrectionNative.setDualIsoAliasMap(handle, isEnabled)
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

        viewModelScope.launch(nativeDispatcher) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    RawCorrectionNative.setDarkFrameFile(handle, pfd.fd)
                }
            } catch (e: Exception) {
                Log.e("GradingViewModel", "Failed to set dark frame: ${e.message}", e)
            }
        }
    }

    fun setDarkFrameMode(mode: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(darkFrameEnabled = mode))
        }

        launchNativeUpdate("set dark frame mode") {
            RawCorrectionNative.setDarkFrameMode(handle, mode)
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

        launchNativeUpdate("set focus dots") {
            RawCorrectionNative.setFocusDotsMode(handle, mode, interpolation)
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

        launchNativeUpdate("set bad pixels") {
            RawCorrectionNative.setBadPixelsMode(handle, mode, searchMethod, interpolation)
        }
    }

    fun setChromaSmoothMode(mode: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(chromaSmooth = mode))
        }

        launchNativeUpdate("set chroma smooth") {
            RawCorrectionNative.setChromaSmoothMode(handle, mode)
        }
    }

    fun setVerticalStripesMode(mode: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(verticalStripes = mode))
        }

        launchNativeUpdate("set vertical stripes") {
            RawCorrectionNative.setVerticalStripesMode(handle, mode)
        }
    }

    fun setPatternNoise(enable: Boolean) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(patternNoise = if (enable) 1 else 0))
        }

        launchNativeUpdate("set pattern noise") {
            RawCorrectionNative.setPatternNoise(handle, enable)
        }
    }

    fun setRawBlackLevel(level: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIsoBlack = level))
        }

        launchNativeUpdate("set raw black level") {
            RawCorrectionNative.setRawBlackLevel(handle, level)
        }
    }

    fun setRawWhiteLevel(level: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(rawCorrection = it.rawCorrection.copy(dualIsoWhite = level))
        }

        launchNativeUpdate("set raw white level") {
            RawCorrectionNative.setRawWhiteLevel(handle, level)
        }
    }

    fun setExposure(exposure: Float) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(exposure = exposure))
        }

        launchNativeUpdate("set exposure") {
            RawCorrectionNative.setExposureStops(handle, exposure)
        }
    }

    fun setTemperature(kelvin: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        val clampedKelvin = kelvin.coerceIn(2000, 10000)

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(temperature = clampedKelvin))
        }

        launchNativeUpdate("set temperature") {
            RawCorrectionNative.setWhiteBalanceTemperature(handle, clampedKelvin)
        }
    }

    fun setTint(tint: Int) {
        val handle = clipHandle
        if (handle == 0L) return

        updateGrading {
            it.copy(colorGrading = it.colorGrading.copy(tint = tint))
        }

        launchNativeUpdate("set tint") {
            RawCorrectionNative.setWhiteBalanceTint(handle, tint.toFloat())
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

        launchNativeUpdate("set tonemap") {
            RawCorrectionNative.setTonemappingFunction(handle, tonemap)
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

        launchNativeUpdate("set transfer function") {
            RawCorrectionNative.setTransferFunction(handle, function)
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

        launchNativeUpdate("set gamut") {
            RawCorrectionNative.setGamut(handle, gamut)
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

        launchNativeUpdate("apply profile preset") {
            RawCorrectionNative.setImageProfile(handle, preset.id)
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

        launchNativeUpdate("set camera matrix") {
            RawCorrectionNative.setCamMatrixMode(handle, mode)
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

        launchNativeUpdate("set creative adjustments") {
            RawCorrectionNative.setCreativeAdjustments(handle, allow)
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

        launchNativeUpdate("set EXR mode") {
            RawCorrectionNative.setExrMode(handle, enable)
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

        launchNativeUpdate("set AgX") {
            RawCorrectionNative.setAgX(handle, enable)
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

    private fun launchNativeUpdate(action: String, block: () -> Unit) {
        viewModelScope.launch(nativeDispatcher) {
            try {
                block()
                // updateGrading() requests an immediate redraw for responsive UI,
                // but native processing setters run on this dispatcher. Request a
                // second redraw after the native state/LUT is actually committed so
                // GPU preview never caches the pre-update snapshot under the new
                // processing version.
                activeClipHolder.notifyProcessingChanged()
            } catch (e: Exception) {
                Log.e("GradingViewModel", "Failed to $action: ${e.message}", e)
            }
        }
    }
}
