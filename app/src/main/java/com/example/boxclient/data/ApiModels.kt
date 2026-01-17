package com.example.boxclient.data

import com.squareup.moshi.Json
import java.time.OffsetDateTime

data class SenseRequest(
    @Json(name = "agent_id") val agentId: String,
    @Json(name = "box_id") val boxId: Int,
    val property: String, // "X" or "Y"
)

data class SenseResponse(
    @Json(name = "agent_id") val agentId: String,
    @Json(name = "box_id") val boxId: Int,
    val property: String,
    val status: String,         // "completed", "cached", "cancelled"
    val detected: Boolean?,
    val probability: Double?,
    val deadline: Double,
    val x: Double,
    val y: Double,
    @Json(name = "requested_at") val requestedAt: Double,
    @Json(name = "completed_at") val completedAt: Double?,
)

data class DisposeRequest(
    @Json(name = "agent_id") val agentId: String,
    @Json(name = "box_id") val boxId: Int,
    val property: String,
)

data class DisposeResponse(
    @Json(name = "agent_id") val agentId: String,
    @Json(name = "box_id") val boxId: Int,
    val property: String,
    val status: String,   // "completed", "cancelled"
    val success: Boolean?,
    val deadline: Double,
    val x: Double,
    val y: Double,
    @Json(name = "requested_at") val requestedAt: Double,
    @Json(name = "completed_at") val completedAt: Double?,
)

data class SenseResultView(
    @Json(name = "agent_id") val agentId: String,
    val property: String,
    val status: String,
    val detected: Boolean?,
    val probability: Double?,
    @Json(name = "completed_at") val completedAt: Double?,
)


data class DisposalResultView(
    @Json(name="agent_id") val agentId: String,
    val property: String,
    val status: String,
    val success: Boolean?,
    @Json(name="completed_at") val completedAt: Double?,
)
data class BoxState(
    @Json(name = "box_id") val boxId: Int,
    val deadline: Double,
    val x: Double,
    val y: Double,
    @Json(name="has_X") val hasX: Boolean,
    @Json(name="has_Y") val hasY: Boolean,
    @Json(name="disposal_results") val disposalResults: List<DisposalResultView>,
    @Json(name = "sense_results") val senseResults: List<SenseResultView>,
    @Json(name = "disposed_X") val disposedX: Boolean,
    @Json(name = "disposed_Y") val disposedY: Boolean,
    @Json(name = "sense_time_X") val senseTimeX: Double,
    @Json(name = "sense_time_Y") val senseTimeY: Double,
    @Json(name = "dispose_time_X") val disposeTimeX: Double,
    @Json(name = "dispose_time_Y") val disposeTimeY: Double,

)
data class SenseCancelRequest(
    @Json(name = "agent_id") val agentId: String,
    @Json(name = "box_id") val boxId: Int,
    val property: String, // "X" or "Y"
)

data class SenseCancelResponse(
    @Json(name = "agent_id") val agentId: String,
    @Json(name = "box_id") val boxId: Int,
    val property: String,
    val status: String,   // "cancelled", "not_found", "already_completed"
)

data class DisposeCancelRequest(
    @Json(name = "agent_id") val agentId: String,
    @Json(name = "box_id") val boxId: Int,
    val property: String,
)

data class DisposeCancelResponse(
    @Json(name = "agent_id") val agentId: String,
    @Json(name = "box_id") val boxId: Int,
    val property: String,
    val status: String,   // "cancelled", "not_found", "already_completed"
)

data class ServerTimeResponse(
    @Json(name = "server_time") val serverTime: Double,
    @Json(name = "time_limit_sec") val timeLimitSec: Double,

)

data class AgentPropertyDetectionParams(
    val present: Double,
    val absent: Double,
)

data class AgentDetectionParams(
    val X: AgentPropertyDetectionParams,
    val Y: AgentPropertyDetectionParams,
)

data class AgentDetectionParamsResponse(
    val agents: Map<String, AgentDetectionParams>,
    val `default`: AgentDetectionParams,
)

