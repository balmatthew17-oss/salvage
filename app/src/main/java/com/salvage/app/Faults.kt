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
            rx("""connect|pair|bluetooth|\bsync"""),
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

    fun analyse(title: String) = Analysis(rules.filter { it.second.containsMatchIn(title) }.map { it.first })

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
