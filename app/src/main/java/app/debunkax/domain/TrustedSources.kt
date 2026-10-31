package app.debunkax.domain

/**
 * [subdomains] false keeps a Check to the main site: parliamentary groups publish their own partisan pages on
 * subdomains such as groupe-communiste.assemblee-nationale.fr.
 */
data class TrustedSource(val name: String, val domain: String, val reason: String, val subdomains: Boolean = true)

/** Fixed and shown to users as-is; editing it changes Verdicts for everyone (see CONTEXT.md). */
object TrustedSources {
    val all: List<TrustedSource> = listOf(
        TrustedSource("AFP", "afp.com", "Agence de presse publique d'information, sans ligne éditoriale partisane ; vérifie les affirmations via AFP Factuel."),
        TrustedSource("Le Monde", "lemonde.fr", "Quotidien national de référence ; vérifie les affirmations via Les Décodeurs."),
        TrustedSource("franceinfo", "francetvinfo.fr", "Service d'information de France Télévisions ; vérifie les affirmations via Vrai ou Fake."),
        TrustedSource("Libération", "liberation.fr", "Quotidien national ; vérifie les affirmations via CheckNews."),
        TrustedSource("Le Figaro", "lefigaro.fr", "Quotidien national de référence."),
        TrustedSource("BFM TV", "bfmtv.com", "Chaîne d'information en continu, archives écrites de fait établi."),
        TrustedSource("France 24", "france24.com", "Chaîne publique d'information internationale."),
        TrustedSource("RFI", "rfi.fr", "Radio publique internationale, archives écrites de fait établi."),
        TrustedSource("Les Surligneurs", "lessurligneurs.eu", "Juristes universitaires vérifiant les affirmations au regard du droit."),
        TrustedSource("INSEE", "insee.fr", "Institut national de la statistique : chiffres officiels sur l'économie et la population."),
        TrustedSource("DARES", "dares.travail-emploi.gouv.fr", "Statistiques officielles sur l'emploi et le chômage."),
        TrustedSource("DREES", "drees.solidarites-sante.gouv.fr", "Statistiques officielles sur la santé et la protection sociale."),
        TrustedSource("Ministère de l'Intérieur – SSMSI", "interieur.gouv.fr", "Statistiques officielles sur la délinquance et l'immigration."),
        TrustedSource("Légifrance", "legifrance.gouv.fr", "Texte officiel des lois et décrets."),
        TrustedSource("Vie publique", "vie-publique.fr", "Information institutionnelle sur les politiques publiques."),
        TrustedSource("Assemblée nationale", "assemblee-nationale.fr", "Travaux, votes et comptes rendus officiels.", subdomains = false),
        TrustedSource("Sénat", "senat.fr", "Travaux, votes et rapports officiels.", subdomains = false),
        TrustedSource("Conseil constitutionnel", "conseil-constitutionnel.fr", "Décisions officielles sur la conformité des lois."),
        TrustedSource("Cour des comptes", "ccomptes.fr", "Contrôle indépendant des finances publiques."),
        TrustedSource("Banque de France", "banque-france.fr", "Données officielles sur l'économie, la dette et le crédit."),
        TrustedSource("Eurostat", "ec.europa.eu/eurostat", "Statistiques officielles comparables entre pays européens."),
        TrustedSource("OCDE", "oecd.org", "Données internationales comparées."),
    )

    /** True when [url] points into a Trusted Source (host, and path for entries such as eurostat). */
    fun isTrusted(url: String): Boolean {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        if (uri.scheme != "https" && uri.scheme != "http") return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty()
        return all.any { source ->
            val hostPart = source.domain.substringBefore('/')
            val pathPart = source.domain.substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
            val hostOk = host == hostPart || host == "www.$hostPart" || (source.subdomains && host.endsWith(".$hostPart"))
            hostOk && (pathPart.isEmpty() || path == pathPart || path.startsWith("$pathPart/"))
        }
    }

    val domains: List<String> = all.map { it.domain }

    /** For Exa: `*.domain` drops every subdomain but keeps the www. site (checked live). */
    val excludedSubdomains: List<String> = all.filterNot { it.subdomains }.map { "*.${it.domain}" }
}
