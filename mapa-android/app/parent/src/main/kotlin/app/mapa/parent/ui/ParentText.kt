package app.mapa.parent.ui

import app.mapa.core.language.Lang

/**
 * The parent app's words, in English and Hindi, kept in code so the language can switch
 * instantly during onboarding. Hindi needs a native-speaker review before launch.
 */
class ParentText(val lang: Lang) {
    private fun t(en: String, hi: String) = if (lang == Lang.HI) hi else en

    val listen = t("Listen", "सुनें")
    val welcomeChip = t("Welcome", "स्वागत है")
    fun step(i: Int, n: Int) = t("Step $i of $n", "चरण $i / $n")
    val next = t("Next", "आगे")
    val back = t("Back", "पीछे")

    val welcomeTitle = t("Far from home. Still connected.", "घर से दूर, फिर भी जुड़े हुए।")
    val welcomeBody = t(
        "MaPa lets your children know your day is going the way it usually does, without calls, cameras or location. You stay in control of everything.",
        "MaPa आपके बच्चों को बताता है कि आपका दिन हमेशा की तरह चल रहा है, बिना कॉल, कैमरा या लोकेशन के। सब कुछ आपके नियंत्रण में रहता है।",
    )
    val chooseLanguage = t("Choose your language", "अपनी भाषा चुनें")

    val sharesTitle = t("What your family sees", "आपका परिवार क्या देखता है")
    val shares = listOf(
        t("When this phone was last used (the time only)", "यह फ़ोन आख़िरी बार कब इस्तेमाल हुआ (सिर्फ़ समय)"),
        t("Whether today looks like your usual day", "क्या आज का दिन आपके सामान्य दिन जैसा है"),
        t("Whether the phone was carried around (walking or travelling), never where", "फ़ोन साथ लेकर चले या कहीं गए या नहीं, कहाँ गए यह कभी नहीं"),
        t("A short note about the day, written on your family member's phone from a summary without your name", "दिन के बारे में एक छोटा नोट, जो आपके परिवारजन के फ़ोन पर, आपके नाम के बिना एक छोटी जानकारी से लिखा जाता है"),
        t("Battery level, so they know if the phone may switch off", "बैटरी का स्तर, ताकि उन्हें पता रहे कि फ़ोन बंद हो सकता है"),
        t("Only if your family member switches it on in their own app: that same short summary goes to Meta AI so it can write the note. Never your raw phone activity.", "सिर्फ़ तब जब आपके परिवारजन अपने ऐप में इसे चालू करें: वही छोटी जानकारी Meta AI को जाती है ताकि वह नोट लिखे। आपके फ़ोन का कच्चा डेटा कभी नहीं।"),
    )

    val neverTitle = t("What never leaves this phone", "क्या कभी इस फ़ोन से बाहर नहीं जाता")
    val never = listOf(
        t("Which apps you use, your messages, calls or photos", "आप कौन से ऐप इस्तेमाल करते हैं, आपके मैसेज, कॉल या फ़ोटो"),
        t("Your location. MaPa never uses GPS", "आपकी लोकेशन। MaPa कभी GPS इस्तेमाल नहीं करता"),
        t("Camera, microphone or anything on your screen", "कैमरा, माइक्रोफ़ोन या आपकी स्क्रीन पर कुछ भी"),
    )

    val aiTitle = t("A small AI that learns your rhythm, on this phone", "एक छोटा AI जो इसी फ़ोन पर आपकी दिनचर्या सीखता है")
    val aiBody = t(
        "MaPa's model trains only on this phone, at night while it charges. For the first two weeks it learns quietly. It never makes health or safety judgements; it only notices when phone use is different from usual.",
        "MaPa का मॉडल सिर्फ़ इसी फ़ोन पर, रात में चार्जिंग के दौरान सीखता है। पहले दो हफ़्ते यह चुपचाप सीखता है। यह कभी सेहत या सुरक्षा के बारे में कुछ तय नहीं करता; बस यह देखता है कि फ़ोन का इस्तेमाल सामान्य से अलग है या नहीं।",
    )

