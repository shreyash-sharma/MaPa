package app.mapa.shared.ui

/** Words the day picture needs, so the family app and the parent app can pass English or Hindi. */
data class DialLabels(
    val hours: List<String> = listOf("12 AM", "6 AM", "12 PM", "6 PM"),
    val today: String = "Today's phone use",
    val usual: String = "Usual rhythm",
    val now: String = "Now",
    val expected: String = "Usually active by",
)
