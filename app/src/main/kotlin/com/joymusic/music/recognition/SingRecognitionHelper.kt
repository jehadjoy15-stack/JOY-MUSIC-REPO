/**
 * JOY MUSIC Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.joymusic.music.recognition

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.joymusic.innertube.YouTube
import com.joymusic.innertube.models.SongItem
import com.joymusic.shazamkit.models.RecognitionResult
import com.joymusic.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Headless In-App Singing & Lyrics Recognition Engine.
 * Listens to singing/lyrics using on-device speech recognition in the background,
 * queries YouTube Music for the matching track, and delivers the result directly
 * inside JOY MUSIC without displaying any third-party or Google UI.
 */
object SingRecognitionHelper {
    private const val TAG = "SingRecognitionHelper"

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Ready)
    val status: StateFlow<RecognitionStatus> = _status.asStateFlow()

    private val _heardLyrics = MutableStateFlow<String?>(null)
    val heardLyrics: StateFlow<String?> = _heardLyrics.asStateFlow()

    private fun destroyCurrentRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    fun startListening(context: Context, coroutineScope: CoroutineScope) {
        mainHandler.post {
            try {
                destroyCurrentRecognizer()

                _heardLyrics.value = null
                _status.value = RecognitionStatus.Listening

                val recognizer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                ) {
                    try {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } catch (_: Exception) {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                speechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Timber.tag(TAG).d("Ready for singing input")
                    }

                    override fun onBeginningOfSpeech() {
                        Timber.tag(TAG).d("Singing speech started")
                        _status.value = RecognitionStatus.Listening
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Timber.tag(TAG).d("Singing speech ended, analyzing lyrics")
                        _status.value = RecognitionStatus.Processing
                    }

                    override fun onError(error: Int) {
                        Timber.tag(TAG).w("SpeechRecognizer error: %d", error)
                        val message = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                "No lyrics detected. Please sing 2–3 lines clearly into the mic."
                            }
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network connection required to recognize singing"
                            SpeechRecognizer.ERROR_CLIENT -> "Recognition service busy. Please try again."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Microphone is currently in use. Please try again."
                            else -> "Could not recognize audio (Error $error)"
                        }
                        _status.value = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            RecognitionStatus.NoMatch(message)
                        } else {
                            RecognitionStatus.Error(message)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val query = matches?.firstOrNull { it.isNotBlank() }
                        Timber.tag(TAG).d("Sing recognition heard: %s (all matches: %s)", query, matches)

                        if (query.isNullOrBlank()) {
                            _status.value = RecognitionStatus.NoMatch("No lyrics recognized. Try singing again.")
                            return
                        }

                        _heardLyrics.value = query
                        _status.value = RecognitionStatus.Processing

                        coroutineScope.launch(Dispatchers.IO) {
                            searchAndMatchSongs(matches?.filter { it.isNotBlank() } ?: listOf(query))
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull { it.isNotBlank() }
                        if (!partial.isNullOrBlank()) {
                            _heardLyrics.value = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra("android.speech.extra.DICTATION_MODE", true)
                    val currentLocale = java.util.Locale.getDefault()
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocale.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLocale.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                }

                recognizer.startListening(intent)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to start sing recognition")
                _status.value = RecognitionStatus.Error(e.message ?: "Failed to start singing recognition")
            }
        }
    }

    suspend fun searchAndMatchSong(query: String) = searchAndMatchSongs(listOf(query))

    suspend fun searchAndMatchSongs(candidates: List<String>) = withContext(Dispatchers.IO) {
        try {
            val primaryQuery = candidates.firstOrNull() ?: return@withContext
            _heardLyrics.value = primaryQuery
            _status.value = RecognitionStatus.Processing

            var matchedSongItem: SongItem? = null

            for (query in candidates) {
                Timber.tag(TAG).d("Searching YouTube Music for recognized lyrics candidate: '%s'", query)
                val songSearch = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                val song = songSearch?.items?.filterIsInstance<SongItem>()?.firstOrNull()
                if (song != null) {
                    matchedSongItem = song
                    break
                }

                val videoSearch = YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()
                val videoSong = videoSearch?.items?.filterIsInstance<SongItem>()?.firstOrNull()
                if (videoSong != null) {
                    matchedSongItem = videoSong
                    break
                }
            }

            if (matchedSongItem != null) {
                Timber.tag(TAG).i("Matched song from singing: '%s' by %s", matchedSongItem.title, matchedSongItem.artists.joinToString { it.name })
                val recognitionResult = RecognitionResult(
                    trackId = matchedSongItem.id,
                    title = matchedSongItem.title,
                    artist = matchedSongItem.artists.joinToString(", ") { it.name },
                    album = matchedSongItem.album?.name,
                    coverArtUrl = matchedSongItem.thumbnail,
                    coverArtHqUrl = matchedSongItem.thumbnail,
                    genre = null,
                    releaseDate = null,
                    label = null,
                    lyrics = null,
                    shazamUrl = null,
                    appleMusicUrl = null,
                    spotifyUrl = null,
                    isrc = null,
                    youtubeVideoId = matchedSongItem.id,
                )
                _status.value = RecognitionStatus.Success(recognitionResult)
            } else {
                Timber.tag(TAG).w("No songs found for lyrics queries: %s", candidates)
                _status.value = RecognitionStatus.NoMatch("No song found for \"$primaryQuery\". Try singing a different part of the lyrics.")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error searching for singing match")
            _status.value = RecognitionStatus.Error("Failed to search song: ${e.message}")
        }
    }

    fun cancel() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyCurrentRecognizer()
            _heardLyrics.value = null
            _status.value = RecognitionStatus.Ready
        } else {
            mainHandler.post {
                destroyCurrentRecognizer()
                _heardLyrics.value = null
                _status.value = RecognitionStatus.Ready
            }
        }
    }

    fun reset() {
        cancel()
    }
}
