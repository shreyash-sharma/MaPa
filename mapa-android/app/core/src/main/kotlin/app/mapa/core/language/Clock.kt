package app.mapa.core.language

import app.mapa.routine.Bins

/** Times of day and durations, written the way each language reads them. */
object Clock {
    fun time(minuteOfDay: Int, lang: Lang): String {
        val m = Math.floorMod(minuteOfDay, 1440)
        val h24 = m / 60
        val mm = m % 60
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return when (lang.coreTextLang()) {
            BundledLang.EN -> "%d:%02d %s".format(h12, mm, if (h24 < 12) "AM" else "PM")
            BundledLang.HI -> "%s %d:%02d".format(hiDaypart(h24), h12, mm)
        }
    }

    fun timeOfBin(t: Int, lang: Lang) = time(Bins.minuteOfDay(t), lang)

    private fun hiDaypart(h: Int) = when (h) {
        in 4..11 -> "सुबह"
        in 12..15 -> "दोपहर"
        in 16..18 -> "शाम"
        else -> "रात"
    }

    fun duration(minutes: Int, lang: Lang): String {
        val h = minutes / 60
        val m = minutes % 60
        return when (lang.coreTextLang()) {
            BundledLang.EN -> when {
                h == 0 -> "$m minutes"
                m < 10 -> if (h == 1) "an hour" else "$h hours"
                else -> "${if (h == 1) "1 hour" else "$h hours"} $m minutes"
            }
            BundledLang.HI -> when {
                h == 0 -> "$m मिनट"
                m < 10 -> "$h घंटे"
                else -> "$h घंटे $m मिनट"
            }
        }
    }

    /** Roughly: "about an hour", "about 40 minutes". Used for routine shifts. */
    fun roughShift(minutes: Int, lang: Lang): String {
        val a = Math.abs(minutes)
        return when (lang.coreTextLang()) {
            BundledLang.EN -> when {
                a in 50..75 -> "about an hour"
                a >= 76 -> "about ${Math.round(a / 30.0) / 2.0} hours".replace(".0 ", " ")
                else -> "about ${Math.round(a / 5.0) * 5} minutes"
            }
            BundledLang.HI -> when {
                a in 50..75 -> "लगभग एक घंटा"
                a >= 76 -> "लगभग ${Math.round(a / 30.0) / 2.0} घंटे".replace(".0 ", " ")
                else -> "लगभग ${Math.round(a / 5.0) * 5} मिनट"
            }
        }
    }

    /** Monday = 0, as in the engine. */
    fun weekday(dow: Int, lang: Lang): String = when (lang.coreTextLang()) {
        BundledLang.EN -> listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")[dow]
        BundledLang.HI -> listOf("सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार")[dow]
    }

    fun daypart(minuteOfDay: Int, lang: Lang): String {
        val h = minuteOfDay / 60
        return when (lang.coreTextLang()) {
            BundledLang.EN -> when (h) { in 4..11 -> "morning"; in 12..16 -> "afternoon"; in 17..20 -> "evening"; else -> "night" }
            BundledLang.HI -> when (h) { in 4..11 -> "सुबह"; in 12..16 -> "दोपहर"; in 17..20 -> "शाम"; else -> "रात" }
        }
    }
}
