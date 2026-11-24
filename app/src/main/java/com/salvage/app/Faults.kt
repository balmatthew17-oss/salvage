package com.salvage.app

enum class Level { EASY, MAYBE, RISKY }

data class Tag(val label: String, val level: Level)

data class Analysis(val tags: List<Tag>) {
    val risky: Boolean get() = tags.any { it.level == Level.RISKY }
}

/** Reads a listing title and works out what the fault probably is. */
object Faults {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private class Rule(val tag: Tag, val re: Regex, val kinds: Set<Kind>)

    private val ALL = Kind.entries.toSet()
    private val CONSOLES = setOf(Kind.CONSOLE, Kind.CONTROLLER)
    private val PC = setOf(Kind.GPU, Kind.LAPTOP)

    private fun rule(label: String, level: Level, pattern: String, kinds: Set<Kind> = ALL) =
        Rule(Tag(label, level), rx(pattern), kinds)

    // Order = display order. Easy bench fixes first, then gambles, then red flags.
    private val rules: List<Rule> = listOf(
        rule("Powers on", Level.EASY,
            """powers on|turns on (fine|ok|okay|normally)|switches on (fine|ok)|boots (up|fine|ok|to)|loads (up|to)|works (fine|perfectly|ok)|detected in"""),
        rule("HDMI", Level.EASY,
            """hdmi|no display|no picture|no signal|no video|no image|no output""", setOf(Kind.CONSOLE)),
        rule("Port damage", Level.EASY,
            """(hdmi|display ?port|dp|usb-?c?) (port|socket)|(port|socket) (broken|damaged|bent|loose)|bent pins?""", PC),
        rule("Fan / noise", Level.EASY,
            """fans? (noise|noisy|issue|fault|problem|broken|not|making|loud|rattl|grind|error)|(broken|faulty|noisy|loud|dead|missing) fans?|noisy|\bloud\b|grinding|rattl|coil whine"""),
        rule("Software", Level.EASY,
            """safe mode|boot ?loop|update (error|fail)|stuck on|rest mode|error code|\bce-?\d{5}|\be10\d\b|\be20\d\b|needs? (a )?reset|bitlocker|driver (issue|crash|error)"""),
        rule("Disc drive", Level.EASY,
            """dis[ck] drive|won'?t read|not reading|doesn'?t read|\beject|dis[ck]s? (issue|fault|problem|not)""", setOf(Kind.CONSOLE)),
        rule("Screen", Level.EASY,
            """(screen|lcd|panel|display) (crack|broken|damaged|smashed|lines|flicker|dead|black|issue|fault|problem)|(cracked|broken|damaged|smashed|bad|dim) (screen|lcd|panel|hinge|lid)|backlight|hinge""", setOf(Kind.LAPTOP)),
        rule("Keyboard", Level.EASY,
            """(keyboard|keys?|trackpad|touchpad) (not|broken|missing|faulty|issue|fault|damaged|sticky|dead)|(missing|broken|sticky) keys?|(faulty|broken|damaged) (keyboard|trackpad|touchpad)""", setOf(Kind.LAPTOP)),
        rule("Cosmetic", Level.EASY,
            """scratch|cosmetic|\bdent|broken (stand|cover|faceplate|plate|feet|foot|shroud|backplate)|cracked (case|shell|plastic)"""),
        rule("Stick drift", Level.EASY,
            """drift|joy ?stick|thumb ?stick|analog(ue)? stick""", setOf(Kind.CONTROLLER)),
        rule("Buttons / triggers", Level.EASY,
            """button|trigger|bumper|\b[lr][12]\b|adaptive|sticky|not clicking""", setOf(Kind.CONTROLLER)),
        rule("Charging", Level.EASY,
            """charg|usb""", setOf(Kind.CONTROLLER)),
        rule("Charging", Level.EASY,
            """not charging|won'?t charge|doesn'?t charge|charging (port|issue|fault|problem)|charge port|dc ?jack|power jack""", setOf(Kind.LAPTOP)),
        rule("Connection", Level.MAYBE,
            """connect|\bpair|bluetooth|\bsync""", setOf(Kind.CONTROLLER)),
        rule("Battery", Level.MAYBE,
            """batter(y|ies)|won'?t hold""", setOf(Kind.CONTROLLER, Kind.LAPTOP)),
        rule("No display", Level.MAYBE,
            """no display|no picture|no signal|no video|no image|no output|black screen|code ?43|not detected|no post\b""", PC),
        rule("Artifacts", Level.MAYBE,
            """artif|artef|glitch|lines on|flicker|corrupt|crash|vram|memory (error|fault)|bsod|blue screen""", PC),
        rule("Burnt", Level.MAYBE,
            """burn|scorch|smell|melt|blown|shorted|short circuit|dead short|smoked|smoking""", PC),
        rule("Ex-mining", Level.MAYBE,
            """mining|miner|mined""", setOf(Kind.GPU)),
        rule("No power", Level.MAYBE,
            """no power|\bdead\b|won'?t (turn|power|switch|boot)|doesn'?t (turn|power|switch|boot)|not (turning|powering|switching|booting)|won'?t start|no lights?\b|no fans? spin|fans? (don'?t|doesn'?t|won'?t) spin"""),
        rule("Light of death", Level.MAYBE,
            """(blue|white|orange) light|\bbl(o)?d\b""", setOf(Kind.CONSOLE)),
        rule("Overheating", Level.MAYBE,
            """over ?heat|shut(s|ting)? (off|down)|turns? (itself )?off|switch(es)? off|thermal|throttl|hot spot|hotspot"""),
        rule("Storage / SSD", Level.MAYBE,
            """\bssd\b|hard ?drive|\bhdd\b|storage""", setOf(Kind.CONSOLE)),
        rule("No SSD / RAM", Level.MAYBE,
            """no (ssd|hdd|hard ?drive|ram|memory|storage|charger|os)\b|without (an? )?(ssd|hdd|ram|charger)|(ssd|ram|hdd) removed""", setOf(Kind.LAPTOP)),
        rule("Liquid", Level.MAYBE,
            """liquid|water damage|spill|\bwet\b"""),
        rule("Untested", Level.MAYBE,
            """untested|unknown fault|not tested|no (cables|leads|power (cable|lead))"""),
        rule("Banned / locked", Level.RISKY,
            """\bban(ned)?\b|blacklist|\blocked\b|bios (lock|password)|password (locked|protected)|computrace|icloud"""),
        rule("Parts missing", Level.RISKY,
            """(mother)?board only|shell only|case only|housing only|no (ssd|hdd|hard ?drive|motherboard|board|apu|ram|chip|nand)\b|missing (parts|board|motherboard|apu|chips?|ram|ssd|nand)|stripped|(apu|chips?|ram|nand|ssd) (removed|missing)""", CONSOLES),
        rule("Parts missing", Level.RISKY,
            """(core|gpu|die|vram|memory chips?|chips?) (removed|missing)|no (core|gpu|die)\b|pcb only|for parts only.*(no|missing)|stripped""", setOf(Kind.GPU)),
        rule("Parts missing", Level.RISKY,
            """(mother)?board only|no (motherboard|board|screen|gpu)\b|(motherboard|gpu|cpu|screen) (removed|missing)|stripped|shell only|chassis only""", setOf(Kind.LAPTOP)),
    )

