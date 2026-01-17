package com.example.boxclient.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface BoxApi {

    @GET("boxes/state")
    suspend fun getBoxesState(): List<BoxState>

    @GET("time")
    suspend fun getServerTime(): ServerTimeResponse

    @GET("/agents/params")
    suspend fun getAgentParams(): AgentDetectionParamsResponse

    @POST("sense")
    suspend fun sense(@Body req: SenseRequest): SenseResponse

    @POST("dispose")
    suspend fun dispose(@Body req: DisposeRequest): DisposeResponse

    @POST("sense/cancel")
    suspend fun cancelSense(@Body req: SenseCancelRequest): SenseCancelResponse

    @POST("dispose/cancel")
    suspend fun cancelDispose(@Body req: DisposeCancelRequest): DisposeCancelResponse
}

