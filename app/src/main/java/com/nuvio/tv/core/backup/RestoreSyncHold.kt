package com.nuvio.tv.core.backup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A restored local configuration cannot automatically overwrite cloud state on the next sign-in. */
object RestoreSyncHold {
    private const val FILE = "restore_sync_hold"
    private val mutable = MutableStateFlow(false)
    val state = mutable.asStateFlow()
    val held: Boolean get() = mutable.value
    fun initialize(context: Context) { mutable.value = File(context.filesDir, FILE).exists() }
    fun mark(context: Context) {
        RestoreTransaction.durableWrite(File(context.filesDir, FILE), byteArrayOf(1))
        mutable.value = true
    }
    fun release(context: Context) {
        val file = File(context.filesDir, FILE)
        check(!file.exists() || file.delete())
        mutable.value = false
    }
}
