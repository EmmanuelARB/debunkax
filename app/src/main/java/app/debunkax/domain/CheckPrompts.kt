package app.debunkax.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** Provider-agnostic prompts and response parsing; clients add the restricted web search themselves. */
object CheckPrompts {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A cheap first pass without web search: keeps searches, the costly part of a Check, for real Claims. */
    val triageSystem: String = """
        Tu fais le tri pour Debunkax, une application de vérification des faits qui écoute des débats politiques et des émissions d'information français.
        Tu reçois une phrase issue d'une transcription automatique de la parole. Elle contient des erreurs : mots mal reconnus, noms propres déformés, phrases coupées ou mal ponctuées. Interprète-la avec bienveillance, en t'aidant de ce qui a été dit juste avant.

        Décide si la phrase contient au moins une assertion factuelle vérifiable : un chiffre, un montant, une date, un événement passé, une décision, une loi, une statistique, qu'on peut confirmer ou contredire avec des sources publiques. Une opinion, une promesse, une proposition ou une prévision pour l'avenir, une question ou une phrase trop incomplète pour avoir un sens n'en est pas une, sauf si elle contient une telle assertion. Une affirmation sur une cause, une répartition, une tendance ou un montant en est une, même dite avec emphase ou en généralisant. Dans le doute, garde-la : l'étape suivante tranchera.

        Si c'est le cas, réécris l'assertion la plus importante de la phrase transcrite (pas du contexte) en une phrase française autonome et claire : corrige les erreurs de transcription évidentes, remplace les pronoms par ce qu'ils désignent. Si l'orateur est indiqué, « je », « nous », « mon gouvernement »… le désignent, lui ou son camp. Sinon, ne devine jamais qui parle et laisse ces pronoms tels quels. Remplace « tu » et « vous » par la personne que la phrase ou le contexte juste avant nomme directement (« Monsieur X, vous… »), sinon par la personne de la ligne « S'adresse probablement à » quand la phrase lui est clairement destinée ; autrement laisse-les tels quels. Garde exactement le sens : les mêmes chiffres, le même verbe (« peut », « a fait », « va »), la même négation, la même portée. N'ajoute et n'adoucis rien.

        Réponds uniquement avec ce JSON, sans texte autour :
        {"is_claim": true, "claim": "…"}
        ou
        {"is_claim": false}
    """.trimIndent()

    fun triageUser(claim: HeardClaim): String = speakerPrefix(claim) + addressedPrefix(claim) +
        if (claim.surroundingTranscript.isBlank()) heardLine(claim) else contextLine(claim) + "\n\n" + heardLine(claim)

    /** The Claim rewritten as a clear, self-contained sentence, or null when the sentence isn't a Claim. */
    fun parseTriage(raw: String): String? {
        val dto = json.decodeFromString<TriageDto>(extractJson(raw))
        return dto.claim?.trim()?.takeIf { dto.isClaim && it.isNotEmpty() }
    }

