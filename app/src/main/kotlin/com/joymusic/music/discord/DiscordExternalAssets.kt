package com.joymusic.music.discord

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DiscordExternalAssets {

    private const val TAG = "DiscordSvc"
    private const val PROXY_WORKER_URL =
        "https://metrolist-discord-rpc-api.fullerbread2032.workers.dev/image"
    private const val EXTERNAL_ASSETS_API =
        "https://discord.com/api/v9/applications/%s/external-assets"

    private val cache = ConcurrentHashMap<String, String>()
    private const val CACHE_MAX_SIZE = 128

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun getCached(imageUrl: String): String? {
        if (imageUrl.isBlank()) return null
        if (imageUrl.startsWith("mp:")) return imageUrl
        val targetUrl = when {
            imageUrl.startsWith("//") -> "https:$imageUrl"
            !imageUrl.startsWith("http://") && !imageUrl.startsWith("https://") -> "https://$imageUrl"
            else -> imageUrl
        }
        return cache[targetUrl]
    }

    fun resolve(
        imageUrl: String,
        appId: String,
        token: String,
    ): String? {
        if (imageUrl.isBlank()) return null
        if (imageUrl.startsWith("mp:")) return imageUrl

        val targetUrl = when {
            imageUrl.startsWith("//") -> "https:$imageUrl"
            !imageUrl.startsWith("http://") && !imageUrl.startsWith("https://") -> "https://$imageUrl"
            else -> imageUrl
        }

        cache[targetUrl]?.let {
            Timber.tag(TAG).d("resolve: cache hit for %s -> %s", targetUrl.take(60), it)
            return it
        }
        Timber.tag(TAG).d("resolve: cache miss for %s, resolving via worker proxy", targetUrl.take(60))

        // 1. Primary: Kizzy Cloudflare RPC worker proxy (fast & 100% stable)
        try {
            val encodedUrl = URLEncoder.encode(targetUrl, "UTF-8")
            val req = Request.Builder()
                .url("$PROXY_WORKER_URL?url=$encodedUrl")
                .get()
                .build()

            okHttpClient.newCall(req).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val rawId = if (json.has("id")) json.getString("id") else null
                    if (!rawId.isNullOrBlank()) {
                        val result = when {
                            rawId.startsWith("mp:") -> rawId
                            rawId.startsWith("external/") -> "mp:$rawId"
                            else -> "mp:external/$rawId"
                        }
                        cache[targetUrl] = result
                        trimCache()
                        Timber.tag(TAG).i("external-assets (worker): resolved %s -> %s", targetUrl.take(60), result)
                        return result
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "external-assets: worker proxy error for %s", targetUrl.take(60))
        }

        // 2. Fallback: Discord API directly
        val authHeadersToTry = if (token.startsWith("Bearer ") || token.startsWith("Bot ")) {
            listOf(token)
        } else {
            listOf(token, "Bearer $token")
        }

        val jsonMedia = "application/json; charset=utf-8".toMediaType()
        val jsonPayload = JSONObject().put("urls", JSONArray().put(targetUrl)).toString()

        for (authHeader in authHeadersToTry) {
            if (authHeader.isBlank()) continue
            try {
                val req = Request.Builder()
                    .url(EXTERNAL_ASSETS_API.format(appId))
                    .post(jsonPayload.toRequestBody(jsonMedia))
                    .header("Authorization", authHeader)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                okHttpClient.newCall(req).execute().use { response ->
                    val body = response.body?.string()
                    if (response.isSuccessful && !body.isNullOrBlank()) {
                        val rawId = try {
                            val arr = JSONArray(body)
                            if (arr.length() > 0) arr.getJSONObject(0).optString("id") else null
                        } catch (_: Exception) {
                            JSONObject(body).optString("id")
                        }
                        if (!rawId.isNullOrBlank()) {
                            val result = when {
                                rawId.startsWith("mp:") -> rawId
                                rawId.startsWith("external/") -> "mp:$rawId"
                                else -> "mp:external/$rawId"
                            }
                            cache[targetUrl] = result
                            trimCache()
                            Timber.tag(TAG).i("external-assets (direct): resolved %s -> %s", targetUrl.take(60), result)
                            return result
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "external-assets: direct error for %s", targetUrl.take(60))
            }
        }

        return null
    }

    private fun trimCache() {
        if (cache.size > CACHE_MAX_SIZE) {
            val toRemove = cache.size - CACHE_MAX_SIZE
            cache.keys.take(toRemove).forEach { cache.remove(it) }
        }
    }

    fun clearCache() {
        Timber.tag(TAG).d("clearCache: clearing %d entries", cache.size)
        cache.clear()
    }

    fun close() {
        // No-op
    }
}
