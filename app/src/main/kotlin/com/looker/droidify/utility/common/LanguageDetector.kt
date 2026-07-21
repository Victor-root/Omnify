package com.looker.droidify.utility.common

/**
 * Best-effort, dependency-free detection of the natural language a run of UI text is written in — used
 * only to name the single base language of a MONOLINGUAL app whose compiled resources carry no locale
 * qualifier at all. Android's unqualified `res/values/` has no language tag, so a French-authored app
 * with no other translations is otherwise indistinguishable from an English one (the resource table
 * just reports "the default config"); reading the actual base strings and detecting their language is
 * the only way to tell "this app is in French" from "this app is in English".
 *
 * Deliberately conservative: returns null ("couldn't tell") whenever there's too little text, or the two
 * best-scoring languages are too close together to separate confidently. A wrong guess (labelling a
 * French app "German") is worse than an honest "undetermined", so the thresholds favour null over a
 * coin-flip.
 *
 * Two signals, strongest first: a writing-script check that alone pins down the languages that don't
 * share their script with any other common one (Korean, Japanese, Chinese, Thai, Hebrew, Greek, …),
 * then, for the many languages sharing the Latin / Cyrillic / Arabic scripts, scoring the text's word
 * tokens against per-language high-frequency function-word ("stopword") sets — the standard lightweight
 * approach. Never throws.
 */
object LanguageDetector {

    /** Enough characters of a non-shared script to call it: real UI text of such an app is overwhelmingly
     *  that script, while an incidental loanword or symbol in another language's strings never reaches
     *  this many, so it cleanly separates "written in Korean" from "one Korean word in an English app." */
    private const val MIN_SCRIPT_CHARS = 8

    /** Below this many word tokens the stopword signal is too thin to trust — return null instead. */
    private const val MIN_TOKENS = 20

    /** The winning language must have at least this many stopword hits… */
    private const val MIN_STOPWORD_HITS = 5

    /** …and be at least this many times ahead of the runner-up, so two similar languages (e.g. Danish vs
     *  Norwegian, Spanish vs Catalan) resolve to null rather than an arbitrary pick when the text doesn't
     *  clearly favour one. */
    private const val MIN_LEAD_RATIO = 1.3

    /** Only the first slice of a potentially large concatenation is scored — plenty to detect a language,
     *  and bounds the work. */
    private const val MAX_SAMPLE_CHARS = 20_000

    /**
     * The BCP47 language code [text] is most likely written in (e.g. "fr", "de", "ru", "ja"), or null
     * when it can't be told confidently. Two-letter ISO 639-1 where one exists.
     */
    fun detect(text: String): String? {
        val sample = text.take(MAX_SAMPLE_CHARS)
        scriptLanguage(sample)?.let { return it }
        return stopwordLanguage(sample)
    }

    /**
     * A language pinned down by its writing script alone — only for scripts not shared by another common
     * language, so this can never be wrong the way a Latin/Cyrillic/Arabic guess could. Chooses the
     * dominant script by character count; returns null for the shared scripts (handled by [stopwordLanguage])
     * and when there isn't enough of any one script.
     */
    private fun scriptLanguage(text: String): String? {
        var hangul = 0
        var kana = 0
        var han = 0
        var thai = 0
        var hebrew = 0
        var greek = 0
        var georgian = 0
        var armenian = 0
        for (ch in text) {
            when (ch.code) {
                in 0xAC00..0xD7A3, in 0x1100..0x11FF, in 0x3130..0x318F -> hangul++
                in 0x3040..0x30FF -> kana++
                in 0x4E00..0x9FFF -> han++
                in 0x0E00..0x0E7F -> thai++
                in 0x0590..0x05FF -> hebrew++
                in 0x0370..0x03FF -> greek++
                in 0x10A0..0x10FF -> georgian++
                in 0x0530..0x058F -> armenian++
            }
        }
        // Kana present at all means Japanese (Japanese mixes kana with Han; Chinese uses no kana).
        if (kana >= MIN_SCRIPT_CHARS) return "ja"
        val best = listOf(
            "ko" to hangul, "th" to thai, "he" to hebrew, "el" to greek,
            "ka" to georgian, "hy" to armenian, "zh" to han,
        ).maxByOrNull { it.second } ?: return null
        return if (best.second >= MIN_SCRIPT_CHARS) best.first else null
    }

