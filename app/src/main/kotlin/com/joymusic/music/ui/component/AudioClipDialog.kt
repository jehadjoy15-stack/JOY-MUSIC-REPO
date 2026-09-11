/**
 * JOY MUSIC Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.joymusic.music.ui.component

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.joymusic.music.LocalDatabase
import com.joymusic.music.LocalPlayerConnection
import com.joymusic.music.R
import com.joymusic.music.db.entities.LyricsEntity
import com.joymusic.music.di.LyricsHelperEntryPoint
import com.joymusic.music.lyrics.LyricsEntry
import com.joymusic.music.lyrics.LyricsUtils
import com.joymusic.music.models.MediaMetadata
import com.joymusic.music.utils.AudioTrimmer
import com.joymusic.music.utils.VideoClipGenerator
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun AudioClipDialog(
    isVisible: Boolean,
    songId: String,
    title: String,
    artist: String,
    thumbnailUrl: String?,
    durationSeconds: Int,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val downloadCache = playerConnection?.service?.downloadCache
    val playerCache = playerConnection?.service?.playerCache

    val totalDuration = remember(songId, durationSeconds) {
        if (durationSeconds > 0) durationSeconds.toFloat() else 180f
    }

    var sliderRange by remember(songId, totalDuration) {
        val initialEnd = minOf(30f, totalDuration)
        mutableStateOf(0f..initialEnd)
    }

    var isSharingAudio by remember { mutableStateOf(false) }
    var isSharingVideo by remember { mutableStateOf(false) }
    var videoProgress by remember { mutableStateOf(0f) }
    val isSharing = isSharingAudio || isSharingVideo

    var isPreparingPreview by remember { mutableStateOf(false) }
    var sourceFile by remember(songId) { mutableStateOf<File?>(null) }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewCurrentPositionMs by remember { mutableLongStateOf(0L) }
    var lyricsEntries by remember(songId) { mutableStateOf<List<LyricsEntry>>(emptyList()) }

    // Video Theme Customization
    val paletteColors = remember { mutableStateListOf<Color>() }
    var selectedBgStyle by remember { mutableStateOf(LyricsBackgroundStyle.GRADIENT) }
    var selectedBgColor by remember { mutableStateOf<Color?>(null) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl != null) {
            withContext(Dispatchers.IO) {
                try {
                    val loader = ImageLoader(context)
                    val req = ImageRequest.Builder(context).data(thumbnailUrl).allowHardware(false).build()
                    val bmp = loader.execute(req).image?.toBitmap()
                    if (bmp != null) {
                        val p = Palette.from(bmp).generate()
                        val swatches = p.swatches.sortedByDescending { it.population }
                        val colors = swatches.map { Color(it.rgb) }.filter {
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(it.toArgb(), hsv)
                            hsv[1] > 0.15f
                        }.take(6)
                        withContext(Dispatchers.Main) {
                            paletteColors.clear()
                            paletteColors.addAll(colors)
                            if (selectedBgColor == null && colors.isNotEmpty()) {
                                selectedBgColor = colors.first()
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(songId) {
        withContext(Dispatchers.IO) {
            try {
                val dbLyrics = database.lyrics(songId).firstOrNull()?.lyrics
                if (!dbLyrics.isNullOrBlank()) {
                    lyricsEntries = LyricsUtils.parseLyrics(dbLyrics)
                } else {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        LyricsHelperEntryPoint::class.java,
                    )
                    val lyricsHelper = entryPoint.lyricsHelper()
                    val metadata = MediaMetadata(
                        id = songId,
                        title = title,
                        artists = listOf(MediaMetadata.Artist(id = null, name = artist)),
                        duration = durationSeconds,
                    )
                    val fetched = lyricsHelper.getLyrics(metadata)
                    if (fetched.lyrics.isNotBlank() && fetched.lyrics != LyricsEntity.LYRICS_NOT_FOUND) {
                        database.query {
                            upsert(LyricsEntity(songId, fetched.lyrics, fetched.provider))
                        }
                        lyricsEntries = LyricsUtils.parseLyrics(fetched.lyrics)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Instant streaming & audio-focused ExoPlayer for local preview
    val exoPlayer = remember {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
        ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                playWhenReady = false
            }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPreviewPlaying = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                timber.log.Timber.e(error, "AudioClip preview player error")
                isPreviewPlaying = false
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Pre-cache source audio for instant preview
    LaunchedEffect(songId) {
        try {
            val file = AudioTrimmer.getOrDownloadSourceAudio(
                context = context,
                songId = songId,
                downloadCache = downloadCache,
                playerCache = playerCache,
            )
            sourceFile = file
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exoPlayer.prepare()
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Pre-download deferred for $songId")
        }
    }

    // Monitor preview playback bounds and sync position in real-time
    LaunchedEffect(isPreviewPlaying, sliderRange) {
        val startMs = (sliderRange.start * 1000).toLong()
        val endMs = (sliderRange.endInclusive * 1000).toLong()
        if (isPreviewPlaying) {
            while (isActive && isPreviewPlaying) {
                val current = exoPlayer.currentPosition
                previewCurrentPositionMs = current
                if (current >= endMs || current < startMs - 1000L) {
                    exoPlayer.pause()
                    exoPlayer.seekTo(startMs)
                    previewCurrentPositionMs = startMs
                    break
                }
                delay(33L)
            }
        } else {
            previewCurrentPositionMs = startMs
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isSharing) {
                exoPlayer.stop()
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.content_cut),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.share_audio_clip),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Mode Tabs (Time Trimmer vs Video Preview)
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("Clip Time", fontWeight = FontWeight.SemiBold) },
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text(stringResource(R.string.video_preview_title), fontWeight = FontWeight.SemiBold) },
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTabIndex == 0) {
                    // Song Information Card
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(16.dp),
                            )
                            .padding(12.dp),
                    ) {
                        AsyncImage(
                            model = thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Time indicators and duration summary
                    val clipDurationSec = (sliderRange.endInclusive - sliderRange.start).roundToInt()
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = stringResource(R.string.clip_start_time),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = formatTime(sliderRange.start),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.clip_duration, formatTime(clipDurationSec.toFloat())),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = stringResource(R.string.clip_end_time),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = formatTime(sliderRange.endInclusive),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Range Slider with Live Playhead Stick Indicator
                    val currentPlayheadSec = (previewCurrentPositionMs / 1000f).coerceIn(0f, totalDuration)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        RangeSlider(
                            value = sliderRange,
                            onValueChange = { newRange ->
                                val start = newRange.start
                                val end = maxOf(start + 1f, newRange.endInclusive)
                                sliderRange = start..end
                                if (isPreviewPlaying) {
                                    exoPlayer.seekTo((start * 1000).toLong())
                                }
                            },
                            valueRange = 0f..totalDuration,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Live Playhead Needle / Stick Marker
                        if (totalDuration > 0f) {
                            val playheadFraction = (currentPlayheadSec / totalDuration).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(playheadFraction)
                                    .height(28.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(24.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(Color.White)
                                        .border(0.5.dp, Color.Black.copy(alpha = 0.5f), RoundedCornerShape(1.5.dp)),
                                )
                            }
                        }
                    }

                    // Fine-tuning Controls (-1s / +1s)
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Start controls
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalIconButton(
                                onClick = {
                                    val newStart = (sliderRange.start - 1f).coerceAtLeast(0f)
                                    sliderRange = newStart..sliderRange.endInclusive
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Text("-1s", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    val newStart = (sliderRange.start + 1f).coerceAtMost(sliderRange.endInclusive - 1f)
                                    sliderRange = newStart..sliderRange.endInclusive
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Text("+1s", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // End controls
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalIconButton(
                                onClick = {
                                    val newEnd = (sliderRange.endInclusive - 1f).coerceAtLeast(sliderRange.start + 1f)
                                    sliderRange = sliderRange.start..newEnd
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Text("-1s", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    val newEnd = (sliderRange.endInclusive + 1f).coerceAtMost(totalDuration)
                                    sliderRange = sliderRange.start..newEnd
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Text("+1s", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // Video Reel Live Preview & Customization
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Style Chips
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LyricsBackgroundStyle.entries.forEach { style ->
                                val label = when (style) {
                                    LyricsBackgroundStyle.GRADIENT -> stringResource(R.string.video_bg_gradient)
                                    LyricsBackgroundStyle.BLUR -> stringResource(R.string.video_bg_blur)
                                    LyricsBackgroundStyle.SOLID -> stringResource(R.string.video_bg_solid)
                                }
                                FilterChip(
                                    selected = selectedBgStyle == style,
                                    onClick = { selectedBgStyle = style },
                                    label = { Text(label, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(),
                                )
                            }
                        }

                        // Palette Color Swatches
                        if (paletteColors.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 6.dp),
                            ) {
                                (paletteColors + listOf(Color(0xFF1E222D), Color(0xFF12141C))).distinct().take(6).forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(color, RoundedCornerShape(8.dp))
                                            .clickable { selectedBgColor = color }
                                            .border(
                                                2.dp,
                                                if (selectedBgColor == color) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                RoundedCornerShape(8.dp),
                                            ),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 9:16 Video Reel Interactive Preview Card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.72f)
                                .aspectRatio(9f / 16f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                        ) {
                            VideoReelPreviewCard(
                                title = title,
                                artist = artist,
                                thumbnailUrl = thumbnailUrl,
                                bgStyle = selectedBgStyle,
                                bgColor = selectedBgColor,
                                currentTimeMs = previewCurrentPositionMs,
                                lyricsEntries = lyricsEntries,
                                isPlaying = isPreviewPlaying,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Preview Player Button
                FilledTonalButton(
                    onClick = {
                        playerConnection?.player?.let { mainPlayer ->
                            if (mainPlayer.isPlaying) {
                                mainPlayer.pause()
                            }
                        }

                        if (isPreviewPlaying) {
                            exoPlayer.pause()
                        } else {
                            val startMs = (sliderRange.start * 1000).toLong()
                            if (exoPlayer.mediaItemCount == 0 || sourceFile == null) {
                                coroutineScope.launch {
                                    isPreparingPreview = true
                                    try {
                                        val file = AudioTrimmer.getOrDownloadSourceAudio(
                                            context = context,
                                            songId = songId,
                                            downloadCache = downloadCache,
                                            playerCache = playerCache,
                                        )
                                        sourceFile = file
                                        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                                        exoPlayer.prepare()
                                        exoPlayer.seekTo(startMs)
                                        exoPlayer.play()
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.clip_failed, e.localizedMessage ?: "Audio error"),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } finally {
                                        isPreparingPreview = false
                                    }
                                }
                            } else {
                                val currentPos = exoPlayer.currentPosition
                                val endMs = (sliderRange.endInclusive * 1000).toLong()
                                if (currentPos < startMs || currentPos >= endMs - 200L) {
                                    exoPlayer.seekTo(startMs)
                                }
                                exoPlayer.play()
                            }
                        }
                    },
                    enabled = !isSharing && !isPreparingPreview,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isPreparingPreview) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.clip_downloading),
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (isPreviewPlaying) R.drawable.pause else R.drawable.play
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isPreviewPlaying) stringResource(R.string.pause_preview) else stringResource(R.string.clip_preview),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (isSharing) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isSharingVideo) {
                                    stringResource(R.string.clip_creating_video, (videoProgress * 100).roundToInt())
                                } else {
                                    stringResource(R.string.clip_preparing)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isSharingVideo) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { videoProgress },
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .clip(RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Action Buttons (Send Audio & Send Video)
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = {
                            exoPlayer.stop()
                            onDismiss()
                        },
                        enabled = !isSharing,
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Send Audio Button (.m4a)
                        FilledTonalButton(
                            onClick = {
                                exoPlayer.pause()
                                isSharingAudio = true

                                coroutineScope.launch {
                                    val startMs = (sliderRange.start * 1000).toLong()
                                    val endMs = (sliderRange.endInclusive * 1000).toLong()

                                    val result = AudioTrimmer.trimAudio(
                                        context = context,
                                        songId = songId,
                                        title = title,
                                        artist = artist,
                                        startMs = startMs,
                                        endMs = endMs,
                                        downloadCache = downloadCache,
                                        playerCache = playerCache,
                                    )

                                    isSharingAudio = false

                                    result.onSuccess { clipFile ->
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.FileProvider",
                                            clipFile,
                                        )

                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "audio/*"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            putExtra(Intent.EXTRA_TITLE, "$title - $artist (Clip)")
                                            putExtra(Intent.EXTRA_TEXT, "🎵 $title - $artist\nShared via JOY MUSIC")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }

                                        context.startActivity(
                                            Intent.createChooser(shareIntent, context.getString(R.string.clip_share_audio_button))
                                        )
                                        onDismiss()
                                    }.onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.clip_failed, error.localizedMessage ?: "Unknown error"),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                            enabled = !isSharing,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.music_note),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.clip_share_audio_button),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // Send Video Button (.mp4)
                        Button(
                            onClick = {
                                exoPlayer.pause()
                                isSharingVideo = true
                                videoProgress = 0f

                                coroutineScope.launch {
                                    val startMs = (sliderRange.start * 1000).toLong()
                                    val endMs = (sliderRange.endInclusive * 1000).toLong()

                                    val result = VideoClipGenerator.generateStoryVideo(
                                        context = context,
                                        songId = songId,
                                        title = title,
                                        artist = artist,
                                        thumbnailUrl = thumbnailUrl,
                                        startMs = startMs,
                                        endMs = endMs,
                                        lyricsEntries = lyricsEntries,
                                        backgroundStyle = selectedBgStyle,
                                        customBackgroundColor = selectedBgColor?.toArgb(),
                                        downloadCache = downloadCache,
                                        playerCache = playerCache,
                                        onProgress = { progress ->
                                            videoProgress = progress
                                        },
                                    )

                                    isSharingVideo = false

                                    result.onSuccess { videoFile ->
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.FileProvider",
                                            videoFile,
                                        )

                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "video/mp4"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            putExtra(Intent.EXTRA_TITLE, "$title - $artist (Story)")
                                            putExtra(Intent.EXTRA_TEXT, "🎬 $title - $artist\nShared via JOY MUSIC")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }

                                        context.startActivity(
                                            Intent.createChooser(shareIntent, context.getString(R.string.clip_share_video_button))
                                        )
                                        onDismiss()
                                    }.onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.clip_video_failed, error.localizedMessage ?: "Unknown error"),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                            enabled = !isSharing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.share),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.clip_share_video_button),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoReelPreviewCard(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    bgStyle: LyricsBackgroundStyle,
    bgColor: Color?,
    currentTimeMs: Long,
    lyricsEntries: List<LyricsEntry>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "visualizer")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Box(modifier = modifier) {
        // Background
        when (bgStyle) {
            LyricsBackgroundStyle.BLUR -> {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(16.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                )
            }
            LyricsBackgroundStyle.SOLID -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor ?: Color(0xFF141822)),
                )
            }
            LyricsBackgroundStyle.GRADIENT -> {
                val topColor = bgColor ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(topColor, Color(0xFF0F1117), Color(0xFF07080B)),
                            ),
                        ),
                )
            }
        }

        // Content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            // Top Left Branding Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.app_logo),
                            contentDescription = null,
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "JOY MUSIC",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
            }

            // Center: Album Art + Titles
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = title,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = artist,
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }

            // Bottom Section: Live Synced Lyrics or Dynamic Waveform
            val lyricLines = remember(lyricsEntries) { lyricsEntries.filter { it.text.isNotBlank() } }
            if (lyricLines.isNotEmpty()) {
                val activeIndex = lyricLines.indexOfLast { it.time <= currentTimeMs }.coerceAtLeast(0)
                androidx.compose.animation.AnimatedContent(
                    targetState = activeIndex,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (androidx.compose.animation.slideInVertically(
                                animationSpec = tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                initialOffsetY = { it / 2 },
                            ) + androidx.compose.animation.fadeIn(tween(350)))
                                .togetherWith(
                                    androidx.compose.animation.slideOutVertically(
                                        animationSpec = tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                        targetOffsetY = { -it / 2 },
                                    ) + androidx.compose.animation.fadeOut(tween(300))
                                )
                        } else {
                            (androidx.compose.animation.slideInVertically(
                                animationSpec = tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                initialOffsetY = { -it / 2 },
                            ) + androidx.compose.animation.fadeIn(tween(350)))
                                .togetherWith(
                                    androidx.compose.animation.slideOutVertically(
                                        animationSpec = tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                        targetOffsetY = { it / 2 },
                                    ) + androidx.compose.animation.fadeOut(tween(300))
                                )
                        }
                    },
                    label = "previewLyricsSlide",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                ) { currentIdx ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Previous Line (Soft Blurred / Dimmed)
                        if (currentIdx > 0) {
                            Text(
                                text = lyricLines[currentIdx - 1].text,
                                style = TextStyle(
                                    color = Color.White.copy(alpha = 0.32f),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Main Active Line (Deep Solid White, Apple Music Style only when active)
                        val activeText = lyricLines[currentIdx].text
                        val isIntro = currentTimeMs < (lyricLines.firstOrNull()?.time ?: 0L)
                        val isCenterActive = !isIntro
                        Text(
                            text = activeText,
                            style = TextStyle(
                                color = if (isCenterActive) Color.White else Color.White.copy(alpha = 0.38f),
                                fontSize = 12.sp,
                                fontWeight = if (isCenterActive) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                shadow = if (isCenterActive) {
                                    androidx.compose.ui.graphics.Shadow(
                                        color = Color.Black.copy(alpha = 0.6f),
                                        blurRadius = 8f,
                                    )
                                } else null,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )

                        // Upcoming Next Line (Soft Blurred / Dimmed)
                        if (currentIdx + 1 < lyricLines.size) {
                            Text(
                                text = lyricLines[currentIdx + 1].text,
                                style = TextStyle(
                                    color = Color.White.copy(alpha = 0.32f),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                // Live Waveform Visualizer Bars
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(36.dp)
                        .padding(vertical = 4.dp),
                ) {
                    val barCount = 18
                    for (b in 0 until barCount) {
                        val phase = if (isPlaying) wavePhase else 0f
                        val wave1 = sin((phase + b * 0.45f).toDouble()).toFloat()
                        val wave2 = cos((phase * 0.7f - b * 0.3f).toDouble()).toFloat()
                        val combined = ((wave1 * 0.6f + wave2 * 0.4f) * 0.5f + 0.5f)
                        val barHeight = (8f + 24f * combined).dp

                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .height(barHeight)
                                .background(
                                    color = Color.White.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
            }

            // Bottom Watermark
            Text(
                text = "Shared via JOY MUSIC",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Normal,
                ),
            )
        }
    }
}

private fun formatTime(seconds: Float): String {
    val totalSec = seconds.roundToInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%d:%02d", min, sec)
}
