/**
 * JOY MUSIC Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.joymusic.music.ui.screens.recognition

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.joymusic.music.LocalDatabase
import com.joymusic.music.R
import com.joymusic.music.db.entities.RecognitionHistory
import com.joymusic.music.ui.component.IconButton
import com.joymusic.music.ui.utils.backToMain
import com.joymusic.music.utils.SearchRoutes
import com.joymusic.shazamkit.models.RecognitionResult
import com.joymusic.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.Dispatchers
import com.joymusic.music.recognition.MusicRecognitionService
import com.joymusic.music.recognition.SingRecognitionHelper
import kotlinx.coroutines.launch
import java.time.LocalDateTime

enum class RecognitionMode {
    SONG, // Shazam acoustic fingerprinting (Listen to music)
    SING, // In-app lyrics & singing AI engine (Sing to search)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionScreen(
    navController: NavController,
    autoStart: Boolean = false,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    var recognitionMode by remember { mutableStateOf(RecognitionMode.SONG) }

    // Only reset in Ready state
    LaunchedEffect(Unit) {
        if (MusicRecognitionService.recognitionStatus.value is RecognitionStatus.Ready) {
            MusicRecognitionService.reset()
        }
        if (SingRecognitionHelper.status.value is RecognitionStatus.Ready) {
            SingRecognitionHelper.reset()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            MusicRecognitionService.reset()
            SingRecognitionHelper.reset()
        }
    }

    // Observe status from both services
    val songRecognitionStatus by MusicRecognitionService.recognitionStatus.collectAsStateWithLifecycle()
    val singRecognitionStatus by SingRecognitionHelper.status.collectAsStateWithLifecycle()
    val heardLyrics by SingRecognitionHelper.heardLyrics.collectAsStateWithLifecycle()

    val currentStatus = when (recognitionMode) {
        RecognitionMode.SONG -> songRecognitionStatus
        RecognitionMode.SING -> singRecognitionStatus
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
        )
    }

    fun resetToReady() {
        MusicRecognitionService.reset()
        SingRecognitionHelper.reset()
    }

    val speechInputLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val query = matches?.firstOrNull { it.isNotBlank() }
                if (!query.isNullOrBlank()) {
                    coroutineScope.launch {
                        SingRecognitionHelper.searchAndMatchSong(query)
                    }
                } else {
                    resetToReady()
                }
            } else {
                resetToReady()
            }
        }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasPermission = isGranted
            if (isGranted) {
                coroutineScope.launch {
                    when (recognitionMode) {
                        RecognitionMode.SONG -> MusicRecognitionService.recognize(context)
                        RecognitionMode.SING -> SingRecognitionHelper.startListening(context, coroutineScope)
                    }
                }
            }
        }

    fun startRecognition(mode: RecognitionMode = recognitionMode) {
        recognitionMode = mode
        if (hasPermission) {
            coroutineScope.launch {
                when (mode) {
                    RecognitionMode.SONG -> {
                        SingRecognitionHelper.reset()
                        MusicRecognitionService.recognize(context)
                    }
                    RecognitionMode.SING -> {
                        MusicRecognitionService.reset()
                        SingRecognitionHelper.startListening(context, coroutineScope)
                    }
                }
            }
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        if (autoStart && MusicRecognitionService.recognitionStatus.value is RecognitionStatus.Ready) {
            startRecognition(RecognitionMode.SONG)
        }
    }

    fun saveToHistory(result: RecognitionResult) {
        if (MusicRecognitionService.resultSavedExternally) return
        coroutineScope.launch(Dispatchers.IO) {
            database.query {
                insert(
                    RecognitionHistory(
                        trackId = result.trackId,
                        title = result.title,
                        artist = result.artist,
                        album = result.album,
                        coverArtUrl = result.coverArtUrl,
                        coverArtHqUrl = result.coverArtHqUrl,
                        genre = result.genre,
                        releaseDate = result.releaseDate,
                        label = result.label,
                        shazamUrl = result.shazamUrl,
                        appleMusicUrl = result.appleMusicUrl,
                        spotifyUrl = result.spotifyUrl,
                        isrc = result.isrc,
                        youtubeVideoId = result.youtubeVideoId,
                        recognizedAt = LocalDateTime.now(),
                    ),
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recognize_music)) },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.navigateUp() },
                        onLongClick = { navController.backToMain() },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("recognition_history") }) {
                        Icon(
                            painter = painterResource(R.drawable.history),
                            contentDescription = stringResource(R.string.recognition_history),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(
                targetState = currentStatus,
                transitionSpec = {
                    (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                },
                label = "recognition_content",
            ) { status ->
                when (status) {
                    is RecognitionStatus.Ready -> {
                        ReadyState(
                            currentMode = recognitionMode,
                            onModeChanged = { newMode ->
                                recognitionMode = newMode
                                resetToReady()
                            },
                            onStartRecognition = { startRecognition(recognitionMode) },
                        )
                    }

                    is RecognitionStatus.Listening -> {
                        ListeningState(
                            mode = recognitionMode,
                            heardLyrics = heardLyrics,
                            onCancel = ::resetToReady,
                        )
                    }

                    is RecognitionStatus.Processing -> {
                        ProcessingState(mode = recognitionMode)
                    }

                    is RecognitionStatus.Success -> {
                        SuccessState(
                            result = status.result,
                            onPlayOnApp = { result ->
                                val searchQuery = "${result.title} ${result.artist}"
                                navController.navigate(SearchRoutes.resultRoute(searchQuery))
                            },
                            onTryAgain = {
                                startRecognition(recognitionMode)
                            },
                            onClose = ::resetToReady,
                            onSaveToHistory = ::saveToHistory,
                        )
                    }

                    is RecognitionStatus.NoMatch -> {
                        NoMatchState(
                            message = status.message,
                            currentMode = recognitionMode,
                            onSwitchToSingMode = {
                                startRecognition(RecognitionMode.SING)
                            },
                            onSwitchToSongMode = {
                                startRecognition(RecognitionMode.SONG)
                            },
                            onTryAgain = {
                                startRecognition(recognitionMode)
                            },
                        )
                    }

                    is RecognitionStatus.Error -> {
                        ErrorState(
                            message = status.message,
                            isSingMode = recognitionMode == RecognitionMode.SING,
                            onLaunchVoiceDialog = {
                                try {
                                    val voiceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Sing or speak lyrics...")
                                    }
                                    speechInputLauncher.launch(voiceIntent)
                                } catch (_: Exception) {}
                            },
                            onTryAgain = {
                                startRecognition(recognitionMode)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyState(
    currentMode: RecognitionMode,
    onModeChanged: (RecognitionMode) -> Unit,
    onStartRecognition: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Mode Selector Pills
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = if (currentMode == RecognitionMode.SONG) MaterialTheme.colorScheme.primary else Color.Transparent,
                modifier = Modifier.clickable { onModeChanged(RecognitionMode.SONG) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mic),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (currentMode == RecognitionMode.SONG) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.song_mode),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (currentMode == RecognitionMode.SONG) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = if (currentMode == RecognitionMode.SING) MaterialTheme.colorScheme.primary else Color.Transparent,
                modifier = Modifier.clickable { onModeChanged(RecognitionMode.SING) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.music_note),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (currentMode == RecognitionMode.SING) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.sing_mode),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (currentMode == RecognitionMode.SING) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Main Tap Circle
        Box(
            modifier =
                Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                    Color.Transparent,
                                ),
                        ),
                    ).clickable { onStartRecognition() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (currentMode == RecognitionMode.SING) R.drawable.music_note else R.drawable.mic
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    if (currentMode == RecognitionMode.SING) R.string.sing_to_search else R.string.listen_to_music
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (currentMode == RecognitionMode.SING) R.string.sing_to_search_desc else R.string.listen_to_music_desc
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Secondary Option Card
        if (currentMode == RecognitionMode.SONG) {
            Surface(
                onClick = { onModeChanged(RecognitionMode.SING) },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(0.92f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.music_note),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sing_to_search),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.sing_to_search_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Icon(
                        painter = painterResource(R.drawable.arrow_forward),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Surface(
                onClick = {
                    if (!HumSearchHelper.launch(context)) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.hum_not_supported),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(0.92f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.hum_to_search),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.hum_to_search_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Icon(
                        painter = painterResource(R.drawable.arrow_forward),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListeningState(
    mode: RecognitionMode,
    heardLyrics: String?,
    onCancel: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "scale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            modifier = Modifier.size(260.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Outer pulsing ring
            Box(
                modifier =
                    Modifier
                        .size(200.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            )

            // Inner pulsing ring
            Box(
                modifier =
                    Modifier
                        .size(180.dp)
                        .scale(scale * 0.9f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            )

            // Main button
            Box(
                modifier =
                    Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { onCancel() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (mode == RecognitionMode.SING) R.drawable.music_note else R.drawable.mic
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    if (mode == RecognitionMode.SING) R.string.singing_listening else R.string.listening
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            if (mode == RecognitionMode.SING) {
                if (!heardLyrics.isNullOrBlank()) {
                    Text(
                        text = "“$heardLyrics”",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.singing_listening_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun ProcessingState(mode: RecognitionMode = RecognitionMode.SONG) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "rotate")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                ),
            label = "rotation",
        )

        Box(
            modifier = Modifier.size(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .border(
                            width = 4.dp,
                            brush =
                                Brush.sweepGradient(
                                    colors =
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                            MaterialTheme.colorScheme.primary,
                                        ),
                                ),
                            shape = CircleShape,
                        ),
            )

            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            text = stringResource(
                if (mode == RecognitionMode.SING) R.string.singing_processing else R.string.processing
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SuccessState(
    result: RecognitionResult,
    onPlayOnApp: (RecognitionResult) -> Unit,
    onTryAgain: () -> Unit,
    onClose: () -> Unit,
    onSaveToHistory: (RecognitionResult) -> Unit,
) {
    // Save to history when success is shown
    LaunchedEffect(result) {
        onSaveToHistory(result)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        // Album art
        Card(
            modifier =
                Modifier
                    .size(180.dp)
                    .aspectRatio(1f),
            shape = RoundedCornerShape(com.joymusic.music.constants.ThumbnailCornerRadius),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            AsyncImage(
                model = result.coverArtHqUrl ?: result.coverArtUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Track info
        Text(
            text = result.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = result.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        result.album?.let { album ->
            Text(
                text = album,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons - stacked vertically
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = { onPlayOnApp(result) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.play),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.play_on_app))
            }

            FilledTonalButton(
                onClick = onTryAgain,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.mic),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.re_listen))
            }

            // Close button - Material 3 Expressive outlined style
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.close))
            }
        }
    }
}

@Composable
private fun NoMatchState(
    message: String,
    currentMode: RecognitionMode,
    onSwitchToSingMode: () -> Unit,
    onSwitchToSongMode: () -> Unit,
    onTryAgain: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.close),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        Text(
            text = stringResource(R.string.no_match_found),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        // Suggest opposite mode or Singing
        if (currentMode == RecognitionMode.SONG) {
            Surface(
                onClick = onSwitchToSingMode,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(horizontal = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.music_note),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.sing_to_search_desc),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        } else {
            Surface(
                onClick = onSwitchToSongMode,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(horizontal = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mic),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.listen_to_music_desc),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        Button(onClick = onTryAgain) {
            Icon(
                painter = painterResource(R.drawable.refresh),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.try_again))
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    isSingMode: Boolean = false,
    onLaunchVoiceDialog: () -> Unit = {},
    onTryAgain: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.error),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        Text(
            text = stringResource(R.string.recognition_error),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        if (isSingMode) {
            FilledTonalButton(onClick = onLaunchVoiceDialog) {
                Icon(
                    painter = painterResource(R.drawable.mic),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Use Voice Input")
            }
        }

        Button(onClick = onTryAgain) {
            Icon(
                painter = painterResource(R.drawable.refresh),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.try_again))
        }
    }
}

object HumSearchHelper {
    fun launch(context: android.content.Context): Boolean {
        val intents = listOf(
            Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent().apply {
                setClassName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.googlequicksearchbox.SoundSearchActivity",
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra("android.speech.extra.GET_AUDIO_SOUND_SEARCH", true)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Hum or sing to search...")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent(Intent.ACTION_VOICE_COMMAND).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }
}
