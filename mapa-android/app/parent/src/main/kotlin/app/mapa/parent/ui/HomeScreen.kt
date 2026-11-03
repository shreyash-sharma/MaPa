package app.mapa.parent.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.mapa.core.family.ParentCards
import app.mapa.core.pairing.InviteCodes
import app.mapa.core.wire.DayWire
import app.mapa.core.wire.StatusWire
import app.mapa.core.wire.TagDoc
import app.mapa.parent.BuildConfig
import app.mapa.parent.Prefs
import app.mapa.parent.cloud.ParentCloud
import app.mapa.parent.collect.Device
import app.mapa.parent.collect.OemSettings
import app.mapa.parent.rhythm.ModelStore
import app.mapa.parent.collect.Scheduler
import app.mapa.parent.collect.UsageProbe
import app.mapa.parent.rhythm.JsonMaps
import app.mapa.parent.rhythm.ParentRuntime
import app.mapa.routine.LifeTagKind
import app.mapa.shared.Backend
import app.mapa.shared.Db
import app.mapa.shared.ui.AiSpark
import app.mapa.shared.ui.EmblemHalo
import app.mapa.shared.ui.accentNames
import app.mapa.shared.ui.Group
import app.mapa.shared.ui.GroupDivider
import app.mapa.shared.ui.GroupRow
import app.mapa.shared.ui.Initial
import app.mapa.shared.ui.Mapa
import app.mapa.shared.ui.ParentCardView
import app.mapa.shared.ui.PrimaryButton
import app.mapa.shared.ui.SecondaryButton
import app.mapa.shared.ui.SectionTitle
import app.mapa.shared.ui.Space
import app.mapa.shared.ui.TopBar
import app.mapa.shared.ui.Wordmark
import app.mapa.shared.ui.cardLabels
import app.mapa.shared.ui.dayPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private data class Member(val uid: String, val name: String)

