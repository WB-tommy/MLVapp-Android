package fm.magiclantern.forum

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fm.magiclantern.forum.clips.ClipViewModel
import fm.magiclantern.forum.clips.ClipViewModelFactory
import fm.magiclantern.forum.clips.ClipRepository
import fm.magiclantern.forum.export.ExportLocationScreen
import fm.magiclantern.forum.export.ExportProgressScreen
import fm.magiclantern.forum.export.ExportPreferences
import fm.magiclantern.forum.export.ExportSelectionScreen
import fm.magiclantern.forum.export.ExportSettingsScreen
import fm.magiclantern.forum.export.ExportViewModel
import fm.magiclantern.forum.export.ExportViewModelFactory
import fm.magiclantern.forum.settings.SettingsRepository
import fm.magiclantern.forum.settings.SettingsScreen


private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_EXPORT_SELECTION = "export_selection"
private const val ROUTE_EXPORT_SETTINGS = "export_settings"
private const val ROUTE_EXPORT_LOCATION = "export_location"
private const val ROUTE_EXPORT_PROGRESS = "export_progress"

@Composable
fun NavController(
    windowSizeClass: WindowSizeClass,
    cacheSize: Long,
    cores: Int,
    settingsRepository: SettingsRepository
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Create ViewModels
    val clipViewModel: ClipViewModel = viewModel(
        factory = remember(context.applicationContext, cacheSize, cores) {
            ClipViewModelFactory(ClipRepository(context.applicationContext), cacheSize, cores)
        }
    )
    val clipUiState by clipViewModel.uiState.collectAsState()

    val exportPreferences = remember(context.applicationContext) { ExportPreferences(context.applicationContext) }

    val exportViewModel: ExportViewModel = viewModel(
        factory = remember(clipViewModel, cacheSize, cores, exportPreferences) {
            ExportViewModelFactory(
                clipViewModel = clipViewModel,
                totalMemory = cacheSize,
                cpuCores = cores,
                exportPreferences = exportPreferences
            )
        }
    )

    // Define the navigation graph
    NavHost(navController = navController, startDestination = ROUTE_HOME) {

        // Home Screen (where the TopBar lives)
        composable(ROUTE_HOME) {
            MainScreen(
                windowSizeClass = windowSizeClass,
                totalMemory = cacheSize,
                cpuCores = cores,
                navController = navController,
                settingsRepository = settingsRepository,
                clipViewModel = clipViewModel
            )
        }

        // Settings Screen
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                navController = navController,
                settingsRepository = settingsRepository
            )
        }

        // Export Screens
        composable(ROUTE_EXPORT_SELECTION) {
            ExportSelectionScreen(
                exportViewModel = exportViewModel,
                navController = navController
            )
        }
        composable(ROUTE_EXPORT_SETTINGS) {
            ExportSettingsScreen(
                exportViewModel = exportViewModel,
                navController = navController
            )
        }
        composable(ROUTE_EXPORT_LOCATION) {
            ExportLocationScreen(
                exportViewModel = exportViewModel,
                navController = navController
            )
        }
        composable(ROUTE_EXPORT_PROGRESS) {
            ExportProgressScreen(
                exportViewModel = exportViewModel,
                navController = navController
            )
        }
    }
}
