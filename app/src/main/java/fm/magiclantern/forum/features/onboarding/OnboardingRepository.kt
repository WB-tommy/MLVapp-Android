package fm.magiclantern.forum.features.onboarding

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing onboarding state.
 * Tracks whether the user has completed the first-launch onboarding flow.
 */
@Singleton
class OnboardingRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _hasCompletedOnboarding = MutableStateFlow(
        prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    )
    val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()

    /**
     * Marks onboarding as completed. This will persist across app restarts.
     */
    fun completeOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
        _hasCompletedOnboarding.value = true
    }

    companion object {
        private const val PREFS_NAME = "onboarding_prefs"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }
}
