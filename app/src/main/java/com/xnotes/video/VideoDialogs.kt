package com.xnotes.video

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.alist.AlistClient
import com.xnotes.alist.AlistConfig
import com.xnotes.alist.AlistEntry
import com.xnotes.alist.AlistException
import com.xnotes.alist.AlistPaths
import com.xnotes.core.model.VideoSource
import kotlinx.coroutines.launch

/** The open-a-video dialogs one [VideoSession] may show. */
@Composable
internal fun VideoDialogs(session: VideoSession) {
    val context = LocalContext.current
    val pickLocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Keep read access across restarts so a link to a local file still opens later.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        session.insertAndPlay(VideoSource.Local(uri.toString()), name.substringBeforeLast('.'))
    }

    if (session.pickerOpen) {
        OpenVideoDialog(
            onDismiss = { session.pickerOpen = false },
            onAlist = { session.pickerOpen = false; if (session.alist() == null) session.alistSettingsOpen = true else session.alistBrowserOpen = true },
            onUrl = { session.pickerOpen = false; session.urlDialogOpen = true },
            onLocal = { session.pickerOpen = false; pickLocal.launch(arrayOf("video/*", "application/x-mpegURL", "application/vnd.apple.mpegurl")) },
        )
    }
    if (session.urlDialogOpen) {
        UrlDialog(onDismiss = { session.urlDialogOpen = false }) { url ->
            session.urlDialogOpen = false
            val title = Uri.parse(url).lastPathSegment?.substringBeforeLast('.') ?: url
            session.insertAndPlay(VideoSource.Url(url), title)
        }
    }
    if (session.alistSettingsOpen) {
        AlistSettingsDialog(session, onDismiss = { session.alistSettingsOpen = false }, onSaved = {
            session.alistSettingsOpen = false
            session.alistBrowserOpen = true
        })
    }
    if (session.alistBrowserOpen) {
        val client = session.alist()
        if (client == null) {
            session.alistBrowserOpen = false
            session.alistSettingsOpen = true
        } else {
            AlistBrowserDialog(
                session = session,
                client = client,
                onDismiss = { session.alistBrowserOpen = false },
                onSettings = { session.alistBrowserOpen = false; session.alistSettingsOpen = true },
                onPick = { entry ->
                    session.alistBrowserOpen = false
                    session.insertAndPlay(VideoSource.Alist(entry.path), entry.name.substringBeforeLast('.'))
                },
            )
        }
    }
}

/** A dialog width that suits a tablet: roomy in landscape, near-full in a narrow split. */
@Composable
private fun dialogWidth() = (LocalConfiguration.current.screenWidthDp * 0.9f).coerceAtMost(640f).dp

@Composable
private fun OpenVideoDialog(onDismiss: () -> Unit, onAlist: () -> Unit, onUrl: () -> Unit, onLocal: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_insert)) },
        text = {
            Column {
                ChoiceRow(Icons.Filled.Cloud, stringResource(R.string.video_open_alist), onAlist)
                ChoiceRow(Icons.Filled.Link, stringResource(R.string.video_open_url), onUrl)
                ChoiceRow(Icons.Filled.PhoneAndroid, stringResource(R.string.video_open_local), onLocal)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    val ok = url.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_open_url)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                placeholder = { Text("https://…/video.mp4  ·  .m3u8") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.widthIn(min = 320.dp).fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onOpen(url.trim()) }) { Text(stringResource(R.string.video_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AlistSettingsDialog(session: VideoSession, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val initial = remember { session.alistSettings.load() }
    var server by remember { mutableStateOf(initial.server) }
    var user by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var token by remember { mutableStateOf(initial.token) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val okText = stringResource(R.string.alist_test_ok)
    val failText = stringResource(R.string.alist_test_failed)
    val saveFailText = stringResource(R.string.alist_save_failed)
    fun config() = AlistConfig(server.trim(), user.trim(), password, token.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = dialogWidth()),
        title = { Text(stringResource(R.string.alist_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(server, { server = it }, label = { Text(stringResource(R.string.alist_server)) },
                    placeholder = { Text("http://192.168.1.10:5244") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.alist_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.alist_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.alist_token)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.alist_settings_hint), fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = server.isNotBlank() && !testing, onClick = {
                        testing = true
                        status = null
                        scope.launch {
                            status = try {
                                val c = AlistClient(config())
                                if (config().token.isBlank() && config().username.isNotBlank()) c.login()
                                c.list("/", perPage = 1)
                                okText
                            } catch (e: AlistException) {
                                "$failText: ${e.message}"
                            }
                            testing = false
                        }
                    }) { Text(stringResource(R.string.alist_test)) }
                    if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    status?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = server.isNotBlank(), onClick = {
                if (session.alistSettings.save(config())) {
                    session.alistConfigChanged()
                    onSaved()
                } else {
                    status = saveFailText
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Folders and video files of the AList server, with breadcrumbs; remembers the last folder. */
@Composable
private fun AlistBrowserDialog(
    session: VideoSession,
    client: AlistClient,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onPick: (AlistEntry) -> Unit,
) {
    var path by remember { mutableStateOf(session.alistSettings.lastPath) }
    var entries by remember { mutableStateOf<List<AlistEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var forceRefresh by remember { mutableStateOf(false) }

    LaunchedEffect(path, reload) {
        loading = true
        error = null
        try {
            val l = client.list(path, refresh = forceRefresh)
            entries = l.entries.filter { it.isDir || it.isVideo }
                .sortedWith(compareBy<AlistEntry>({ !it.isDir }, { it.name.lowercase() }))
            session.alistSettings.lastPath = path
        } catch (e: AlistException) {
            entries = emptyList()
            error = e.message
            // A folder that vanished: fall back to the root once.
            if (path != "/" && e.code != 401 && e.code != -1) path = "/"
        }
        forceRefresh = false
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = dialogWidth()),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.video_open_alist), modifier = Modifier.weight(1f))
                IconButton(onClick = { forceRefresh = true; reload++ }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.alist_refresh))
                }
                IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Settings, stringResource(R.string.alist_settings))
                }
            }
        },
        text = {
            Column {
                // Breadcrumbs, scrolling sideways for deep paths.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    if (path != "/") {
                        IconButton(onClick = { path = AlistPaths.parent(path) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.alist_up))
                        }
                    }
                    for ((i, crumb) in AlistPaths.breadcrumbs(path).withIndex()) {
                        if (i > 0) Text("›", modifier = Modifier.padding(horizontal = 2.dp))
                        TextButton(onClick = { path = crumb.second }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                            Text(if (i == 0) stringResource(R.string.alist_root) else crumb.first, maxLines = 1)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 480.dp)) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        error != null -> Text(stringResource(R.string.alist_error, error ?: ""), modifier = Modifier.align(Alignment.Center).padding(16.dp))
                        entries.isEmpty() -> Text(stringResource(R.string.alist_empty), modifier = Modifier.align(Alignment.Center))
                        else -> LazyColumn {
                            items(entries, key = { it.path }) { e ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .clickable { if (e.isDir) path = e.path else onPick(e) }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(if (e.isDir) Icons.Filled.Folder else Icons.Filled.Movie, null, modifier = Modifier.size(24.dp))
                                    Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                                    if (!e.isDir) Text(humanSize(e.size), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i])
}
