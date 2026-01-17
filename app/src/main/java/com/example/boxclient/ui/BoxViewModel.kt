package com.example.boxclient.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.boxclient.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import android.os.SystemClock

enum class ActionKind { SENSE, DISPOSE }




data class ActionKey(
    val boxId: Int,
    val prop: String,      // "X" or "Y"
    val kind: ActionKind
)

data class Countdown(
    val totalSec: Double,
    val remainingSec: Double,
    val running: Boolean
)


data class BoxUiState(
    val boxes: List<BoxState> = emptyList(),
    val isLoading: Boolean = false,
    val lastMessage: String? = null,
    val selectedBoxIdInput: String = "",
    val selectedProperty: String = "X",       // "X" or "Y"
    val selectedBoxId: Int? = null,          // currently selected box (for red highlight)
    val serverTime: Double? = null,          // time synced from server
    val isBusy: Boolean = false,             // “waiting for response”
    val nearbyBoxIds: Set<Int> = emptySet(),
    val countdowns: Map<ActionKey, Countdown> = emptyMap(),
    val selectedAgentId: String = "human_a",  // ✅ NEW
    val agentParams: AgentDetectionParamsResponse? = null, // ✅ NEW (optional)
    val showSyncOverlay: Boolean = false,
    val syncOverlayText: String = "SYNC CHECK — please read this out loud:\n“Three… two… one… now.”",
    val timeLimitSec: Double? = null,
)

class BoxViewModel : ViewModel() {

    private val api = NetworkModule.api
    private val countdownJobs = mutableMapOf<ActionKey, Job>()
    private val activeActionJobs = mutableMapOf<ActionKey, Job>()

    var uiState = androidx.compose.runtime.mutableStateOf(BoxUiState())
        private set

    private var pollingJob: Job? = null

    init {
        startPolling()
        viewModelScope.launch {
            try {
                val params = api.getAgentParams()
                uiState.value = uiState.value.copy(agentParams = params)
            } catch (_: Exception) { }
        }
    }

    fun showSyncOverlay(text: String? = null) {
        uiState.value = uiState.value.copy(
            showSyncOverlay = true,
            syncOverlayText = text ?: uiState.value.syncOverlayText
        )
    }

    fun hideSyncOverlay() {
        uiState.value = uiState.value.copy(showSyncOverlay = false)
    }

    fun cancelAll() {
        val boxId = requireSelectedBoxOrShowError() ?: return
        val prop = uiState.value.selectedProperty
        val agentId = uiState.value.selectedAgentId

        // Stop local countdown(s) immediately (if you have them)
        // stopCountdown(ActionKey(boxId, prop, ActionKind.SENSE))
        // stopCountdown(ActionKey(boxId, prop, ActionKind.DISPOSE))

        uiState.value = uiState.value.copy(
            isBusy = true,
            lastMessage = "Cancelling any active action on box $boxId..."
        )

        viewModelScope.launch {
            var senseStatus: String? = null
            var dispStatus: String? = null

            // Try cancelling sense
            try {
                val resp = api.cancelSense(
                    SenseCancelRequest(agentId = agentId, boxId = boxId, property = prop)
                )
                senseStatus = resp.status
            } catch (_: Exception) { }

            // Try cancelling dispose
            try {
                val resp = api.cancelDispose(
                    DisposeCancelRequest(agentId = agentId, boxId = boxId, property = prop)
                )
                dispStatus = resp.status
            } catch (_: Exception) { }

            uiState.value = uiState.value.copy(
                isBusy = false,
                lastMessage = buildString {
                    append("Cancel: ")
                    if (senseStatus != null) append("sense=$senseStatus ")
                    if (dispStatus != null) append("dispose=$dispStatus")
                    if (senseStatus == null && dispStatus == null) append("failed")
                }.trim()
            )

            fetchBoxesAndTime()
        }
    }

    private fun startCountdown(key: ActionKey, totalSec: Double) {
        // Cancel any existing countdown for this key
        countdownJobs.remove(key)?.cancel()

        val startMs = SystemClock.elapsedRealtime()
        uiState.value = uiState.value.copy(
            countdowns = uiState.value.countdowns + (key to Countdown(totalSec, totalSec, running = true))
        )

        val job = viewModelScope.launch {
            while (isActive) {
                val elapsedSec = (SystemClock.elapsedRealtime() - startMs) / 1000.0
                val remaining = max(0.0, totalSec - elapsedSec)

                uiState.value = uiState.value.copy(
                    countdowns = uiState.value.countdowns + (key to Countdown(totalSec, remaining, running = remaining > 0.0))
                )

                if (remaining <= 0.0) break
                delay(150) // smooth enough; can do 100–250ms
            }
        }

        countdownJobs[key] = job
    }

