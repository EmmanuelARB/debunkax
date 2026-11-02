/// The `ProviderModel`s offered to users. Only combinations whose web search really honours the
/// Trusted Source allowlist belong here (ADR 0001): on OpenRouter, every model through the forced
/// Exa engine, which honours include_domains. OpenRouter's native search can't be used: it only runs
/// on the model makers' own endpoints, and none of them accepts zero data retention.
///
/// Single-model offer: DeepSeek V4.1 Flash (spikes/verdicts/MODELS.md).
public enum Models {
    // Open weights, with zero-data-retention providers on OpenRouter. It has no native search, so the
    // Exa engine is forced: Exa honours include_domains, keeping Checks within Trusted Sources (ADR 0001).
    public static let openRouterDeepSeekV41Flash = ProviderModel(
        provider: .openRouter,
        id: "deepseek/deepseek-v4.1-flash",
        label: "DeepSeek V4.1 Flash (poids ouverts)",
        openRouterEngine: "exa"
    )

    public static let all: [ProviderModel] = [openRouterDeepSeekV41Flash]

    public static let `default`: ProviderModel = openRouterDeepSeekV41Flash

    public static func find(_ provider: Provider, _ id: String) -> ProviderModel? {
        all.first { $0.provider == provider && $0.id == id }
    }
}
