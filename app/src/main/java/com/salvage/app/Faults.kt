package com.salvage.app

enum class Level { EASY, MAYBE, RISKY }

data class Tag(val label: String, val level: Level)

data class Analysis(val tags: List<Tag>) {
    val risky: Boolean get() = tags.any { it.level == Level.RISKY }
}

/** Reads a listing title and works out what the fault probably is. */
object Faults {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    // Order = display order. Easy bench fixes first, then gambles, then red flags.
    private val rules: List<Pair<Tag, Regex>> = listOf(
        Tag("Powers on", Level.EASY) to
            rx("""powers on|turns on (fine|ok|okay|normally)|switches on (fine|ok)|boots (up|fine|ok|to)|loads (up|to)|works (fine|perfectly|ok)"""),
        Tag("HDMI", Level.EASY) to
            rx("""hdmi|no display|no picture|no signal|no video|no image|no output"""),
        Tag("Fan / noise", Level.EASY) to
            rx("""\bfan\b|noisy|\bloud\b|grinding|rattl"""),
        Tag("Software", Level.EASY) to
            rx("""safe mode|boot ?loop|update (error|fail)|stuck on|rest mode|error code|\bce-?\d{5}|\be10\d\b|\be20\d\b|needs? (a )?reset"""),
        Tag("Disc drive", Level.EASY) to
            rx("""dis[ck] drive|won'?t read|not reading|doesn'?t read|\beject|dis[ck]s? (issue|fault|problem|not)"""),
        Tag("Cosmetic", Level.EASY) to
            rx("""crack|scratch|cosmetic|\bdent|broken (stand|cover|faceplate|plate|feet|foot)"""),
        Tag("Stick drift", Level.EASY) to
            rx("""drift|joy ?stick|thumb ?stick|analog(ue)? stick"""),
        Tag("Buttons / triggers", Level.EASY) to
            rx("""button|trigger|bumper|\b[lr][12]\b|adaptive|sticky|not clicking"""),
        Tag("Charging", Level.EASY) to
            rx("""charg|usb"""),
        Tag("Connection", Level.MAYBE) to
            rx("""connect|\bpair|bluetooth|\bsync"""),
        Tag("Battery", Level.MAYBE) to
            rx("""batter(y|ies)|won'?t hold"""),
        Tag("No power", Level.MAYBE) to
            rx("""no power|\bdead\b|won'?t (turn|power|switch|boot)|doesn'?t (turn|power|switch|boot)|not (turning|powering|switching|booting)|won'?t start|no lights?\b"""),
        Tag("Light of death", Level.MAYBE) to
            rx("""(blue|white|orange) light|\bbl(o)?d\b"""),
        Tag("Overheating", Level.MAYBE) to
            rx("""over ?heat|shut(s|ting)? (off|down)|turns? (itself )?off|switch(es)? off"""),
        Tag("Storage / SSD", Level.MAYBE) to
            rx("""\bssd\b|hard ?drive|\bhdd\b|storage"""),
        Tag("Liquid", Level.MAYBE) to
            rx("""liquid|water damage|spill|\bwet\b"""),
        Tag("Untested", Level.MAYBE) to
            rx("""untested|unknown fault|not tested|no (cables|leads|power (cable|lead))"""),
        Tag("Banned", Level.RISKY) to
            rx("""\bban(ned)?\b|blacklist|\blocked\b"""),
        Tag("Parts missing", Level.RISKY) to
            rx("""(mother)?board only|shell only|case only|housing only|no (ssd|hdd|hard ?drive|motherboard|board|apu|ram|chip|nand)\b|missing (parts|board|motherboard|apu|chips?|ram|ssd|nand)|stripped|(apu|chips?|ram|nand|ssd) (removed|missing)"""),
    )

    /** Tags from the title, plus any found in the description ([extra] = tag labels). */
    fun analyse(title: String, extra: List<String>? = null): Analysis {
        val fromTitle = rules.filter { it.second.containsMatchIn(title) }.map { it.first }
        if (extra.isNullOrEmpty()) return Analysis(fromTitle)
        // Keep the usual order; "Powers on" from the description cancels a guessed "No power".
        val all = rules.map { it.first }.filter { it in fromTitle || it.label in extra }
        val powersOn = all.any { it.label == "Powers on" }
        return Analysis(all.filter { !(powersOn && it.label == "No power" && it !in fromTitle) })
    }

    // --- Reading the description ---

    /** Words that mark a sentence as being about the fault (not postage, payment, returns...). */
    private val faultContext = rx("""fault|issue|problem|not work|n'?t work|does ?n'?t|won'?t|wont|can'?t|cannot|broken|error|spares|repair|\bdead\b|no (power|display|picture|signal|video|sound)|stopped|fails?|failed|faulty|damage|drift|sticky|untested|not tested|light|overheat|shuts?|turns? (off|on)|powers? (on|off)|boots?|hdmi|fan|noise|liquid|water|banned|missing|crack|tested|working|as seen|reason for|selling (as|because)""")
    private val boilerplate = rx("""postage|shipping|dispatch|delivery|courier|payment|paypal|return|refund|feedback|bank holiday|collection only|please message|smoke|pet|cat|dog|vat|invoice|bid only if|serious buyers|timewasters""")
    private val justSpares = rx("""[\w\s]{0,25}(spares|parts)\s*(or|and|&|/)\s*repairs?[\w\s.!]{0,20}""")
    private val negated = rx("""(n'?t|not|never|no longer|won'?t|wont|unable to|fails? to|can'?t)\s+(\w+\s+){0,2}(power|turn|switch|boot|load|work)""")

    /**
     * Pulls the seller's description of the fault out of the condition notes and description.
     * Only sentences that talk about a fault are used, so postage / returns text can't add tags.
     */
    fun readDescription(condition: String?, descriptionHtml: String?): Notes {
        val sentences = ArrayList<String>()
        condition?.let { sentences += split(it) }
        descriptionHtml?.let { sentences += split(htmlToText(it)) }

        val faultSentences = sentences
            .filter { faultContext.containsMatchIn(it) && !boilerplate.containsMatchIn(it) && !justSpares.matches(it) }
            .distinct()

        val text = faultSentences.joinToString(". ")
        val tags = rules.filter { (tag, re) ->
            if (tag.label == "Powers on") {
                // "Powers on" only counts in a sentence that isn't negated ("doesn't power on").
                faultSentences.any { re.containsMatchIn(it) && !negated.containsMatchIn(it) }
            } else re.containsMatchIn(text)
        }.map { it.first.label }

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

    /** A controller on its own (not a console that comes with one). */
    fun isControllerListing(title: String): Boolean =
        padWords.containsMatchIn(title) &&
            !consoleWords.containsMatchIn(title) &&
            !comesWithPad.containsMatchIn(title)

    /** A whole console that turned up in a controller search. */
    fun isConsoleListing(title: String): Boolean =
        consoleWords.containsMatchIn(title) || !padWords.containsMatchIn(title)
}
