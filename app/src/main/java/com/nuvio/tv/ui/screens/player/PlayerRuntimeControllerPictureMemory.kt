package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.picture.VideoZoom
import com.nuvio.tv.core.picture.VideoZoomPolicy
import com.nuvio.tv.core.picture.PlayerPreferencePolicy
import android.util.Log
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * F37 + F36 on TV: the per-series picture memory (aspect mode + manual zoom) and the manual-zoom
 * events. Decisions live in PlayerPreferencePolicy; this file only moves values between the policy,
 * the UI state and TrackPreferenceDataStore's device-local picture store.
 */

/** Suspend: runs inside preparePlaybackBeforeStart, before initializePlayer reads the aspect. */
internal suspend fun PlayerRuntimeController.restoreSeriesPicture() {
    val id = contentId?.takeIf { it.isNotBlank() }
    val stored = if (id != null && rememberPlayerPreferences) trackPreferenceDataStore.loadVodPicture(id) else null
    val defaultMode = trackPreferenceDataStore.defaultVodAspectMode()
    val choice = PlayerPreferencePolicy.initialPicture(
        rememberEnabled = rememberPlayerPreferences,
        stored = stored,
        globalAspectMode = defaultMode ?: deviceLocalPlayerPreferences.aspectMode.first(),
    )
    seriesPictureApplied = true
    Log.d(PlayerRuntimeController.TAG, "PICTURE_PREF restore: remembered=${stored != null} -> $choice")
    val storedFill = deviceLocalPlayerPreferences.tunneledSurfaceFill.first()
    val explicitMode = stored?.aspectMode ?: defaultMode?.name
    val fill = when (explicitMode) {
        com.nuvio.tv.core.picture.AspectMode.ORIGINAL.name -> false
        com.nuvio.tv.core.picture.AspectMode.STRETCH.name -> true
        else -> storedFill
    }
    _uiState.update { it.copy(aspectMode = choice.aspectMode, videoZoom = choice.zoom, tunneledSurfaceFill = fill) }
}

internal fun PlayerRuntimeController.persistSeriesPicture() {
    val id = contentId ?: return
    if (!PlayerPreferencePolicy.persistsSeriesChoice(rememberPlayerPreferences, id)) return
    val state = _uiState.value
    val memory = PlayerPreferencePolicy.pictureMemory(state.aspectMode, state.videoZoom)
    scope.launch { trackPreferenceDataStore.saveVodPicture(id, memory) }
}

internal fun PlayerRuntimeController.handleAspectModeEvent(event: PlayerEvent) {
    when (event) {
        PlayerEvent.OnShowAspectModeDialog -> {
            hideControlsJob?.cancel()
            cancelPauseOverlay()
            _uiState.update { it.copy(
                showAspectModeDialog = true,
                showMoreDialog = false,
                showVideoZoomPanel = false,
                showSpeedDialog = false,
                showAudioOverlay = false,
                showSubtitleOverlay = false,
                showSubtitleStylePanel = false,
                showSubtitleTimingDialog = false,
                showSubtitleDelayOverlay = false,
                showControls = true
            ) }
        }
        PlayerEvent.OnDismissAspectModeDialog -> {
            _uiState.update { it.copy(showAspectModeDialog = false) }
            scheduleHideControls()
        }
        is PlayerEvent.OnSelectAspectMode -> {
            if (_uiState.value.tunnelingEnabled &&
                event.mode !in listOf(com.nuvio.tv.core.picture.AspectMode.ORIGINAL, com.nuvio.tv.core.picture.AspectMode.STRETCH)) return
            _uiState.update { it.copy(
                aspectMode = event.mode,
                tunneledSurfaceFill = if (it.tunnelingEnabled)
                    event.mode == com.nuvio.tv.core.picture.AspectMode.STRETCH else it.tunneledSurfaceFill
            ) }
            persistSeriesPicture()
        }
        PlayerEvent.OnApplyAspectModeToAll -> {
            val state = _uiState.value
            val mode = if (state.tunnelingEnabled) {
                if (state.tunneledSurfaceFill) com.nuvio.tv.core.picture.AspectMode.STRETCH
                else com.nuvio.tv.core.picture.AspectMode.ORIGINAL
            } else state.aspectMode
            scope.launch {
                trackPreferenceDataStore.applyAspectModeToAllVod(mode)
                _uiState.update { it.copy(showAspectModeDialog = false) }
                scheduleHideControls()
            }
        }
        else -> Unit
    }
}

internal fun PlayerRuntimeController.handleVideoZoomEvent(event: PlayerEvent) {
    when (event) {
        PlayerEvent.OnShowVideoZoomPanel -> {
            if (_uiState.value.tunnelingEnabled) {
                // Tunneled video ignores view scale (PlayerAspectScaleUtils): say so instead of a dead panel.
                _uiState.update {
                    it.copy(
                        showAspectRatioIndicator = true,
                        aspectRatioIndicatorText = context.getString(R.string.player_zoom_unavailable_tunneling),
                    )
                }
                hideAspectRatioIndicatorJob?.cancel()
                hideAspectRatioIndicatorJob = scope.launch {
                    delay(1500)
                    _uiState.update { it.copy(showAspectRatioIndicator = false) }
                }
                return
            }
            _uiState.update {
                it.copy(
                    showVideoZoomPanel = true,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSpeedDialog = false,
                    showControls = true,
                )
            }
        }
        PlayerEvent.OnDismissVideoZoomPanel -> {
            _uiState.update { it.copy(showVideoZoomPanel = false) }
            scheduleHideControls()
        }
        is PlayerEvent.OnAdjustVideoZoom -> {
            _uiState.update { it.copy(videoZoom = VideoZoomPolicy.adjust(it.videoZoom, event.axis, event.steps)) }
            persistSeriesPicture()
        }
        PlayerEvent.OnResetVideoZoom -> {
            _uiState.update { it.copy(videoZoom = VideoZoom.IDENTITY) }
            persistSeriesPicture()
        }
        else -> Unit
    }
}
