package com.nuvio.tv.core.profile

/** Temporarily withholds new cloud sync work while a local configuration is being copied. */
object ConfigurationMutation {
    private val count = java.util.concurrent.atomic.AtomicInteger()
    val active: Boolean get() = count.get() > 0
    @Synchronized fun begin() {
        check(!com.nuvio.tv.core.network.BackendWorkspaceStore.switching) { "Server change in progress" }
        count.incrementAndGet()
    }
    fun end() { count.decrementAndGet() }
}
