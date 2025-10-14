package fm.forum.mlvapp

import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fm.forum.mlvapp.settings.SettingsRepository
import fm.forum.mlvapp.settings.SettingsScreen


private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun NavController(
    cacheSize: Long,
    cores: Int,
    settingsRepository: SettingsRepository
) {
    val navController = rememberNavController()

    // Define the navigation graph
    NavHost(navController = navController, startDestination = ROUTE_HOME) {

        // Home Screen (where the TopBar lives)
        composable(ROUTE_HOME) {
            MainScreen(
                totalMemory = cacheSize,
                cpuCores = cores,
                navController = navController,
                settingsRepository = settingsRepository
            )
        }

        // Settings Screen
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                navController = navController,
                settingsRepository = settingsRepository
            )
        }
    }
}
