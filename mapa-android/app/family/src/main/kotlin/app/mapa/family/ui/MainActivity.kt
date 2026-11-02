package app.mapa.family.ui

import android.Manifest
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.mapa.core.family.ParentCard
import app.mapa.core.family.ParentCards
import app.mapa.core.language.Clock
import app.mapa.core.language.Lang
import app.mapa.core.language.Phrases
import app.mapa.core.wire.TagWire
import app.mapa.core.cloud.AskAnswer
import app.mapa.core.cloud.AskMapa
import app.mapa.core.cloud.CheckIn
import java.util.Locale
import java.time.format.DateTimeFormatter
import app.mapa.core.cloud.TagProposal
import app.mapa.family.AskContext
import app.mapa.family.CloudAi
import app.mapa.family.FamilyNotes
import app.mapa.family.FamilyRepo
import app.mapa.core.language.Note
import app.mapa.core.wire.NoteFactsWire
import androidx.compose.runtime.produceState
import app.mapa.family.PairResult
import app.mapa.routine.LifeTagKind
import app.mapa.routine.RoutineState
import app.mapa.shared.Backend
import app.mapa.shared.ui.AiSpark
import app.mapa.shared.ui.DayArc
import app.mapa.shared.ui.Disclaimer
import app.mapa.shared.ui.Group
import app.mapa.shared.ui.GroupDivider
import app.mapa.shared.ui.GroupRow
import app.mapa.shared.ui.Mapa
import app.mapa.shared.ui.MapaTheme
import app.mapa.shared.ui.SampleDemoRole
import app.mapa.shared.ui.SampleDemoScreen
import app.mapa.shared.ui.ParentCardView
import app.mapa.shared.ui.Eyebrow
import app.mapa.shared.ui.PrimaryButton
import app.mapa.shared.ui.SecondaryButton
import app.mapa.shared.ui.SectionTitle
import app.mapa.shared.ui.Space
import app.mapa.shared.ui.TopBar
import app.mapa.shared.ui.Wordmark
import app.mapa.shared.ui.cardLabels
import app.mapa.shared.ui.dayPart
import app.mapa.shared.ui.stateColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private var demoMode by mutableStateOf(false)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        demoMode = isSampleDemoIntent(intent)
        intent.getStringExtra(app.mapa.family.Alerts.EXTRA_PID)?.let { openFromAlert.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(app.mapa.family.Alerts.EXTRA_PID)?.let { openFromAlert.value = it }
        enableEdgeToEdge()
        demoMode = isSampleDemoIntent(intent)
        // Every screen applies safeDrawingPadding so headers stay clear of the status bar.
        setContent {
            MapaTheme(dark = debugTheme() ?: androidx.compose.foundation.isSystemInDarkTheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (demoMode) SampleDemoScreen(SampleDemoRole.FAMILY) else FamilyRoot()
                }
            }
        }
    }

    private fun isSampleDemoIntent(intent: android.content.Intent?): Boolean =
        app.mapa.family.BuildConfig.DEBUG && intent?.data?.let {
            it.scheme == "mapa" && it.host == "demo" && it.path == "/sample"
        } == true
}

/** Parent to open because an alert was tapped. */
private val openFromAlert = mutableStateOf<String?>(null)

private fun savedLang(ctx: Context) = Lang.of(ctx.getSharedPreferences("mapa_family", Context.MODE_PRIVATE).getString("lang", "en"))
private fun saveLang(ctx: Context, l: Lang) = ctx.getSharedPreferences("mapa_family", Context.MODE_PRIVATE).edit().putString("lang", l.code).apply()

