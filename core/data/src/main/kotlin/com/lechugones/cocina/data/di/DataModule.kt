package com.lechugones.cocina.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.lechugones.cocina.data.db.CocinaDatabase
import com.lechugones.cocina.data.db.CocinaMigrations
import com.lechugones.cocina.data.db.RecipeDao
import com.lechugones.cocina.data.recipe.RecipeRepository
import com.lechugones.cocina.data.recipe.RoomRecipeRepository
import com.lechugones.cocina.data.settings.DataStoreSettingsRepository
import com.lechugones.cocina.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    /** DataStore file name; the backup format (SPEC § 4.7) depends on it. */
    const val SETTINGS_STORE = "settings"

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): CocinaDatabase =
        Room
            .databaseBuilder(context, CocinaDatabase::class.java, CocinaDatabase.NAME)
            .addMigrations(*CocinaMigrations.ALL)
            .build()

    @Provides
    fun provideRecipeDao(database: CocinaDatabase): RecipeDao = database.recipeDao()

    /** Injected so tests can control "now". */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(SETTINGS_STORE) }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindRecipeRepository(impl: RoomRecipeRepository): RecipeRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
