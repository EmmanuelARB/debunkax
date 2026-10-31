# Model comparison — OpenRouter "most popular" vs the current offer

46 golden entries × 7 models, real two-step Checks (search-free pre-check, then searched
Check), reasoning off, temperature 0, ZDR, Exa with the Trusted Sources allowlist. All models read the
**same cached search pages** (`search-cache`, 37/46 entries cached; the misses are searched once and
shared), so score differences are the models' doing. `-PbenchParallelism=3`. Raw results in
`results/` (gitignored). Candidates were picked from OpenRouter's popularity
leaderboard (openrouter.ai/rankings); GPT-5.2 was dropped from the offer before the
run.

| Model | Exact | Alert class | Not-a-claim | False Alerts* | Med / P90 (s) | Cost / 100 Checks | Untrusted citations |
|---|---|---|---|---|---|---|---|
| DeepSeek V4.1 Flash (listed, default) | 24/46 | 33/46 | 10/10 | 2/20 | 4.0 / 9.3 | $0.02 † | 0 |
| MiMo V2.6 Flash (candidate) | 22/46 | 28/46 | 10/10 | **0/20** | 9.9 / 23.2 | $0.05 | 0 |
| DeepSeek V4 Flash 0731 (candidate) | 21/44 | 27/44 | 9/10 | 1/19 | 5.0 / 12.0 | $0.03 | 0 |
| GPT-6 Luna (candidate) | 19/46 | 31/46 | 10/10 | 2/20 | **3.2 / 4.6** | $0.04 | 0 |
| Claude Sonnet 5 (listed) | 19/46 | 26/46 | 10/10 | 1/20 | 5.3 / 7.6 | $1.16 | 0 |
| Claude Haiku 4.5 (listed) | 19/46 | 25/46 | 9/10 | 2/20 | 4.1 / 5.5 | $0.48 | 0 |
| GLM 5.3 Flash, `reasoning: {effort: low}` (2nd run) | 22/46 | 28/46 | 10/10 | 1/20 | 5.3 / 13.8 | $0.05 | 0 |

\* False Alerts = an alert verdict (false / misleading / lacks_context) on an entry that is accurate,
unverifiable or not a claim.

† V4.1 Flash cost as reported by OpenRouter at the time; an earlier run measured $0.60 per 100 on the
same model. Either DeepSeek cut the price ~30× or OpenRouter under-reports cost for it — verify before
quoting this number.

- **GLM 5.3 Flash refuses `reasoning: {enabled: false}`** ("Reasoning is mandatory for this endpoint and
  cannot be disabled", 46/46 api_error on the first run) **but accepts `reasoning: {effort: low}`**
  (second run): 22/46 exact, 1 false Alert, 5.3 s median / 13.8 s p90,
  $0.05 per 100 Checks, no untrusted citations. "Low" effort keeps reasoning output tiny (≈ 270 output
  tokens per request on average), so the forced reasoning costs almost nothing. Offering it requires the
  client's `reasoningEffort` option, currently wired bench-only (`-PbenchReasoningEffort`).
- DeepSeek V4 Flash 0731 lost 2 of 46 entries to a transient "Pas de connexion".
- **DeepSeek V4.1 Flash with `reasoning: {effort: low}` is strictly worse** (run
  same cached pages): 21/46 exact vs 24, alert-class 30/46 vs 33,
  recall 15/26 vs 18, the same 2 false Alerts, 7.8 s median vs 4.0 s, ≈ 6× the cost. Of the 12 changed
  verdicts, 6 were within-alert-class label swaps and 3 were lost Alerts; none gained. Confirms the
  earlier decision to run with reasoning off.
- 46 entries: a difference of 1–3 is noise (project rule). No citation left the allowlist for any model.

**Read:** no candidate beats DeepSeek V4.1 Flash on exact accuracy (52 %); the closest are MiMo V2.6
Flash and V4 Flash 0731 at 48 %, inside the noise band. The new value is in **cost and speed**: V4
Flash 0731 (≈ $0.03/100, 5 s) and GPT-6 Luna (≈ $0.04/100, 3 s) are 10–20× cheaper than the listed
Anthropic models at comparable accuracy, and MiMo is the only model with **zero** false Alerts (at the
price of a 23 s p90). GLM 5.3 Flash with low reasoning joins the 48 % group at $0.05/100 and a 5.3 s
median — OpenRouter's most-used model this month, usable only with the effort-level workaround.

**Recommendation:** keep DeepSeek V4.1 Flash as the default. Add **DeepSeek V4 Flash 0731** to the
offer as the cheap option (same family, ~1/10 the price, accuracy within noise). GPT-6 Luna is a
reasonable fast alternative; MiMo only if false-Alert-free beats speed for you. Sonnet 5 and Haiku keep
no accuracy edge over V4.1 Flash at 6–60× the cost — keep or drop is a preference call.
