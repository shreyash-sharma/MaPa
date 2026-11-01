package app.mapa.core.language

import app.mapa.routine.Bins
import app.mapa.routine.Decider
import app.mapa.routine.NewRoutineQuestion
import app.mapa.routine.Reason
import app.mapa.routine.RoutineState
import app.mapa.routine.TrendReport
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every sentence MaPa shows or sends, in English and Hindi. These are the template layer
 * the AI falls back to, and the wording rules the AI is held to: phone patterns only,
 * never health, safety or an all-clear. Hindi wording needs a native-speaker review.
 */
class Phrases(val lang: Lang) {

    fun stateLabel(s: RoutineState): String = when (lang) {
        Lang.EN -> when (s) {
            RoutineState.ACTIVE -> "Active"
            RoutineState.QUIET -> "Quiet"
            RoutineState.UNUSUAL_INACTIVITY -> "Unusual"
            RoutineState.MONITORING_UNAVAILABLE -> "Monitoring unavailable"
            RoutineState.PAUSED_BY_PARENT -> "Paused by parent"
        }
        Lang.HI -> when (s) {
            RoutineState.ACTIVE -> "सक्रिय"
            RoutineState.QUIET -> "शांत"
            RoutineState.UNUSUAL_INACTIVITY -> "असामान्य"
            RoutineState.MONITORING_UNAVAILABLE -> "निगरानी उपलब्ध नहीं"
            RoutineState.PAUSED_BY_PARENT -> "साझा करना रुका है"
        }
    }

    /** One line explaining the current state. [nowT] is the current bin in the parent's local time. */
    fun detail(reason: Reason, p: Person, nowT: Int): String = when (reason) {
        is Reason.RecentInteraction -> when (lang) {
            Lang.EN -> "Phone last used at ${t(reason.lastInteractionT)}."
            Lang.HI -> "फ़ोन आख़िरी बार ${t(reason.lastInteractionT)} पर इस्तेमाल हुआ।"
        }
        is Reason.RecentMovement -> when (lang) {
            Lang.EN -> "Phone moved at ${t(reason.lastMovedT)}."
            Lang.HI -> "फ़ोन ${t(reason.lastMovedT)} पर साथ ले जाया गया।"
        }
        is Reason.WithinRoutine -> reason.expectedByT?.let {
            when (lang) {
                Lang.EN -> "Within ${p.name}'s usual pattern. Next phone use usually by ${t(it)}."
                Lang.HI -> "${p.name} के सामान्य पैटर्न के भीतर। अगला फ़ोन इस्तेमाल आम तौर पर ${t(it)} तक होता है।"
            }
        } ?: when (lang) {
            Lang.EN -> "Within ${p.name}'s usual pattern."
            Lang.HI -> "${p.name} के सामान्य पैटर्न के भीतर।"
        }
        is Reason.LongerThanUsual -> longer(reason, p)
        is Reason.NoFreshData -> noFresh(reason, p, nowT)
        Reason.UsageAccessMissing -> when (lang) {
            Lang.EN -> "Usage access is turned off on ${p.name}'s phone, so MaPa can't see phone activity right now."
            Lang.HI -> "${p.name} के फ़ोन पर Usage Access बंद है, इसलिए MaPa अभी फ़ोन की गतिविधि नहीं देख सकता।"
        }
        Reason.SharingPaused -> when (lang) {
            Lang.EN -> "${p.name} has paused sharing."
            Lang.HI -> "${p.name} ने साझा करना रोक दिया है।"
        }
    }

