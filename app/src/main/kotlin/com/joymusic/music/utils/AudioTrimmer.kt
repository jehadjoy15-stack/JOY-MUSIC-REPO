/**
 * JOY MUSIC Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.joymusic.music.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.getSystemService
import com.joymusic.innertube.YouTube
import com.joymusic.music.constants.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.core.net.toUri

object AudioTrimmer {
    private const val TAG = "AudioTrimmer"

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun tryExtractFromCache(
        cache: Cache?,
        key: String,
        destinationFile: File,
    ): Boolean {
        if (cache == null) return false
        val cachedSpans = try { cache.getCachedSpans(key) } catch (_: Exception) { emptySet() }
        if (cachedSpans.isEmpty()) return false

        val totalCached = cachedSpans.sumOf { it.length }
        if (totalCached < 50_000L) return false

        try {
            val cacheDataSource = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(null)
                .createDataSource()

            val dataSpec = DataSpec.Builder()
                .setUri(key.toUri())
                .setKey(key)
                .build()

            cacheDataSource.open(dataSpec)
            destinationFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (true) {
                    bytesRead = cacheDataSource.read(buffer, 0, buffer.size)
                    if (bytesRead <= 0) break
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
            }
            cacheDataSource.close()
            return destinationFile.length() > 50_000L
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not extract directly from cache for key: %s", key)
            destinationFile.delete()
            return false
        }
    }

    private fun downloadFastWithRanges(
        streamUrl: String,
        headers: Map<String, String>,
        contentLength: Long?,
        destinationFile: File,
    ) {
        val totalBytes = contentLength ?: run {
            val probeReq = Request.Builder()
                .url(streamUrl)
                .header("Range", "bytes=0-0")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            httpClient.newCall(probeReq).execute().use { res ->
                val rangeHeader = res.header("Content-Range")
                rangeHeader?.substringAfterLast('/')?.trim()?.toLongOrNull()
            }
        }

        if (totalBytes != null && totalBytes > 100_000L) {
            val chunkSize = 1024 * 1024L // 1MB unthrottled chunks
            destinationFile.outputStream().use { output ->
                var start = 0L
                while (start < totalBytes) {
                    val end = minOf(start + chunkSize - 1L, totalBytes - 1L)
                    val chunkReq = Request.Builder()
                        .url(streamUrl)
                        .header("Range", "bytes=$start-$end")
                        .apply { headers.forEach { (k, v) -> header(k, v) } }
                        .build()

                    httpClient.newCall(chunkReq).execute().use { response ->
                        if (!response.isSuccessful && response.code != 206) {
                            throw IOException("HTTP ${response.code} downloading chunk $start-$end")
                        }
                        val body = response.body ?: throw IOException("Empty chunk body")
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    start = end + 1L
                }
                output.flush()
            }
        } else {
            val req = Request.Builder()
                .url(streamUrl)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                res.body?.byteStream()?.use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
            }
        }
    }

    suspend fun getOrDownloadSourceAudio(
        context: Context,
        songId: String,
        downloadCache: Cache? = null,
        playerCache: Cache? = null,
    ): File = withContext(Dispatchers.IO) {
        val tempSourceDir = File(context.cacheDir, "source_audio").apply { mkdirs() }
        val cachedSource = File(tempSourceDir, "source_$songId.tmp")

        if (cachedSource.exists() && cachedSource.length() > 50_000L) {
            Timber.tag(TAG).d("Using existing cached source for $songId (${cachedSource.length()} bytes)")
            return@withContext cachedSource
        }

        // 1. Try local cache extraction (0ms network)
        if (tryExtractFromCache(downloadCache, songId, cachedSource)) {
            Timber.tag(TAG).d("Extracted $songId from downloadCache")
            return@withContext cachedSource
        }
        if (tryExtractFromCache(playerCache, songId, cachedSource)) {
            Timber.tag(TAG).d("Extracted $songId from playerCache")
            return@withContext cachedSource
        }

        // 2. High-speed unthrottled range download from network
        val connectivityManager = context.getSystemService<ConnectivityManager>()
            ?: throw IllegalStateException("ConnectivityManager unavailable")

        Timber.tag(TAG).d("Fetching player response for $songId")
        val playbackData = YTPlayerUtils.playerResponseForPlayback(
            videoId = songId,
            audioQuality = AudioQuality.HIGH,
            connectivityManager = connectivityManager,
        ).getOrThrow()

        val tempDownload = File(tempSourceDir, "source_${songId}_dl_${System.currentTimeMillis()}.tmp")
        if (tempDownload.exists()) tempDownload.delete()

        try {
            Timber.tag(TAG).d("Downloading fast chunked stream for %s", songId)
            downloadFastWithRanges(
                streamUrl = playbackData.streamUrl,
                headers = playbackData.streamHeaders,
                contentLength = playbackData.format.contentLength,
                destinationFile = tempDownload,
            )

            if (tempDownload.length() > 50_000L) {
                if (cachedSource.exists()) cachedSource.delete()
                tempDownload.renameTo(cachedSource)
            } else {
                throw IOException("Downloaded stream file is incomplete or too small")
            }
        } finally {
            if (tempDownload.exists()) {
                tempDownload.delete()
            }
        }

        Timber.tag(TAG).d("Source audio ready: %s (%d bytes)", cachedSource.absolutePath, cachedSource.length())
        cachedSource
    }

    suspend fun trimAudio(
        context: Context,
        songId: String,
        title: String,
        artist: String,
        startMs: Long,
        endMs: Long,
        downloadCache: Cache? = null,
        playerCache: Cache? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(endMs > startMs) { "End time must be greater than start time" }

            val sourceFile = getOrDownloadSourceAudio(context, songId, downloadCache, playerCache)

            val clipsDir = File(context.cacheDir, "audio_clips").apply { mkdirs() }

            // Clean up old clips older than 1 hour
            clipsDir.listFiles()?.filter {
                System.currentTimeMillis() - it.lastModified() > 3600_000L
            }?.forEach { it.delete() }

            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(40).trim()
            val sanitizedArtist = artist.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(30).trim()

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(sourceFile.absolutePath)

                var audioTrackIndex = -1
                var audioFormat: MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        audioFormat = format
                        break
                    }
                }

                if (audioTrackIndex < 0 || audioFormat == null) {
                    throw IllegalStateException("No audio track found in media file")
                }

                val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
                val isOpusOrWebm = mime.contains("opus", ignoreCase = true) || mime.contains("webm", ignoreCase = true)

                val (muxerOutputFormat, extension) = when {
                    isOpusOrWebm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                        Pair(MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, "ogg")
                    }
                    isOpusOrWebm -> {
                        Pair(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm")
                    }
                    else -> {
                        Pair(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a")
                    }
                }

                val clipFileName = if (sanitizedArtist.isNotEmpty()) {
                    "$sanitizedTitle - $sanitizedArtist (Clip).$extension"
                } else {
                    "$sanitizedTitle (Clip).$extension"
                }

                val outputFile = File(clipsDir, clipFileName)
                if (outputFile.exists()) outputFile.delete()

                val muxer = MediaMuxer(outputFile.absolutePath, muxerOutputFormat)
                val muxerTrackIndex = muxer.addTrack(audioFormat)
                muxer.start()

                extractor.selectTrack(audioTrackIndex)
                val startUs = startMs * 1000L
                val endUs = endMs * 1000L

                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    512 * 1024
                }.coerceAtLeast(256 * 1024)

                val buffer = ByteBuffer.allocateDirect(maxBufferSize)
                val bufferInfo = MediaCodec.BufferInfo()
                var firstSampleTimeUs = -1L

                try {
                    while (true) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) {
                            break
                        }

                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs > endUs) {
                            break
                        }

                        if (sampleTimeUs >= startUs) {
                            if (firstSampleTimeUs < 0) {
                                firstSampleTimeUs = sampleTimeUs
                            }
                            bufferInfo.presentationTimeUs = (sampleTimeUs - firstSampleTimeUs).coerceAtLeast(0L)
                            bufferInfo.flags = extractor.sampleFlags
                            muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                        }

                        extractor.advance()
                    }
                } finally {
                    try {
                        muxer.stop()
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error stopping muxer")
                    }
                    muxer.release()
                }

                outputFile
            } finally {
                extractor.release()
            }
        }
    }
}
