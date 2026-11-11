package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A backup holds this gate while taking a logical cross-store snapshot. */
internal object ConfigurationSnapshotGate { val mutex = Mutex() }

internal class SnapshotGatedDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    override val data get() = delegate.data
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        ConfigurationSnapshotGate.mutex.withLock { delegate.updateData(transform) }
}
