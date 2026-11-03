package app.mapa.shared.ui

import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mapa.core.family.ParentCard
import app.mapa.routine.RoutineState
import org.json.JSONArray
import org.json.JSONObject

enum class DemoRole { PHONE_OWNER, FAMILY }

private enum class DemoTab { TODAY, HISTORY, ALERTS, PEOPLE, SHARING, MORE }

private data class DemoDay(
    val dayNumber: Int,
    val dayOffset: Int,
    val weekday: String,
    val interactions: List<Int>,
    val observed: List<Boolean>,
    val charging: List<Boolean>,
    val battery: List<Int>,
    val interactionTotal: Int,
    val observedBins: Int,
    val chargingBins: Int,
    val firstUseBin: Int,
    val lastUseBin: Int,
    val batteryLast: Int,
) {
    val lastUseMinute: Int get() = lastUseBin * 15
    val firstUseMinute: Int get() = firstUseBin * 15
    val currentBattery: Int get() = batteryLast.coerceAtLeast(0)
    val currentCharging: Boolean get() = charging.lastOrNull() == true
}

private data class DemoAlert(
    val dayNumber: Int,
    val dayOffset: Int,
    val weekday: String,
    val alertBin: Int,
    val quietMinutes: Int,
    val activityResumedBin: Int?,
)

private data class DemoPerson(
    val name: String,
    val historyDays: Int,
    val usualUse: List<Double>,
    val days: List<DemoDay>,
    val alerts: List<DemoAlert>,
) {
    val today: DemoDay get() = days.last()
    val usualFirstUseMinute: Int get() {
        val values = days.map { it.firstUseMinute }.filter { it >= 0 }.sorted()
        return if (values.isEmpty()) 0 else values[values.size / 2]
    }
}

private fun JSONArray.ints() = List(length()) { getInt(it) }
private fun JSONArray.booleans() = List(length()) { getBoolean(it) }
private fun JSONArray.doubles() = List(length()) { getDouble(it) }

/** Identity-free Ma and Papa timelines extracted from the frozen final-v2 model-derived train subset. */
private fun loadDemoPeople(context: Context): List<DemoPerson> {
    val root = JSONObject(context.assets.open("demo/demo-history.json").bufferedReader().use { it.readText() })
    require(root.getString("provenance") == "model_derived" && root.getString("split") == "train")
    val people = root.getJSONArray("people")
    return List(people.length()) { index ->
        val person = people.getJSONObject(index)
        val dayRows = person.getJSONArray("days")
        val days = List(dayRows.length()) { dayIndex ->
            val row = dayRows.getJSONObject(dayIndex)
            val day = DemoDay(
                dayNumber = row.getInt("day_number"),
                dayOffset = row.getInt("day_offset"),
                weekday = row.getString("weekday"),
                interactions = row.getJSONArray("interactions").ints(),
                observed = row.getJSONArray("observed").booleans(),
                charging = row.getJSONArray("charging").booleans(),
                battery = row.getJSONArray("battery").ints(),
                interactionTotal = row.getInt("interaction_total"),
                observedBins = row.getInt("observed_bins"),
                chargingBins = row.getInt("charging_bins"),
                firstUseBin = row.getInt("first_use_bin"),
                lastUseBin = row.getInt("last_use_bin"),
                batteryLast = row.getInt("battery_last"),
            )
            require(day.interactions.size == 96 && day.observed.size == 96 && day.charging.size == 96)
            day
        }
        val alertRows = person.getJSONArray("alerts")
        val alerts = List(alertRows.length()) { alertIndex ->
            val row = alertRows.getJSONObject(alertIndex)
            DemoAlert(
                dayNumber = row.getInt("day_number"),
                dayOffset = row.getInt("day_offset"),
                weekday = row.getString("weekday"),
                alertBin = row.getInt("alert_bin"),
                quietMinutes = row.getInt("quiet_minutes"),
                activityResumedBin = if (row.isNull("activity_resumed_bin")) null else row.getInt("activity_resumed_bin"),
            )
        }
        DemoPerson(
            name = person.getString("name"),
            historyDays = person.getInt("history_days"),
            usualUse = person.getJSONArray("usual_use_probability").doubles(),
            days = days,
            alerts = alerts,
        )
    }
}

