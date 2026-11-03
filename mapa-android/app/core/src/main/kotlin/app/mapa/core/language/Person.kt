package app.mapa.core.language

enum class Lang(val code: String) {
    EN("en"), HI("hi"), TA("ta"), FR("fr"), MR("mr");

    companion object {
        fun of(code: String?): Lang = entries.firstOrNull { it.code == code } ?: EN
    }
}

/** How the family refers to the parent. Role only drives pronouns and Hindi verb agreement. */
enum class Role(val wire: String) {
    MOTHER("mother"), FATHER("father"), OTHER("other");

    companion object {
        fun of(wire: String?): Role = entries.firstOrNull { it.wire == wire } ?: OTHER
    }
}

data class Person(val name: String, val role: Role) {
    val subj get() = when (role) { Role.MOTHER -> "she"; Role.FATHER -> "he"; Role.OTHER -> "they" }
    val obj get() = when (role) { Role.MOTHER -> "her"; Role.FATHER -> "him"; Role.OTHER -> "them" }
    val pos get() = when (role) { Role.MOTHER -> "her"; Role.FATHER -> "his"; Role.OTHER -> "their" }
    /** "she's" / "he's" / "they're" */
    val subjIs get() = when (role) { Role.MOTHER -> "she's"; Role.FATHER -> "he's"; Role.OTHER -> "they're" }
    val usuallyIs get() = when (role) { Role.MOTHER -> "she's usually"; Role.FATHER -> "he's usually"; Role.OTHER -> "they're usually" }
    val hasVerb get() = if (role == Role.OTHER) "have" else "has"
    val pickUp get() = if (role == Role.OTHER) "pick up" else "picks up"
    val start get() = if (role == Role.OTHER) "start" else "starts"

    /** Hindi respectful plural; feminine for mothers. "हो जाती हैं" vs "हो जाते हैं". */
    val hiFem get() = role == Role.MOTHER
}

/** Existing core templates are English/Hindi; live app labels are translated separately. */
enum class BundledLang { EN, HI }
fun Lang.coreTextLang(): BundledLang = if (this == Lang.HI) BundledLang.HI else BundledLang.EN