@Composable
fun HomeScreen(text: ParentText, onDeleted: () -> Unit, judgeDemo: Boolean = false) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(prefs.paused) }
    var showPaused by remember { mutableStateOf(prefs.showPaused) }
    var members by remember { mutableStateOf(emptyList<Member>()) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var taggedToday by remember { mutableStateOf<LifeTagKind?>(null) }
    val pid = remember(refresh, judgeDemo) { if (judgeDemo) null else ParentCloud.parentId }
    val visibleMembers = if (judgeDemo && prefs.consented) listOf(Member("local-family", "Riya")) else members

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            refresh++
        }
    }
    DisposableEffect(pid) {
        val reg = if (judgeDemo) null else pid?.let { id ->
            Db.members(id).addSnapshotListener { snap, _ ->
                members = snap?.documents?.map { Member(it.id, it.getString("name") ?: "Family member") }.orEmpty()
            }
        }
        onDispose { reg?.remove() }
    }

    fun runTick() = scope.launch {
        if (!judgeDemo) withContext(Dispatchers.IO) { runCatching { ParentRuntime.tick(ctx, "manual") } }
        refresh++
    }

    val usageOk = remember(refresh) { UsageProbe.hasAccess(ctx) }
    var settings by remember { mutableStateOf(false) }
    val togglePause = {
        paused = !paused
        prefs.paused = paused
        if (!judgeDemo) {
            scope.launch(Dispatchers.IO) { runCatching { ParentRuntime.publishPause(ctx) } }
            if (!paused) Scheduler.tickAfterBinCloses(ctx)
        }
        refresh++
        Unit
    }
    val invite = {
        scope.launch {
            val code = withContext(Dispatchers.IO) { runCatching { ParentCloud.createInvite(pid!!) }.getOrNull() }
            if (code != null) {
                prefs.inviteCodes = prefs.inviteCodes + code
                inviteCode = code
            } else message = text.offlineStart
        }
        Unit
    }
    val card = remember(refresh) {
        val status = JsonMaps.fromJson(prefs.lastStatusJson)?.let { StatusWire.decode(it) }
        val day = JsonMaps.fromJson(prefs.lastDayJson)?.let { DayWire.decode(it) }
        status?.let { ParentCards.build(prefs.person, it, day, System.currentTimeMillis(), prefs.lang, prefs.showPaused) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        if (!settings) {
            // Papa's whole home: who sees, and one big Pause. Everything else is under "More".
            TopBar(start = { Wordmark() })
            Column(Modifier.padding(horizontal = Space.gutter)) {
                // One calm picture first: the emblem in its halo. Grey and still while sharing is paused.
                if (!paused) EmblemHalo(Modifier.align(Alignment.CenterHorizontally), size = 72.dp)
                else Spacer(Modifier.height(Space.xl))
                Text(text.greeting(dayPart(), prefs.name), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Space.l))
                Text(
                    if (paused) androidx.compose.ui.text.AnnotatedString(text.pausedHome)
                    else accentNames(text.sharingWith(visibleMembers.map { it.name }), visibleMembers.map { it.name }, Mapa.colors.accent),
                    style = MaterialTheme.typography.displaySmall,
                    color = if (paused) Mapa.colors.paused else MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(Space.m))
                Text(
                    if (!paused) text.onlyWhen else if (showPaused) text.pausedBody else text.pausedHiddenBody,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (pid == null && !judgeDemo) Notice(text.offlineStart)
                Spacer(Modifier.height(Space.xxl))
                Spacer(Modifier.height(Space.xxl))
                if (paused) PrimaryButton(text.resume, togglePause, Modifier.heightIn(min = 72.dp))
                else SecondaryButton(text.pause, togglePause, Modifier.heightIn(min = 72.dp))
                Spacer(Modifier.height(Space.l))
                TextButton(onClick = { settings = true }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 56.dp)) {
                    Text(text.more, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(Space.xxl))
            }
        } else {
            BackHandler { settings = false }
            TopBar(start = {
                IconButton(onClick = { settings = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, text.back) }
                Text(text.more, style = MaterialTheme.typography.titleLarge)
            })
            Column(Modifier.padding(horizontal = Space.gutter)) {
                SectionTitle(text.familyTitle, sub = text.familySub)
                Group {
                    if (visibleMembers.isEmpty()) GroupRow { Text(text.noFamily, style = MaterialTheme.typography.bodyLarge) }
                    visibleMembers.forEachIndexed { i, m ->
                        if (i > 0) GroupDivider()
                        GroupRow(
                            leading = { Initial(m.name) },
                            trailing = {
                                TextButton(onClick = { pid?.let { ParentCloud.removeMember(it, m.uid) } }) {
                                    Text(text.remove, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                                }
                            },
                        ) { Text(m.name, style = MaterialTheme.typography.titleMedium) }
                    }
                }
                TextButton(onClick = invite, enabled = pid != null, modifier = Modifier.padding(top = Space.s).heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(Space.s))
                    Text(text.invite, style = MaterialTheme.typography.labelLarge)
                }

                SectionTitle(text.sharingTitle)
                Group {
                    GroupRow(
                        Modifier.toggleable(value = !paused, role = SemanticsRole.Switch, onValueChange = { togglePause() }),
                        trailing = { Switch(checked = !paused, onCheckedChange = null) },
                    ) { Text(if (paused) text.sharingPaused else text.sharingOn, style = MaterialTheme.typography.titleSmall) }
                    GroupDivider()
                    GroupRow(
                        Modifier.toggleable(value = showPaused, role = SemanticsRole.Switch, onValueChange = {
                            showPaused = it
                            prefs.showPaused = it
                            if (!judgeDemo) pid?.let { id -> scope.launch(Dispatchers.IO) { runCatching { ParentCloud.saveProfile(id, prefs.profile(), prefs.consentAt) } } }
                        }),
                        trailing = { Switch(checked = showPaused, onCheckedChange = null) },
                    ) { Text(text.showPaused, style = MaterialTheme.typography.titleSmall) }
                    GroupDivider()
                    GroupRow(
                        Modifier.clickable { runTick() },
                        leading = { Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    ) { Text(text.updateNow, style = MaterialTheme.typography.titleSmall) }
                }

                Setup(text, refresh)

                SectionTitle(text.hoodTitle, sub = text.hoodSub)
                Group {
                    val hood = remember(refresh) { ModelStore.factory(ctx) }
                    GroupRow { Column { Text(text.hoodNet, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(text.hoodNetValue + " (" + hood.modelId + ")", style = MaterialTheme.typography.bodyLarge) } }
                    GroupDivider()
                    GroupRow { Column { Text(text.hoodRuns, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(ModelStore.runtime, style = MaterialTheme.typography.bodyLarge) } }
                    GroupDivider()
                    GroupRow { Column { Text(text.hoodSpeed, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(text.hoodSpeedValue(hood.stats.meanMicros, hood.stats.steps), style = MaterialTheme.typography.bodyLarge) } }
                }

                SectionTitle(text.privacyTitle)
                Group {
                    GroupRow(
                        Modifier.clickable(onClickLabel = text.deleteAll) { confirmDelete = true },
                        leading = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    ) { Text(text.deleteAll, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall) }
                }
                message?.let { Notice(it) }

                if (BuildConfig.DEBUG) DebugPanel(onChanged = { runTick() })
                Spacer(Modifier.height(Space.xxl))
            }
        }
    }

    inviteCode?.let { code ->
        AlertDialog(
            onDismissRequest = { inviteCode = null },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(text.inviteTitle, style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                    Surface(shape = MaterialTheme.shapes.small, color = Mapa.colors.sunk, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            InviteCodes.display(code),
                            style = MaterialTheme.typography.displayMedium.copy(letterSpacing = 4.sp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = Space.l, horizontal = Space.l),
                        )
                    }
                    Text(text.inviteBody, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = {
                Button(
                    shape = MaterialTheme.shapes.small,
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text.shareMessage(InviteCodes.display(code)))
                        ctx.startActivity(Intent.createChooser(send, text.shareCode))
                    },
                ) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.s))
                    Text(text.shareCode, style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = { TextButton(onClick = { inviteCode = null }) { Text(text.close, style = MaterialTheme.typography.labelLarge) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(text.deleteConfirmTitle, style = MaterialTheme.typography.headlineSmall) },
            text = { Text(text.deleteConfirmBody, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                Button(
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { pid?.let { ParentCloud.deleteEverything(it, prefs.inviteCodes) } }.isSuccess
                            }
                            if (ok) {
                                Scheduler.cancel(ctx)
                                withContext(Dispatchers.IO) { ParentRuntime.forget(ctx) }
                                prefs.clearAll()
                                onDeleted()
                            } else {
                                // Online data couldn't be removed yet: stop sharing now, let them retry.
                                prefs.paused = true
                                paused = true
                                message = text.deleteFailed
                            }
                        }
                    },
                ) { Text(text.deleteYes, style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(text.cancel, style = MaterialTheme.typography.labelLarge) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(options: List<Pair<LifeTagKind, String>>, selected: Set<LifeTagKind>, onPick: (LifeTagKind) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        options.forEach { (kind, label) ->
            val on = kind in selected
            FilterChip(
                selected = on,
                onClick = { onPick(kind) },
                label = { Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = Space.m)) },
                leadingIcon = if (on) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
                shape = CircleShape,
            )
        }
    }
}

@Composable
private fun Notice(message: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(top = Space.l),
    ) {
        Row(Modifier.padding(Space.l), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(Space.m))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun Setup(text: ParentText, refresh: Int) {
    val ctx = LocalContext.current
    val usage = remember(refresh) { UsageProbe.hasAccess(ctx) }
    val battery = remember(refresh) { Device.ignoringBatteryOptimizations(ctx) }
    SectionTitle(text.setupTitle)
    Group {
        SetupRow(text.usageAccess, if (usage) null else text.usageAccessWhy, usage, text.allow, text.done) {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        GroupDivider()
        SetupRow(text.battery, null, battery, text.allow, text.done) {
            runCatching {
                @Suppress("BatteryLife")
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        GroupDivider()
        SetupRow(text.autostart, null, null, text.open, text.done) { OemSettings.open(ctx) }
    }
}

/** [ok] null means MaPa can't tell (OEM autostart settings), so the action stays available. */
@Composable
private fun SetupRow(label: String, why: String?, ok: Boolean?, actionLabel: String, doneLabel: String, onClick: () -> Unit) {
    GroupRow(
        leading = {
            if (ok == true) Icon(Icons.Filled.CheckCircle, doneLabel, tint = Mapa.colors.active)
            else Icon(Icons.Filled.Info, null, tint = if (ok == false) Mapa.colors.unusual else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailing = if (ok == true) null else ({
            OutlinedButton(onClick, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        why?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DebugPanel(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    SectionTitle("Developer (debug build)", sub = if (Backend.usingEmulator) "Backend: Firebase Emulator Suite (demo-mapa)" else "Backend: Firebase project")
    SecondaryButton("Replace history with 21 sample days", {
        scope.launch {
            withContext(Dispatchers.IO) { ParentRuntime.loadSampleDays(ctx) }
            onChanged()
        }
    })
    // Day-one checks: the same parent with 0, 1, 3 or 7 days of history behind today.
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        listOf(0, 1, 3, 7).forEach { n ->
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { ParentRuntime.loadSampleDays(ctx, days = n) }
                    onChanged()
                }
            }) { Text("$n d") }
        }
    }
}

/** Tags today (local midnight to midnight) so the model leaves it out of training. */
private fun tagToday(ctx: android.content.Context, prefs: Prefs, kind: LifeTagKind) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val tag = TagDoc(
        UUID.randomUUID().toString(), kind,
        today.atStartOfDay(zone).toInstant().toEpochMilli(),
        today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        "parent", prefs.name,
    )
    ParentRuntime.host(ctx).addTag(tag.id, tag.toLifeTag(zone))
    ParentCloud.parentId?.let { ParentCloud.addTag(it, tag) }
}
