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

object AudioTrimmer {
    private const val TAG = "AudioTrimmer"

    private val httpClient: OkHttpClient by lazy {
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

    suspend fun getOrDownloadSourceAudio(context: Context, songId: String): File = withContext(Dispatchers.IO) {
        val tempSourceDir = File(context.cacheDir, "source_audio").apply { mkdirs() }
        val cachedSource = File(tempSourceDir, "source_$songId.tmp")

        if (cachedSource.exists() && cachedSource.length() > 50_000L) {
            Timber.tag(TAG).d("Using existing cached source for $songId (${cachedSource.length()} bytes)")
            return@withContext cachedSource
        }

        val connectivityManager = context.getSystemService<ConnectivityManager>()
            ?: throw IllegalStateException("ConnectivityManager unavailable")

        Timber.tag(TAG).d("Fetching player response for $songId")
        val playbackData = YTPlayerUtils.playerResponseForPlayback(
            videoId = songId,
            audioQuality = AudioQuality.HIGH,
            connectivityManager = connectivityManager,
        ).getOrThrow()

        val requestBuilder = Request.Builder()
            .url(playbackData.streamUrl)

        playbackData.streamHeaders.forEach { (name, value) ->
            requestBuilder.header(name, value)
        }

        val tempDownload = File(tempSourceDir, "source_${songId}_dl_${System.currentTimeMillis()}.tmp")
        if (tempDownload.exists()) tempDownload.delete()

        try {
            Timber.tag(TAG).d("Downloading stream to temporary file: %s", tempDownload.absolutePath)
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to download audio stream: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response body")
                body.byteStream().use { input ->
                    tempDownload.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                    }
                }
            }

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
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(endMs > startMs) { "End time must be greater than start time" }

            val sourceFile = getOrDownloadSourceAudio(context, songId)

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
