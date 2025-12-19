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
                    loadClipGradingState(details.guid)
                }
            }
        }
    }

    /**
     * Load grading state for UI display only
     */
    private fun loadClipGradingState(guid: Long) {
        val grading = clipGradingStates.getOrPut(guid) {
            ClipGradingData()
        }
        _currentGrading.value = grading
        
        // Sync receipt debayer mode to ActiveClipHolder for PlayerViewModel
        activeClipHolder.setReceiptDebayerMode(grading.debayerMode)
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
