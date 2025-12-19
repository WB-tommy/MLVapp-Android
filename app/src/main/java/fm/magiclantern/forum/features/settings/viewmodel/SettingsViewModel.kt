package fm.magiclantern.forum.features.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val isDropFrameMode: StateFlow<Boolean> = settingsRepository.dropFrameMode
    val debayerMode: StateFlow<DebayerMode> = settingsRepository.debayerMode

    fun setDropFrameMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDropFrameMode(enabled)
        }
    }

    fun setDebayerMode(mode: DebayerMode) {
        viewModelScope.launch {
            settingsRepository.setDebayerMode(mode)
        }
    }
}
