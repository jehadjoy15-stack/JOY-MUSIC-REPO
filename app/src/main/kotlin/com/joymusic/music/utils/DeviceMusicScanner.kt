/**
 * JOY MUSIC Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.joymusic.music.utils

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.joymusic.music.db.entities.AlbumEntity
import com.joymusic.music.db.entities.ArtistEntity
import com.joymusic.music.db.entities.Song
import com.joymusic.music.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

object DeviceMusicScanner {
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_MEDIA_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    suspend fun scanDeviceAudio(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext emptyList()

        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.SIZE,
        )

        val selection = " != 0 AND  >= 5000"
        val sortOrder = " COLLATE NOCASE ASC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                val artworkUriBase = Uri.parse("content://media/external/audio/albumart")

                while (cursor.moveToNext()) {
                    val mediaStoreId = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown Title"
                    val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
                    val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Album"
                    val durationMs = cursor.getLong(durationCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
                    val artworkUri = ContentUris.withAppendedId(artworkUriBase, albumId)

                    val songId = contentUri.toString()
                    val artistId = "local_artist_"
                    val albumEntityId = "local_album_"

                    val songEntity = SongEntity(
                        id = songId,
                        title = title,
                        duration = (durationMs / 1000).toInt().coerceAtLeast(1),
                        thumbnailUrl = artworkUri.toString(),
                        albumId = albumEntityId,
                        albumName = album,
                        dateDownload = LocalDateTime.now(),
                        isDownloaded = true,
                        inLibrary = LocalDateTime.now(),
                    )

                    val artistEntity = ArtistEntity(
                        id = artistId,
                        name = artist,
                        thumbnailUrl = null,
                        isLocal = true,
                    )

                    val albumEntity = AlbumEntity(
                        id = albumEntityId,
                        title = album,
                        thumbnailUrl = artworkUri.toString(),
                        year = null,
                        songCount = 1,
                        duration = (durationMs / 1000).toInt().coerceAtLeast(1),
                        isLocal = true,
                    )

                    songs.add(
                        Song(
                            song = songEntity,
                            artists = listOf(artistEntity),
                            album = albumEntity,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to scan device audio files")
        }

        songs
    }
}
