package com.nuvio.tv.core.diagnostics

import android.app.Application
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.SentrySettingsDataStore
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object SentryInitializer {
    private val droppedIssueText = listOf(
        "Large HTTP payload",
        "File IO on Main Thread"
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var active = false

    fun start(application: Application, settings: SentrySettingsDataStore) {
        if (started) return
        started = true
        val initialEnabled = runBlocking(Dispatchers.IO) {
            settings.enabled.first()
        }
        applyEnabled(application, initialEnabled)
        scope.launch {
            settings.enabled.distinctUntilChanged().collect { enabled ->
                applyEnabled(application, enabled)
            }
        }
    }

    private fun applyEnabled(application: Application, enabled: Boolean) {
        if (!enabled) {
            if (Sentry.isEnabled()) {
                Sentry.close()
            }
            active = false
            return
        }

        val dsn = BuildConfig.SENTRY_DSN.trim()
        if (dsn.isBlank()) return
        if (active && Sentry.isEnabled()) return

        SentryAndroid.init(application) { options ->
            options.dsn = dsn
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.environment = BuildConfig.SENTRY_ENVIRONMENT
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.tracesSampleRate = 0.0
            options.setMaxBreadcrumbs(50)
            options.setIgnoredErrors(droppedIssueText)
            options.setIgnoredTransactions(droppedIssueText)
            options.setTraceOptionsRequests(false)
            options.setEnableAutoActivityLifecycleTracing(false)
            options.setEnableTimeToFullDisplayTracing(false)
            options.setEnableFramesTracking(false)
            options.setEnablePerformanceV2(false)
            options.setEnableNetworkEventBreadcrumbs(false)
            options.setReportHistoricalAnrs(false)
            options.setAttachAnrThreadDump(false)
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                redactEvent(event)
                if (shouldDrop(event)) null else event
            }
        }

        Sentry.configureScope { scope ->
            scope.setTag("app.application_id", BuildConfig.APPLICATION_ID)
            scope.setTag("app.flavor", BuildConfig.FLAVOR)
            scope.setTag("app.build_type", BuildConfig.BUILD_TYPE)
            scope.setTag("app.version_name", BuildConfig.VERSION_NAME)
            scope.setTag("app.version_code", BuildConfig.VERSION_CODE.toString())
        }
        active = true
    }

    internal fun redactEvent(event: SentryEvent) {
        event.request = null
        event.user = null
        event.message?.let { message ->
            message.message = com.nuvio.tv.core.security.LogSanitizer.sanitize(message.message)
            message.formatted = com.nuvio.tv.core.security.LogSanitizer.sanitize(message.formatted)
            message.params = message.params?.map(com.nuvio.tv.core.security.LogSanitizer::sanitize)
        }
        event.exceptions?.forEach { exception ->
            exception.value = com.nuvio.tv.core.security.LogSanitizer.sanitize(exception.value)
            exception.stacktrace?.frames?.forEach { frame ->
                frame.vars?.replaceAll { key, value -> redactDiagnosticEntry(key, value) }
            }
        }
        event.breadcrumbs?.forEach { breadcrumb ->
            breadcrumb.message = com.nuvio.tv.core.security.LogSanitizer.sanitize(breadcrumb.message)
            breadcrumb.data.replaceAll { key, value -> redactDiagnosticEntry(key, value) }
        }
        event.extras?.replaceAll { key, value -> redactDiagnosticEntry(key, value) }
        event.tags?.replaceAll { key, value -> redactDiagnosticEntry(key, value).toString() }
    }

    private val sensitiveDiagnosticKey = Regex("(?i).*(password|token|secret|api.?key|authorization|cookie|username|mac.address).*")

    private fun redactDiagnosticEntry(key: String, value: Any?): Any =
        if (sensitiveDiagnosticKey.matches(key)) "***" else redactDiagnosticValue(value)

    private fun redactDiagnosticValue(value: Any?): Any = when (value) {
        null -> ""
        is String -> com.nuvio.tv.core.security.LogSanitizer.sanitize(value)
        is Map<*, *> -> value.entries.associate { (key, item) ->
            val name = key.toString()
            name to redactDiagnosticEntry(name, item)
        }
        is Iterable<*> -> value.map(::redactDiagnosticValue)
        is Array<*> -> value.map(::redactDiagnosticValue)
        else -> value
    }

    private fun shouldDrop(event: SentryEvent): Boolean {
        return eventText(event).any { text ->
            droppedIssueText.any { dropped ->
                text.contains(dropped, ignoreCase = true)
            }
        }
    }

    private fun eventText(event: SentryEvent): List<String> {
        val values = mutableListOf<String>()
        event.message?.formatted?.let(values::add)
        event.message?.message?.let(values::add)
        event.logger?.let(values::add)
        event.transaction?.let(values::add)
        event.exceptions?.forEach { exception ->
            exception.type?.let(values::add)
            exception.value?.let(values::add)
        }
        return values
    }
}