    private fun longer(r: Reason.LongerThanUsual, p: Person): String {
        val since = t(r.silentSinceT)
        val usual = r.usualFirstActivityMinute
        // "Usually active by" only makes sense when the silence began before the usual start of the day.
        val morning = usual != null && Bins.minuteOfDay(r.silentSinceT) < usual
        val dow = Bins.dow(Bins.day(r.silentSinceT), Bins.EPOCH_DOW)
        val first = when (lang) {
            Lang.EN -> if (morning) {
                "${p.name} hasn't used ${p.pos} phone since $since, and ${p.usuallyIs} active by ${Clock.time(usual!!, lang)} ${daytypeEn(dow)}."
            } else {
                "${p.name} hasn't used ${p.pos} phone since $since, which is longer than usual for a ${Clock.weekday(dow, lang)} ${Clock.daypart(Bins.minuteOfDay(r.silentSinceT), lang)}."
            }
            Lang.HI -> if (morning) {
                "${p.name} ने $since के बाद से फ़ोन इस्तेमाल नहीं किया है, जबकि ${daytypeHi(dow)} वे आम तौर पर ${Clock.time(usual!!, lang)} तक सक्रिय हो ${if (p.hiFem) "जाती हैं" else "जाते हैं"}।"
            } else {
                "${p.name} ने $since के बाद से फ़ोन इस्तेमाल नहीं किया है, जो ${Clock.weekday(dow, lang)} की ${Clock.daypart(Bins.minuteOfDay(r.silentSinceT), lang)} के लिए सामान्य से ज़्यादा देर है।"
            }
        }
        // Model-decided only: how rare this silence is for this parent, else the calibrated confidence.
        val modelSays = r.decidedBy == Decider.MODEL && !r.floorRule
        val rarity = r.recurrenceWeeks?.takeIf { modelSays && it >= 2 }?.let { w ->
            val weeks = w.roundToInt()
            when (lang) {
                Lang.EN -> if (weeks >= 52) " A quiet stretch like this is rarer than once a year for ${p.obj}."
                else " A quiet stretch like this happens about once in $weeks weeks for ${p.obj}."
                Lang.HI -> if (weeks >= 52) " उनके लिए इतनी लंबी शांति साल में एक बार से भी कम होती है।"
                else " उनके लिए इतनी लंबी शांति लगभग $weeks हफ़्तों में एक बार होती है।"
            }
        }
        val confidence = rarity ?: r.confidence?.takeIf { modelSays }?.let { c ->
            val pct = (c * 100).toInt().coerceAtMost(99)
            when (lang) {
                Lang.EN -> " We're $pct% confident this is unusual for ${p.obj}."
                Lang.HI -> " हमें $pct% भरोसा है कि यह उनके लिए असामान्य है।"
            }
        } ?: ""
        val nudge = when (lang) {
            Lang.EN -> " This may be nothing; you might want to call."
            Lang.HI -> " हो सकता है कोई बात न हो; आप एक बार फ़ोन कर सकते हैं।"
        }
        return first + confidence + nudge
    }

    private fun noFresh(r: Reason.NoFreshData, p: Person, nowT: Int): String {
        val since = r.lastSyncT ?: return when (lang) {
            Lang.EN -> "We haven't heard from ${p.name}'s phone yet."
            Lang.HI -> "${p.name} के फ़ोन से अभी तक कोई अपडेट नहीं मिला है।"
        }
        val gap = Clock.duration(((nowT - since) * Bins.MINUTES).coerceAtLeast(15), lang)
        val battery = r.lastBattery?.takeIf { it >= 0 }
        val probablyOff = battery != null && battery <= 10 && r.lastCharging != true
        return when (lang) {
            Lang.EN -> "We haven't heard from ${p.name}'s phone for $gap." + when {
                probablyOff -> " Its battery was at $battery%, so it has probably switched off."
                battery != null -> " Its battery was at $battery%; it may be offline, or MaPa may have been stopped."
                else -> ""
            }
            Lang.HI -> "$gap से ${p.name} के फ़ोन से कोई अपडेट नहीं मिला है।" + when {
                probablyOff -> " बैटरी $battery% थी, इसलिए शायद फ़ोन बंद हो गया है।"
                battery != null -> " बैटरी $battery% थी; फ़ोन ऑफ़लाइन हो सकता है या MaPa रुक गया हो सकता है।"
                else -> ""
            }
        }
    }

    /**
     * The forecast line: "Mom usually picks up her phone by 7:40 AM. It's 9:10 AM." once the
     * usual time has passed with no use today; otherwise when the next use is expected.
     */
    fun forecast(p: Person, nowT: Int, usualFirstMinute: Int?, usedToday: Boolean, expectedByT: Int?): String? {
        val nowMin = Bins.minuteOfDay(nowT)
        if (!usedToday && usualFirstMinute != null && nowMin > usualFirstMinute + 15 && nowMin < 20 * 60) {
            return when (lang) {
                Lang.EN -> "${p.name} usually ${p.pickUp} ${p.pos} phone by ${Clock.time(usualFirstMinute, lang)}. It's ${Clock.time(nowMin, lang)}."
                Lang.HI -> "${p.name} आम तौर पर ${Clock.time(usualFirstMinute, lang)} तक फ़ोन उठा ${if (p.hiFem) "लेती हैं" else "लेते हैं"}। अभी ${Clock.time(nowMin, lang)} बजे हैं।"
            }
        }
        val e = expectedByT ?: return null
        if (e <= nowT) return null
        return when (lang) {
            Lang.EN -> "Next phone use expected by about ${t(e)}."
            Lang.HI -> "अगला फ़ोन इस्तेमाल लगभग ${t(e)} तक अपेक्षित है।"
        }
    }

