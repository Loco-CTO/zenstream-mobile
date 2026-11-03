package com.zenstream.zenstreammobile.offline

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Wait for the persisted setting and process-wide network policy before the next test runs. */
internal suspend fun ConnectionCoordinator.setModeAndAwait(mode: ConnectionMode) {
    setMode(mode)
    withTimeout(10_000) {
        while (
            state.value.mode != mode ||
                OfflineNetworkPolicy.allowed != (mode == ConnectionMode.AUTOMATIC)
        ) {
            delay(10)
        }
    }
}
