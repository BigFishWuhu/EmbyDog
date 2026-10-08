package com.embydog.app.core.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaCacheModule {
    private const val CACHE_SIZE_BYTES = 2L * 1024 * 1024 * 1024

    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideMediaCache(@ApplicationContext context: Context): SimpleCache = SimpleCache(
        File(context.cacheDir, "media"),
        LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
        StandaloneDatabaseProvider(context),
    )
}