    private fun rulesFor(kind: Kind) = rules.filter { kind in it.kinds }

    /** Tags from the title, plus any found in the description ([extra] = tag labels). */
    fun analyse(title: String, extra: List<String>? = null, kind: Kind = Kind.CONSOLE): Analysis {
        val rs = rulesFor(kind)
        val fromTitle = rs.filter { it.re.containsMatchIn(title) }.map { it.tag }
        if (extra.isNullOrEmpty()) return Analysis(fromTitle.distinct())
        // Keep the usual order; "Powers on" from the description cancels a guessed "No power".
        val all = rs.map { it.tag }.filter { it in fromTitle || it.label in extra }.distinct()
        val powersOn = all.any { it.label == "Powers on" }
        return Analysis(all.filter { !(powersOn && it.label == "No power" && it !in fromTitle) })
    }

    // --- Reading the description ---

    /** Words that mark a sentence as being about the fault (not postage, payment, returns...). */
    private val faultContext = rx("""fault|issue|problem|not work|n'?t work|does ?n'?t|won'?t|wont|can'?t|cannot|broken|error|spares|repair|\bdead\b|no (power|display|picture|signal|video|sound)|stopped|fails?|failed|faulty|damage|drift|sticky|untested|not tested|light|overheat|shuts?|turns? (off|on)|powers? (on|off)|boots?|hdmi|fan|noise|liquid|water|banned|missing|crack|tested|working|as seen|reason for|selling (as|because)|artif|artef|code ?43|post|detected|screen|keyboard|burn|vram|thermal|temps?\b""")
    private val boilerplate = rx("""postage|shipping|dispatch|delivery|courier|payment|paypal|return|refund|feedback|bank holiday|collection only|please message|smoke[- ]free|pet[- ]free|\bpets?\b|\bcats?\b|\bdogs?\b|\bvat\b|invoice|bid only if|serious buyers|timewasters""")
    private val justSpares = rx("""[\w\s]{0,25}(spares|parts)\s*(or|and|&|/)\s*repairs?[\w\s.!]{0,20}""")
    private val negated = rx("""(n'?t|not|never|no longer|won'?t|wont|unable to|fails? to|can'?t)\s+(\w+\s+){0,2}(power|turn|switch|boot|load|work)""")

