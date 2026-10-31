package app.debunkax.check

import app.debunkax.domain.Provider
import app.debunkax.domain.ProviderModel

/**
 * The [ProviderModel]s offered to users. Only combinations whose web search really honours the
 * Trusted Source allowlist belong here (ADR 0001): on OpenRouter, every model through the forced
 * Exa engine, which honours include_domains. OpenRouter's native search can't be used: it only runs
 * on the model makers' own endpoints, and none of them accepts zero data retention ("No endpoints
 * found matching your data policy", checked live).
 *
 * Single-model offer: the model comparison (spikes/verdicts/MODELS.md) put DeepSeek
 * V4.1 Flash first on accuracy (52 % exact on the golden set, two independent runs) and the Claude
 * models 8–60× dearer with no quality edge; the user kept the best model only and dropped Claude.
 */
object Models {
    // Model id verified live against https://openrouter.ai/api/v1/models.
    // Open weights (MIT, huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash), with zero-data-retention
    // providers on OpenRouter (checked live). It has no native search, so the Exa engine is
    // forced: Exa honours include_domains, keeping Checks within Trusted Sources (ADR 0001).
    val OPENROUTER_DEEPSEEK_V4_1_FLASH = ProviderModel(
        provider = Provider.OPENROUTER,
        id = "deepseek/deepseek-v4.1-flash",
        label = "DeepSeek V4.1 Flash (poids ouverts)",
        openRouterEngine = "exa",
    )

    val all: List<ProviderModel> = listOf(
        OPENROUTER_DEEPSEEK_V4_1_FLASH,
    )

    val default: ProviderModel = OPENROUTER_DEEPSEEK_V4_1_FLASH

    fun find(provider: Provider, id: String): ProviderModel? =
        all.firstOrNull { it.provider == provider && it.id == id }
}
