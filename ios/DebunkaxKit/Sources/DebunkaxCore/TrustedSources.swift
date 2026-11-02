import Foundation

/// `subdomains` false keeps a Check to the main site: parliamentary groups publish their own partisan pages on
/// subdomains such as groupe-communiste.assemblee-nationale.fr.
public struct TrustedSource: Hashable, Sendable {
    public let name: String
    public let domain: String
    public let reason: String
    public let subdomains: Bool

    public init(name: String, domain: String, reason: String, subdomains: Bool = true) {
        self.name = name
        self.domain = domain
        self.reason = reason
        self.subdomains = subdomains
    }
}

/// Fixed and shown to users as-is; editing it changes Verdicts for everyone (see CONTEXT.md).
public enum TrustedSources {
    public static let all: [TrustedSource] = [
        TrustedSource(name: "AFP", domain: "afp.com", reason: "Agence de presse publique d'information, sans ligne éditoriale partisane ; vérifie les affirmations via AFP Factuel."),
        TrustedSource(name: "Le Monde", domain: "lemonde.fr", reason: "Quotidien national de référence ; vérifie les affirmations via Les Décodeurs."),
        TrustedSource(name: "franceinfo", domain: "francetvinfo.fr", reason: "Service d'information de France Télévisions ; vérifie les affirmations via Vrai ou Fake."),
        TrustedSource(name: "Libération", domain: "liberation.fr", reason: "Quotidien national ; vérifie les affirmations via CheckNews."),
        TrustedSource(name: "Le Figaro", domain: "lefigaro.fr", reason: "Quotidien national de référence."),
        TrustedSource(name: "BFM TV", domain: "bfmtv.com", reason: "Chaîne d'information en continu, archives écrites de fait établi."),
        TrustedSource(name: "France 24", domain: "france24.com", reason: "Chaîne publique d'information internationale."),
        TrustedSource(name: "RFI", domain: "rfi.fr", reason: "Radio publique internationale, archives écrites de fait établi."),
        TrustedSource(name: "Les Surligneurs", domain: "lessurligneurs.eu", reason: "Juristes universitaires vérifiant les affirmations au regard du droit."),
        TrustedSource(name: "INSEE", domain: "insee.fr", reason: "Institut national de la statistique : chiffres officiels sur l'économie et la population."),
        TrustedSource(name: "DARES", domain: "dares.travail-emploi.gouv.fr", reason: "Statistiques officielles sur l'emploi et le chômage."),
        TrustedSource(name: "DREES", domain: "drees.solidarites-sante.gouv.fr", reason: "Statistiques officielles sur la santé et la protection sociale."),
        TrustedSource(name: "Ministère de l'Intérieur – SSMSI", domain: "interieur.gouv.fr", reason: "Statistiques officielles sur la délinquance et l'immigration."),
        TrustedSource(name: "Légifrance", domain: "legifrance.gouv.fr", reason: "Texte officiel des lois et décrets."),
        TrustedSource(name: "Vie publique", domain: "vie-publique.fr", reason: "Information institutionnelle sur les politiques publiques."),
        TrustedSource(name: "Assemblée nationale", domain: "assemblee-nationale.fr", reason: "Travaux, votes et comptes rendus officiels.", subdomains: false),
        TrustedSource(name: "Sénat", domain: "senat.fr", reason: "Travaux, votes et rapports officiels.", subdomains: false),
        TrustedSource(name: "Conseil constitutionnel", domain: "conseil-constitutionnel.fr", reason: "Décisions officielles sur la conformité des lois."),
        TrustedSource(name: "Cour des comptes", domain: "ccomptes.fr", reason: "Contrôle indépendant des finances publiques."),
        TrustedSource(name: "Banque de France", domain: "banque-france.fr", reason: "Données officielles sur l'économie, la dette et le crédit."),
        TrustedSource(name: "Eurostat", domain: "ec.europa.eu/eurostat", reason: "Statistiques officielles comparables entre pays européens."),
        TrustedSource(name: "OCDE", domain: "oecd.org", reason: "Données internationales comparées."),
    ]

    /// True when `url` points into a Trusted Source (host, and path for entries such as eurostat).
    public static func isTrusted(_ url: String) -> Bool {
        guard let components = URLComponents(string: url.trimmingCharacters(in: .whitespacesAndNewlines)),
            components.scheme == "https",
            let rawHost = components.host, !rawHost.isEmpty
        else { return false }
        let host = rawHost.lowercased()
        let path = components.path
        return all.contains { source in
            let hostPart: String
            let pathPart: String
            if let slash = source.domain.firstIndex(of: "/") {
                hostPart = String(source.domain[..<slash])
                pathPart = "/" + String(source.domain[source.domain.index(after: slash)...])
            } else {
                hostPart = source.domain
                pathPart = ""
            }
            let hostOk = host == hostPart || host == "www.\(hostPart)" || (source.subdomains && host.hasSuffix(".\(hostPart)"))
            return hostOk && (pathPart.isEmpty || path == pathPart || path.hasPrefix("\(pathPart)/"))
        }
    }

    public static let domains: [String] = all.map(\.domain)

    /// For Exa: `*.domain` drops every subdomain but keeps the www. site (checked live).
    public static let excludedSubdomains: [String] = all.filter { !$0.subdomains }.map { "*.\($0.domain)" }
}
