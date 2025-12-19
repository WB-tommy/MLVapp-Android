package fm.magiclantern.forum.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module providing app-wide singletons.
 * 
 * Note: SettingsRepository and ActiveClipHolder are now provided via @Inject constructor 
 * with @Singleton annotation. No explicit @Provides needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