@Composable
private fun FamilyRoot() {
    val ctx = LocalContext.current
    var lang by remember { mutableStateOf(savedLang(ctx)) }
    var signedIn by remember { mutableStateOf(FamilyRepo.uid != null) }
    var openParent by rememberSaveable { mutableStateOf<String?>(null) }
    val fromAlert = openFromAlert.value
    LaunchedEffect(fromAlert) { if (fromAlert != null) { openParent = fromAlert; openFromAlert.value = null } }
    var adding by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var codeEntry by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    val text = FamilyText(lang)
    val toggleLang = { lang = if (lang == Lang.EN) Lang.HI else Lang.EN; saveLang(ctx, lang) }

    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(signedIn) {
        if (signedIn) {
            FamilyRepo.onSignedIn(ctx)
            if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    when {
        !signedIn -> SignInScreen(text, toggleLang) { signedIn = true }
        settings -> {
            BackHandler { settings = false }
            SettingsScreen(text, toggleLang, onBack = { settings = false }, onSignOut = { settings = false; FamilyRepo.signOut(ctx); signedIn = false })
        }
        adding -> {
            BackHandler { adding = false }
            AddParentScreen(text, onBack = { adding = false }, onHaveCode = { codeEntry = true }, onDone = { pid -> adding = false; openParent = pid })
            if (codeEntry) PairDialog(text) { codeEntry = false; adding = false }
        }
        openParent != null -> {
            BackHandler { openParent = null }
            DetailScreen(openParent!!, text, onBack = { openParent = null }, onHistory = { focus = openParent; tab = Tab.HISTORY; openParent = null })
        }
        else -> {
            // The three top-level places share the Stitch tab bar; detail, set-up and settings do not.
            val pids by remember { FamilyRepo.links() }.collectAsState(initial = emptyList())
            val chosen = focus?.takeIf { it in pids } ?: pids.firstOrNull()
            if (tab != Tab.TODAY) BackHandler { tab = Tab.TODAY }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.TODAY -> HomeScreen(text, onOpen = { openParent = it }, onAdd = { adding = true }, onSettings = { settings = true })
                    Tab.HISTORY -> HistoryScreen(pids, chosen, text) { focus = it }
                    Tab.RHYTHM -> RhythmScreen(pids, chosen, text) { focus = it }
                }
                if (pids.isNotEmpty()) TabBar(tab, text, { tab = it }, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** Shared page frame: background, system-bar insets, a top bar and a scrolling body with the page gutter. */
@Composable
internal fun Screen(topBar: @Composable () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        topBar()
        Column(Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.xxl), content = content)
    }
}

@Composable
private fun LangButton(text: FamilyText, toggleLang: () -> Unit) {
    TextButton(onClick = toggleLang, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text.language, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SignInScreen(text: FamilyText, toggleLang: () -> Unit, onDone: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var useEmail by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = androidx.compose.ui.platform.LocalContext.current
    fun google() {
        busy = true; error = null
        scope.launch {
            runCatching { FamilyRepo.signInWithGoogle(activity) }
                .onSuccess { onDone() }
                .onFailure { error = text.googleFailed }
            busy = false
        }
    }
    fun go(create: Boolean) {
        busy = true; error = null
        scope.launch {
            runCatching { if (create) FamilyRepo.createAccount(email, password) else FamilyRepo.signIn(email, password) }
                .onSuccess { onDone() }
                .onFailure { error = text.signInFailed }
            busy = false
        }
    }
    Screen(topBar = { TopBar(start = { Wordmark() }, end = { LangButton(text, toggleLang) }) }) {
        Spacer(Modifier.height(Space.xl))
        DayArc(DemoDay, text.dialExplainer, { m -> app.mapa.core.language.Clock.time(m, text.lang) })
        Spacer(Modifier.height(Space.xl))
        Text(text.signInTitle, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.m))
        Text(text.signInBody, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.xl))
        // One action: Google. The first time it also creates the account. Email stays as a small fallback.
        if (!useEmail) {
            PrimaryButton(text.withGoogle, { google() }, enabled = !busy)
            Spacer(Modifier.height(Space.s))
            Text(text.googleNote, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Notice(it) }
            Spacer(Modifier.height(Space.l))
            TextButton(onClick = { useEmail = true; error = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text.withEmail, style = MaterialTheme.typography.labelLarge)
            }
            if (Backend.usingEmulator) Disclaimer(text.emulator)
            return@Screen
        }
        OutlinedTextField(
            email, { email = it.trim() }, label = { Text(text.email) }, singleLine = true,
            shape = MaterialTheme.shapes.small, textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Space.m))
        OutlinedTextField(
            password, { password = it }, label = { Text(text.password) }, singleLine = true,
            shape = MaterialTheme.shapes.small, textStyle = MaterialTheme.typography.bodyLarge,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Notice(it) }
        val ready = !busy && email.contains('@') && password.length >= 6
        Spacer(Modifier.height(Space.xl))
        PrimaryButton(text.signIn, { go(false) }, enabled = ready)
        Spacer(Modifier.height(Space.xl))
        Text(text.orDivider, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.s))
        SecondaryButton(text.createAccount, { go(true) }, enabled = ready)
        if (Backend.usingEmulator) Disclaimer(text.emulator)
    }
}

/** Re-renders the cards every minute so "updated N min ago" and staleness stay honest without new data. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    return now
}

@Composable
private fun HomeScreen(text: FamilyText, onOpen: (String) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    val links by remember { FamilyRepo.links() }.collectAsState(initial = null)
    Screen(topBar = { TopBar(start = { Wordmark() }, end = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, text.settings) } }) }) {
        val pids = links
        if (pids != null && pids.isEmpty()) {
            Spacer(Modifier.height(Space.xxl))
            DayArc(EmptyDay, text.noParents, { m -> app.mapa.core.language.Clock.time(m, text.lang) })
            Spacer(Modifier.height(Space.xxl))
            Text(text.noParents, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Space.s))
            Text(text.noParentsBody, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.xl))
            PrimaryButton(text.addParent, onAdd)
        } else if (pids != null) {
            Spacer(Modifier.height(Space.xl))
            Eyebrow(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag(text.lang.code)).format(LocalDate.now()))
            Spacer(Modifier.height(Space.s))
            Text(text.greeting(dayPart()), style = MaterialTheme.typography.displayMedium, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Space.xxl))
            Column(verticalArrangement = Arrangement.spacedBy(Space.xl)) {
                pids.forEach { pid -> ParentSummary(pid, text, onClick = { onOpen(pid) }) }
            }
            TextButton(onClick = onAdd, modifier = Modifier.padding(top = Space.l).heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(Space.s))
                Text(text.addParent, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(Space.xxl))
        Text(text.notMedical, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (Backend.usingEmulator) Text(text.emulator, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
        if (pids?.isNotEmpty() == true) Spacer(Modifier.height(TabBarSpace))
    }
}

/** Language, alerts and account: everything that is not about a parent's day. */
@Composable
private fun SettingsScreen(text: FamilyText, toggleLang: () -> Unit, onBack: () -> Unit, onSignOut: () -> Unit) {
    val ctx = LocalContext.current
    Screen(topBar = {
        TopBar(start = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, text.back) }
            Text(text.settings, style = MaterialTheme.typography.titleLarge)
        })
    }) {
        SectionTitle(text.languageTitle)
        Group {
            GroupRow(Modifier.clickable { toggleLang() }, trailing = { Text(text.language, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }) {
                Text(text.languageNow, style = MaterialTheme.typography.titleSmall)
            }
        }
        SectionTitle(text.alertsTitle, sub = text.alertsBody)
        Group {
            GroupRow(Modifier.clickable {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }, leading = { Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) {
                Text(text.alertsTitle, style = MaterialTheme.typography.titleSmall)
            }
        }
        if (CloudAi.available) {
            var metaOn by remember { mutableStateOf(CloudAi.enabled) }
            SectionTitle(text.metaAiTitle, sub = text.metaAiBody)
            Group {
                GroupRow(
                    Modifier.clickable { metaOn = !metaOn; CloudAi.setEnabled(ctx, metaOn) },
                    trailing = { Switch(checked = metaOn, onCheckedChange = { metaOn = it; CloudAi.setEnabled(ctx, it) }) },
                ) {
                    Text(text.metaAiSwitch, style = MaterialTheme.typography.titleSmall)
                }
            }
            if (metaOn) {
                var noteLang by remember { mutableStateOf(CloudAi.noteLang) }
                SectionTitle(text.noteLangTitle, sub = text.noteLangBody)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf<app.mapa.core.cloud.NoteLang?>(null) .plus(app.mapa.core.cloud.NoteLang.entries).forEach { l ->
                        androidx.compose.material3.FilterChip(
                            selected = noteLang == l,
                            onClick = { noteLang = l; CloudAi.setNoteLang(ctx, l) },
                            label = { Text(l?.native ?: text.noteLangApp, style = MaterialTheme.typography.titleSmall) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }
        SectionTitle(text.accountTitle)
        Group {
            GroupRow { Text(FamilyRepo.email ?: "", style = MaterialTheme.typography.bodyLarge) }
            GroupDivider()
            GroupRow(Modifier.clickable(onClick = onSignOut)) {
                Text(text.signOut, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Disclaimer(text.notMedical)
    }
}

/** Everything that is not about the day lives here, out of the way. */
@Composable
private fun Menu(text: FamilyText, toggleLang: (() -> Unit)? = null, signOut: (() -> Unit)? = null, leave: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, text.moreOptions) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            toggleLang?.let { DropdownMenuItem(text = { Text(text.language, style = MaterialTheme.typography.bodyLarge) }, onClick = { open = false; it() }) }
            signOut?.let { DropdownMenuItem(text = { Text(text.signOut, style = MaterialTheme.typography.bodyLarge) }, onClick = { open = false; it() }) }
            leave?.let { DropdownMenuItem(text = { Text(text.leave, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }, onClick = { open = false; it() }) }
        }
    }
}

@Composable
private fun ParentSummary(pid: String, text: FamilyText, expanded: Boolean = false, onClick: (() -> Unit)? = null, withCall: Boolean = false) {
    val profile by remember(pid) { FamilyRepo.profile(pid) }.collectAsState(initial = null)
    val status by remember(pid) { FamilyRepo.status(pid) }.collectAsState(initial = null)
    val zone = FamilyRepo.zone(profile)
    val day by remember(pid, zone, LocalDate.now(zone)) { FamilyRepo.today(pid, zone) }.collectAsState(initial = null)
    val now = rememberNow()
    val p = profile ?: return
    val person = FamilyRepo.person(p)
    val built = ParentCards.build(person, status, day, now, text.lang, p.showPaused)
    // Option C: this phone writes the note (Gemini Nano, or the template) in this member's language.
    val facts = status?.noteFacts?.let { NoteFactsWire.decode(it, person, text.lang) }
    val note by produceState<Note?>(null, facts) { value = facts?.let { FamilyNotes.write(it) } }
    val card = note?.takeIf { built.note != null }?.let { built.copy(note = it.text, noteByAi = it.byAi) } ?: built
    val ctx = LocalContext.current
    var number by remember(pid) { mutableStateOf(if (withCall) FamilyRepo.phone(ctx, pid) else null) }
    // Pick from this member's contacts; the Phone.CONTENT_URI picker grants a one-time read, no permission.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { FamilyRepo.savePhone(ctx, pid, it); number = it }
        }
    }
    val worry = card.state == RoutineState.UNUSUAL_INACTIVITY || card.state == RoutineState.MONITORING_UNAVAILABLE
    ParentCardView(p.name, card, labels = cardLabels(text.lang, if (note?.let { CloudAi.isCloud(it) } == true) text.aiLabelCloud else text.aiLabel), expanded = expanded, onClick = onClick, footer = {
        if (!withCall) return@ParentCardView
        val n = number
        if (n != null) CallButton(text.call(p.name), n, prominent = worry)
        else TextButton(
            onClick = { pick.launch(android.content.Intent(android.content.Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
            modifier = Modifier.padding(top = Space.l).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Call, null, Modifier.size(20.dp))
            Spacer(Modifier.width(Space.s))
            Text(text.addNumber(p.name), style = MaterialTheme.typography.labelLarge)
        }
    })
}

@Composable
private fun PairDialog(text: FamilyText, onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text.addParent, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(text.noParentsBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    code, { code = it.uppercase().take(7) }, label = { Text(text.codeLabel) }, singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 3.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    name, { name = it }, label = { Text(text.yourName) }, singleLine = true,
                    shape = MaterialTheme.shapes.small, textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            Button(shape = MaterialTheme.shapes.small, enabled = !busy && code.isNotBlank() && name.isNotBlank(), onClick = {
                busy = true; error = null
                scope.launch {
                    val r = FamilyRepo.pair(code, name)
                    busy = false
                    if (r == PairResult.OK) onClose() else error = text.pairError(r)
                }
            }) { Text(text.connect, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(text.cancel, style = MaterialTheme.typography.labelLarge) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailScreen(pid: String, text: FamilyText, onBack: () -> Unit, onHistory: () -> Unit) {
    val profile by remember(pid) { FamilyRepo.profile(pid) }.collectAsState(initial = null)
    val events by remember(pid) { FamilyRepo.events(pid) }.collectAsState(initial = emptyList())
    val trend by remember(pid) { FamilyRepo.latestTrend(pid) }.collectAsState(initial = null)
    val routine by remember(pid) { FamilyRepo.routine(pid) }.collectAsState(initial = null)
    val tags by remember(pid) { FamilyRepo.tags(pid) }.collectAsState(initial = emptyList())
    var allEvents by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ph = Phrases(text.lang)
    val person = FamilyRepo.person(profile)
    val zone = FamilyRepo.zone(profile)
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val taggedToday = tags.filter { it.startMs <= todayStart && it.endMs > todayStart }.map { it.kind }.toSet()

    Screen(topBar = {
        TopBar(
            start = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, text.back) } },
            end = { Menu(text, leave = { scope.launch { runCatching { FamilyRepo.leave(pid) }; onBack() } }) },
        )
    }) {
        Spacer(Modifier.height(Space.s))
        ParentSummary(pid, text, expanded = true, withCall = true)
        // Stitch order: Call, then "Mark today as different" right under it.
        Spacer(Modifier.height(Space.m))
        if (taggedToday.isNotEmpty()) {
            Text(text.tagged + ": " + taggedToday.joinToString(", ") { text.tag(it) }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (!tagging) {
            SecondaryButton(text.markDifferent, { tagging = true })
        } else {
            Text(text.differentDayBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Space.m))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                TagWire.PICKABLE.forEach { kind: LifeTagKind ->
                    FilterChip(
                        selected = false,
                        onClick = { FamilyRepo.tagToday(pid, zone, kind, FamilyRepo.firstName); tagging = false },
                        label = { Text(text.tag(kind), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = Space.m)) },
                        shape = CircleShape,
                    )
                }
            }
        }


        // The model asks; a person decides. The one thing on this screen that wants an answer.
        routine?.takeIf { it.answer == null }?.let { r ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(top = Space.xxl),
            ) {
                Column(Modifier.padding(Space.gutter)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AiSpark(size = 16.dp)
                        Spacer(Modifier.width(Space.s))
                        Text(text.routineTitle, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(Space.m))
                    Text(ph.routineQuestion(person, FamilyRepo.question(r)), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Space.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        PrimaryButton(text.yes, { FamilyRepo.answerRoutine(pid, true) }, Modifier.weight(1f))
                        SecondaryButton(text.no, { FamilyRepo.answerRoutine(pid, false) }, Modifier.weight(1f))
                    }
                }
            }
        }
        routine?.takeIf { it.answer != null && it.appliedAtMs == null }?.let {
            Text(text.answered, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Space.l))
        }

        if (events.isNotEmpty()) {
            SectionTitle(text.details)
            val shown = if (allEvents) events else events.take(2)
            shown.forEachIndexed { i, (_, e) ->
                val local = Instant.ofEpochMilli(e.atMs).atZone(zone)
                val minute = local.hour * 60 + local.minute
                val day = if (local.toLocalDate() == LocalDate.now(zone)) "" else Clock.weekday(local.dayOfWeek.value - 1, text.lang) + ", "
                TimelineItem(
                    time = day + Clock.time(minute, text.lang),
                    state = ph.stateLabel(e.to),
                    body = e.text.ifBlank { ph.detail(e.reason, person, e.t) },
                    color = stateColor(e.to),
                    last = i == shown.lastIndex,
                )
            }
            if (!allEvents && events.size > 2) {
                TextButton(onClick = { allEvents = true }) { Text(text.showAll, style = MaterialTheme.typography.labelLarge) }
            }
        }

        trend?.let { r ->
            SectionTitle(text.thisWeek)
            ph.trend(person, r).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = Space.s))
            }
        }

        // Ask MaPa lives here, on the parent's own screen.
        SectionTitle(text.askTitle)
        AskSection(pid, text)

        // Stitch: "Past days and questions" leads to the History tab for this parent.
        TextButton(onClick = onHistory, modifier = Modifier.padding(top = Space.xl).heightIn(min = 48.dp).align(Alignment.CenterHorizontally)) {
            Text(text.pastDays, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(Space.s))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
        }

        if (CloudAi.available && CloudAi.enabled) {
            var story by remember(pid) { mutableStateOf("") }
            var busy by remember(pid) { mutableStateOf(false) }
            var failed by remember(pid) { mutableStateOf(false) }
            var proposal by remember(pid) { mutableStateOf<TagProposal?>(null) }
            Spacer(Modifier.height(Space.xl))
            Text(text.describeTitle, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = story,
                onValueChange = { story = it.take(CheckIn.MAX_INPUT_CHARS); failed = false },
                placeholder = { Text(text.describeHint(person.subj.replaceFirstChar { it.uppercase() })) },
                modifier = Modifier.fillMaxWidth().padding(top = Space.s),
            )
            Text(text.sentToMeta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Space.s))
            SecondaryButton(
                if (busy) text.reading else text.askMeta,
                { scope.launch { busy = true; failed = false; proposal = CloudAi.propose(story, zone); failed = proposal == null; busy = false } },
                enabled = !busy && story.isNotBlank(),
            )
            if (failed) Text(text.noTagFound, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.m))
            proposal?.let { p ->
                val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.forLanguageTag(text.lang.code))
                val range = Instant.ofEpochMilli(p.startMs).atZone(zone).format(fmt) + " – " + Instant.ofEpochMilli(p.endMs).atZone(zone).format(fmt)
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(top = Space.l)) {
                    Column(Modifier.padding(Space.gutter)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AiSpark(size = 16.dp)
                            Spacer(Modifier.width(Space.s))
                            Text(text.proposalTitle(p.kind, range), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(Space.l))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            PrimaryButton(text.addTag, {
                                FamilyRepo.addTag(pid, zone, p, FamilyRepo.firstName)
                                proposal = null; story = ""
                            }, Modifier.weight(1f))
                            SecondaryButton(text.cancel, { proposal = null }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }


        Spacer(Modifier.height(Space.xxl))
        Text(text.notMedical, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The one useful action when something looks off: call. Large only when the state calls for it. */
@Composable
private fun CallButton(label: String, number: String, prominent: Boolean) {
    val ctx = LocalContext.current
    val dial = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))) }
    Spacer(Modifier.height(Space.xl))
    if (prominent) PrimaryButton(label, dial, leading = { Icon(Icons.Filled.Call, null, Modifier.size(20.dp)) })
    else SecondaryButton(label, dial, leading = { Icon(Icons.Filled.Call, null, Modifier.size(20.dp)) })
}