    fun learning(p: Person, dayNumber: Int, learningDays: Int): String = when (lang) {
        Lang.EN -> "Learning ${p.name}'s rhythm: ${dayNumber.coerceAtMost(learningDays)} of $learningDays days"
        Lang.HI -> "${p.name} की दिनचर्या सीख रहे हैं: $learningDays में से ${dayNumber.coerceAtMost(learningDays)} दिन"
    }

    /** Day one onwards, when the model already decides: a confidence word that never blocks anything. */
    fun gettingToKnow(p: Person, days: Int): String = when (lang) {
        Lang.EN -> "Getting to know ${p.name}'s day" + (if (days > 0) ": ${if (days == 1) "1 day" else "$days days"} so far" else "") + ". MaPa works from day one."
        Lang.HI -> "${p.name} का दिन जान रहे हैं" + (if (days > 0) ": अब तक $days दिन" else "") + "। MaPa पहले दिन से काम करता है।"
    }

    fun rhythmUpdated(trainedT: Int): String {
        val dow = Bins.dow(Bins.day(trainedT), Bins.EPOCH_DOW)
        // Training runs after midnight; name the evening before ("updated Sunday" for a Monday 2 AM run).
        val named = Clock.weekday(Math.floorMod(dow - 1, 7), lang)
        return when (lang) {
            Lang.EN -> "Rhythm updated $named night"
            Lang.HI -> "दिनचर्या $named रात अपडेट हुई"
        }
    }

    fun modelLine(model: String, modelReady: Boolean, modelDecides: Boolean): String = when (lang) {
        Lang.EN -> when {
            modelDecides && modelReady -> "On-device rhythm model is active"
            modelDecides -> "On-device rhythm model is learning; simple rules decide for now"
            else -> "Simple rules decide; rhythm model not installed"
        }
        Lang.HI -> when {
            modelDecides && modelReady -> "फ़ोन पर चल रहा दिनचर्या मॉडल सक्रिय है"
            modelDecides -> "दिनचर्या मॉडल सीख रहा है; अभी सरल नियम तय करते हैं"
            else -> "सरल नियम तय करते हैं; दिनचर्या मॉडल इंस्टॉल नहीं है"
        }
    }

    fun updatedAgo(minutes: Long): String = when (lang) {
        Lang.EN -> when {
            minutes < 1 -> "Updated just now"
            minutes < 60 -> "Updated $minutes min ago"
            else -> "Updated ${Clock.duration(minutes.toInt(), lang)} ago"
        }
        Lang.HI -> when {
            minutes < 1 -> "अभी अपडेट हुआ"
            minutes < 60 -> "$minutes मिनट पहले अपडेट हुआ"
            else -> "${Clock.duration(minutes.toInt(), lang)} पहले अपडेट हुआ"
        }
    }

    fun noUpdatesYet(): String = when (lang) {
        Lang.EN -> "No updates yet"
        Lang.HI -> "अभी तक कोई अपडेट नहीं"
    }

    fun lowBattery(p: Person, battery: Int): String = when (lang) {
        Lang.EN -> "${p.name}'s phone battery is at $battery% and not charging. Updates may stop if it switches off."
        Lang.HI -> "${p.name} के फ़ोन की बैटरी $battery% है और चार्ज नहीं हो रही। फ़ोन बंद होने पर अपडेट रुक सकते हैं।"
    }

    fun routineQuestion(p: Person, q: NewRoutineQuestion): String {
        val shift = Clock.roughShift(q.shiftMinutes, lang)
        return when (lang) {
            Lang.EN -> "${p.name}'s mornings have started $shift ${if (q.later) "later" else "earlier"} for ${q.weeks} weeks. Is this ${p.pos} new routine?"
            Lang.HI -> "पिछले ${q.weeks} हफ़्तों से ${p.name} की सुबह $shift ${if (q.later) "देर से" else "पहले"} शुरू हो रही है। क्या यह उनकी नई दिनचर्या है?"
        }
    }

    /** Weekly rhythm trend, worded as phone patterns. */
    fun trend(p: Person, r: TrendReport): List<String> = buildList {
        r.firstUseShiftMinutes?.takeIf { abs(it) >= 15 }?.let {
            add(when (lang) {
                Lang.EN -> "${p.name}'s first phone use this week was about ${abs(it)} minutes ${if (it > 0) "later" else "earlier"} than usual."
                Lang.HI -> "इस हफ़्ते ${p.name} का पहला फ़ोन इस्तेमाल सामान्य से लगभग ${abs(it)} मिनट ${if (it > 0) "देर से" else "पहले"} हुआ।"
            })
        }
        r.lastUseShiftMinutes?.takeIf { abs(it) >= 15 }?.let {
            add(when (lang) {
                Lang.EN -> "The last phone use in the evening was about ${abs(it)} minutes ${if (it > 0) "later" else "earlier"} than usual."
                Lang.HI -> "शाम का आख़िरी फ़ोन इस्तेमाल सामान्य से लगभग ${abs(it)} मिनट ${if (it > 0) "देर से" else "पहले"} हुआ।"
            })
        }
        r.activityChange?.takeIf { abs(it) >= 0.1 }?.let {
            val pct = (abs(it) * 100).roundToInt()
            add(when (lang) {
                Lang.EN -> "Phone activity was about $pct% ${if (it > 0) "higher" else "lower"} than usual."
                Lang.HI -> "फ़ोन की गतिविधि सामान्य से लगभग $pct% ${if (it > 0) "ज़्यादा" else "कम"} रही।"
            })
        }
        if (isEmpty()) add(when (lang) {
            Lang.EN -> "${p.name}'s phone patterns this week were close to usual."
            Lang.HI -> "इस हफ़्ते ${p.name} के फ़ोन इस्तेमाल का पैटर्न सामान्य के क़रीब रहा।"
        })
    }

