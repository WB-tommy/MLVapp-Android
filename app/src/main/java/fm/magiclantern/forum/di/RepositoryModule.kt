package fm.magiclantern.forum.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for repository dependencies.
 * 
 * Note: ClipRepository is now provided via @Inject constructor with @Singleton annotation.
 * This module can be used for additional repository bindings if needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule
