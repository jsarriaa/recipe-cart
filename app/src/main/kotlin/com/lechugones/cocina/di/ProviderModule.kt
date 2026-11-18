package com.lechugones.cocina.di

import com.lechugones.cocina.BuildConfig
import com.lechugones.cocina.provider.SupermarketProvider
import com.lechugones.cocina.provider.mercadona.MercadonaProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Network clients and the supermarket providers (docs/providers/README.md). Each provider is
 * contributed to a `Set<SupermarketProvider>`; feature code never names a concrete provider.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {
    /** Honest identification, no browser impersonation (etiquette rule 4). */
    val USER_AGENT = "CocinaParaLechugones/${BuildConfig.VERSION_NAME} (Android; personal, non-commercial)"

    /** Shared by the providers and the image loader. */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build(),
                )
            }.build()

    @Provides
    @Singleton
    @IntoSet
    fun provideMercadona(
        client: OkHttpClient,
        clock: Clock,
    ): SupermarketProvider = MercadonaProvider.create(client, clock)
}