    /** Template daily note: two short sentences built only from [f]. Also the AI's fallback. */
    fun dailyNote(f: NoteFacts): String {
        val p = f.person
        val first = f.firstActivityTodayMinute
        val usual = f.usualFirstMinute.takeIf { !f.learning }
        val s1 = when (lang) {
            Lang.EN -> when {
                first == null && usual != null -> "No phone activity yet today; ${p.name} usually ${p.start} by ${Clock.time(usual, lang)}."
                first == null -> "No phone activity yet today."
                usual == null -> "${p.name}'s first phone use today was at ${Clock.time(first, lang)}."
                first - usual >= 45 -> "${p.name} started later than usual today and has been active since ${Clock.time(first, lang)}."
                usual - first >= 45 -> "${p.name} started earlier than usual today, at ${Clock.time(first, lang)}."
                else -> "${p.name}'s day started around the usual time, at ${Clock.time(first, lang)}."
            }
            Lang.HI -> when {
                first == null && usual != null -> "आज अभी तक फ़ोन पर कोई गतिविधि नहीं; ${p.name} आम तौर पर ${Clock.time(usual, lang)} तक शुरू कर ${if (p.hiFem) "देती हैं" else "देते हैं"}।"
                first == null -> "आज अभी तक फ़ोन पर कोई गतिविधि नहीं।"
                usual == null -> "आज ${p.name} ने पहली बार ${Clock.time(first, lang)} पर फ़ोन इस्तेमाल किया।"
                first - usual >= 45 -> "आज ${p.name} ने सामान्य से देर से शुरुआत की और ${Clock.time(first, lang)} से सक्रिय हैं।"
                usual - first >= 45 -> "आज ${p.name} ने सामान्य से पहले, ${Clock.time(first, lang)} पर शुरुआत की।"
                else -> "आज ${p.name} का दिन लगभग सामान्य समय पर, ${Clock.time(first, lang)} पर शुरू हुआ।"
            }
        }
        val last = f.lastInteractionMinute?.takeIf { f.lastInteractionToday && first != null } ?: return s1
        val lastTime = Clock.time(last, lang)
        val day = Clock.weekday(f.weekday, lang)
        val ratio = if (f.learning || f.usualActiveBinsSoFar < 3) null else f.activeBinsToday / f.usualActiveBinsSoFar
        val s2 = when (lang) {
            Lang.EN -> when {
                ratio == null -> "Last used at $lastTime."
                ratio < 0.6 -> "Quieter than a usual $day so far; last used at $lastTime."
                ratio > 1.5 -> "Busier than a usual $day so far; last used at $lastTime."
                else -> "Activity is close to a usual $day; last used at $lastTime."
            }
            Lang.HI -> when {
                ratio == null -> "आख़िरी बार $lastTime पर इस्तेमाल हुआ।"
                ratio < 0.6 -> "अब तक सामान्य $day से कम गतिविधि; आख़िरी बार $lastTime पर इस्तेमाल हुआ।"
                ratio > 1.5 -> "अब तक सामान्य $day से ज़्यादा गतिविधि; आख़िरी बार $lastTime पर इस्तेमाल हुआ।"
                else -> "गतिविधि सामान्य $day जैसी है; आख़िरी बार $lastTime पर इस्तेमाल हुआ।"
            }
        }
        return "$s1 $s2"
    }

    private fun t(bin: Int) = Clock.timeOfBin(bin, lang)

    private fun daytypeEn(dow: Int) = when (Bins.daytype(dow)) { 0 -> "on weekdays"; 1 -> "on Saturdays"; else -> "on Sundays" }
    private fun daytypeHi(dow: Int) = when (Bins.daytype(dow)) { 0 -> "कार्यदिवसों में"; 1 -> "शनिवार को"; else -> "रविवार को" }
}
