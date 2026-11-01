package app.mapa.family.ui

import app.mapa.core.language.Lang
import app.mapa.family.PairResult
import app.mapa.routine.LifeTagKind

/** Family app strings. The family member picks the language; it is independent of the parent's. */
class FamilyText(val lang: Lang) {
    private fun t(en: String, hi: String) = if (lang == Lang.HI) hi else en

    val appTitle = "MaPa"
    val signInTitle = t("Stay close to your parents' daily rhythm", "अपने माता-पिता की दिनचर्या से जुड़े रहें")
    val signInBody = t(
        "MaPa shows whether your parent's phone activity looks like their usual day. It is not a medical or emergency service.",
        "MaPa बताता है कि आपके माता-पिता के फ़ोन का इस्तेमाल उनके सामान्य दिन जैसा है या नहीं। यह कोई मेडिकल या आपातकालीन सेवा नहीं है।",
    )
    val email = t("Email", "ईमेल")
    val password = t("Password (6+ characters)", "पासवर्ड (6+ अक्षर)")
    val signIn = t("Sign in", "साइन इन")
    val createAccount = t("Create account", "खाता बनाएँ")
    val withGoogle = t("Continue with Google", "Google से जारी रखें")
    val withEmail = t("Use email instead", "इसके बजाय ईमेल इस्तेमाल करें")
    val googleNote = t("New to MaPa? This creates your account.", "MaPa पर नए हैं? इससे आपका खाता बन जाता है।")
    val googleFailed = t("Google sign-in did not finish. Try again, or use email.", "Google से साइन इन पूरा नहीं हुआ। फिर से कोशिश करें, या ईमेल इस्तेमाल करें।")
    val signInFailed = t("That didn't work. Check the email and password.", "यह नहीं हुआ। ईमेल और पासवर्ड जाँचें।")

    val noParents = t("No one added yet", "अभी कोई नहीं जुड़ा")
    val noParentsBody = t(
        "Ask your parent to open MaPa on their phone and tap \"Invite a family member\". Then enter the code here.",
        "अपने माता-पिता से उनके फ़ोन पर MaPa खोलकर \"परिवार के सदस्य को जोड़ें\" दबाने को कहें। फिर यहाँ कोड डालें।",
    )
    val addParent = t("Add a parent", "माता-पिता जोड़ें")
    val codeLabel = t("6-character code", "6 अक्षर का कोड")
    val yourName = t("Your name (they'll see it)", "आपका नाम (उन्हें दिखेगा)")
    val connect = t("Connect", "जोड़ें")
    val cancel = t("Cancel", "रद्द करें")
    fun pairError(r: PairResult) = when (r) {
        PairResult.BAD_CODE -> t("That code isn't valid.", "यह कोड सही नहीं है।")
        PairResult.EXPIRED -> t("That code has expired. Ask for a new one.", "यह कोड पुराना हो गया है। नया कोड माँगें।")
        PairResult.OFFLINE -> t("Couldn't reach MaPa. Check your internet.", "MaPa से जुड़ नहीं पाए। इंटरनेट जाँचें।")
        PairResult.REFUSED -> t("The code was refused. Ask for a new one.", "कोड स्वीकार नहीं हुआ। नया कोड माँगें।")
        PairResult.NOT_SIGNED_IN -> t("Please sign in again.", "कृपया फिर से साइन इन करें।")
        PairResult.OK -> ""
    }

