// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.model

import kotlinx.serialization.Serializable

/**
 * One dictionary entry: a single real-world *concept*, named in every language we ship.
 *
 * ## Why one entry per concept, not per language pair
 *
 * The obvious design — a separate row for "cup→杯子" and another for "カップ→컵" — duplicates
 * the concept four times and makes the matcher four times as large, for no gain. Instead an
 * entry carries the word in *each* language ([words]) plus a short meaning per language
 * ([glosses]), and the app decides which language is the headword when it builds a card.
 *
 * That also gets the pedagogy right: recognising `cup` and producing `カップ` really are two
 * different memories, and because each becomes its own card with its own FSRS state, the
 * scheduler keeps them apart without the data model having to model "directions" at all.
 *
 * ## Why the lexicon is the source of truth
 *
 * An image labeller only ever emits a coarse label ("Coffee cup"). Turning that into a
 * learnable card is a dictionary lookup, not a model call. When there is no entry we say
 * "I don't know this one" instead of inventing a word — see [LexiconMatch] and
 * `RecognitionOutcome.NoMatch`.
 */
@Serializable
data class LexiconEntry(
    /** Stable id, conventionally `en.cup`. */
    val id: String,

    /** The concept named in each language: `{"en": "cup", "zh": "杯子", "ja": "カップ"}`. */
    val words: Map<String, String> = emptyMap(),

    /** IPA keyed by language. Only the Latin-script headword usually has one. */
    val ipa: Map<String, String> = emptyMap(),

    /** Short meaning keyed by language, primarily the user's native language. */
    val glosses: Map<String, String> = emptyMap(),

    /** Example sentence, written in [primaryLanguage]. */
    val example: String? = null,

    val exampleGlosses: Map<String, String> = emptyMap(),

    val emoji: String? = null,

    val category: String? = null,

    val tags: List<String> = emptyList(),

    /**
     * Extra strings that should resolve to this entry, lowercased.
     *
     * Needed for model labels that cannot be derived from the headword: plurals
     * (`s:cups` in ECDICT), compound labels ("Home good"), or regional synonyms.
     */
    val labelAliases: List<String> = emptyList(),

    /**
     * Corpus frequency rank, lower = more common. Sourced from the BNC word list; `0` means
     * unknown. Used to order candidates and to cap the shipped dictionary size.
     */
    val frequency: Int = 0,

    /** Where this data came from, so attribution stays traceable per entry. */
    val source: String = "builtin",
) {
    /**
     * The Latin-script headword.
     *
     * Image labellers emit English, so English is the matching key and therefore the primary
     * headword even when other languages are present.
     */
    val headword: String
        get() = words["en"] ?: words.values.firstOrNull { it.isNotBlank() } ?: id

    val primaryLanguage: String
        get() = if (words.containsKey("en")) "en" else words.keys.firstOrNull() ?: "en"

    fun word(language: Lang): String? = words[language.tag] ?: words.values.firstOrNull()

    fun word(tag: String): String? = words[tag] ?: words.values.firstOrNull()

    fun ipaFor(language: Lang): String? = ipa[language.tag] ?: ipa.values.firstOrNull()

    /** Meaning in [language]; falls back to any available gloss. */
    fun gloss(language: Lang): String? = glosses[language.tag]
        ?: glosses.values.firstOrNull { it.isNotBlank() }

    fun exampleTranslation(language: Lang): String? =
        exampleGlosses[language.tag] ?: exampleGlosses.values.firstOrNull()

    /** Turn this entry into a card whose headword is the word in [target]. */
    fun toCard(target: Lang, native: Lang, source: EntrySource): WordCard? {
        val w = words[target.tag]?.takeIf { it.isNotBlank() } ?: return null
        return WordCard(
            id = WordCard.newId(),
            headword = w,
            language = target.tag,
            ipa = ipaFor(target),
            glosses = buildMap {
                put(native.tag, gloss(native) ?: w)
                // Keep every other translation we have: it costs nothing and makes the card
                // useful to a learner who is also picking up the other languages.
                glosses.forEach { (tag, value) -> if (tag != native.tag) put(tag, value) }
            },
            example = example,
            exampleGlosses = exampleGlosses,
            emoji = emoji,
            tags = tags,
            category = category,
            source = source,
        )
    }

    companion object {
        /**
         * 「词典里还没有这个词」→ 一条用户词条。
         *
         * id 写成 `user-<语言>.<规范化的词>`，两个性质都是要用的：**稳定**，所以同一个词第二次
         * 保存落在同一条上（是覆盖，不是攒出三条只差空格的副本），而删除只需要这一个 id；
         * **看得出来源**，所以内置那一层与人写的那一层混在一起时，文件里还能一眼分辨。
         *
         * 词或释义是空白时返回 null，而不是造一条出去：`headword` 会退化成 id 本身，
         * 界面上就多出一行写着 `user-en.` 的词。
         */
        fun userEntry(word: String, gloss: String, target: Lang, native: Lang): LexiconEntry? {
            val term = word.trim()
            val meaning = gloss.trim()
            if (term.isEmpty() || meaning.isEmpty()) return null
            val slug = term.lowercase().replace(BLANKS, "-")
            return LexiconEntry(
                id = "user-${target.tag}.$slug",
                words = mapOf(target.tag to term),
                glosses = mapOf(native.tag to meaning),
                source = "user",
            )
        }

        private val BLANKS = Regex("""\s+""")
    }
}

