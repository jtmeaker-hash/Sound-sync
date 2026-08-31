package com.example.network

import com.example.model.GitHubRelease
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

interface GitHubReleaseApiService {

    @Headers(
        "Accept: application/vnd.github+json",
        "User-Agent: SoundSync-Android-Updater"
    )
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<GitHubRelease>

    @Headers(
        "Accept: application/vnd.github+json",
        "User-Agent: SoundSync-Android-Updater"
    )
    @GET("repos/{owner}/{repo}/releases")
    suspend fun getAllReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<List<GitHubRelease>>

    @Streaming
    @GET
    suspend fun downloadFile(
        @Url fileUrl: String,
        @Header("User-Agent") userAgent: String = "SoundSync-Android-Updater"
    ): Response<ResponseBody>

    companion object {
        private const val GITHUB_API_BASE_URL = "https://api.github.com/"

        fun create(okHttpClient: OkHttpClient? = null): GitHubReleaseApiService {
            val client = okHttpClient ?: OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(GITHUB_API_BASE_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(GitHubReleaseApiService::class.java)
        }
    }
}