    /**
     * The Latin/Cyrillic/Arabic-script language whose high-frequency function words best match [text].
     * The stopword sets are naturally script-specific (French words are Latin, Russian words Cyrillic),
     * so scoring against every set at once can't cross scripts. Null when too little text, too few hits,
     * or the top two are too close (see [MIN_LEAD_RATIO]).
     */
    private fun stopwordLanguage(text: String): String? {
        val tokens = text.lowercase().split(NON_LETTER).filter { it.isNotEmpty() }
        if (tokens.size < MIN_TOKENS) return null
        val ranked = STOPWORDS
            .map { (lang, set) -> lang to tokens.count { it in set } }
            .sortedByDescending { it.second }
        val top = ranked.firstOrNull() ?: return null
        if (top.second < MIN_STOPWORD_HITS) return null
        val second = ranked.getOrNull(1)?.second ?: 0
        if (second > 0 && top.second < second * MIN_LEAD_RATIO) return null
        return top.first
    }

    /** Splits on any run of non-letter characters (Unicode-aware, so Cyrillic/Arabic/… letters are kept). */
    private val NON_LETTER = Regex("""[^\p{L}]+""")

    private fun words(vararg s: String): Set<String> = s.flatMap { it.split(' ') }.toSet()

    /** ~20-35 high-frequency function words per language — enough to separate the common cases while
     *  staying compact. Covers the languages F-Droid apps are actually authored in; a base language
     *  outside this set simply returns null (honest "undetermined") rather than a wrong guess. */
    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "en" to words("the and to of a in is you that it for on with as are this be or at not your all can will if we an has but from"),
        "fr" to words("le la les de des un une et à est en que qui pour vous dans ne pas ce cette du au sur avec plus par il elle nous votre sont ou se son"),
        "de" to words("der die das und ist zu den ein eine nicht mit sie auf für von dem im sich werden wird oder auch aus an bei dass wenn kann wie nur"),
        "es" to words("el la los las de un una y es en que por para con no se su del al lo como más pero este esta son si tu todo"),
        "it" to words("il lo la gli le di un una e è che per con non si del al come più questo questa sono se ma nel alla della"),
        "pt" to words("o a os as de um uma e é que para com não se do da no na em por mais como mas ou seu sua você este está"),
        "nl" to words("de het een en van is te dat op niet met voor zijn aan er om ook maar of naar deze wordt kan als bij"),
        "ca" to words("el la els les de un una i és en que per amb no es del al més però aquest aquesta són si tot com"),
        "ro" to words("și de la un o în cu pe este nu se care pentru sau dar mai ca din prin acest această sunt ale"),
        "pl" to words("i w na nie że do się z jest to o jak dla po ale lub przez tak może są być tylko już od bez"),
        "cs" to words("a v na se je že s do o k z ale nebo jako pro aby když už také jsou být tento této této"),
        "sk" to words("a v na sa je že s do o k z ale alebo ako pre aby keď už tiež sú byť tento táto toto"),
        "sv" to words("och att det som en är på för med av den till inte om ett har de du kan men eller vad"),
        "da" to words("og at det som en er på for med af den til ikke om et har de du kan men eller hvad"),
        "nb" to words("og å det som en er på for med av den til ikke om et har de du kan men eller hva"),
        "fi" to words("ja on ei että se ne tai kun mutta jos sekä kuin myös vain voi ole olla tämä nämä"),
        "hu" to words("a az és hogy nem egy is van meg de csak már ez vagy mint ha még amikor ből ban"),
        "tr" to words("ve bir bu için ile çok da de ne var daha ama veya gibi kadar olarak ise mı mi"),
        "id" to words("dan yang di ke dari untuk dengan ini itu pada adalah tidak atau akan juga bisa dalam"),
        "vi" to words("và của là có không được cho với các những một này để trong khi hoặc người khi"),
        "ru" to words("и в не на что с по это как к из у за от для вы но о же так все был если или вас мы"),
        "uk" to words("і в не на що з до як це за від для ви але або так усе ваш який ще вже про ми"),
        "bg" to words("и в не на че с за да се от по това как към или но да са ще във един тази"),
        "ar" to words("في من على إلى عن مع هذا أن لا ما أو كل قد هو هي التي الذي هذه إن كان"),
        "fa" to words("و در به از که این را با برای است می تا هم یا بر آن شما ما هر یک بود"),
    )
}