private fun demoCard(person: DemoPerson, day: DemoDay) = ParentCard(
    state = RoutineState.QUIET,
    stateLabel = "Quieter than usual",
    detail = "No phone use recorded since ${clockTime(day.lastUseMinute)}. ${person.name}'s median first-use time is ${clockTime(person.usualFirstUseMinute)}.",
    updatedAgo = "Updated 10:45 AM",
    stale = false,
    forecast = "Usual first phone use: around ${clockTime(person.usualFirstUseMinute)}.",
    note = "Phone activity cannot show what someone is doing. Quiet phone time has many ordinary explanations.",
    noteByAi = false,
    learning = null,
    modelLine = "MaPa Rhythm Hybrid",
    confidencePct = null,
    battery = day.currentBattery,
    charging = day.currentCharging,
    chart = ParentCard.Chart(
        active = List(96) { bin -> if (day.observed[bin] && day.interactions[bin] > 0) 1 else 0 },
        usual = person.usualUse,
        nowBin = 43,
        expectedByBin = (person.usualFirstUseMinute / 15).coerceAtLeast(0),
    ),
    lastUseMinute = day.lastUseMinute.coerceAtLeast(0),
    usualFirstMinute = person.usualFirstUseMinute,
)

@Composable
fun DemoAppScreen(role: DemoRole) {
    val context = LocalContext.current
    val people = remember { loadDemoPeople(context) }
    val isFamily = role == DemoRole.FAMILY
    var selectedName by remember { mutableStateOf(if (role == DemoRole.FAMILY) "Ma" else "Papa") }
    var tab by remember { mutableStateOf(DemoTab.TODAY) }
    var selectedDayNumber by remember { mutableStateOf<Int?>(null) }
    var previewAlertKey by remember { mutableStateOf<String?>(null) }
    var sharingEnabled by remember { mutableStateOf(true) }
    val person = people.firstOrNull { it.name == selectedName } ?: people.first()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
    ) {
        TopBar(start = { Wordmark() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).fillMaxWidth()
                .padding(horizontal = Space.gutter).padding(bottom = Space.l),
        ) {
            Spacer(Modifier.height(Space.xl))
            Eyebrow(if (isFamily) "FAMILY VIEW · ${person.name.uppercase()}" else "PHONE OWNER VIEW · ${person.name.uppercase()}")
            Spacer(Modifier.height(Space.s))
            Text(
                text = when (tab) {
                    DemoTab.TODAY -> if (isFamily) "A calmer way to check in" else "Your day, on your terms"
                    DemoTab.HISTORY -> "The rhythm over time"
                    DemoTab.ALERTS -> "Check-in history"
                    DemoTab.PEOPLE -> "People you follow"
                    DemoTab.SHARING -> "Your sharing settings"
                    DemoTab.MORE -> "More"
                },
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Space.s))
            Text(
                text = when (tab) {
                    DemoTab.TODAY -> if (isFamily) "Phone activity gives your family context. It cannot tell them why a day is quiet." else "Your phone-use rhythm, shared with family—with no camera, microphone, or location feed."
                    DemoTab.HISTORY -> "${person.name}'s ${person.historyDays}-day phone-use history, shared by day."
                    DemoTab.ALERTS -> "A record of quiet-period check-ins. Preview a message without sending it."
                    DemoTab.PEOPLE -> "Choose a family member to see their shared rhythm and history."
                    DemoTab.SHARING -> "You choose whether your phone-use rhythm is shared."
                    DemoTab.MORE -> "Your history, check-ins and sharing settings."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isFamily && tab != DemoTab.PEOPLE) {
                Spacer(Modifier.height(Space.l))
                PersonPicker(people, selectedName) { name ->
                    selectedName = name
                    selectedDayNumber = null
                }
            }
            Spacer(Modifier.height(Space.xl))
            when (tab) {
                DemoTab.TODAY -> TodayDemo(person, isFamily, onHistory = { tab = DemoTab.HISTORY }, onAlerts = { tab = DemoTab.ALERTS })
                DemoTab.HISTORY -> HistoryDemo(person, selectedDayNumber) { selectedDayNumber = it }
                DemoTab.ALERTS -> AlertsDemo(
                    people = if (isFamily) people else listOf(person),
                    previewAlertKey = previewAlertKey,
                    onPreview = { previewAlertKey = if (previewAlertKey == it) null else it },
                )
                DemoTab.PEOPLE -> PeopleDemo(people, selectedName) { name -> selectedName = name; tab = DemoTab.TODAY }
                DemoTab.SHARING -> SharingDemo(sharingEnabled) { sharingEnabled = it }
                DemoTab.MORE -> MoreDemo(
                    person = person,
                    onHistory = { tab = DemoTab.HISTORY },
                    onAlerts = { tab = DemoTab.ALERTS },
                    onSharing = { tab = DemoTab.SHARING },
                )
            }
            Spacer(Modifier.height(Space.xl))
        }
        DemoNavigation(isFamily, tab) { tab = it }
    }
}

