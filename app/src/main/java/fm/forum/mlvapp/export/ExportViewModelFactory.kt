
package fm.forum.mlvapp.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import fm.forum.mlvapp.clips.ClipViewModel

class ExportViewModelFactory(
    private val clipViewModel: ClipViewModel,
    private val totalMemory: Long,
    private val cpuCores: Int,
    private val exportPreferences: ExportPreferences
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExportViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ExportViewModel(
                clipViewModel = clipViewModel,
                totalMemory = totalMemory,
                cpuCores = cpuCores,
                exportPreferences = exportPreferences
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
