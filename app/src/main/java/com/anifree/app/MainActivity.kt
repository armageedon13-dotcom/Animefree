package com.anifree.app

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.database.Cursor
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Black = Color(0xFF101010)
private val Yellow = Color(0xFFFFD400)
private val Panel = Color(0xFF222222)

data class Anime(val id: Int, val title: String, val description: String, val image: String, val siteUrl: String?, val isAdult: Boolean = false)

data class ApprovedSource(val animeId: Int, val url: String, val mode: PlaybackMode)
// Add entries ONLY after a rights check and technical verification by the operator.
// Each direct URL must point to an authorized downloadable 1080p MP4.
data class ApprovedDownload(val animeId: Int, val episode: Int, val mp4Url: String, val subtitleVttUrl: String?, val licenseConfirmed: Boolean)
private val approvedDownloads = listOf<ApprovedDownload>()

private fun validDownloadUrl(url: String): Boolean = runCatching {
    val u = Uri.parse(url)
    u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null &&
        u.path.orEmpty().lowercase().endsWith(".mp4")
}.getOrDefault(false)

private fun enqueueAuthorizedDownload(context: Context, entry: ApprovedDownload): String {
    if (!entry.licenseConfirmed || !validDownloadUrl(entry.mp4Url)) return "Fonte sem autorização ou MP4 inválido."
    val name = "Anifree_${entry.animeId}_E${entry.episode}_1080p.mp4"
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val request = DownloadManager.Request(Uri.parse(entry.mp4Url))
        .setTitle(name)
        .setDescription("Vídeo 1080p autorizado — legendado quando disponível")
        .setMimeType("video/mp4")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(false)
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, name)
    manager.enqueue(request)
    // Download of an authorized subtitle sidecar, if supplied. The source must also be approved.
    val subtitle = entry.subtitleVttUrl
    if (subtitle != null && runCatching {
            val u = Uri.parse(subtitle)
            u.scheme == "https" && !u.host.isNullOrBlank() && u.path.orEmpty().endsWith(".vtt")
        }.getOrDefault(false)) {
        manager.enqueue(DownloadManager.Request(Uri.parse(subtitle))
            .setTitle("Legendas E${entry.episode}")
            .setMimeType("text/vtt")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, name.removeSuffix(".mp4") + ".vtt"))
    }
    return "Download iniciado (Wi-Fi). Acompanhe na biblioteca."
}

data class DownloadItem(val id: Long, val title: String, val status: Int, val localUri: String?)
private fun library(context: Context): List<DownloadItem> {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val result = mutableListOf<DownloadItem>()
    manager.query(DownloadManager.Query()).use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
        val titleIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
        val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
        val uriIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
        while (cursor.moveToNext()) {
            val title = cursor.getString(titleIndex) ?: continue
            if (!title.startsWith("Anifree_")) continue
            result += DownloadItem(cursor.getLong(idIndex), title, cursor.getInt(statusIndex), cursor.getString(uriIndex))
        }
    }
    return result
}

enum class PlaybackMode { INTERNAL_EMBED, EXTERNAL_OFFICIAL }

// Demo-only list. A production backend must verify rights, source ownership and approval.
private val approvedSources = listOf<ApprovedSource>()

// AniList provides popularity, NOT streaming view counts or Brazilian age classifications.
private suspend fun fetchAnime(search: String, order: String = "POPULARITY_DESC"): List<Anime> = withContext(Dispatchers.IO) {
    require(order in setOf("POPULARITY_DESC", "START_DATE_DESC"))
    val query = "query (\$search: String) { Page(page: 1, perPage: 30) { media(type: ANIME, search: \$search, sort: $order, isAdult: false) { id title { romaji english } description(asHtml: false) coverImage { large } siteUrl isAdult } } }"
    val payload = JSONObject().put("query", query).put("variables", JSONObject().put("search", search.ifBlank { JSONObject.NULL })).toString()
    val connection = (URL("https://graphql.anilist.co").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 10000
        setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
    }
    try {
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val array = root.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Anime(obj.getInt("id"), obj.getJSONObject("title").optString("english").takeIf { it.isNotBlank() && it != "null" } ?: obj.getJSONObject("title").optString("romaji"), obj.optString("description"), obj.getJSONObject("coverImage").optString("large"), obj.optString("siteUrl").takeIf { it.startsWith("https://") }, obj.optBoolean("isAdult"))
        }
    } finally { connection.disconnect() }
}

