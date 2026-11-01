package app.mapa.core.wire

import app.mapa.core.language.Lang
import app.mapa.core.language.NoteFacts
import app.mapa.core.language.Person
import app.mapa.routine.RoutineState

/**
 * The note's facts as synced: times, counts and battery only, the same structured summary a
 * language model is allowed to see. Name and language are left out: the family phone adds its
 * own, so each family member gets the note in the language they chose.
 */
object NoteFactsWire {
    fun encode(f: NoteFacts): Map<String, Any?> = mapOf(
        "weekday" to f.weekday,
        "nowMinute" to f.nowMinute,
        "state" to f.state.name,
        "firstMinute" to f.firstActivityTodayMinute,
        "usualFirstMinute" to f.usualFirstMinute,
        "lastMinute" to f.lastInteractionMinute,
        "lastToday" to f.lastInteractionToday,
        "activeBins" to f.activeBinsToday,
        "usualActiveBins" to f.usualActiveBinsSoFar,
        "battery" to f.battery,
        "charging" to f.charging,
        "learning" to f.learning,
    )

    fun decode(m: Map<String, Any?>, person: Person, lang: Lang): NoteFacts? = runCatching {
        NoteFacts(
            person = person,
            lang = lang,
            weekday = m.int("weekday")!!,
            nowMinute = m.int("nowMinute")!!,
            state = RoutineState.valueOf(m.str("state")!!),
            firstActivityTodayMinute = m.int("firstMinute"),
            usualFirstMinute = m.int("usualFirstMinute"),
            lastInteractionMinute = m.int("lastMinute"),
            lastInteractionToday = m.bool("lastToday") ?: false,
            activeBinsToday = m.int("activeBins") ?: 0,
            usualActiveBinsSoFar = m.double("usualActiveBins") ?: 0.0,
            battery = m.int("battery") ?: -1,
            charging = m.bool("charging") ?: false,
            learning = m.bool("learning") ?: true,
        )
    }.getOrNull()
}
