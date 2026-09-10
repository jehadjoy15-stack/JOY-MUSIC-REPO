/**
 * JOY MUSIC Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.joymusic.music.ui.component

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.joymusic.music.R
import com.joymusic.music.utils.AudioTrimmer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

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
    val coroutineScope = rememberCoroutineScope()

    val totalDuration = remember(songId, durationSeconds) {
        if (durationSeconds > 0) durationSeconds.toFloat() else 180f
    }

    var sliderRange by remember(songId, totalDuration) {
        val initialEnd = minOf(30f, totalDuration)
        mutableStateOf(0f..initialEnd)
    }

    var isSharing by remember { mutableStateOf(false) }
    var isPreparingPreview by remember { mutableStateOf(false) }
    var sourceFile by remember(songId) { mutableStateOf<File?>(null) }
    var isPreviewPlaying by remember { mutableStateOf(false) }

    // Mini ExoPlayer for local preview
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = false
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPreviewPlaying = isPlaying
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Pre-cache source audio in background without blocking UI
    LaunchedEffect(songId) {
        try {
            val file = AudioTrimmer.getOrDownloadSourceAudio(context, songId)
            sourceFile = file
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exoPlayer.prepare()
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Background source preparation deferred")
        }
    }

    // Monitor preview playback bounds
    LaunchedEffect(isPreviewPlaying, sliderRange) {
        if (isPreviewPlaying) {
            val endMs = (sliderRange.endInclusive * 1000).toLong()
            val startMs = (sliderRange.start * 1000).toLong()
            while (isActive && isPreviewPlaying) {
                if (exoPlayer.currentPosition >= endMs) {
                    exoPlayer.pause()
                    exoPlayer.seekTo(startMs)
                    break
                }
                delay(100L)
            }
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
                .padding(20.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
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

                Spacer(modifier = Modifier.height(16.dp))

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

                Spacer(modifier = Modifier.height(20.dp))

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

                Spacer(modifier = Modifier.height(12.dp))

                // Range Slider
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

                Spacer(modifier = Modifier.height(20.dp))

                // Preview Player Button
                FilledTonalButton(
                    onClick = {
                        if (isPreviewPlaying) {
                            exoPlayer.pause()
                        } else {
                            coroutineScope.launch {
                                val startMs = (sliderRange.start * 1000).toLong()
                                if (sourceFile == null) {
                                    isPreparingPreview = true
                                    try {
                                        val file = AudioTrimmer.getOrDownloadSourceAudio(context, songId)
                                        sourceFile = file
                                        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                                        exoPlayer.prepare()
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.clip_failed, e.localizedMessage ?: "Network error"),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        isPreparingPreview = false
                                        return@launch
                                    }
                                    isPreparingPreview = false
                                } else if (exoPlayer.mediaItemCount == 0) {
                                    exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(sourceFile!!)))
                                    exoPlayer.prepare()
                                }
                                exoPlayer.seekTo(startMs)
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
                            text = if (isPreviewPlaying) "Pause Preview" else stringResource(R.string.clip_preview),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isSharing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.clip_preparing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Action Buttons
                Row(
                    horizontalArrangement = Arrangement.End,
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

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            exoPlayer.pause()
                            isSharing = true

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
                                )

                                isSharing = false

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
                                        Intent.createChooser(shareIntent, context.getString(R.string.share_audio_clip))
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.clip_share_button),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(seconds: Float): String {
    val totalSec = seconds.roundToInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%d:%02d", min, sec)
}
