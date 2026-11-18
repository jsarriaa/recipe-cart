package com.lechugones.cocina.di

import com.lechugones.cocina.BuildConfig
import com.lechugones.cocina.data.backup.AppInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideAppInfo(): AppInfo = AppInfo(versionName = BuildConfig.VERSION_NAME)
}