@Composable
private fun TodayDemo(person: DemoPerson, isFamily: Boolean, onHistory: () -> Unit, onAlerts: () -> Unit) {
    val day = person.today
    if (isFamily) {
        StoryPanel(
            title = "TODAY'S RHYTHM · ${person.historyDays} DAYS OF HISTORY",
            body = "${day.observedBins} observed 15-minute intervals · ${day.interactionTotal} phone interactions · ${day.chargingBins} charging intervals",
        )
        Spacer(Modifier.height(Space.l))
    }
    ParentCardView(
        name = person.name,
        card = demoCard(person, day),
        labels = CardLabels(today = "Today", lastUse = "Last observed phone use", usualFirst = "Profile's usual first use", timeOf = ::clockTime),
        expanded = isFamily,
    )
    if (isFamily) {
        Spacer(Modifier.height(Space.l))
        QuickLink(title = "${person.historyDays}-day history", detail = "See the daily activity rhythm", onClick = onHistory)
        Spacer(Modifier.height(Space.s))
        QuickLink(title = "${person.alerts.size} check-in alerts", detail = "Review the alert history", onClick = onAlerts)
    }
    if (isFamily && person.name == "Ma") {
        Spacer(Modifier.height(Space.l))
        StoryPanel(title = "MA AND PAPA", body = "Ma's 7-day view and Papa's 30-day view are both available. Use the profile selector above to switch.")
    }
}

@Composable
private fun PersonPicker(people: List<DemoPerson>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        people.forEach { person ->
            FilterChip(
                selected = selected == person.name,
                onClick = { onSelect(person.name) },
                label = { Text("${person.name} · ${person.historyDays} days") },
                shape = CircleShape,
            )
        }
    }
}

