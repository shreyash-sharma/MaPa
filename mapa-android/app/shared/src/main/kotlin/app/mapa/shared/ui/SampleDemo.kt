package app.mapa.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mapa.core.family.ParentCard
import app.mapa.routine.RoutineState

enum class SampleDemoRole { PHONE_OWNER, FAMILY }

/** Fixed fictional example shown only when a debug build receives the demo deep link. */
private val sampleCard = ParentCard(
    state = RoutineState.QUIET,
    stateLabel = "Within usual rhythm",
    detail = "Phone activity today is close to this person's usual pattern.",
    updatedAgo = "Example view - not a live update",
    stale = false,
    forecast = "Phone use usually starts around 7:30 AM.",
    note = "A quiet afternoon followed a usual morning. This is an example note from sample data.",
    noteByAi = false,
    learning = null,
    modelLine = "MaPa Rhythm Hybrid",
    confidencePct = null,
    battery = 72,
    charging = false,
    chart = ParentCard.Chart(
        active = List(96) { bin -> if (bin in setOf(6, 8, 12, 18, 22, 27, 31, 38, 45, 51, 58, 66, 74, 82)) 1 else 0 },
        usual = List(96) { bin -> when (bin) { in 5..15 -> 0.55; in 16..35 -> 0.3; in 36..70 -> 0.22; in 71..87 -> 0.15; else -> 0.02 } },
        nowBin = 72,
        expectedByBin = null,
    ),
    lastUseMinute = 17 * 60 + 45,
    usualFirstMinute = 7 * 60 + 30,
)

/** The demo preview does not enter account, collection, or cloud flows. */
@Composable
fun SampleDemoScreen(role: SampleDemoRole) {
    val isFamily = role == SampleDemoRole.FAMILY
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(start = { Wordmark() })
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.xxl),
        ) {
            Spacer(Modifier.height(Space.xl))
            Eyebrow(if (isFamily) "FAMILY APP - SAMPLE MODE" else "PARENT APP - SAMPLE MODE")
            Spacer(Modifier.height(Space.s))
            Text(
                if (isFamily) "A family's daily picture" else "A phone owner's daily rhythm",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Space.s))
            Text(
                "This is a fictional example. It uses no account and collects or sends no phone activity.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.xl))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "SAMPLE DATA - both demo views show the same example story. They do not sync with each other.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Space.l),
                )
            }
            Spacer(Modifier.height(Space.l))
            ParentCardView(
                name = if (isFamily) "Sam" else "Your phone",
                card = sampleCard,
                labels = CardLabels(
                    today = "Sample day",
                    lastUse = "Last sample phone use",
                    usualFirst = "Usual first use",
                    timeOf = { minute ->
                        val hour = (minute / 60) % 12
                        "${if (hour == 0) 12 else hour}:${(minute % 60).toString().padStart(2, '0')} ${if (minute / 60 < 12) "AM" else "PM"}"
                    },
                ),
                expanded = isFamily,
            )
            if (!isFamily) {
                Spacer(Modifier.height(Space.l))
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Sample history", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Illustrative example only. No real phone usage, location, or contacts are read.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
