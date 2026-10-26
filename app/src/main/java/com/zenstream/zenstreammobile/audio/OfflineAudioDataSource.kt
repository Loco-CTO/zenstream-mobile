package com.zenstream.zenstreammobile.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.zenstream.zenstreammobile.offline.OfflineNetworkPolicy

/** Media3 HTTP uses its own transport; enforce the same policy as the app HTTP clients. */
@UnstableApi
internal class OfflineAudioDataSource(private val delegate: DataSource) : DataSource by delegate {
    private var network = false

    override fun open(dataSpec: DataSpec): Long {
        network = dataSpec.uri.scheme in setOf("http", "https")
        if (network) OfflineNetworkPolicy.requireNetwork()
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (network) OfflineNetworkPolicy.requireNetwork()
        return delegate.read(buffer, offset, length)
    }

    override fun close() {
        try {
            delegate.close()
        } finally {
            network = false
        }
    }
}