@Composable
private fun HistoryDemo(person: DemoPerson, selectedDayNumber: Int?, onSelectDay: (Int) -> Unit) {
    Text("LAST ${person.historyDays} DAYS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(Space.m))
    ActivityBars(person.days)
    Spacer(Modifier.height(Space.l))
    person.days.reversed().forEach { day ->
        HistoryDayRow(day, selected = day.dayNumber == selectedDayNumber) { onSelectDay(day.dayNumber) }
        Spacer(Modifier.height(Space.xs))
    }
    selectedDayNumber?.let { number ->
        person.days.firstOrNull { it.dayNumber == number }?.let { day ->
            Spacer(Modifier.height(Space.l))
            StoryPanel(
                title = "${dayLabel(day.dayOffset).uppercase()} · ${day.weekday.uppercase()}",
                body = "${day.interactionTotal} phone interactions · ${day.observedBins} observed intervals · ${day.chargingBins} charging intervals",
            )
        }
    }
}

@Composable
private fun ActivityBars(days: List<DemoDay>) {
    val maxTotal = maxOf(1, days.maxOf { it.interactionTotal })
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Phone interactions by day", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().height(82.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                days.forEachIndexed { index, day ->
                    val fraction = (day.interactionTotal.toFloat() / maxTotal).coerceIn(0.06f, 1f)
                    Box(
                        Modifier.weight(1f).fillMaxWidth().height(82.dp * fraction)
                            .background(if (index == days.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.34f), RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dayLabel(days.first().dayOffset), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HistoryDayRow(day: DemoDay, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${dayLabel(day.dayOffset)} · ${day.weekday}", style = MaterialTheme.typography.titleSmall)
                Text("${day.interactionTotal} interactions · ${day.observedBins} intervals observed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (day.dayOffset == 0) Text("Today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AlertsDemo(people: List<DemoPerson>, previewAlertKey: String?, onPreview: (String) -> Unit) {
    val alerts = people.flatMap { person -> person.alerts.map { person to it } }.sortedBy { it.second.dayOffset }
    StoryPanel(
        title = "${alerts.size} CHECK-IN ALERTS IN THIS VIEW",
        body = "Each entry follows a 12-hour stretch of continuous observed quiet. Open a check-in preview to see how a family message could sound.",
    )
    Spacer(Modifier.height(Space.l))
    if (alerts.isEmpty()) {
        StoryPanel(title = "NO CHECK-IN ALERTS", body = "No long observed quiet periods appear in this history.")
    }
    alerts.forEach { (person, alert) ->
        val key = "${person.name}-${alert.dayNumber}"
        AlertHistoryRow(person, alert, previewAlertKey == key, onPreview = { onPreview(key) })
        Spacer(Modifier.height(Space.s))
    }
}

@Composable
private fun AlertHistoryRow(person: DemoPerson, alert: DemoAlert, showPreview: Boolean, onPreview: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("12h", Modifier.padding(horizontal = Space.m, vertical = Space.xs), style = MaterialTheme.typography.labelMedium)
                }
                Text("${person.name} · Check-in suggested", style = MaterialTheme.typography.titleSmall)
            }
            Text("${dayLabel(alert.dayOffset)} · ${alert.weekday} · ${clockTime(alert.alertBin * 15)}", style = MaterialTheme.typography.bodyMedium)
            Text("Observed quiet: ${durationLabel(alert.quietMinutes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                alert.activityResumedBin?.let { "Phone activity resumed at ${clockTime(it * 15)}." } ?: "No later phone activity was observed that day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (person.name == "Ma") {
                TextButton(onClick = onPreview) { Text(if (showPreview) "Hide check-in preview" else "Preview a check-in") }
                if (showPreview) {
                    Text("Hi Ma, just checking in. How is your day going?", style = MaterialTheme.typography.bodyMedium)
                    Text("Preview only · nothing is sent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PeopleDemo(people: List<DemoPerson>, selected: String, onSelect: (String) -> Unit) {
    people.forEach { person ->
        Surface(
            onClick = { onSelect(person.name) },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(person.name, style = MaterialTheme.typography.titleLarge)
                    if (selected == person.name) Text("Selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text("${person.historyDays} days of shared rhythm · ${person.alerts.size} check-in alerts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onSelect(person.name) }) { Text("View ${person.name}'s day") }
            }
        }
        Spacer(Modifier.height(Space.s))
    }
    StoryPanel(title = "SHARED BY THE PHONE OWNER", body = "The family view only shows phone activity that the phone owner has chosen to share.")
}

@Composable
private fun SharingDemo(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share my daily rhythm", style = MaterialTheme.typography.titleMedium)
                    Text(if (enabled) "Your rhythm is shared with family" else "Sharing is paused", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = onChange)
            }
            Text("The phone owner can pause sharing at any time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(Space.l))
    StoryPanel(title = "PRIVACY ON THE PHONE", body = "MaPa uses phone activity and does not require a camera, microphone, or location feed.")
}

@Composable
private fun MoreDemo(person: DemoPerson, onHistory: () -> Unit, onAlerts: () -> Unit, onSharing: () -> Unit) {
    QuickLink(title = "${person.historyDays}-day history", detail = "See the daily activity rhythm", onClick = onHistory)
    Spacer(Modifier.height(Space.s))
    QuickLink(title = "${person.alerts.size} check-in alerts", detail = "Review the alert history", onClick = onAlerts)
    Spacer(Modifier.height(Space.s))
    QuickLink(title = "Sharing settings", detail = "Choose what your family can see", onClick = onSharing)
}

@Composable
private fun QuickLink(title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Space.l), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun StoryPanel(title: String, body: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DemoNavigation(isFamily: Boolean, selected: DemoTab, onSelect: (DemoTab) -> Unit) {
    val tabs = if (isFamily) listOf(DemoTab.TODAY, DemoTab.HISTORY, DemoTab.ALERTS, DemoTab.PEOPLE)
        else listOf(DemoTab.TODAY, DemoTab.MORE)
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = if (isFamily) selected == tab else if (selected == DemoTab.TODAY) tab == DemoTab.TODAY else tab == DemoTab.MORE,
                onClick = { onSelect(tab) },
                icon = {},
                label = { Text(tabTitle(tab)) },
            )
        }
    }
}

private fun tabTitle(tab: DemoTab) = when (tab) {
    DemoTab.TODAY -> "Today"
    DemoTab.HISTORY -> "History"
    DemoTab.ALERTS -> "Alerts"
    DemoTab.PEOPLE -> "People"
    DemoTab.SHARING -> "Sharing"
    DemoTab.MORE -> "More"
}

private fun dayLabel(offset: Int) = when (offset) {
    0 -> "Today"
    1 -> "Yesterday"
    else -> "$offset days ago"
}

private fun durationLabel(minutes: Int): String {
    val hours = minutes / 60
    val remaining = minutes % 60
    return if (remaining == 0) "${hours}h" else "${hours}h ${remaining}m"
}

private fun clockTime(minute: Int): String {
    val safeMinute = minute.coerceAtLeast(0)
    val hour = (safeMinute / 60) % 12
    return "${if (hour == 0) 12 else hour}:${(safeMinute % 60).toString().padStart(2, '0')} ${if (safeMinute / 60 < 12) "AM" else "PM"}"
}
