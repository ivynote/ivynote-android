package app.ivynote.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ivynote.android.IvyApp
import app.ivynote.android.platform.SqliteDocumentStore
import app.ivynote.core.ApiClient
import app.ivynote.core.ApiError
import app.ivynote.core.L10n
import app.ivynote.core.LibraryMigrator
import app.ivynote.core.LibraryRef
import app.ivynote.core.OfficialRegions
import app.ivynote.core.ServerAddress
import app.ivynote.core.ServerInfo
import app.ivynote.core.SessionStore
import app.ivynote.core.SyncRegion
import app.ivynote.core.User
import app.ivynote.core.Validation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone

private enum class Mode { REGISTER, LOGIN }

/**
 * Signs in to the sync service and creates the synced library once an account
 * is behind it. The service is chosen at the top: the official node that fits
 * the device is preselected, the other one is a tap away, and a self-hosted
 * server sits behind "自定义服务器…".
 *
 * With [promoting], a local library moves to the cloud: only a newly
 * registered account can take it ([LibraryMigrator]), so this offers sign-up only.
 */
@Composable
fun CloudSignInScreen(promoting: LibraryRef?, onBack: () -> Unit) {
    val c = Ivy.colors
    val s = LocalStrings.current
    val app = IvyApp.instance
    val catalog = app.catalog
    val scope = rememberCoroutineScope()

    // By the system's region, not the interface language (ours replaces the default locale).
    var server by remember { mutableStateOf(SyncRegion.preferred(app.systemLocale.country, TimeZone.getDefault().id).server) }
    var info by remember { mutableStateOf<ServerInfo?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var probeRun by remember { mutableIntStateOf(0) }
    var serverMenu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }

    var mode by rememberSaveable { mutableStateOf(Mode.REGISTER) }
    var identity by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Moving a local library
    var migrating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<LibraryMigrator.Progress?>(null) }
    var migrationError by remember { mutableStateOf<String?>(null) }
    var newAccount by remember { mutableStateOf<Triple<ServerAddress, String, User>?>(null) }

    // The website may have moved an official node (e.g. to a temporary domain).
    // Wait briefly for its answer before the first probe, else go on with the
    // last known address; a late answer still moves an official choice.
    val regions by OfficialRegions.regions.collectAsState()
    var regionsAsked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val answer = app.appScope.async { OfficialRegions.refresh() }
        withTimeoutOrNull(1_500) { answer.await() }
        regionsAsked = true
    }
    LaunchedEffect(regions) {
        SyncRegion.of(server)?.let { r -> if (r.server != server) server = r.server }
    }

    val region = remember(server, regions) { SyncRegion.of(server) }
    val canRegister = info?.canRegister ?: true
    val registering = promoting != null || mode == Mode.REGISTER

    LaunchedEffect(server, probeRun, regionsAsked) {
        val target = server
        probing = true
        probeError = null
        info = null
        if (!regionsAsked) return@LaunchedEffect
        try {
            info = ApiClient(target).probe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            probeError = e.message
        } finally {
            probing = false
        }
    }
    LaunchedEffect(info) {
        if (info?.canRegister == false && promoting == null) mode = Mode.LOGIN
    }

    val libraryName = when {
        promoting != null -> promoting.name
        region != null -> s.defaultLibraryName
        else -> info?.siteName?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "ivynote" } ?: server.host
    }

    /** Copies the local library into the new account, then opens it as a synced library. */
    suspend fun migrate(local: LibraryRef) {
        val (srv, token, user) = newAccount ?: return
        migrating = true
        migrationError = null
        progress = null
        try {
            val content = withContext(Dispatchers.IO) {
                val store = SqliteDocumentStore(catalog.storeFile(local.id))
                try { LibraryMigrator.Content.read(store, app.filesDir(local.id)) } finally { store.close() }
            }
            val result = LibraryMigrator(ApiClient(srv, token), app.deviceId).run(content) { p -> progress = p }
            val ref = catalog.createSignedIn(srv, libraryName, token, user)
            catalog.markMigrated(local.id, ref.id)
            if (result.rejected.isNotEmpty()) {
                // Opened anyway; the local library keeps everything.
                android.widget.Toast.makeText(app, L10n.s.migrationRejected(result.rejected.size), android.widget.Toast.LENGTH_LONG).show()
            }
            if (result.failedFiles.isNotEmpty()) {
                android.widget.Toast.makeText(app, L10n.s.migrationFilesFailed(result.failedFiles.size), android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LibraryMigrator.AccountNotEmpty) {
            migrationError = e.message
        } catch (e: Exception) {
            migrationError = L10n.s.migrationInterrupted(e.message ?: e.javaClass.simpleName)
        } finally {
            migrating = false
        }
    }

    fun submit() {
        if (busy || info == null) return
        if (registering) {
            val problem = Validation.username(username) ?: Validation.password(password)
            if (problem != null) {
                error = problem
                return
            }
        }
        error = null
        busy = true
        val target = server
        scope.launch {
            try {
                val api = ApiClient(target)
                val (token, user) = if (registering) {
                    api.register(username.trim(), password, email.trim())
                } else {
                    api.login(identity.trim(), password)
                }
                password = ""
                if (promoting != null) {
                    newAccount = Triple(target, token, user)
                    migrate(promoting)
                } else {
                    catalog.createSignedIn(target, libraryName, token, user)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                error = if (e.status == 401 && !registering) L10n.s.wrongCredentials else e.message
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.canvas).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back, tint = c.ink2) }
            Text(if (promoting == null) s.signInTitle else s.turnOnSyncTitle, style = SerifTitle, fontSize = 18.sp, color = c.ink)
        }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (migrating || migrationError != null) {
                    Caption(s.turnOnSyncTitle)
                    FormCard {
                        if (migrating) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.ivy)
                                Spacer(Modifier.width(10.dp))
                                Text(progressText(progress), color = c.ink)
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(progress = { progressShare(progress) }, modifier = Modifier.fillMaxWidth(), color = c.ivy, trackColor = c.sunken)
                        } else {
                            Text(migrationError ?: "", color = c.danger)
                            TextButton(onClick = { promoting?.let { p -> scope.launch { migrate(p) } } }) { Text(s.continueMigration, color = c.ivy) }
                        }
                    }
                    return@Column
                }

                // The sync node
                Caption(s.syncNode)
                Box {
                    FormCard(Modifier.clickable { serverMenu = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (region == null) Icons.Outlined.Dns else Icons.Outlined.Public, null, tint = c.ivy)
                            Spacer(Modifier.width(10.dp))
                            Text(region?.let { s.officialRegion(it.title) } ?: server.host, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            when {
                                probing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.muted)
                                info != null -> Icon(Icons.Outlined.CheckCircle, null, tint = c.ok, modifier = Modifier.size(18.dp))
                                probeError != null -> Icon(Icons.Outlined.Warning, null, tint = c.warn, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Outlined.UnfoldMore, null, tint = c.faint, modifier = Modifier.size(16.dp))
                        }
                    }
                    DropdownMenu(expanded = serverMenu, onDismissRequest = { serverMenu = false }) {
                        for (r in SyncRegion.entries) {
                            DropdownMenuItem(
                                text = { Text(s.officialRegion(r.title)) },
                                leadingIcon = { if (region == r) Icon(Icons.Outlined.Check, null, tint = c.ivy) else Spacer(Modifier.size(24.dp)) },
                                onClick = { serverMenu = false; server = r.server },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(s.customServerEllipsis) }, leadingIcon = { Icon(Icons.Outlined.Dns, null) },
                            onClick = { serverMenu = false; custom = true })
                    }
                }
                when {
                    probeError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (region != null) s.nodeUnreachable else probeError ?: "", color = c.danger, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { probeRun++ }) { Text(s.retry, color = c.ivy) }
                    }
                    promoting == null -> Caption(s.signInHint)
                    else -> Caption(s.promoteHint(promoting.name))
                }

                if (promoting == null && canRegister) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = mode == Mode.REGISTER, onClick = { mode = Mode.REGISTER; error = null },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(s.register) }
                        SegmentedButton(selected = mode == Mode.LOGIN, onClick = { mode = Mode.LOGIN; error = null },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(s.login) }
                    }
                }

                AccountFields(
                    login = !registering,
                    identity = identity, onIdentity = { identity = it },
                    username = username, onUsername = { username = it },
                    email = email, onEmail = { email = it },
                    displayName = null, onDisplayName = {},
                    password = password, onPassword = { password = it },
                    onSubmit = ::submit,
                )
                when {
                    error != null -> Text(error ?: "", color = c.danger, fontSize = 13.sp)
                    info?.hasUsers == false && region == null -> Caption(s.firstAdmin)
                    promoting != null && info?.canRegister == false -> Text(s.registrationClosed, color = c.danger, fontSize = 13.sp)
                }
                val canSubmit = if (registering) canRegister && username.isNotEmpty() && password.isNotEmpty() else identity.isNotEmpty() && password.isNotEmpty()
                InkButton(
                    text = if (promoting != null) s.registerAndSync else if (mode == Mode.LOGIN) s.login else s.register,
                    onClick = ::submit,
                    enabled = canSubmit && !probing && info != null,
                    busy = busy,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                )
                if (promoting != null) {
                    Caption(s.promoteNote)
                }
            }
        }
    }

    if (custom) {
        CustomServerDialog(onDismiss = { custom = false }) { address, serverInfo ->
            custom = false
            server = address
            // The probe runs again for the new address; its answer is already known to be good.
            info = serverInfo
        }
    }
}