    val consentTitle = t("Your choice", "आपकी मर्ज़ी")
    val nameLabel = t("What does your family call you?", "आपका परिवार आपको क्या कहकर बुलाता है?")
    val nameHint = t("For example Mom, Papa, Nani", "जैसे माँ, पापा, नानी")
    val roleMother = t("Mother", "माँ")
    val roleFather = t("Father", "पिता")
    val roleOther = t("Other", "अन्य")
    val agree = t(
        "I agree to share this phone's activity pattern with family members I invite. I can pause, remove people or delete everything at any time.",
        "मैं इस फ़ोन की गतिविधि का पैटर्न उन परिवारजनों के साथ साझा करने के लिए सहमत हूँ जिन्हें मैं जोड़ूँ। मैं कभी भी रोक सकता/सकती हूँ, लोगों को हटा सकता/सकती हूँ या सब कुछ मिटा सकता/सकती हूँ।",
    )
    val start = t("Start sharing", "साझा करना शुरू करें")
    val offlineStart = t(
        "Couldn't reach MaPa's servers. Sharing will start as soon as this phone is online.",
        "MaPa के सर्वर से जुड़ नहीं पाए। फ़ोन ऑनलाइन होते ही साझा करना शुरू हो जाएगा।",
    )

    val sharingOn = t("Sharing is on", "साझा करना चालू है")
    val sharingPaused = t("Sharing is paused", "साझा करना रुका है")
    val pause = t("Pause sharing", "साझा करना रोकें")
    val resume = t("Resume sharing", "साझा करना फिर शुरू करें")
    val showPaused = t("Let family see that I paused", "परिवार को दिखे कि मैंने रोका है")
    val preview = t("This is exactly what your family sees", "आपका परिवार ठीक यही देखता है")
    val noPreview = t("Your first update will appear here within 15 minutes.", "आपका पहला अपडेट 15 मिनट में यहाँ दिखेगा।")
    val updateNow = t("Update now", "अभी अपडेट करें")
    val aiNoteLabel = t("Written on this phone by on-device AI", "इसी फ़ोन पर ऑन-डिवाइस AI ने लिखा")

    val setupTitle = t("Keep MaPa running", "MaPa को चालू रखें")
    val usageAccess = t("Allow Usage Access (needed)", "Usage Access की अनुमति दें (ज़रूरी)")
    val usageAccessWhy = t("Lets MaPa see when the phone was used. It never sees which apps.", "इससे MaPa देख पाता है कि फ़ोन कब इस्तेमाल हुआ। यह कभी नहीं देखता कि कौन सा ऐप।")
    val battery = t("Don't let the battery saver stop MaPa", "बैटरी सेवर को MaPa रोकने न दें")
    val autostart = t("Allow MaPa to start automatically", "MaPa को अपने आप शुरू होने दें")
    val done = t("Done", "हो गया")

    val familyTitle = t("Family members", "परिवार के सदस्य")
    val noFamily = t("No one yet. Invite your children so they can see your rhythm card.", "अभी कोई नहीं। अपने बच्चों को जोड़ें ताकि वे आपका कार्ड देख सकें।")
    val invite = t("Invite a family member", "परिवार के सदस्य को जोड़ें")
    val inviteTitle = t("Invite code", "आमंत्रण कोड")
    val inviteBody = t("Ask them to enter this code in the MaPa Family app. It works for 24 hours.", "उन्हें यह कोड MaPa Family ऐप में डालने को कहें। यह 24 घंटे तक काम करेगा।")
    val shareCode = t("Send code", "कोड भेजें")
    fun shareMessage(code: String) = t(
        "Join me on MaPa. Open the MaPa Family app and enter the code $code (valid for 24 hours).",
        "MaPa पर मुझसे जुड़ें। MaPa Family ऐप खोलें और कोड $code डालें (24 घंटे के लिए मान्य)।",
    )
    val remove = t("Remove", "हटाएँ")
    val close = t("Close", "बंद करें")