    /**
     * Pulls the seller's description of the fault out of the condition notes and description.
     * Only sentences that talk about a fault are used, so postage / returns text can't add tags.
     */
    fun readDescription(condition: String?, descriptionHtml: String?, kind: Kind): Notes {
        val sentences = ArrayList<String>()
        condition?.let { sentences += split(it) }
        descriptionHtml?.let { sentences += split(htmlToText(it)) }

        val faultSentences = sentences
            .filter { faultContext.containsMatchIn(it) && !boilerplate.containsMatchIn(it) && !justSpares.matches(it) }
            .distinct()

        val text = faultSentences.joinToString(". ")
        val tags = rulesFor(kind).filter { r ->
            if (r.tag.label == "Powers on") {
                // "Powers on" only counts in a sentence that isn't negated ("doesn't power on").
                faultSentences.any { r.re.containsMatchIn(it) && !negated.containsMatchIn(it) }
            } else r.re.containsMatchIn(text)
        }.map { it.tag.label }.distinct()

        val summary = faultSentences.take(3)
            .map { if (it.length > 150) it.take(147).trimEnd() + "…" else it }
            .joinToString(" · ")
            .ifEmpty { null }
        return Notes(summary, tags)
    }

    private fun htmlToText(html: String): String {
        val cleaned = html
            .replace(Regex("""(?is)<(style|script)[^>]*>.*?</\1>"""), " ")
            .replace(Regex("""(?i)<br\s*/?>|</p>|</div>|</li>|</h\d>|<li[^>]*>"""), "\n")
        return android.text.Html.fromHtml(cleaned, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun split(text: String): List<String> =
        text.take(6000)
            .split(Regex("""\n+|(?<=[.!?])\s+|\s+[-–•*]\s+|\s{3,}"""))
            .map { it.replace(Regex("""\s+"""), " ").trim().trim('-', '*', '•', ' ') }
            .filter { it.length in 8..400 }

    // --- Accessory filter (eBay's console category does most of the work; this catches strays) ---

    private val alwaysJunk = rx("""empty box|box only|face ?plates?|cover plates?|\bskins?\b|charging (station|dock)""")
    private val consoleWords = rx("""console|dis[ck] edition|digital edition|\b[12] ?tb\b|\b(512|825) ?gb\b|\bslim\b|\bbundle\b""")
    private val startsWithAccessory = rx("""^\W*(new |genuine |official |sony |microsoft |wireless |white |black )*(dualsense|controller|headset|camera|media remote|remote|charger|stand|cooling)""")
    private val forConsole = rx("""\b(for|compatible with|fits)\s+(the\s+)?(sony\s+|microsoft\s+)?(ps5|playstation|xbox)""")

    fun isAccessory(title: String): Boolean {
        if (alwaysJunk.containsMatchIn(title)) return true
        if (consoleWords.containsMatchIn(title)) return false
        return startsWithAccessory.containsMatchIn(title) || forConsole.containsMatchIn(title)
    }

    private val padWords = rx("""controller|dual ?sense|joy ?pad|game ?pad|\bpads?\b""")
    private val comesWithPad = rx("""(with|w/|and|&|\+|plus|inc\.?|incl\.?|includes?|including|x ?2|two|2x?)\s*(\d\s*)?(x\s*)?(official\s+|wireless\s+|white\s+|black\s+)?(controllers?|dual ?sense|pads?)""")

    private val pcJunk = rx("""empty box|box only|(screen|lcd|keyboard|battery|charger|adapter|backplate|shroud|bracket|cooler|heatsink|fans?|case|shell|lid|palm ?rest|hinges?|cable) only|charger for|adapter for|replacement (fan|screen|keyboard|battery|cooler)|water ?block|riser|fan for|cooler for|bracket for|\bfor (asus|msi|gigabyte|zotac|palit|evga|nvidia|dell|hp|lenovo|acer|razer)\b.*\b(fan|cooler|screen|keyboard|battery|charger|heatsink)""")

    /** Spare parts / accessories that turn up in GPU and laptop searches. */
    fun isPcJunk(title: String) = pcJunk.containsMatchIn(title)

    /** A controller on its own (not a console that comes with one). */
    fun isControllerListing(title: String): Boolean =
        padWords.containsMatchIn(title) &&
            !consoleWords.containsMatchIn(title) &&
            !comesWithPad.containsMatchIn(title)

    /** A whole console that turned up in a controller search. */
    fun isConsoleListing(title: String): Boolean =
        consoleWords.containsMatchIn(title) || !padWords.containsMatchIn(title)
}
