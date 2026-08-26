package com.joymusic.music.discord

import com.joymusic.music.db.entities.Song
import timber.log.Timber

object DiscordActivityBuilder {
    private const val TAG = "DiscordSvc"

    fun build(
        songId: String,
        song: Song? = null,
        artistName: String,
        albumName: String?,
        artistThumbnail: String?,
        songTitle: String,
        startTimestamp: Long,
        endTimestamp: Long? = null,
        resolvedThumbnail: String? = null,
        advancedMode: Boolean = false,
        activityType: Int = DiscordActivity.TYPE_LISTENING,
        activityName: String? = null,
        stateTemplate: String = DiscordDefaults.STATE_TEMPLATE,
        detailsTemplate: String = DiscordDefaults.DETAILS_TEMPLATE,
        btn1Enabled: Boolean = true,
        btn1Label: String = DiscordDefaults.BUTTON1_LABEL,
        btn1Url: String = DiscordDefaults.BUTTON1_URL_TEMPLATE,
        btn2Enabled: Boolean = true,
        btn2Label: String = DiscordDefaults.BUTTON2_LABEL,
        btn2Url: String = DiscordDefaults.BUTTON2_URL,
        largeImageType: String = "thumbnail",
        largeImageCustomUrl: String = "",
        smallImageType: String = "artist",
        smallImageCustomUrl: String = "",
        largeTextSource: String = "album",
        largeTextCustom: String = "",
    ): DiscordActivity {
        val state: String
        val details: String?
        val renderedBtn1Label: String?
        val renderedBtn1Url: String?
        val renderedBtn2Label: String?
        val renderedBtn2Url: String?

        if (advancedMode) {
            state = DiscordTemplateRenderer.render(
                stateTemplate.ifEmpty { DiscordDefaults.STATE_TEMPLATE },
                songTitle, artistName, albumName, songId
            )
            details = DiscordTemplateRenderer.render(
                detailsTemplate.ifEmpty { DiscordDefaults.DETAILS_TEMPLATE },
                songTitle, artistName, albumName, songId
            )
            renderedBtn1Label = if (btn1Enabled) {
                DiscordTemplateRenderer.render(
                    btn1Label.ifEmpty { DiscordDefaults.BUTTON1_LABEL },
                    songTitle, artistName, albumName, songId
                )
            } else null
            renderedBtn1Url = if (btn1Enabled) {
                DiscordTemplateRenderer.render(
                    btn1Url.ifEmpty { DiscordDefaults.BUTTON1_URL_TEMPLATE },
                    songTitle, artistName, albumName, songId
                )
            } else null
            renderedBtn2Label = if (btn2Enabled) {
                DiscordTemplateRenderer.render(
                    btn2Label.ifEmpty { DiscordDefaults.BUTTON2_LABEL },
                    songTitle, artistName, albumName, songId
                )
            } else null
            renderedBtn2Url = if (btn2Enabled) {
                DiscordTemplateRenderer.render(
                    btn2Url.ifEmpty { DiscordDefaults.BUTTON2_URL },
                    songTitle, artistName, albumName, songId
                )
            } else null
        } else {
            state = artistName
            details = songTitle
            renderedBtn1Label = DiscordDefaults.BUTTON1_LABEL
            renderedBtn1Url = "${DiscordDefaults.YOUTUBE_WATCH_URL}$songId"
            renderedBtn2Label = DiscordDefaults.BUTTON2_LABEL
            renderedBtn2Url = "https://joymusic.site/"
        }

        val renderedName = if (advancedMode && !activityName.isNullOrEmpty()) {
            DiscordTemplateRenderer.render(
                activityName,
                songTitle, artistName, albumName, songId,
            )
        } else {
            activityName?.takeIf { it.isNotEmpty() } ?: "JOY MUSIC"
        }

        val songThumbnail = resolvedThumbnail?.takeIf { it.isNotBlank() }
            ?: song?.song?.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$songId/hqdefault.jpg"

        val largeImage = when (largeImageType) {
            "thumbnail" -> songThumbnail
            "artist" -> artistThumbnail ?: songThumbnail
            "appicon" -> "https://raw.githubusercontent.com/jehadjoy15-stack/apk-joy-music/main/assets/icon.png"
            "custom" -> largeImageCustomUrl.ifBlank { songThumbnail }
            else -> songThumbnail
        }

        val smallImage = when (smallImageType) {
            "thumbnail" -> songThumbnail
            "artist" -> artistThumbnail
            "appicon" -> "https://raw.githubusercontent.com/jehadjoy15-stack/apk-joy-music/main/assets/icon.png"
            "custom" -> smallImageCustomUrl.ifBlank { artistThumbnail }
            "dontshow" -> null
            else -> artistThumbnail
        }

        val renderedLargeText = when (largeTextSource) {
            "song" -> songTitle
            "artist" -> artistName
            "album" -> albumName ?: ""
            "app" -> "JOY MUSIC"
            "custom" -> largeTextCustom.ifBlank { albumName ?: "" }
            "dontshow" -> null
            else -> albumName ?: ""
        }

        val resolvedType = if (advancedMode) activityType else DiscordActivity.TYPE_LISTENING

        val result = DiscordActivity(
            activityType = resolvedType,
            name = renderedName,
            state = state,
            details = details,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            largeImage = largeImage,
            largeText = renderedLargeText,
            smallImage = smallImage,
            smallText = artistName,
            button1Label = renderedBtn1Label,
            button1Url = renderedBtn1Url,
            button2Label = renderedBtn2Label,
            button2Url = renderedBtn2Url,
        )

        Timber.tag(TAG).d(
            "build: result — name=%s, type=%d, state=%s, details=%s, btn1=%s, btn2=%s",
            result.name, result.activityType, result.state, result.details,
            result.button1Label?.take(30), result.button2Label?.take(30),
        )

        return result
    }
}