private fun progressText(p: LibraryMigrator.Progress?): String = when (p?.phase) {
    null, LibraryMigrator.Progress.Phase.CHECK -> L10n.s.preparing
    LibraryMigrator.Progress.Phase.FILES -> L10n.s.uploadingFiles(p.done, p.total)
    LibraryMigrator.Progress.Phase.NOTES -> L10n.s.uploadingNotes(p.done, p.total)
    LibraryMigrator.Progress.Phase.DONE -> L10n.s.migrationDone
}

private fun progressShare(p: LibraryMigrator.Progress?): Float = when (p?.phase) {
    null -> 0f
    LibraryMigrator.Progress.Phase.CHECK -> 0.02f
    // Files fill the first half, notes the second.
    LibraryMigrator.Progress.Phase.FILES -> if (p.total > 0) 0.5f * p.done / p.total else 0.5f
    LibraryMigrator.Progress.Phase.NOTES -> if (p.total > 0) p.done.toFloat() / p.total else 1f
    LibraryMigrator.Progress.Phase.DONE -> 1f
}

/** A self-hosted server: checks the address, then hands it back. */
@Composable
private fun CustomServerDialog(onDismiss: () -> Unit, onPick: (ServerAddress, ServerInfo) -> Unit) {
    val c = Ivy.colors
    val scope = rememberCoroutineScope()
    val last = remember {
        IvyApp.instance.prefs.getString(SessionStore.LAST_SERVER_KEY)
            ?.let(ServerAddress::parseOrNull)?.takeIf { SyncRegion.of(it) == null }?.base.orEmpty()
    }
    var address by remember { mutableStateOf(last) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = ServerAddress.parseOrNull(address)
    val s = LocalStrings.current

    fun connect() {
        if (checking) return
        error = null
        val server = try {
            ServerAddress.parse(address)
        } catch (e: ServerAddress.ParseError) {
            error = e.message
            return
        }
        checking = true
        scope.launch {
            try {
                onPick(server, ApiClient(server).probe())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                checking = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.customServer) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Text("https://notes.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { connect() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    error != null -> Text(error ?: "", color = c.danger, fontSize = 13.sp)
                    parsed?.isInsecure == true -> Text(s.insecureWarning, color = c.warn, fontSize = 13.sp)
                    else -> Text(s.customServerHint, color = c.muted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::connect, enabled = address.isNotBlank() && !checking) {
                if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(s.useServer)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** Signs in again to a synced library whose session ended (or that was signed out). */
@Composable
fun SignInScreen(ref: LibraryRef, session: SessionStore) {
    val c = Ivy.colors
    val s = LocalStrings.current
    val catalog = IvyApp.instance.catalog
    val notice by session.notice.collectAsState()
    val scope = rememberCoroutineScope()
    val server = ref.server ?: return
    var info by remember { mutableStateOf<ServerInfo?>(null) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var probeRun by remember { mutableIntStateOf(0) }

    var mode by rememberSaveable { mutableStateOf(Mode.LOGIN) }
    var identity by rememberSaveable { mutableStateOf(ref.account.orEmpty()) }
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(probeRun) {
        probeError = null
        try {
            val i = session.client(server).probe()
            info = i
            if (!i.hasUsers) mode = Mode.REGISTER
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            probeError = e.message
        }
    }

    fun submit() {
        if (busy) return
        if (mode == Mode.REGISTER) {
            val problem = Validation.username(username) ?: Validation.password(password)
            if (problem != null) { error = problem; return }
        }
        error = null
        busy = true
        scope.launch {
            try {
                val api = session.client(server)
                val (token, user) = if (mode == Mode.LOGIN) api.login(identity.trim(), password)
                else api.register(username.trim(), password, email.trim(), displayName.trim())
                session.signIn(server, token, user)
                password = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                error = if (e.status == 401 && mode == Mode.LOGIN) L10n.s.wrongCredentials else e.message
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.canvas).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { catalog.open(null) }) { Icon(Icons.Outlined.Book, contentDescription = s.libraries, tint = c.ink2) }
            Text(ref.name, style = SerifTitle, fontSize = 18.sp, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IvyLeaf(68.dp)
                Text(info?.siteName?.takeIf { it.isNotBlank() } ?: s.appName, style = SerifTitle, fontSize = 26.sp, color = c.ink)
                Text(s.slogan, color = c.muted, fontSize = 14.sp)
                notice?.takeIf { mode == Mode.LOGIN }?.let {
                    Surface(color = c.warnSoft, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(it, color = c.warnInk, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                    }
                }
                val current = info
                if (current == null) {
                    if (probeError != null) {
                        Text(s.cannotConnect, fontWeight = FontWeight.SemiBold, color = c.ink)
                        Text(probeError ?: "", color = c.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                        InkButton(s.retry, onClick = { probeRun++ })
                    } else {
                        CircularProgressIndicator(color = c.muted)
                    }
                    return@Column
                }
                if (current.canRegister && current.hasUsers) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = mode == Mode.LOGIN, onClick = { mode = Mode.LOGIN; error = null },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(s.login) }
                        SegmentedButton(selected = mode == Mode.REGISTER, onClick = { mode = Mode.REGISTER; error = null },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(s.register) }
                    }
                }
                AccountFields(
                    login = mode == Mode.LOGIN,
                    identity = identity, onIdentity = { identity = it },
                    username = username, onUsername = { username = it },
                    email = email, onEmail = { email = it },
                    displayName = displayName, onDisplayName = { displayName = it },
                    password = password, onPassword = { password = it },
                    onSubmit = ::submit,
                )
                when {
                    error != null -> Text(error ?: "", color = c.danger, fontSize = 13.sp)
                    !current.hasUsers -> Caption(s.firstAdmin)
                    server.isInsecure -> Text(s.httpWarning, color = c.warn, fontSize = 13.sp)
                }
                val canSubmit = if (mode == Mode.LOGIN) identity.isNotEmpty() && password.isNotEmpty() else username.isNotEmpty() && password.isNotEmpty()
                InkButton(if (mode == Mode.LOGIN) s.login else s.createAccount, onClick = ::submit, enabled = canSubmit, busy = busy,
                    modifier = Modifier.fillMaxWidth().height(44.dp))
                Text(server.toString(), color = c.faint, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AccountFields(
    login: Boolean,
    identity: String, onIdentity: (String) -> Unit,
    username: String, onUsername: (String) -> Unit,
    email: String, onEmail: (String) -> Unit,
    displayName: String?, onDisplayName: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val s = LocalStrings.current
    val plain = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (login) {
            OutlinedTextField(identity, onIdentity, label = { Text(s.usernameOrEmail) }, singleLine = true, keyboardOptions = plain,
                modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(username, onUsername, label = { Text(s.username) }, singleLine = true, keyboardOptions = plain,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, onEmail, label = { Text(s.emailOptional) }, singleLine = true,
                keyboardOptions = plain.copy(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            if (displayName != null) {
                OutlinedTextField(displayName, onDisplayName, label = { Text(s.displayNameOptional) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
            }
        }
        OutlinedTextField(
            password, onPassword,
            label = { Text(if (login) s.password else s.passwordMin8) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FormCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Ivy.colors.surface, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(modifier)) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}