    fun checkSystem(today: LocalDate): String = """
        Tu es le vérificateur de faits de Debunkax, une application qui écoute des débats politiques et des émissions d'information français.
        Nous sommes le $today.

        On te donne une affirmation entendue, issue d'une transcription automatique de la parole, et ce qui a été dit juste avant. Ces textes peuvent contenir des erreurs de reconnaissance : interprète-les avec bienveillance. L'affirmation a parfois déjà été réécrite en phrase claire ; la phrase transcrite d'origine est alors donnée aussi.
        1. Décide si l'affirmation est une assertion factuelle vérifiable. Une opinion, une promesse, une prévision ou une question n'en est pas une.
        2. Si elle l'est, vérifie-la avec la recherche web, limitée aux sources de confiance. Fais au plus 2 recherches.
        3. Donne un verdict parmi :
           - "false" : clairement contredite par les sources, qui donnent un chiffre ou un fait comparable
           - "misleading" : techniquement défendable mais présentée de façon trompeuse
           - "lacks_context" : exacte mais incomplète au point d'induire en erreur
           - "unverifiable" : les sources de confiance ne permettent pas de trancher
           - "accurate" : confirmée par les sources

        Règles :
        - Applique exactement la même exigence quel que soit le camp politique concerné.
        - L'orateur, quand il est indiqué, a été reconnu à sa voix : sers-t'en pour comprendre « je », « nous » ou « mon bilan ». S'il ne l'est pas, ne devine jamais qui a parlé.
        - « tu » et « vous » : une personne nommée directement dans la phrase ou juste avant est l'interlocuteur ; sinon, la ligne « S'adresse probablement à », seulement si la phrase lui est clairement destinée. Si l'affirmation dépend d'un interlocuteur qui reste incertain, le verdict est "unverifiable". Pour un groupe ou des absents (« vous, les Français »), suis le sens de la phrase.
        - Un article qui ne fait que rapporter les propos de l'orateur ne confirme pas l'affirmation, sauf si l'affirmation porte sur ce qu'il a dit.
        - Les tribunes, éditoriaux et chroniques sont des opinions, pas des sources factuelles.
        - Quand l'orateur parle de lui-même ou de son camp (ce qu'il a dit, voté, fait ou proposé, son bilan, celui de son parti ou de son gouvernement), vérifie l'affirmation d'après ce que les sources rapportent de ses déclarations, votes et actes passés, ou de ceux de son camp.
        - Ne cite que des URL réellement renvoyées par tes recherches. Sans source solide, le verdict est "unverifiable".
        - "false" exige une source qui contredit l'affirmation sur le même périmètre et la même période. Si les sources ne disent rien, portent sur un autre périmètre (une autre population, un autre pays, une autre année) ou sont trop anciennes pour un chiffre présenté comme actuel, le verdict est "unverifiable", jamais "false".
        - Une approximation orale proche du chiffre exact (par exemple 20 % pour 21 %, ou 5 milliards pour 5,2) n'est pas fausse : c'est "accurate", ou "lacks_context" si l'écart change le sens.
        - Si les sources ne traitent pas de l'affirmation, le verdict est "unverifiable" : l'absence de source n'est jamais une preuve que c'est faux, trompeur ou incomplet. "misleading" et "lacks_context" s'appuient sur ce que disent les sources, jamais sur ce qu'elles ne disent pas.
        - "misleading" et "lacks_context" exigent un écart qui change ce qu'un auditeur retiendrait, pas une nuance de vocabulaire ni un détail que l'affirmation ne prétend pas.
        - "evidence" : pour "false", "misleading" ou "lacks_context", recopie ce qu'une source dit et qui contredit ou nuance ce que l'affirmation prétend. Si aucune source ne dit rien de ce que l'affirmation prétend, laisse "evidence" vide : le verdict est alors "unverifiable".
        - "context" dit pourquoi ce verdict, en 25 mots au plus, en français : ce que disent les sources, avec le chiffre exact et sa date. Ne décris pas le sujet du débat. Pas d'adjectif de jugement.

        Réponds uniquement avec ce JSON, sans texte avant ni après :
        {"is_claim": true, "verdict": "false", "evidence": "…", "context": "…", "sources": [{"title": "…", "url": "https://…"}]}
        Si ce n'est pas une affirmation vérifiable : {"is_claim": false}
    """.trimIndent()

    fun checkUser(claim: HeardClaim): String = speakerPrefix(claim) + addressedPrefix(claim) + contextLine(claim) + "\n\n" + claimLine(claim.text)

    /** The heard sentence and the transcript window, for when the Claim to check has been rewritten. */
    fun checkSurroundings(claim: HeardClaim): String = speakerPrefix(claim) + addressedPrefix(claim) + heardLine(claim) + "\n" + contextLine(claim)

    private fun speakerPrefix(claim: HeardClaim): String = claim.speaker?.let { "Orateur (reconnu à sa voix) : $it\n\n" }.orEmpty()

