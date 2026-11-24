package com.lechugones.cocina.di

import com.lechugones.cocina.BuildConfig
import com.lechugones.cocina.provider.SupermarketProvider
import com.lechugones.cocina.provider.ahorramas.AhorramasProvider
import com.lechugones.cocina.provider.aldi.AldiProvider
import com.lechugones.cocina.provider.bonarea.BonareaProvider
import com.lechugones.cocina.provider.carrefour.CarrefourProvider
import com.lechugones.cocina.provider.consum.ConsumProvider
import com.lechugones.cocina.provider.dia.DiaProvider
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

    /** Día: verified from the phone on 2026-11-19 (docs/providers/dia.md). */
    @Provides
    @Singleton
    @IntoSet
    fun provideDia(
        client: OkHttpClient,
        clock: Clock,
    ): SupermarketProvider = DiaProvider.create(client, clock)

    @Provides
    @Singleton
    @IntoSet
    fun provideMercadona(
        client: OkHttpClient,
        clock: Clock,
    ): SupermarketProvider = MercadonaProvider.create(client, clock)

    /** Carrefour: its public search index only, national prices (docs/providers/carrefour.md). */
    @Provides
    @Singleton
    @IntoSet
    fun provideCarrefour(
        client: OkHttpClient,
        clock: Clock,
    ): SupermarketProvider = CarrefourProvider.create(client, clock)

    /** Aldi: its category pages (no online shop), península shelf prices (docs/providers/aldi.md). */
    @Provides
    @Singleton
    @IntoSet
    fun provideAldi(
        client: OkHttpClient,
        clock: Clock,
    ): SupermarketProvider = AldiProvider.create(client, clock)

    /** bonÀrea: its online shop's search, national catalogue and prices (docs/providers/bonarea.md). */
    @Provides
    @Singleton
    @IntoSet
    fun provideBonarea(
        client: OkHttpClient,
        clock: Clock,
    ): SupermarketProvider = BonareaProvider.create(client, clock)

    /** Ahorramás: its product grid (Madrid area, one price list) (docs/providers/ahorramas.md). */
    @Provides
    @Singleton
    @IntoSet
    fun provideAhorramas(
        client: OkHttpClient,
        clock: Clock,
    ): SupermarketProvider = AhorramasProvider.create(client, clock)

    /** Consum: its shop API, national prices; verified from the phone on 2026-11-24 (docs/providers/consum.md). */
    @Provides
    @Singleton
    @IntoSet
    fun provideConsum(
        client: OkHttpClient,
        clock: Clock,
    ): SupermarketProvider = ConsumProvider.create(client, clock)
}
