/**
 * JOY MUSIC Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.joymusic.music.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.joymusic.music.R
import timber.log.Timber
import java.io.File

object DownloadFolderHelper {
    private const val TAG = "DownloadFolderHelper"

    /**
     * Returns the default download folder in internal app storage.
     */
    fun getDefaultDownloadFolder(context: Context): File {
        return context.filesDir.resolve("download").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Returns the external app storage download folder.
     */
    fun getExternalAppDownloadFolder(context: Context): File {
        val extDir = context.getExternalFilesDir(null)
        return if (extDir != null) {
            extDir.resolve("download").apply {
                if (!exists()) mkdirs()
            }
        } else {
            getDefaultDownloadFolder(context)
        }
    }

    /**
     * Returns the public Music/JOY MUSIC directory.
     */
    fun getPublicMusicDownloadFolder(context: Context): File {
        return try {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val joyMusicDir = File(musicDir, "JOY MUSIC")
            if (joyMusicDir.exists() || joyMusicDir.mkdirs()) {
                joyMusicDir
            } else {
                getExternalAppDownloadFolder(context)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Cannot access public Music directory")
            getExternalAppDownloadFolder(context)
        }
    }

    /**
     * Validates and returns the active download folder.
     * Falls back to default internal storage if the custom folder is not writable or does not exist.
     */
    fun getDownloadFolder(context: Context, customPath: String?): File {
        if (!customPath.isNullOrBlank()) {
            try {
                val file = File(customPath)
                if (file.exists() && file.canWrite()) {
                    return file
                }
                if (!file.exists() && file.mkdirs() && file.canWrite()) {
                    return file
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to use custom download path: %s", customPath)
            }
        }
        return getDefaultDownloadFolder(context)
    }

    /**
     * Returns a user-friendly display name for the current download folder setting.
     */
    fun getFolderDisplayName(context: Context, customPath: String?): String {
        if (customPath.isNullOrBlank()) {
            return context.getString(R.string.download_folder_default)
        }

        val defaultPath = context.filesDir.resolve("download").absolutePath
        if (customPath == defaultPath) {
            return context.getString(R.string.download_folder_default)
        }

        val extAppPath = context.getExternalFilesDir(null)?.resolve("download")?.absolutePath
        if (extAppPath != null && customPath == extAppPath) {
            return context.getString(R.string.download_folder_external)
        }

        val musicPath = try {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "JOY MUSIC").absolutePath
        } catch (_: Exception) {
            null
        }
        if (musicPath != null && customPath == musicPath) {
            return context.getString(R.string.download_folder_music)
        }

        return customPath
    }

    /**
     * Resolves an Android SAF folder URI into an absolute file path if possible.
     */
    fun uriToPath(uri: Uri): String? {
        return try {
            val documentId = DocumentsContract.getTreeDocumentId(uri) ?: return null
            val parts = documentId.split(":")
            val type = parts.getOrNull(0) ?: return null
            val relativePath = if (parts.size > 1) parts[1] else ""

            if ("primary".equals(type, ignoreCase = true)) {
                val base = Environment.getExternalStorageDirectory().absolutePath
                if (relativePath.isNotEmpty()) "$base/$relativePath" else base
            } else {
                val possiblePath = "/storage/$type/$relativePath".trimEnd('/')
                val file = File(possiblePath)
                if (file.exists() || file.mkdirs()) {
                    possiblePath
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error converting tree URI to path: %s", uri)
            null
        }
    }
}
