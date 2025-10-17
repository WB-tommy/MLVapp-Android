package fm.forum.mlvapp.clips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import fm.forum.mlvapp.data.ClipRepository

class ClipViewModelFactory(
    private val repository: ClipRepository,
    private val totalMemory: Long,
    private val cpuCores: Int
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ClipViewModel::class.java)) {
            return ClipViewModel(repository, totalMemory, cpuCores) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
