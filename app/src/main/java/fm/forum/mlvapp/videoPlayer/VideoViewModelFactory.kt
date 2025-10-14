package fm.forum.mlvapp.videoPlayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import fm.forum.mlvapp.settings.SettingsRepository

class VideoViewModelFactory(
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VideoViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VideoViewModel(settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
