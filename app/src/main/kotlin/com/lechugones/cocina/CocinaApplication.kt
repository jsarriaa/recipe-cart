package com.lechugones.cocina

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class CocinaApplication :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    lateinit var httpClient: Provider<OkHttpClient>

    /** Product images go through the same client, so they carry the same honest User-Agent. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { httpClient.get() })) }
            .build()
}
