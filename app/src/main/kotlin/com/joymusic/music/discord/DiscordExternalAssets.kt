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
        if (imageUrl.startsWith("mp:") || imageUrl.startsWith("external/") || imageUrl.startsWith("attachments/")) return imageUrl
        val targetUrl = when {
            imageUrl.startsWith("//") -> "https:$imageUrl"
            !imageUrl.startsWith("http://") && !imageUrl.startsWith("https://") -> "https://$imageUrl"
            else -> imageUrl
        }
        val cached = cache[targetUrl]
        return if (cached != null && (cached.startsWith("mp:") || cached.startsWith("external/") || cached.startsWith("attachments/"))) {
            cached
        } else {
            null
        }
    }

    fun resolve(
        imageUrl: String,
        appId: String,
        token: String,
    ): String? {
        if (imageUrl.isBlank()) return null
        if (imageUrl.startsWith("mp:") || imageUrl.startsWith("external/") || imageUrl.startsWith("attachments/")) return imageUrl

        val targetUrl = when {
            imageUrl.startsWith("//") -> "https:$imageUrl"
            !imageUrl.startsWith("http://") && !imageUrl.startsWith("https://") -> "https://$imageUrl"
            else -> imageUrl
        }

        getCached(targetUrl)?.let {
            Timber.tag(TAG).d("resolve: cache hit for %s -> %s", targetUrl.take(60), it)
            return it
        }
        Timber.tag(TAG).d("resolve: cache miss for %s, resolving via Discord external-assets", targetUrl.take(60))

        val appIdsToTry = listOf(appId, "973592186835107870", "1053744669524021278").distinct().filter { it.isNotBlank() }
        val endpointsToTry = listOf(
            "https://discord.com/api/v9/applications/%s/external-assets",
            "https://discord.com/api/v10/applications/%s/external-assets",
        )

        val authHeadersToTry = if (token.startsWith("Bearer ") || token.startsWith("Bot ")) {
            listOf(token)
        } else {
            listOf(token, "Bearer $token")
        }

        val jsonMedia = "application/json; charset=utf-8".toMediaType()
        val jsonPayload = JSONObject().put("urls", JSONArray().put(targetUrl)).toString()

        for (targetAppId in appIdsToTry) {
            for (endpoint in endpointsToTry) {
                val url = endpoint.format(targetAppId)
                for (authHeader in authHeadersToTry) {
                    if (authHeader.isBlank()) continue
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .post(jsonPayload.toRequestBody(jsonMedia))
                            .header("Authorization", authHeader)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .build()

                        okHttpClient.newCall(req).execute().use { response ->
                            val body = response.body?.string()
                            Timber.tag(TAG).d("external-assets (appId=%s, endpoint=%s): status=%d, body=%s",
                                targetAppId, endpoint.substringAfter("api/"), response.code, body?.take(100))
                            if (response.isSuccessful && !body.isNullOrBlank()) {
                                val rawId = try {
                                    val arr = JSONArray(body)
                                    if (arr.length() > 0) {
                                        val obj = arr.getJSONObject(0)
                                        obj.optString("external_asset_path").takeIf { it.isNotBlank() }
                                            ?: obj.optString("id").takeIf { it.isNotBlank() }
                                            ?: obj.optString("asset_id").takeIf { it.isNotBlank() }
                                            ?: obj.optString("path").takeIf { it.isNotBlank() }
                                    } else null
                                } catch (_: Exception) {
                                    try {
                                        val obj = JSONObject(body)
                                        obj.optString("external_asset_path").takeIf { it.isNotBlank() }
                                            ?: obj.optString("id").takeIf { it.isNotBlank() }
                                            ?: obj.optString("asset_id").takeIf { it.isNotBlank() }
                                            ?: obj.optString("path").takeIf { it.isNotBlank() }
                                    } catch (_: Exception) {
                                        null
                                    }
                                }

                                if (!rawId.isNullOrBlank()) {
                                    val result = when {
                                        rawId.startsWith("mp:") -> rawId
                                        rawId.startsWith("external/") -> "mp:$rawId"
                                        rawId.startsWith("attachments/") -> "mp:$rawId"
                                        else -> "mp:external/$rawId"
                                    }
                                    cache[targetUrl] = result
                                    trimCache()
                                    Timber.tag(TAG).i("external-assets: successfully resolved %s -> %s", targetUrl.take(60), result)
                                    return result
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "external-assets: error requesting %s", url)
                    }
                }
            }
        }

        Timber.tag(TAG).w("external-assets: could not resolve %s", targetUrl.take(60))
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
