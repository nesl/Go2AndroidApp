package com.example.boxclient.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
object NetworkModule {

    private const val BASE_URL = "http://172.17.40.64:8080/"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val client = OkHttpClient.Builder()
        // Total time allowed for the whole call (DNS+connect+TLS+request+response)
        .callTimeout(200, TimeUnit.SECONDS)

        // Time allowed to establish TCP/TLS connection
        .connectTimeout(200, TimeUnit.SECONDS)

        // Time allowed between bytes while reading the response
        .readTimeout(200, TimeUnit.SECONDS)

        // Time allowed between bytes while sending the request body
        .writeTimeout(200, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val api: BoxApi = retrofit.create(BoxApi::class.java)
}