// Strict default-deny policy: an operator must confirm Brazilian classification <= 12.
// Add AniList IDs only after human review of the title, synopsis and episodes.
private val kidsApprovedIds: Set<Int> = emptySet()
private fun visibleToKids(anime: Anime) = !anime.isAdult && anime.id in kidsApprovedIds

private enum class Profile { ADULT, KIDS }
private class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("anifree_profiles", Context.MODE_PRIVATE)
    fun current(): Profile = if (prefs.getString("active", "ADULT") == "KIDS") Profile.KIDS else Profile.ADULT
    fun save(profile: Profile) = prefs.edit().putString("active", profile.name).apply()
}

// Only an approved HTTPS embed host should ever be rendered internally.
private val approvedEmbedHosts = setOf("www.youtube-nocookie.com", "www.youtube.com")
private fun allowedEmbed(url: String): Boolean = runCatching {
    val uri = Uri.parse(url)
    uri.scheme == "https" && uri.host in approvedEmbedHosts && uri.path.orEmpty().startsWith("/embed/")
}.getOrDefault(false)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var search by remember { mutableStateOf("") }
            var submitted by remember { mutableStateOf("") }
            var anime by remember { mutableStateOf<List<Anime>>(emptyList()) }
            var loading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var selected by remember { mutableStateOf<Anime?>(null) }
            var embedUrl by remember { mutableStateOf<String?>(null) }
            var tab by remember { mutableIntStateOf(0) }
            val profileStore = remember { ProfileStore(this@MainActivity) }
            var profile by remember { mutableStateOf(profileStore.current()) }
            val parentalPin = remember { ParentalPin(this@MainActivity) }
            var pinDialog by remember { mutableStateOf(if (profile == Profile.KIDS) "unlock" else "") }
            var pinValue by remember { mutableStateOf("") }
            var pinConfirm by remember { mutableStateOf("") }
            var pinError by remember { mutableStateOf("") }
            var kidsUnlocked by remember { mutableStateOf(profile != Profile.KIDS) }
            var pendingAdult by remember { mutableStateOf(false) }
            var playing by remember { mutableStateOf<LicensedEpisode?>(null) }
            var libraryRefresh by remember { mutableIntStateOf(0) }
            var downloadNotice by remember { mutableStateOf<String?>(null) }
            var downloads by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }
            val context = LocalContext.current
            LaunchedEffect(submitted, tab) {
                loading = true; error = null
                try { anime = fetchAnime(if (tab == 0) submitted else "", if (tab == 1) "START_DATE_DESC" else "POPULARITY_DESC") } catch (e: Exception) { error = "Não foi possível carregar o catálogo. Verifique sua conexão." }
                finally { loading = false }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Yellow, background = Black, surface = Panel, onPrimary = Black)) {
                Column(Modifier.fillMaxSize().background(Black).padding(16.dp)) {
                    Text("ANIFREE", color = Yellow, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Perfil: ${if (profile == Profile.KIDS) "Kids (até 12)" else "Adulto"}", color = Yellow)
                        TextButton(onClick = {
                            pinValue = ""; pinConfirm = ""; pinError = ""
                            pinDialog = if (!parentalPin.configured) "setup" else if (profile == Profile.ADULT) "enterKids" else "exitKids"
                        }) { Text("Trocar perfil") }
                    }
                    if (pinDialog.isNotEmpty()) {
                        val mode = pinDialog
                        AlertDialog(
                            onDismissRequest = { if (mode != "unlock") pinDialog = "" },
                            title = { Text(when(mode) { "setup" -> "Criar PIN parental"; "enterKids" -> "Entrar no Kids"; "exitKids" -> "Sair do Kids"; else -> "Desbloquear Kids" }) },
                            text = {
                                Column {
                                    Text("Informe o PIN do responsável (4 a 6 números).")
                                    OutlinedTextField(value = pinValue, onValueChange = { pinValue = it.filter(Char::isDigit).take(6) },
                                        label = { Text("PIN") }, singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                                    if (mode == "setup") OutlinedTextField(value = pinConfirm,
                                        onValueChange = { pinConfirm = it.filter(Char::isDigit).take(6) },
                                        label = { Text("Confirmar PIN") }, singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                                    if (pinError.isNotEmpty()) Text(pinError, color = Color.Red)
                                }
                            },
                            confirmButton = { TextButton(onClick = {
                                if (mode == "setup") {
                                    if (pinValue != pinConfirm || !parentalPin.setup(pinValue)) pinError = "PIN inválido ou confirmação diferente."
                                    else { pinDialog = ""; pinValue = ""; pinConfirm = "" }
                                } else if (parentalPin.verify(pinValue)) {
                                    pinDialog = ""; pinValue = ""; pinError = ""
                                    if (mode == "enterKids") { profile = Profile.KIDS; profileStore.save(profile); selected = null; kidsUnlocked = true }
                                    if (mode == "exitKids") { profile = Profile.ADULT; profileStore.save(profile); selected = null; kidsUnlocked = true }
                                    if (mode == "unlock") kidsUnlocked = true
                                } else pinError = if (parentalPin.lockedOut()) "Muitas tentativas. Aguarde 1 minuto." else "PIN incorreto."
                            }) { Text("Confirmar") } },
                            dismissButton = { if (mode != "unlock") TextButton(onClick = { pinDialog = "" }) { Text("Cancelar") } }
                        )
                    }
                    if (profile == Profile.KIDS && !kidsUnlocked) {
                        Text("Perfil Kids bloqueado. Informe o PIN para continuar.", color = Yellow)
                    } else {
                    Spacer(Modifier.height(10.dp))
                    if (playing != null) {
                        BackHandler { playing = null }
                        TextButton(onClick = { playing = null }) { Text("← Voltar") }
                        EpisodePlayer(playing!!)
                    } else if (embedUrl != null) {
                        BackHandler { embedUrl = null }
                        TextButton(onClick = { embedUrl = null }) { Text("← Voltar") }
                        AndroidViewEmbed(embedUrl!!)
                    } else if (selected != null) {
                        TextButton(onClick = { selected = null }) { Text("← Catálogo") }
                        Text(selected!!.title, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(selected!!.description.replace(Regex("<[^>]*>"), ""), color = Color.LightGray)
                        Spacer(Modifier.height(14.dp))
                        val sources = approvedSources.filter { it.animeId == selected!!.id }
                        if (sources.isEmpty() && LicensedCatalog.episodes.none { it.animeId == selected!!.id }) Text("Nenhum episódio licenciado integrado no momento.", color = Yellow)
                        LicensedCatalog.episodes.filter { it.animeId == selected!!.id }.forEach { episode ->
                            Text(episode.episodeTitle, color = Yellow)
                            Button(onClick = { playing = episode }) { Text("Assistir • legendado • até 1080p") }
                            if (SourcePolicy.downloadable(episode)) {
                                Button(onClick = {
                                    runCatching { OfflineLibrary(context).enqueue(episode) }
                                        .onSuccess { libraryRefresh++ }
                                }) { Text("Baixar MP4 autorizado (Wi-Fi)") }
                            } else Text("Download MP4 indisponível nesta fonte", color = Color.Gray)
                        }
                        val downloadable = approvedDownloads.filter { it.animeId == selected!!.id && it.licenseConfirmed }
                        if (downloadable.isEmpty()) Text("Download MP4 1080p indisponível para este título.", color = Color.Gray)
                        downloadable.forEach { entry ->
                            Button(onClick = {
                                downloadNotice = enqueueAuthorizedDownload(context, entry)
                            }) { Text("Baixar episódio ${entry.episode} • MP4 • 1080p • Legendado") }
                        }
                        downloadNotice?.let { Text(it, color = Yellow) }
                        sources.forEach { source ->
                            Button(onClick = {
                                if (source.mode == PlaybackMode.INTERNAL_EMBED && allowedEmbed(source.url)) embedUrl = source.url
                                else if (source.mode == PlaybackMode.EXTERNAL_OFFICIAL && source.url.startsWith("https://")) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                            }) { Text(if (source.mode == PlaybackMode.INTERNAL_EMBED) "Assistir no Anifree" else "Abrir no serviço oficial") }
                        }
                        selected!!.siteUrl?.let { url ->
                            OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Ver informações no AniList") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Explorar", "Novos", "Top 10", "Comunidade", "Downloads").forEachIndexed { index, label ->
                                TextButton(onClick = { tab = index }) { Text(label, color = if (tab == index) Yellow else Color.Gray) }
                            }
                        }
                        if (tab in 0..2) {
                            if (tab == 1) Text("Novos lançamentos", color = Yellow)
                            if (tab == 2) Text("Top 10 por popularidade AniList (não por visualizações)", color = Yellow)
                            if (tab == 0) {
                            OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Pesquisar anime") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(onClick = { submitted = search }, modifier = Modifier.fillMaxWidth()) { Text("Buscar") }
                            }
                            if (loading) CircularProgressIndicator(color = Yellow)
                            error?.let { Text(it, color = Color.Red) }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items((if (profile == Profile.KIDS) anime.filter(::visibleToKids) else anime).let { if (tab == 2) it.take(10) else it }) { item ->
                                    Row(Modifier.fillMaxWidth().clickable { selected = item }.background(Panel, RoundedCornerShape(12.dp)).padding(8.dp)) {
                                        AsyncImage(model = item.image, contentDescription = null, modifier = Modifier.size(80.dp, 110.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column { Text(if (tab == 2) "${anime.indexOf(item) + 1}º • ${item.title}" else item.title, fontWeight = FontWeight.Bold); Text("Ver detalhes", color = Yellow) }
                                    }
                                }
                            }
                            if (profile == Profile.KIDS && anime.none(::visibleToKids)) Text("Nenhum título com classificação até 12 anos verificada neste resultado.", color = Yellow)
                        } else if (tab == 3) {
                            Text("Envie sugestões de títulos e links oficiais para moderação.", color = Color.LightGray)
                            Text("Publicação comunitária depende de backend e revisão humana; ainda não habilitada.", color = Yellow)
                        } else {
                            val library = OfflineLibrary(context)
                            val entries = remember(libraryRefresh) { library.items() }
                            Text("Biblioteca offline", color = Yellow, style = MaterialTheme.typography.titleLarge)
                            Text("Arquivos MP4 autorizados. Toque em Atualizar para consultar o andamento.")
                            Button(onClick = { libraryRefresh++ }) { Text("Atualizar") }
                            LazyColumn {
                                items(entries) { entry ->
                                    val status = library.status(entry.id)
                                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                        Text(entry.title)
                                        Text(when(status) {
                                            android.app.DownloadManager.STATUS_SUCCESSFUL -> "Concluído"
                                            android.app.DownloadManager.STATUS_RUNNING -> "Baixando"
                                            android.app.DownloadManager.STATUS_PENDING -> "Na fila"
                                            android.app.DownloadManager.STATUS_PAUSED -> "Pausado"
                                            else -> "Falha"
                                        }, color = Yellow)
                                        if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                            Button(onClick = {
                                                library.localUri(entry.id)?.let { uri ->
                                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                                }
                                            }) { Text("Assistir offline") }
                                        }
                                        TextButton(onClick = { library.delete(entry.id); libraryRefresh++ }) { Text("Excluir") }
                                    }
                                }
                            }

                        }
                    }
                    } // protected Kids content
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AndroidViewEmbed(url: String) {
    androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString().orEmpty()
                    return !allowedEmbed(target)
                }
            }
            loadUrl(url)
        }
    }, modifier = Modifier.fillMaxSize())
}
