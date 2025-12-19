package fm.magiclantern.forum.features.export.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import fm.magiclantern.forum.features.clips.viewmodel.ClipListViewModel
import fm.magiclantern.forum.features.export.ExportPreferences
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel

/**
 * Factory for ExportViewModel.
 * 
 * Note: ExportPreferences is now injectable via Hilt and passed from the caller.
 * This factory bridges between Hilt-managed ClipListViewModel and manually-constructed ExportViewModel.
 */
class ExportViewModelFactory(
    private val clipListViewModel: ClipListViewModel,
    private val gradingViewModel: GradingViewModel,
    private val totalMemory: Long,
    private val cpuCores: Int,
    private val exportPreferences: ExportPreferences
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExportViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ExportViewModel(
                clipListViewModel = clipListViewModel,
                gradingViewModel = gradingViewModel,
                totalMemory = totalMemory,
                cpuCores = cpuCores,
                exportPreferences = exportPreferences
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