    val tagTitle = t("Is today different?", "क्या आज का दिन अलग है?")
    val tagBody = t("Tell MaPa, so it doesn't learn today as your usual day.", "MaPa को बताएँ, ताकि वह आज को आपका सामान्य दिन न समझे।")
    val tagUnwell = t("Unwell", "तबीयत ठीक नहीं")
    val tagTravelling = t("Travelling", "यात्रा पर")
    val tagGuests = t("Guests", "मेहमान")
    val tagFestival = t("Festival", "त्योहार")
    val tagged = t("Noted for today", "आज के लिए दर्ज")
    val allow = t("Allow", "अनुमति दें")
    val open = t("Open", "खोलें")
    val familySub = t("Only people you invite can see your card.", "आपका कार्ड सिर्फ़ वही देख सकते हैं जिन्हें आप जोड़ते हैं।")
    val previewSub = t("Built on this phone. Raw usage never leaves it.", "इसी फ़ोन पर बना। असली इस्तेमाल का डेटा कभी बाहर नहीं जाता।")
    val pausedBody = t("Nothing is being shared. Your family sees that updates are paused.", "अभी कुछ साझा नहीं हो रहा। परिवार को दिखता है कि अपडेट रुके हैं।")
    val pausedHiddenBody = t("Nothing is being shared. Your family sees that updates are unavailable.", "अभी कुछ साझा नहीं हो रहा। परिवार को दिखता है कि अपडेट उपलब्ध नहीं हैं।")
    val onBody = t("Your family sees your rhythm card. You can pause any time.", "परिवार आपका कार्ड देखता है। आप कभी भी रोक सकते हैं।")
    fun greeting(part: Int, name: String) = when (part) {
        0 -> t("Good morning, $name", "सुप्रभात, $name")
        1 -> t("Good afternoon, $name", "नमस्ते, $name")
        else -> t("Good evening, $name", "शुभ संध्या, $name")
    }
    // Under the hood
    val hoodTitle = t("Under the hood", "अंदर क्या चलता है")
    val hoodSub = t("How MaPa works on this phone.", "इस फ़ोन पर MaPa कैसे काम करता है।")
    val hoodNet = t("Rhythm network", "दिनचर्या का नेटवर्क")
    val hoodRuns = t("Runs with", "इससे चलता है")
    val hoodSpeed = t("Time for one step", "एक कदम का समय")
    fun hoodSpeedValue(micros: Long, steps: Long) = if (micros <= 0L) "-" else t("Typically $micros µs on this phone", "इस फ़ोन पर आमतौर पर $micros µs")
    val hoodNetValue = t("MaPa Rhythm Hybrid (CNN, GRU and a 30-day LSTM), learns on this phone", "MaPa रिदम हाइब्रिड (CNN, GRU और 30 दिन का LSTM), इसी फ़ोन पर सीखता है")
    val settings = t("Settings", "सेटिंग्स")
    val sharingTitle = t("Sharing", "साझा करना")
    val familySees = t("Your family sees this. It is worked out on this phone.", "आपका परिवार यही देखता है। यह इसी फ़ोन पर तय होता है।")
    val usageNeeded = t("MaPa needs Usage Access to see when this phone is used.", "फ़ोन कब इस्तेमाल हुआ, यह देखने के लिए MaPa को Usage Access चाहिए।")
    // Family-started setup: the parent only says yes.
    val noInviteTitle = t("Your family sets this up for you", "आपका परिवार इसे आपके लिए सेट करेगा")
    val noInviteBody = t("Ask them to send you the MaPa link, then tap it.", "उनसे MaPa का लिंक भेजने को कहें, फिर उस पर टैप करें।")
    val haveCode = t("I have a code", "मेरे पास कोड है")
    val codeLabel = t("Code from your family", "परिवार से मिला कोड")
    val continueLabel = t("Continue", "आगे बढ़ें")
    fun wantsToSee(name: String) = t("$name wants to see your daily phone rhythm", "$name आपके फ़ोन की रोज़ की दिनचर्या देखना चाहते हैं")
    fun wantsBody(name: String) = t(
        "$name will see when this phone is used and whether today looks like your usual day. Never your calls, messages, photos or location.",
        "$name देख पाएँगे कि फ़ोन कब इस्तेमाल हुआ और आज का दिन आपके सामान्य दिन जैसा है या नहीं। आपकी कॉल, मैसेज, फ़ोटो या लोकेशन कभी नहीं।",
    )
    val whatShared = t("What is shared?", "क्या साझा होता है?")
    val yesShare = t("Yes, share", "हाँ, साझा करें")
    val notNow = t("Not now", "अभी नहीं")
    val linkExpired = t("This link has expired or was already used. Ask your family for a new one.", "यह लिंक पुराना है या पहले ही इस्तेमाल हो चुका है। परिवार से नया लिंक माँगें।")
    val loading = t("Opening your family's invite", "परिवार का निमंत्रण खुल रहा है")
    val oneStepTitle = t("One last step", "बस एक आख़िरी कदम")
    val oneStepBody = t("Turn on the switch next to MaPa. MaPa comes back here by itself.", "MaPa के आगे वाला बटन चालू करें। MaPa अपने आप यहाँ लौट आएगा।")
    val androidWarn = t(
        "Android will say MaPa can see which apps you use. MaPa only looks at the time the phone was used, never which apps.",
        "Android कहेगा कि MaPa देख सकता है कि आप कौन से ऐप इस्तेमाल करते हैं। MaPa सिर्फ़ समय देखता है, ऐप कभी नहीं।",
    )
    val openSwitch = t("Open the switch", "बटन खोलें")
    fun sharingWith(names: List<String>) = if (names.isEmpty()) t("Sharing is on", "साझा करना चालू है")
        else t("Sharing with ", "इनके साथ साझा: ") + names.joinToString(", ")
    val onlyWhen = t("They see when your phone is used or carried around. Never where, never what.", "उन्हें दिखता है कि फ़ोन कब इस्तेमाल हुआ या साथ ले जाया गया। कहाँ या क्या, कभी नहीं।")
    val pausedHome = t("Sharing is paused", "साझा करना रुका है")
    val more = t("Who can see, or stop sharing", "कौन देख सकता है, या साझा करना बंद करें")
    val privacyTitle = t("Privacy", "निजता")

    val learningOnPhone = t("MaPa is learning your rhythm on this phone", "MaPa इसी फ़ोन पर आपकी दिनचर्या सीख रहा है")

    val deleteAll = t("Delete everything", "सब कुछ मिटाएँ")
    val deleteConfirmTitle = t("Delete everything?", "सब कुछ मिटाएँ?")
    val deleteConfirmBody = t(
        "This stops sharing, removes your family members and deletes everything MaPa stored, on this phone and online. It can't be undone.",
        "इससे साझा करना बंद होगा, परिवार के सदस्य हटेंगे और MaPa ने जो कुछ भी इस फ़ोन पर और ऑनलाइन रखा है वह मिट जाएगा। इसे वापस नहीं लाया जा सकता।",
    )
    val deleteYes = t("Yes, delete everything", "हाँ, सब कुछ मिटाएँ")
    val cancel = t("Cancel", "रद्द करें")
    val deleteFailed = t("Couldn't reach MaPa's servers to delete online data. Please try again when online.", "ऑनलाइन डेटा मिटाने के लिए सर्वर से जुड़ नहीं पाए। ऑनलाइन होने पर फिर कोशिश करें।")
}