/** One change in the "Why did this change?" timeline: a state-coloured dot on a rail, newest first. */
@Composable
internal fun TimelineItem(time: String, state: String, body: String, color: androidx.compose.ui.graphics.Color, last: Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 6.dp).size(12.dp).background(color, CircleShape))
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.padding(bottom = Space.xl)) {
            Text("$time  $state", style = MaterialTheme.typography.titleSmall, color = color)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Notice(message: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(top = Space.m),
    ) {
        Row(Modifier.padding(Space.l), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(Space.m))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/** An illustrative rhythm for the sign-in screen: a usual band with nothing recorded yet. */
private val DemoDay = ParentCard.Chart(
    active = List(96) { b -> if (b in 26..44 && b % 3 != 0) 1 else 0 },
    usual = List(96) { b ->
        val m = b.toDouble()
        (0.85 * kotlin.math.exp(-(m - 32) * (m - 32) / 60) + 0.55 * kotlin.math.exp(-(m - 54) * (m - 54) / 30) + 0.9 * kotlin.math.exp(-(m - 80) * (m - 80) / 80)).coerceAtMost(1.0)
    },
    nowBin = 44,
    expectedByBin = 31,
)

/** An empty day for the no-parents state. */
private val EmptyDay = ParentCard.Chart(active = List(96) { 0 }, usual = List(96) { 0.0 }, nowBin = 0, expectedByBin = null)

/** Debug builds only: `--es mapa.theme light|dark` previews a theme without changing the phone's setting. */
private fun ComponentActivity.debugTheme(): Boolean? =
    if (!app.mapa.family.BuildConfig.DEBUG) null
    else when (intent?.getStringExtra("mapa.theme")) { "light" -> false; "dark" -> true; else -> null }