/** On-disk lexicon file. One per source/language grouping; user files merge on top. */
@Serializable
data class LexiconFile(
    val schemaVersion: Int = 2,
    /** What this file is mostly made of, e.g. "en" or "builtin". Purely informational. */
    val language: String,
    val entries: List<LexiconEntry> = emptyList(),
)

/** One candidate produced by matching model labels against the lexicon. */
data class LexiconMatch(
    val entry: LexiconEntry,
    /** 0..1 confidence. Combines how precisely the label matched with the model's own score. */
    val confidence: Float,
    /** The model label that produced this match, for the "why did it say that?" UI. */
    val matchedLabel: String,
)

/**
 * In-memory lexicon with a match-first index.
 *
 * Design note: we deliberately do *not* hard-code the classifier's ~400 label strings. ML Kit
 * could swap models and rename labels at any time, and a lookup table keyed to one model's
 * output would rot. Labels are normalised, tokenised and matched against headwords and aliases,
 * so a dictionary improvement helps every model, and every future model inherits it.
 */
class LexiconIndex(entries: List<LexiconEntry>) {

    /** Normalised English headword -> entries. */
    private val byHeadword: Map<String, List<LexiconEntry>>

    /** Normalised alias -> entries. */
    private val byAlias: Map<String, List<LexiconEntry>>

    /** Singular -> plural, so "cups" finds "cup". */
    private val singularToPlural: Map<String, String>

    val entries: List<LexiconEntry> = entries

    init {
        val head = HashMap<String, MutableList<LexiconEntry>>()
        val alias = HashMap<String, MutableList<LexiconEntry>>()
        val plural = HashMap<String, String>()
        for (e in entries) {
            val key = normalize(e.headword)
            if (key.isNotEmpty()) head.getOrPut(key) { mutableListOf() } += e
            for (a in e.labelAliases) {
                val n = normalize(a)
                if (n.isNotEmpty()) alias.getOrPut(n) { mutableListOf() } += e
            }
            val h = normalize(e.headword)
            val p = pluralOf(h)
            if (p != h) plural[h] = p
        }
        byHeadword = head
        byAlias = alias
        singularToPlural = plural
    }

    val size: Int get() = entries.size

