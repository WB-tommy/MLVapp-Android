package fm.magiclantern.forum

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fm.magiclantern.forum.features.clips.ui.ClipRemovalScreen
import fm.magiclantern.forum.features.export.ui.ExportLocationScreen
import fm.magiclantern.forum.features.export.ExportPreferences
import fm.magiclantern.forum.features.export.ui.ExportProgressScreen
import fm.magiclantern.forum.features.export.ui.ExportSelectionScreen
import fm.magiclantern.forum.features.export.ui.ExportSettingsScreen
import fm.magiclantern.forum.features.export.viewmodel.ExportViewModel
import fm.magiclantern.forum.features.export.viewmodel.ExportViewModelFactory
import fm.magiclantern.forum.features.clips.viewmodel.ClipListViewModel
import fm.magiclantern.forum.features.grading.viewmodel.GradingViewModel
import fm.magiclantern.forum.features.onboarding.OnboardingRepository
import fm.magiclantern.forum.features.onboarding.OnboardingScreen
import fm.magiclantern.forum.features.player.viewmodel.PlayerViewModel
import fm.magiclantern.forum.features.settings.ui.SettingsScreen
import fm.magiclantern.forum.features.player.ui.FullScreenView

private const val ROUTE_HOME = "home"
private const val ROUTE_FULLSCREEN = "fullscreen"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_EXPORT_SELECTION = "export_selection"
private const val ROUTE_EXPORT_SETTINGS = "export_settings"
private const val ROUTE_EXPORT_LOCATION = "export_location"
private const val ROUTE_EXPORT_PROGRESS = "export_progress"
private const val ROUTE_CLIP_REMOVAL = "clip_removal"
private const val ROUTE_ONBOARDING = "onboarding"

@Composable
fun NavController(
    windowSizeClass: WindowSizeClass,
    cacheSize: Long,
    cores: Int
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Onboarding repository
    val onboardingRepository = remember { OnboardingRepository(context.applicationContext) }
    val hasCompletedOnboarding by onboardingRepository.hasCompletedOnboarding.collectAsState()

    // Determine start destination based on onboarding state
    val startDestination = if (hasCompletedOnboarding) ROUTE_HOME else ROUTE_ONBOARDING

    // Hilt-injected ViewModels
    val clipListViewModel: ClipListViewModel = hiltViewModel()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val gradingViewModel: GradingViewModel = hiltViewModel()

    // Set system info (memory/cores) for clip loading
    LaunchedEffect(cacheSize, cores) {
        clipListViewModel.setSystemInfo(cacheSize, cores)
    }

    // ExportPreferences - create locally since it needs to be passed to factory
    val exportPreferences = remember { ExportPreferences(context.applicationContext) }

    // ExportViewModel still uses factory (needs ClipListViewModel and GradingViewModel references)
    val exportViewModel: ExportViewModel = viewModel(
        factory = remember(clipListViewModel, gradingViewModel, cacheSize, cores, exportPreferences) {
            ExportViewModelFactory(
                clipListViewModel = clipListViewModel,
                gradingViewModel = gradingViewModel,
                totalMemory = cacheSize,
                cpuCores = cores,
                exportPreferences = exportPreferences
            )
        }
    )

    // Define the navigation graph
    NavHost(navController = navController, startDestination = startDestination) {

        // Onboarding Screen
        composable(ROUTE_ONBOARDING) {
            OnboardingScreen(
                onComplete = {
                    onboardingRepository.completeOnboarding()
                    navController.navigate(ROUTE_HOME) {
                        popUpTo(ROUTE_ONBOARDING) { inclusive = true }
                    }
                }
            )
        }


        // Home Screen
        composable(ROUTE_HOME) {
            MainScreen(
                windowSizeClass = windowSizeClass,
                totalMemory = cacheSize,
                cpuCores = cores,
                navController = navController,
                clipListViewModel = clipListViewModel,
                playerViewModel = playerViewModel,
                gradingViewModel = gradingViewModel
            )
        }

        // Full Screen View
        composable(ROUTE_FULLSCREEN) {
            FullScreenView(
                navController = navController,
                playerViewModel = playerViewModel,
                cpuCores = cores,
            )
        }

        // Settings Screen
        composable(ROUTE_SETTINGS) {
            SettingsScreen(navController = navController)
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

        // Clip Removal Screen
        composable(ROUTE_CLIP_REMOVAL) {
            ClipRemovalScreen(
                clipListViewModel = clipListViewModel,
                navController = navController
            )
        }
    }
}
