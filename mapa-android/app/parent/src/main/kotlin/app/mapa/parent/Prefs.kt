package app.mapa.parent

import android.content.Context
import app.mapa.core.language.Lang
import app.mapa.core.language.Person
import app.mapa.core.language.Role

/** Small key-value state on the parent's phone. Nothing here is uploaded as-is. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("mapa_parent", Context.MODE_PRIVATE)

    var consented: Boolean
        get() = sp.getBoolean("consented", false)
        set(v) = sp.edit().putBoolean("consented", v).apply()

    var consentAt: Long
        get() = sp.getLong("consentAt", 0L)
        set(v) = sp.edit().putLong("consentAt", v).apply()

    var paused: Boolean
        get() = sp.getBoolean("paused", false)
        set(v) = sp.edit().putBoolean("paused", v).apply()

    /** Whether the family may see that sharing is paused (otherwise they see "unavailable"). */
    var showPaused: Boolean
        get() = sp.getBoolean("showPaused", true)
        set(v) = sp.edit().putBoolean("showPaused", v).apply()

    /** What the family calls this parent, e.g. "Mom", "Papa", "Nani". */
    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit().putString("name", v.trim()).apply()

    var role: Role
        get() = Role.of(sp.getString("role", null))
        set(v) = sp.edit().putString("role", v.wire).apply()

    var lang: Lang
        get() = Lang.of(sp.getString("lang", null))
        set(v) = sp.edit().putString("lang", v.code).apply()

    val person: Person get() = Person(name.ifBlank { "Your parent" }, role)

    var lastInteractionAt: Long
        get() = sp.getLong("lastInteractionAt", 0L)
        set(v) = sp.edit().putLong("lastInteractionAt", v).apply()

    var lastSyncAt: Long
        get() = sp.getLong("lastSyncAt", 0L)
        set(v) = sp.edit().putLong("lastSyncAt", v).apply()

    /** State last reported to the family, to detect changes for the "Why did this change?" timeline. */
    var lastState: String
        get() = sp.getString("lastState", "") ?: ""
        set(v) = sp.edit().putString("lastState", v).apply()

    var note: String
        get() = sp.getString("note", "") ?: ""
        set(v) = sp.edit().putString("note", v).apply()

    var noteSource: String
        get() = sp.getString("noteSource", "") ?: ""
        set(v) = sp.edit().putString("noteSource", v).apply()

    var noteAt: Long
        get() = sp.getLong("noteAt", 0L)
        set(v) = sp.edit().putLong("noteAt", v).apply()

    var noteState: String
        get() = sp.getString("noteState", "") ?: ""
        set(v) = sp.edit().putString("noteState", v).apply()

    var uploadedTrendDay: Int
        get() = sp.getInt("uploadedTrendDay", -1)
        set(v) = sp.edit().putInt("uploadedTrendDay", v).apply()

    /** Epoch day of the last 30-day cleanup of this parent's shared history. */
    var prunedDay: Long
        get() = sp.getLong("prunedDay", -1L)
        set(v) = sp.edit().putLong("prunedDay", v).apply()

    var askedRoutineSinceDay: Int
        get() = sp.getInt("askedRoutineSinceDay", Int.MIN_VALUE)
        set(v) = sp.edit().putInt("askedRoutineSinceDay", v).apply()

    /** The last note's structured facts, synced so the family phone can write the AI note. */
    var noteFactsJson: String
        get() = sp.getString("noteFactsJson", "") ?: ""
        set(v) = sp.edit().putString("noteFactsJson", v).apply()

    /** A family-started pairing code from a link, waiting for the parent to say yes. */
    var pendingCode: String
        get() = sp.getString("pendingCode", "") ?: ""
        set(v) = sp.edit().putString("pendingCode", v).apply()

    /** Invite codes this phone created, so "delete everything" can remove them too. */
    var inviteCodes: Set<String>
        get() = sp.getStringSet("inviteCodes", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("inviteCodes", v).apply()

    /** Last full status uploaded, kept so the parent can see exactly what the family sees. */
    var lastStatusJson: String
        get() = sp.getString("lastStatusJson", "") ?: ""
        set(v) = sp.edit().putString("lastStatusJson", v).apply()

    var lastDayJson: String
        get() = sp.getString("lastDayJson", "") ?: ""
        set(v) = sp.edit().putString("lastDayJson", v).apply()

    fun profile(zone: String = java.time.ZoneId.systemDefault().id) =
        app.mapa.core.wire.ParentProfile(name, role.wire, lang.code, zone, paused, showPaused)

    fun clearAll() = sp.edit().clear().apply()
}
