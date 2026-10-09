package com.anifree.app

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.Locale

/** Select 1080p as preference, not a guarantee; keep adaptive fallback. */
@Composable
fun EpisodePlayer(episode: LicensedEpisode, modifier: Modifier = Modifier) {
    if (!SourcePolicy.approved(episode.streamUrl, episode.allowedHosts) ||
        (episode.subtitlePtUrl != null && !SourcePolicy.approved(episode.subtitlePtUrl, episode.allowedHosts))) {
        Text("Fonte não autorizada para reprodução interna")
        return
    }
    val context = LocalContext.current
    val player = remember(episode) {
        val subtitle = episode.subtitlePtUrl?.let {
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(it))
                .setMimeType(MimeTypes.TEXT_VTT).setLanguage("pt-BR")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        }
        val media = MediaItem.Builder().setUri(episode.streamUrl)
            .setMimeType(when(episode.streamType) {
                StreamType.MP4 -> MimeTypes.VIDEO_MP4
                StreamType.HLS -> MimeTypes.APPLICATION_M3U8
                StreamType.DASH -> MimeTypes.APPLICATION_MPD
            })
            .apply { if (subtitle != null) setSubtitleConfigurations(listOf(subtitle)) }
            .build()
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(media)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage("pt-BR")
                .setPreferredAudioLanguage("ja")
                .setMaxVideoSize(1920, 1080)
                .build()
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Column(modifier) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player } },
            update = { it.player = player }, modifier = Modifier.fillMaxWidth().heightIn(min = androidx.compose.ui.unit.Dp(220f)))
        Text("Preferência: áudio original + legendas PT-BR, até 1080p (quando disponíveis)")
        Text("Qualidade e idiomas dependem das faixas fornecidas pelo provedor.", style = MaterialTheme.typography.bodySmall)
    }
}