    val details = t("Why did this change?", "यह क्यों बदला?")
    val back = t("Back", "वापस")
    val timeline = t("Recent changes", "हाल के बदलाव")
    val noEvents = t("No changes yet.", "अभी कोई बदलाव नहीं।")
    val thisWeek = t("This week", "इस हफ़्ते")
    val differentDay = t("Is today different for them?", "क्या आज उनका दिन अलग है?")
    val differentDayBody = t(
        "Tell MaPa, so it doesn't learn today as their usual day.",
        "MaPa को बताएँ, ताकि वह आज को उनका सामान्य दिन न समझे।",
    )
    fun tag(k: LifeTagKind) = when (k) {
        LifeTagKind.UNWELL -> t("Unwell", "तबीयत ठीक नहीं")
        LifeTagKind.TRAVELLING -> t("Travelling", "यात्रा पर")
        LifeTagKind.GUESTS -> t("Guests", "मेहमान")
        LifeTagKind.FESTIVAL -> t("Festival", "त्योहार")
        LifeTagKind.OTHER -> t("Away from phone", "फ़ोन से दूर")
        else -> k.name.lowercase()
    }
    val tagged = t("Noted for today", "आज के लिए दर्ज")
    val routineTitle = t("A question from MaPa", "MaPa का एक सवाल")
    val yes = t("Yes", "हाँ")
    val no = t("No", "नहीं")
    val answered = t("Thanks, MaPa will use your answer.", "धन्यवाद, MaPa आपका जवाब इस्तेमाल करेगा।")
    val leave = t("Stop following", "फ़ॉलो करना बंद करें")
    val language = t("हिन्दी", "English")
    val signOut = t("Sign out", "साइन आउट")
    fun greeting(part: Int) = when (part) {
        0 -> t("Good morning", "सुप्रभात")
        1 -> t("Good afternoon", "नमस्ते")
        else -> t("Good evening", "शुभ संध्या")
    }
    val showAll = t("Show all", "सब देखें")
    val markDifferent = t("Mark today as different", "आज को अलग दिन बताएँ")
    // Setting up a parent (family does the work, the parent only says yes)
    val setupTitle = t("Set up a parent", "माता-पिता को जोड़ें")
    val setupBody = t("You do the setup. They only tap Yes on their phone.", "सेटअप आप करेंगे। उन्हें अपने फ़ोन पर बस हाँ दबाना है।")
    val whatCall = t("What do you call them?", "आप उन्हें क्या कहते हैं?")
    /** Quick picks for "What do you call them?", with the relation each implies. */
    val callNames = listOf(
        t("Ma", "माँ") to "mother", t("Papa", "पापा") to "father",
        t("Mummy", "मम्मी") to "mother", t("Aunty", "आंटी") to "other",
    )
    val whatCallOther = t("Or type a name", "या कोई और नाम लिखें")
    val whatCallHint = t("Nani, Bauji, Asha", "नानी, बाऊजी, आशा")
    val theirLanguage = t("Their language", "उनकी भाषा")
    val theirNumber = t("Their phone number", "उनका फ़ोन नंबर")
    val chooseContact = t("Choose from contacts", "कॉन्टैक्ट से चुनें")
    val numberWhy = t("Only on your phone, for the Call button.", "सिर्फ़ आपके फ़ोन पर, कॉल बटन के लिए।")
    val yourNameLabel = t("Your name, as they know you", "आपका नाम, जैसे वे जानते हैं")
    val createInvite = t("Create their link", "उनका लिंक बनाएँ")
    fun sendTitle(name: String) = t("Send this to $name", "$name को यह भेजें")
    val sendBody = t(
        "Send it on WhatsApp. If you are with them, open the camera on their phone and point it at this code.",
        "WhatsApp पर भेजें। अगर आप उनके पास हैं, तो उनके फ़ोन का कैमरा इस कोड की ओर करें।",
    )
    val sendWhatsApp = t("Send on WhatsApp", "WhatsApp पर भेजें")
    fun waiting(name: String) = t("Waiting for $name to tap Yes", "$name के हाँ दबाने का इंतज़ार")
    fun shareText(name: String, link: String, code: String) = t(
        "$name, I set up MaPa for you. Install the MaPa app, then tap this link: $link\nIf it doesn't open, type this code in MaPa: $code",
        "$name, मैंने आपके लिए MaPa सेट किया है। MaPa ऐप इंस्टॉल करें, फिर यह लिंक दबाएँ: $link\nअगर न खुले, तो MaPa में यह कोड डालें: $code",
    )
    fun addNumber(name: String) = t("Add $name's number for one-tap calling", "एक टैप में कॉल के लिए $name का नंबर जोड़ें")
    fun call(name: String) = t("Call $name", "$name को कॉल करें")
    val settings = t("Settings", "सेटिंग्स")
    val languageTitle = t("Language", "भाषा")
    val languageNow = t("English", "हिन्दी")
    val alertsTitle = t("Alerts", "अलर्ट")
    val alertsBody = t("Choose which alerts can make a sound.", "चुनें कि कौन से अलर्ट आवाज़ करें।")
    val accountTitle = t("Account", "खाता")
    val moreOptions = t("More options", "और विकल्प")
    val yourParents = t("Your parents", "आपके माता-पिता")
    val tapForDetails = t("Tap a card to see why it changed.", "बदलाव की वजह देखने के लिए कार्ड दबाएँ।")
    val dialExplainer = t("This line shows today. The shaded part is their usual rhythm, learned on their phone.", "यह रेखा आज का दिन दिखाती है। छाया वाला हिस्सा उनकी सामान्य दिनचर्या है, जो उनके फ़ोन पर सीखी गई।")
    val orDivider = t("New to MaPa?", "MaPa पर नए हैं?")
    val aiLabel = t("Written on this phone by on-device AI", "इस फ़ोन पर ऑन-डिवाइस AI ने लिखा")
    val notMedical = t(
        "MaPa reads phone activity patterns only. It cannot tell you about health or safety. If you're worried, call.",
        "MaPa सिर्फ़ फ़ोन इस्तेमाल का पैटर्न देखता है। यह सेहत या सुरक्षा के बारे में नहीं बता सकता। चिंता हो तो फ़ोन करें।",
    )
    // Meta AI (opt-in)
    val metaAiTitle = t("Meta AI", "Meta AI")
    val metaAiBody = t(
        "Let Meta AI write the daily note and read what you type about their day. It sees a short summary without their name, and your words. It never sees raw phone activity.",
        "Meta AI को रोज़ का नोट लिखने और उनके दिन के बारे में आपके लिखे शब्द पढ़ने दें। वह बिना नाम की छोटी जानकारी और आपके शब्द देखता है। फ़ोन का कच्चा डेटा कभी नहीं देखता।",
    )
    val metaAiSwitch = t("Use Meta AI", "Meta AI इस्तेमाल करें")
    val noteLangTitle = t("Daily note language", "रोज़ के नोट की भाषा")
    val noteLangBody = t(
        "Meta AI translates the daily note. A translation is shown only if it passes our checks; otherwise you see English.",
        "Meta AI रोज़ का नोट अनुवाद करता है। अनुवाद तभी दिखता है जब वह हमारी जाँच पास करे; नहीं तो अंग्रेज़ी दिखती है।",
    )
    val noteLangApp = t("App language", "ऐप की भाषा")
    val aiLabelCloud = t("Written by Meta AI from a short summary. No name was sent.", "Meta AI ने छोटी जानकारी से लिखा। नाम नहीं भेजा गया।")
    val describeTitle = t("Or tell MaPa in your own words", "या MaPa को अपने शब्दों में बताएँ")
    fun describeHint(subj: String) = t("$subj is at a wedding until Sunday", "$subj रविवार तक शादी में हैं")
    val sentToMeta = t("This text goes to Meta AI.", "यह लिखा हुआ Meta AI को जाता है।")
    val askMeta = t("Ask Meta AI", "Meta AI से पूछें")
    val reading = t("Reading…", "पढ़ रहा है…")
    val noTagFound = t("I could not turn that into a tag. Use the buttons above.", "इसे टैग में नहीं बदल पाया। ऊपर के बटन इस्तेमाल करें।")
    fun proposalTitle(kind: LifeTagKind, range: String) = t("${tag(kind)}: $range", "${tag(kind)}: $range")
    val addTag = t("Add", "जोड़ें")
    val askTitle = t("Ask MaPa", "MaPa से पूछें")
    fun askSuggestions(name: String) = if (lang == Lang.HI) listOf("इस हफ़्ते $name का फ़ोन कैसा रहा?", "क्या आज सामान्य से अलग था?")
        else listOf("How has $name been this week?", "Was today different from usual?")
    val askHint = t("Ask about their phone activity this week", "इस हफ़्ते उनके फ़ोन इस्तेमाल के बारे में पूछें")
    val askSent = t("Your question and a short summary without their name go to Meta AI.", "आपका सवाल और बिना नाम की छोटी जानकारी Meta AI को जाती है।")
    val askSend = t("Ask", "पूछें")
    // Tabs (Stitch's dock, in plain words)
    val tabToday = t("Today", "आज")
    val tabHistory = t("History", "पिछले दिन")
    val tabRhythm = t("Rhythm", "दिनचर्या")
    val lastSevenDays = t("Last 7 days", "पिछले 7 दिन")
    val noHistory = t("No days shared yet.", "अभी कोई दिन साझा नहीं हुआ।")
    val noData = t("No data", "डेटा नहीं")
    val noUse = t("No phone use", "फ़ोन इस्तेमाल नहीं")
    fun firstUse(time: String) = t("First use $time", "पहली बार $time")
    val pastDays = t("Past days and questions", "पिछले दिन और सवाल")
    fun askIntro(name: String) = t("Ask about $name's phone activity. MaPa answers only from the shared facts.", "$name के फ़ोन इस्तेमाल के बारे में पूछें। MaPa सिर्फ़ साझा जानकारी से जवाब देता है।")
    val askLocal = t("Answered on this phone from the shared facts. Nothing is sent.", "इसी फ़ोन पर साझा जानकारी से जवाब। कुछ भी बाहर नहीं जाता।")
    val askFromFacts = t("Answered from the shared facts, without AI.", "साझा जानकारी से जवाब, AI के बिना।")
    val emulator = t("Test backend (local emulator)", "टेस्ट बैकएंड (लोकल एमुलेटर)")
}
