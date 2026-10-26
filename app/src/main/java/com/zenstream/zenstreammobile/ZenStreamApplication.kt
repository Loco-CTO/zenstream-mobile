package com.zenstream.zenstreammobile

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.zenstream.zenstreammobile.offline.OfflineNetworkPolicy
import com.zenstream.zenstreammobile.offline.OfflineRuntime

class ZenStreamApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        OfflineRuntime.get(this)
    }

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { OfflineNetworkPolicy.client() }))
            }
            .build()
}