    /**
     * Rank lexicon entries against raw model labels.
     *
     * @param labels model output as `(text, modelScore)` pairs, highest score first.
     * @param limit maximum candidates to return.
     * @return best-first candidates; empty means the label space is outside the dictionary.
     */
    fun match(
        labels: List<Pair<String, Float>>,
        limit: Int = 8,
    ): List<LexiconMatch> {
        if (labels.isEmpty()) return emptyList()
        val results = LinkedHashMap<String, LexiconMatch>()

        for ((rawLabel, modelScore) in labels) {
            val label = normalize(rawLabel)
            if (label.isEmpty()) continue

            // 1. The whole label is the entry ("coffee cup" -> entry "coffee cup").
            consider(results, byHeadword[label], rawLabel, modelScore, exact = 1.0f)
            consider(results, byAlias[label], rawLabel, modelScore, exact = 0.97f)

            // 2. Plural / singular folding, which is what rescues "Trees" -> "tree".
            val singular = singularize(label)
            if (singular != label) {
                consider(results, byHeadword[singular], rawLabel, modelScore, exact = 0.92f)
                consider(results, byAlias[singular], rawLabel, modelScore, exact = 0.9f)
            }

            // 3. Contiguous n-grams, longest first: this is what rescues "Coffee cup" -> "cup"
            //    without needing an alias for every compound label the model might emit.
            val tokens = label.split(' ').filter { it.isNotEmpty() }
            for (n in minOf(3, tokens.size) downTo 1) {
                for (i in 0..(tokens.size - n)) {
                    val gram = tokens.subList(i, i + n).joinToString(" ")
                    val strength = when (n) {
                        3 -> 0.86f
                        2 -> 0.78f
                        else -> 0.58f
                    }
                    consider(results, byHeadword[gram], rawLabel, modelScore, strength)
                    consider(results, byAlias[gram], rawLabel, modelScore, strength * 0.95f)
                }
            }

            // 4. Reverse: the label is the plural of a known headword.
            singularToPlural[label]?.let { knownPlural ->
                consider(results, byHeadword[knownPlural], rawLabel, modelScore, exact = 0.7f)
            }
        }

        return results.values
            .sortedByDescending { it.confidence }
            .take(limit)
    }

    private fun consider(
        into: MutableMap<String, LexiconMatch>,
        candidates: List<LexiconEntry>?,
        label: String,
        modelScore: Float,
        exact: Float,
    ) {
        if (candidates.isNullOrEmpty()) return
        for (e in candidates) {
            // Multiplicative: a sloppy model label must not be able to claim a perfect match.
            val confidence = (exact * modelScore.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val existing = into[e.id]
            if (existing == null || confidence > existing.confidence) {
                into[e.id] = LexiconMatch(e, confidence, label)
            }
        }
    }

    fun byId(id: String): LexiconEntry? = entries.firstOrNull { it.id == id }

    /** Search for the "add a word" picker: matches headwords in any language, or any gloss. */
    fun search(query: String, language: Lang? = null, limit: Int = 50): List<LexiconEntry> {
        val q = normalize(query)
        return entries.asSequence()
            .filter { language == null || it.words.containsKey(language.tag) }
            .filter {
                q.isEmpty() ||
                    it.words.values.any { w -> normalize(w).contains(q) } ||
                    it.glosses.values.any { g -> g.contains(query, ignoreCase = true) }
            }
            .sortedWith(compareBy({ it.frequency.takeIf { f -> f > 0 } ?: Int.MAX_VALUE }, { it.headword }))
            .take(limit)
            .toList()
    }

    companion object {
        private val ARTICLES = setOf("a", "an", "the", "some")

        /** Lowercase, strip punctuation, collapse whitespace, drop articles. */
        fun normalize(raw: String): String = raw
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString(" ")
            .split(' ')
            .filter { it.isNotEmpty() && it !in ARTICLES }
            .joinToString(" ")

        /** Naive English plural folding: cups -> cup, buses -> bus, leaves -> leaf. */
        fun singularize(word: String): String = when {
            word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
            word.endsWith("ses") || word.endsWith("xes") || word.endsWith("zes") ||
                word.endsWith("ches") || word.endsWith("shes") ->
                if (word.length > 4) word.dropLast(2) else word

            word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && word.length > 3 ->
                word.dropLast(1)

            else -> word
        }

        /** Inverse of [singularize]; only used to index plurals. */
        fun pluralOf(word: String): String = when {
            word.endsWith("y") && word.length > 2 && word[word.length - 2] !in "aeiou" ->
                word.dropLast(1) + "ies"

            word.endsWith("s") || word.endsWith("x") || word.endsWith("z") ||
                word.endsWith("ch") || word.endsWith("sh") -> word + "es"

            else -> word + "s"
        }
    }
}
