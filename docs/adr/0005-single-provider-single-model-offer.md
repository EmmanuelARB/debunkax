# Single provider, single model: OpenRouter and DeepSeek V4.1 Flash only

ADR 0001 offered two ways to bring a key (Claude pasted, or OpenRouter through OAuth) and a short
model list (Claude Haiku 4.5 and Sonnet 5, GPT-5.2, DeepSeek V4.1 Flash). The model
comparison (`spikes/verdicts/MODELS.md`) benched the popular OpenRouter models against the offer on
the 46-entry golden set: DeepSeek V4.1 Flash was the only model at 52 % exact verdicts across two
independent runs, the Claude models cost 8–60× more per Check with no accuracy edge, and every model
missed about half of the Alerts anyway (a recall problem no model swap fixes). The user decided to
keep the best model only and to remove Claude BYOK entirely. This supersedes the provider choice in
ADR 0001, whose core decisions — no server, the user's own key, zero data retention per request —
stand unchanged: OpenRouter's OAuth PKCE key is still the user's own key, stored encrypted.

## Consequences

- The offer is a single model: **DeepSeek V4.1 Flash** through the forced Exa engine (`Models.all`
  has one entry; the Claude client, the pasted-key UI and the `CLAUDE` provider value are deleted,
  not disabled).
- Sign-in is OpenRouter only (OAuth PKCE). A stored `provider = CLAUDE` in an existing installation
  decodes as "no provider" (the settings repository already maps unknown names to null), so the
  affected user simply reconnects through OpenRouter — no migration, no crash.
- The model picker in Réglages now shows exactly one model; it is kept rather than hidden because a
  second model is expected to return after the recall work (the golden set will grow first).
- The Verdict bench still compares many models (they are bench-only entries), but every request now
  goes through the OpenRouter client; the Anthropic developer key in `local.properties` is no longer
  read by the app or the bench.
- If a Claude-direct path ever returns, it must earn its place against the current offer on a grown
  golden set, not on preference.