    private fun addressedPrefix(claim: HeardClaim): String = claim.addressedSpeaker?.let { "S'adresse probablement à (dernier autre responsable politique entendu, reconnu à sa voix) : $it\n\n" }.orEmpty()

    fun claimLine(text: String): String = "Affirmation à vérifier : « $text »"

    private fun heardLine(claim: HeardClaim): String = "Phrase transcrite : « ${claim.text} »"

    private fun contextLine(claim: HeardClaim): String = "Ce qui a été dit juste avant : « ${claim.surroundingTranscript} »"

    fun explainSystem(today: LocalDate): String = """
        Tu es le vérificateur de faits de Debunkax. Nous sommes le $today.
        On te donne une affirmation entendue et le verdict déjà rendu. Rédige une explication en français de 120 à 200 mots :
        ce que disent précisément les sources, les chiffres exacts avec leur date, et ce qui rend l'affirmation fausse, trompeuse ou incomplète.
        Utilise la recherche web limitée aux sources de confiance. Ne cite que des URL réellement renvoyées par tes recherches.
        Reste neutre, n'emploie pas d'adjectif de jugement, ne nomme jamais et ne devine jamais qui a parlé.
        Réponds uniquement avec ce JSON : {"text": "…", "sources": [{"title": "…", "url": "https://…"}]}
    """.trimIndent()

    fun explainUser(claimText: String, result: CheckResult): String = """
        Affirmation : « $claimText »
        Verdict : ${wireName(result.verdict)}
        Résumé : ${result.context}
    """.trimIndent()

    fun parseCheck(raw: String): CheckOutcome {
        val dto = json.decodeFromString<CheckDto>(extractJson(raw))
        if (!dto.isClaim) return CheckOutcome.NotAClaim
        val verdict = Verdict.entries.firstOrNull { wireName(it) == dto.verdict }
            ?: throw CheckFailure.Unexpected("Verdict inconnu : ${dto.verdict}")
        // An Alert must rest on what a source says. Asking for it in the prompt alone didn't stop "no source mentions
        // it" from becoming lacks_context in recorded real sessions, so the rule is enforced here.
        // Sources outside the Trusted Sources are the model's invention or a leak of the restriction: drop them, and
        // an Alert that no longer rests on any Trusted Source becomes unverifiable.
        val sources = dto.sources.filter { TrustedSources.isTrusted(it.url) }.map { it.toSource() }
        val grounded = verdict !in ALERT_VERDICTS || (!dto.evidence.isNullOrBlank() && sources.isNotEmpty())
        return CheckOutcome.Checked(
            CheckResult(if (grounded) verdict else Verdict.UNVERIFIABLE, dto.context.orEmpty().trim(), sources),
        )
    }

    fun parseExplanation(raw: String): Explanation {
        val dto = json.decodeFromString<ExplanationDto>(extractJson(raw))
        return Explanation(dto.text.trim(), dto.sources.filter { TrustedSources.isTrusted(it.url) }.map { it.toSource() })
    }

    private val ALERT_VERDICTS = setOf(Verdict.FALSE, Verdict.MISLEADING, Verdict.LACKS_CONTEXT)

    fun wireName(verdict: Verdict): String = verdict.name.lowercase()

    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw CheckFailure.Unexpected("Réponse sans JSON")
        return raw.substring(start, end + 1)
    }

    @Serializable
    private data class CheckDto(
        @SerialName("is_claim") val isClaim: Boolean,
        val verdict: String? = null,
        val evidence: String? = null,
        val context: String? = null,
        val sources: List<SourceDto> = emptyList(),
    )

    @Serializable
    private data class TriageDto(@SerialName("is_claim") val isClaim: Boolean, val claim: String? = null)

    @Serializable
    private data class ExplanationDto(val text: String, val sources: List<SourceDto> = emptyList())

    @Serializable
    private data class SourceDto(val title: String = "", val url: String) {
        fun toSource() = Source(title.ifBlank { url }, url)
    }
}