    private fun stopCountdown(key: ActionKey) {
        countdownJobs.remove(key)?.cancel()

    }

    // Utility: find duration from current box list
    private fun getDurationSec(boxId: Int, prop: String, kind: ActionKind): Double? {
        val box = uiState.value.boxes.firstOrNull { it.boxId == boxId } ?: return null
        return when (kind) {
            ActionKind.SENSE -> if (prop == "X") box.senseTimeX else box.senseTimeY
            ActionKind.DISPOSE -> if (prop == "X") box.disposeTimeX else box.disposeTimeY
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                fetchBoxesAndTime()
                delay(2000)  // poll every 2 seconds
            }
        }
    }

    private suspend fun fetchBoxesAndTime() {
        try {
            val boxes = api.getBoxesState()
            val timeResp = api.getServerTime()

            uiState.value = uiState.value.copy(
                boxes = boxes,
                serverTime = timeResp.serverTime,
                timeLimitSec = timeResp.timeLimitSec,
                // keep lastMessage as-is unless there's an error
            )
        } catch (e: Exception) {
            uiState.value = uiState.value.copy(
                lastMessage = "Error fetching state: ${e.message}"
            )
        }
    }

    fun updateNearbyBoxes(newNearby: Set<Int>) {
        val prevNearby = uiState.value.nearbyBoxIds

        uiState.value = uiState.value.copy(nearbyBoxIds = newNearby)

        // Boxes that were nearby but aren't anymore
        val lost = prevNearby - newNearby
        if (lost.isEmpty()) return

        // Find any active actions whose boxId is now "lost"
        val keysToAbort = activeActionJobs.keys.filter { it.boxId in lost }
        if (keysToAbort.isEmpty()) return

        keysToAbort.forEach { key ->
            abortActionBecauseNotNearby(key)
        }
    }

    private fun abortActionBecauseNotNearby(key: ActionKey) {
        // 1) stop UI countdown (also resets display back to base duration)
        stopCountdown(key)

        // 2) cancel the in-flight coroutine (Retrofit suspend calls should cancel)
        activeActionJobs.remove(key)?.cancel()

        // 3) tell the server to cancel, so it stops sleeping / returns early
        viewModelScope.launch {
            try {
                when (key.kind) {
                    ActionKind.SENSE -> {
                        val agentId = uiState.value.selectedAgentId

                        api.cancelSense(
                            SenseCancelRequest(agentId = agentId, boxId = key.boxId, property = key.prop)
                        )
                    }
                    ActionKind.DISPOSE -> {
                        val agentId = uiState.value.selectedAgentId

                        api.cancelDispose(
                            DisposeCancelRequest(agentId = agentId, boxId = key.boxId, property = key.prop)
                        )
                    }
                }

                uiState.value = uiState.value.copy(
                    isBusy = false,
                    lastMessage = "Stopped ${key.kind} ${key.prop} on box ${key.boxId}: moved out of range."
                )
                fetchBoxesAndTime()
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    isBusy = false,
                    lastMessage = "Out-of-range cancel failed (server may still complete): ${e.message}"
                )
            }
        }
    }


    fun selectBox(boxId: Int) {
        uiState.value = uiState.value.copy(selectedBoxId = boxId)
    }



    fun onBoxIdInputChanged(newValue: String) {
        uiState.value = uiState.value.copy(selectedBoxIdInput = newValue)
    }

    fun onPropertyChanged(newProp: String) {
        uiState.value = uiState.value.copy(selectedProperty = newProp)
    }

    private fun requireSelectedBoxOrShowError(): Int? {
        val id = uiState.value.selectedBoxId
        if (id == null) {
            uiState.value = uiState.value.copy(
                lastMessage = "Select a box from the list or map first."
            )
        }
        return id
    }


    fun setMessage(msg: String) {
        uiState.value = uiState.value.copy(lastMessage = msg)
    }

    fun setSelectedAgent(agent: String) {
        uiState.value = uiState.value.copy(selectedAgentId = agent)
    }


    fun requestSense() {
        val boxId = requireSelectedBoxOrShowError() ?: return
        val prop = uiState.value.selectedProperty

        if (boxId !in uiState.value.nearbyBoxIds) {
            uiState.value = uiState.value.copy(
                lastMessage = "You must be near Box $boxId to SENSE it (beacon CNode1${"%02d".format(boxId)})."
            )
            return
        }

        val key = ActionKey(boxId, prop, ActionKind.SENSE)

        val job = viewModelScope.launch {
            uiState.value = uiState.value.copy(
                isBusy = true,
                lastMessage = "Waiting for SENSE $prop on box $boxId..."
            )


            getDurationSec(boxId, prop, ActionKind.SENSE)?.let { startCountdown(key, it) }

            try {
                val agentId = uiState.value.selectedAgentId
                val resp = api.sense(
                    SenseRequest(
                        agentId = agentId,
                        boxId = boxId,
                        property = prop,
                    )
                )

                stopCountdown(key)
                uiState.value = uiState.value.copy(
                    isBusy = false,
                    lastMessage = "Sense $prop on box $boxId: status=${resp.status}, detected=${resp.detected}"
                )
                fetchBoxesAndTime()
            } catch (e: Exception) {
                stopCountdown(key)
                uiState.value = uiState.value.copy(
                    isBusy = false,
                    lastMessage = "Sense failed: ${e.message}"
                )
            } finally {
                activeActionJobs.remove(key)
            }
        }
        activeActionJobs[key] = job
    }


    fun requestDispose() {
        val boxId = requireSelectedBoxOrShowError() ?: return
        val prop = uiState.value.selectedProperty

        if (boxId !in uiState.value.nearbyBoxIds) {
            uiState.value = uiState.value.copy(
                lastMessage = "You must be near Box $boxId to DISPOSE it (beacon CNode1${"%02d".format(boxId)})."
            )
            return
        }

        val key = ActionKey(boxId, prop, ActionKind.DISPOSE)
        val job = viewModelScope.launch {
            uiState.value = uiState.value.copy(
                isBusy = true,
                lastMessage = "Waiting for DISPOSE $prop on box $boxId..."
            )


            getDurationSec(boxId, prop, ActionKind.DISPOSE)?.let { startCountdown(key, it) }

            try {
                val agentId = uiState.value.selectedAgentId
                val resp = api.dispose(
                    DisposeRequest(
                        agentId = agentId,
                        boxId = boxId,
                        property = prop,
                    )
                )

                stopCountdown(key)
                uiState.value = uiState.value.copy(
                    isBusy = false,
                    lastMessage = "Dispose $prop on box $boxId: status=${resp.status}, success=${resp.success}"
                )
                fetchBoxesAndTime()
            } catch (e: Exception) {
                stopCountdown(key)
                uiState.value = uiState.value.copy(
                    isBusy = false,
                    lastMessage = "Dispose failed: ${e.message}"
                )
            } finally{
                activeActionJobs.remove(key)
            }
        }
        activeActionJobs[key] = job
    }


    fun cancelSense() {
        val boxId = requireSelectedBoxOrShowError() ?: return
        val prop = uiState.value.selectedProperty
        val key = ActionKey(boxId, prop, ActionKind.SENSE)

        viewModelScope.launch {
            uiState.value = uiState.value.copy(
                isBusy = true,
                lastMessage = "Cancelling SENSE $prop on box $boxId..."
            )
            try {
                val agentId = uiState.value.selectedAgentId

                val resp = api.cancelSense(
                    SenseCancelRequest(
                        agentId = agentId,
                        boxId = boxId,
                        property = prop,
                    )
                )
                stopCountdown(key)
                uiState.value = uiState.value.copy(
                    isBusy = false,
                    lastMessage = "Cancel SENSE $prop on box $boxId: status=${resp.status}"
                )
                fetchBoxesAndTime()
            } catch (e: Exception) {
                stopCountdown(key)
                uiState.value = uiState.value.copy(
                    isBusy = false,
                    lastMessage = "Cancel sense failed: ${e.message}"
                )
            }
        }
    }


    fun cancelDispose() {
        val boxId = requireSelectedBoxOrShowError() ?: return
        val prop = uiState.value.selectedProperty

        viewModelScope.launch {
            uiState.value = uiState.value.copy(
                isBusy = true,
                lastMessage = "Cancelling DISPOSE $prop on box $boxId..."
            )

            try {
                val agentId = uiState.value.selectedAgentId

                val resp = api.cancelDispose(
                    DisposeCancelRequest(
                        agentId = agentId,
                        boxId = boxId,
                        property = prop,
                    )
                )

                uiState.value = uiState.value.copy(
                    isBusy = false,
                    lastMessage = "Cancel DISPOSE $prop on box $boxId: status=${resp.status}"
                )

                fetchBoxesAndTime()   // 🔹 refresh state + server time

            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    isBusy = false,
                    lastMessage = "Cancel dispose failed: ${e.message}"
                )
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
