/**
 * JOY MUSIC Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.joymusic.music.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import com.joymusic.music.LocalDatabase
import com.joymusic.music.LocalPlayerAwareWindowInsets
import com.joymusic.music.LocalPlayerConnection
import com.joymusic.music.R
import com.joymusic.music.constants.DownloadLocationKey
import com.joymusic.music.constants.EnableSongCacheKey
import com.joymusic.music.constants.MaxImageCacheSizeKey
import com.joymusic.music.constants.MaxSongCacheSizeKey
import com.joymusic.music.extensions.tryOrNull
import com.joymusic.music.ui.component.ActionPromptDialog
import com.joymusic.music.ui.component.IconButton
import com.joymusic.music.ui.component.Material3SettingsGroup
import com.joymusic.music.ui.component.Material3SettingsItem
import android.text.format.Formatter
import com.joymusic.music.ui.utils.backToMain
import com.joymusic.music.utils.DownloadFolderHelper
import com.joymusic.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalCoilApi::class, ExperimentalMaterial3Api::class, DelicateCoilApi::class)
@Composable
fun StorageSettings(
    navController: NavController
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val imageDiskCache = context.imageLoader.diskCache ?: return
    val playerCache = LocalPlayerConnection.current?.service?.playerCache ?: return
    val downloadCache = LocalPlayerConnection.current?.service?.downloadCache ?: return

    val coroutineScope = rememberCoroutineScope()
    val songCacheString = stringResource(R.string.song_cache).lowercase()
    val imageCacheString = stringResource(R.string.image_cache).lowercase()
    val (maxImageCacheSize, onMaxImageCacheSizeChange) = rememberPreference(
        key = MaxImageCacheSizeKey,
        defaultValue = 512
    )
    val (maxSongCacheSize, onMaxSongCacheSizeChange) = rememberPreference(
        key = MaxSongCacheSizeKey,
        defaultValue = 1024
    )
    val (enableSongCache, onEnableSongCacheChange) = rememberPreference(
        key = EnableSongCacheKey,
        defaultValue = true
    )

    val (downloadLocation, onDownloadLocationChange) = rememberPreference(
        key = DownloadLocationKey,
        defaultValue = ""
    )
    var showDownloadLocationDialog by remember { mutableStateOf(false) }

    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val resolvedPath = DownloadFolderHelper.uriToPath(uri)
            if (resolvedPath != null) {
                val testFile = File(resolvedPath)
                if (testFile.canWrite() || testFile.mkdirs()) {
                    onDownloadLocationChange(resolvedPath)
                    Toast.makeText(context, R.string.download_folder_updated, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.download_folder_not_writable, Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, R.string.download_folder_not_writable, Toast.LENGTH_LONG).show()
            }
        }
    }

    var clearDownloads by remember { mutableStateOf(false) }
    var clearCacheDialog by remember { mutableStateOf(false) }
    var clearImageCacheDialog by remember { mutableStateOf(false) }

    // State for the confirmation dialog
    var showCacheWarningDialog by remember { mutableStateOf(false) }
    var cacheType by remember { mutableStateOf("") }
    var cacheUsage by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var onConfirmAction by remember { mutableStateOf<() -> Unit>({}) }

    var imageCacheSize by remember {
        androidx.compose.runtime.mutableLongStateOf(imageDiskCache.size)
    }
    var playerCacheSize by remember {
        androidx.compose.runtime.mutableLongStateOf(tryOrNull { playerCache.cacheSpace } ?: 0)
    }
    var downloadCacheSize by remember {
        mutableLongStateOf(tryOrNull { downloadCache.cacheSpace } ?: 0)
    }
    val imageCacheProgress by animateFloatAsState(
        targetValue =
            (imageCacheSize.toFloat() / (maxImageCacheSize * 1024 * 1024L)).coerceIn(
                0f,
                1f,
            ),
        label = "imageCacheProgress",
    )
    val playerCacheProgress by animateFloatAsState(
        targetValue =
            (playerCacheSize.toFloat() / (maxSongCacheSize * 1024 * 1024L)).coerceIn(
                0f,
                1f,
            ),
        label = "playerCacheProgress",
    )

    LaunchedEffect(maxImageCacheSize) {
        SingletonImageLoader.reset()
        if (maxImageCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                imageDiskCache.clear()
            }
        }
    }
    LaunchedEffect(maxSongCacheSize) {
        if (maxSongCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                playerCache.keys.forEach { key ->
                    playerCache.removeResource(key)
                }
            }
        }
    }

    LaunchedEffect(imageDiskCache) {
        while (isActive) {
            delay(500)
            imageCacheSize = imageDiskCache.size
        }
    }
    LaunchedEffect(playerCache) {
        while (isActive) {
            delay(500)
            playerCacheSize = tryOrNull { playerCache.cacheSpace } ?: 0
        }
    }
    LaunchedEffect(downloadCache) {
        while (isActive) {
            delay(500)
            downloadCacheSize = tryOrNull { downloadCache.cacheSpace } ?: 0
        }
    }

    if (showDownloadLocationDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadLocationDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.download),
                    contentDescription = null,
                )
            },
            title = {
                Text(stringResource(R.string.download_location))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.download_location_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val defaultPath = remember { DownloadFolderHelper.getDefaultDownloadFolder(context).absolutePath }
                    val extAppPath = remember { DownloadFolderHelper.getExternalAppDownloadFolder(context).absolutePath }
                    val publicMusicPath = remember { DownloadFolderHelper.getPublicMusicDownloadFolder(context).absolutePath }

                    // Option 1: Default Internal Storage
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onDownloadLocationChange("")
                                Toast.makeText(context, R.string.download_folder_updated, Toast.LENGTH_SHORT).show()
                                showDownloadLocationDialog = false
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                    ) {
                        RadioButton(
                            selected = downloadLocation.isBlank() || downloadLocation == defaultPath,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.download_folder_default),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = defaultPath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Option 2: External App Storage
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onDownloadLocationChange(extAppPath)
                                Toast.makeText(context, R.string.download_folder_updated, Toast.LENGTH_SHORT).show()
                                showDownloadLocationDialog = false
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                    ) {
                        RadioButton(
                            selected = downloadLocation == extAppPath,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.download_folder_external),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = extAppPath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Option 3: Public Music Folder
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onDownloadLocationChange(publicMusicPath)
                                Toast.makeText(context, R.string.download_folder_updated, Toast.LENGTH_SHORT).show()
                                showDownloadLocationDialog = false
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                    ) {
                        RadioButton(
                            selected = downloadLocation == publicMusicPath,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.download_folder_music),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = publicMusicPath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Option 4: Custom Folder (SAF)
                    val isCustomSelected = downloadLocation.isNotBlank() &&
                            downloadLocation != defaultPath &&
                            downloadLocation != extAppPath &&
                            downloadLocation != publicMusicPath

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showDownloadLocationDialog = false
                                openDocumentTreeLauncher.launch(null)
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                    ) {
                        RadioButton(
                            selected = isCustomSelected,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.download_folder_custom),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (isCustomSelected) {
                                Text(
                                    text = downloadLocation,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadLocationDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                if (downloadLocation.isNotBlank()) {
                    TextButton(
                        onClick = {
                            onDownloadLocationChange("")
                            Toast.makeText(context, R.string.download_folder_updated, Toast.LENGTH_SHORT).show()
                            showDownloadLocationDialog = false
                        }
                    ) {
                        Text(stringResource(R.string.download_folder_reset))
                    }
                }
            },
        )
    }

    if (clearDownloads) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_all_downloads),
            onDismiss = { clearDownloads = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    downloadCache.keys.forEach { key ->
                        downloadCache.removeResource(key)
                    }
                }
                clearDownloads = false
            },
            onCancel = { clearDownloads = false },
            content = {
                Text(text = stringResource(R.string.clear_downloads_dialog))
            },
        )
    }
    if (clearCacheDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_song_cache),
            onDismiss = { clearCacheDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    playerCache.keys.forEach { key ->
                        playerCache.removeResource(key)
                    }
                }
                clearCacheDialog = false
            },
            onCancel = { clearCacheDialog = false },
            content = {
                Text(text = stringResource(R.string.clear_song_cache_dialog))
            },
        )
    }
    if (clearImageCacheDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_image_cache),
            onDismiss = { clearImageCacheDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    val urlsToPreserve = mutableSetOf<String>()
                    val downloadedSongs =
                        try {
                            database.downloadedSongsByNameAsc().first()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    downloadedSongs.forEach { song ->
                        song.song.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
                        song.album?.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
                    }
                    val directory = imageDiskCache.directory.toFile()
                    if (directory.exists() && directory.isDirectory) {
                        directory.listFiles()?.forEach { file ->
                            if (file.isFile && !file.name.startsWith("journal")) {
                                val isPreserved = urlsToPreserve.any { hash -> file.name.startsWith(hash) }
                                if (!isPreserved) {
                                    file.delete()
                                }
                            }
                        }
                    }
                    imageDiskCache.clear()
                }
                clearImageCacheDialog = false
            },
            onCancel = { clearImageCacheDialog = false },
            content = {
                Text(text = stringResource(R.string.clear_image_cache_dialog))
            },
        )
    }

    // Confirmation Dialog
    if (showCacheWarningDialog) {
        AlertDialog(
            onDismissRequest = { showCacheWarningDialog = false },
            title = { Text(stringResource(R.string.cache_size_warning_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.cache_size_warning_message,
                        Formatter.formatShortFileSize(context, cacheUsage),
                        cacheType,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmAction()
                        showCacheWarningDialog = false
                    },
                ) {
                    Text(
                        stringResource(R.string.cache_size_warning_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCacheWarningDialog = false }) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            },
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            ).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top,
                ),
            ),
        )
        Material3SettingsGroup(
            title = stringResource(R.string.storage),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.storage),
                        title = { Text(stringResource(R.string.downloaded_songs)) },
                        description = {
                            Text(text = Formatter.formatShortFileSize(context, downloadCacheSize))
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.download),
                        title = { Text(stringResource(R.string.download_location)) },
                        description = {
                            Text(text = DownloadFolderHelper.getFolderDisplayName(context, downloadLocation))
                        },
                        onClick = {
                            showDownloadLocationDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.clear_all),
                        title = { Text(stringResource(R.string.clear_all_downloads)) },
                        onClick = {
                            clearDownloads = true
                        },
                    ),
                ),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.song_cache),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.enable_song_cache)) },
                    description = { Text(stringResource(R.string.enable_song_cache_desc)) },
                    trailingContent = {
                        Switch(
                            checked = enableSongCache,
                            onCheckedChange = onEnableSongCacheChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (enableSongCache) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onEnableSongCacheChange(!enableSongCache) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.max_song_cache_size)) },
                    enabled = enableSongCache,
                    description = {
                        val songCacheValues =
                            remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, -1) }
                        Column {
                            Text(
                                text = when (maxSongCacheSize) {
                                    0 -> stringResource(R.string.disable)
                                    -1 -> stringResource(R.string.unlimited)
                                    else -> Formatter.formatShortFileSize(context, maxSongCacheSize * 1024 * 1024L)
                                }
                            )
                            Slider(
                                value = songCacheValues.indexOf(maxSongCacheSize).toFloat(),
                                enabled = enableSongCache,
                                onValueChange = {
                                    val newValue = songCacheValues[it.roundToInt()]
                                    val newLimitInBytes = if (newValue == -1) {
                                        Long.MAX_VALUE
                                    } else {
                                        newValue * 1024 * 1024L
                                    }

                                        if (newLimitInBytes < playerCacheSize) {
                                            cacheUsage = playerCacheSize
                                            cacheType = songCacheString
                                            onConfirmAction = { onMaxSongCacheSizeChange(newValue) }
                                            showCacheWarningDialog = true
                                        } else {
                                            onMaxSongCacheSizeChange(newValue)
                                        }
                                    },
                                    steps = songCacheValues.size - 2,
                                    valueRange = 0f..(songCacheValues.size - 1).toFloat(),
                                )
                                LinearProgressIndicator(
                                    progress = { playerCacheProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    strokeCap = StrokeCap.Round,
                                )
                                Spacer(modifier = Modifier.padding(2.dp))
                                Text(
                                    text =
                                        if (maxSongCacheSize == -1) {
                                            Formatter.formatShortFileSize(context, playerCacheSize)
                                        } else {
                                            "${Formatter.formatShortFileSize(context, playerCacheSize)} / ${
                                                Formatter.formatShortFileSize(context, 
                                                    maxSongCacheSize * 1024 * 1024L,
                                                )
                                            }"
                                        },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.clear_all),
                        title = { Text(stringResource(R.string.clear_song_cache)) },
                        onClick = {
                            clearCacheDialog = true
                        },
                    ),
                ),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.image_cache),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.manage_search),
                        title = { Text(stringResource(R.string.max_image_cache_size)) },
                        description = {
                            val imageCacheValues =
                                remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192) }
                            Column {
                                Text(
                                    text =
                                        when (maxImageCacheSize) {
                                            0 -> stringResource(R.string.disable)
                                            else -> Formatter.formatShortFileSize(context, maxImageCacheSize * 1024 * 1024L)
                                        },
                                )
                                Slider(
                                    value = imageCacheValues.indexOf(maxImageCacheSize).toFloat(),
                                    onValueChange = {
                                        val newValue = imageCacheValues[it.roundToInt()]
                                        val newLimitInBytes = newValue * 1024 * 1024L

                                        if (newLimitInBytes < imageCacheSize) {
                                            cacheUsage = imageCacheSize
                                            cacheType = imageCacheString
                                            onConfirmAction = { onMaxImageCacheSizeChange(newValue) }
                                            showCacheWarningDialog = true
                                        } else {
                                            onMaxImageCacheSizeChange(newValue)
                                        }
                                    },
                                    steps = imageCacheValues.size - 2,
                                    valueRange = 0f..(imageCacheValues.size - 1).toFloat(),
                                )
                                LinearProgressIndicator(
                                    progress = { imageCacheProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    strokeCap = StrokeCap.Round,
                                )
                                Spacer(modifier = Modifier.padding(2.dp))
                                Text(
                                    text = "${Formatter.formatShortFileSize(context, imageCacheSize)} / ${
                                        Formatter.formatShortFileSize(context, 
                                            maxImageCacheSize * 1024 * 1024L,
                                        )
                                    }",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.clear_all),
                        title = { Text(stringResource(R.string.clear_image_cache)) },
                        onClick = {
                            clearImageCacheDialog = true
                        },
                    ),
                ),
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.storage)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
