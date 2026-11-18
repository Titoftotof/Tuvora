package com.nuvio.tv.core.network

import android.content.Context
import android.content.pm.ProviderInfo
import androidx.core.content.FileProvider

/** FileProvider is created with a framework context, not Hilt's scoped application context. */
class WorkspaceFileProvider : FileProvider() {
    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(BackendWorkspaceStore.wrap(context), info)
    }
}
