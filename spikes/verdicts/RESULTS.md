# Verdict bench results

## OpenRouter, two-step Check (search-free pre-check, then Exa search)

46 golden entries (36 Claims, 10 non-Claims) × 4 models. The stricter "false" rule applies, reasoning is off, and every request asks for ZDR. No API errors and no JSON failures. Cost is what OpenRouter reported, $1.91 in total. Raw results in `results/` (gitignored).

| Model | Exact | Alert class | Alerts raised (correct) | Alert recall | False Alerts on accurate / unverifiable / not-a-claim | Wrong "false" | Median / P90 latency | Cost per 100 Checks |
|---|---|---|---|---|---|---|---|---|
| DeepSeek V4.1 Flash | 52 % | 63 % | 18 (14) | 14/26 | 4/20 | 4 | 4.2 / 9.1 s | $0.60 |
| Claude Haiku 4.5 | 35 % | 52 % | 13 (11) | 11/26 | 2/20 | 4 | 4.7 / 6.2 s | $0.89 |
| Claude Sonnet 5 | 39 % | 52 % | 12 (10) | 10/26 | 2/20 | 1 | 6.7 / 8.9 s | $1.47 |
| GPT-5.2 | 41 % | 52 % | 13 (12) | 12/26 | 1/20 | 4 | 5.9 / 8.8 s | $1.14 |

- Every model rejected all 10 non-Claims correctly (DeepSeek: 10/10, with 2 real Claims also rejected).
- Every model misses about half of the Claims that deserve an Alert. Most misses are rated accurate or unverifiable.
- Citations: every cited URL was on an allowlisted domain or one of its subdomains, which Exa's include_domains also matches. The first bench summaries flagged 9–18 % "out of allowlist" because their check didn't accept subdomains (fixed since). One subdomain is a problem: GPT-5.2 cited `groupe-communiste.assemblee-nationale.fr`, a party group's site, not a neutral source.
- 46 entries is small: a difference of 1–2 answers is noise. The golden set is dominated by franceinfo, itself a Trusted Source, so scores are optimistic.

**Recommendation:** keep DeepSeek V4.1 Flash as the default. It has the best accuracy and recall, the lowest cost and the lowest median latency. The price is more false Alerts (4/20 vs 1–2/20). GPT-5.2 is the careful option: the fewest false Alerts, for about twice the cost.

For scale: live, DeepSeek produced about 150 Verdicts per hour of dense debate, about $1.20 per hour.

## prompt iterations, DeepSeek V4.1 Flash only

Parliamentary subdomains excluded (`*.assemblee-nationale.fr`, `*.senat.fr`), so no party-group site can be cited. The bench now records the rewritten Claim and the Verdict's context, and `-PbenchModels=` limits it to some models. Raw results in `results/` (gitignored). Cost: $1.11 for 4 runs.

| Run | Prompt change | Exact | Recall | False Alerts |
|---|---|---|---|---|
| 201556 | baseline (subdomains excluded) | 21/46 | 14/26 | 3/20 |
| 202201 | pre-check keeps doubtful Claims and the exact meaning; no source ≠ false; no nitpicking; partial confirmation → lacks_context | 23/46 | 18/26 | 3/20 |
| 202803 | + oral wording example, + vague Claims → unverifiable | 22/46 | 14/26 | 2/20 |
| 203410 | vague-Claims rule removed (otherwise as 202201 + oral example) | 17/46 | 15/26 | 6/20 |

- **Run-to-run noise is larger than the prompt effects.** Runs 202201 and 203410 differ by one sentence, yet false Alerts went from 3 to 6. Exa returns different pages from one run to the next, and the Verdict follows the pages. One run per prompt can't rank prompts; comparing two prompts needs about 3 runs each (≈ $0.85 per prompt) or a larger golden set.
- Kept: the pre-check changes (it had rewritten "peut verbaliser" as "propose de verbaliser"), "no source is never proof it's false", and no Alert for a wording nuance. Dropped: "partial confirmation → lacks_context" (3 of the 6 false Alerts in the last run were that kind) and the vague-Claims rule (it turned real misses into "unverifiable").
- Remaining false-Alert pattern: two sources that measure different things (700 000 people who were under an OQTF vs 439 000 on the AME), and pedantry that the prompt doesn't fully stop (97 % of stations vs of the territory).
- One JSON failure in 184 Checks: a French quote inside the JSON broke parsing.

## search cache and temperature 0

`-PbenchCache` replays saved search results (see README). One live run filled it (37 entries, 74 pages; 1/20 false Alerts, 14/26 recall, $0.28). Two replays at the default temperature differed on 10 of 46 entries (false Alerts 3 and 2). With temperature 0 (now set on every OpenRouter request), two replays differed on 4 entries and both gave 1/20 false Alerts, 14–16/26 recall, for $0.009 each. Three earlier live runs were lost to a bug in the cache's first version (about $0.80).

## prompt iterations on cached searches

All runs replay the same saved searches, DeepSeek V4.1 Flash at temperature 0. OpenRouter routes DeepSeek to about 27 providers running different quantizations (fp4, fp8, unknown), which added noise, so the last six runs are pinned to Fireworks (`-PbenchProvider=fireworks`; DeepSeek's own endpoint refuses ZDR). Errors are rate limits (HTTP 429) that ran out of retries.

| Prompt | Routing | Recall | False Alerts |
|---|---|---|---|
| baseline | OpenRouter's choice, 3 runs | 14, 16, 16 /26 | 1, 1, 1 /20 |
| A: approximation "within 10 %" instead of "close" | OpenRouter's choice, 3 runs | 17, 16, 16 | 2, 1, 2 |
| A + B: a fact plus its consequence needs both confirmed | OpenRouter's choice, 3 runs | 16, 16, 17 | 2, 2, 2 |
| A + C: an indirect count (aid recipients…) can't make a population estimate false | OpenRouter's choice, 3 runs | 15, 15, 14 | 1, 1, 1 |
| baseline | Fireworks, 2 runs | 14, 16 | 0, 0 |
| A | Fireworks, 2 runs | 16, 17 | 2, 1 |
| A + C | Fireworks, 2 runs | 17, 15 | 1, 0 |

- B was dropped: it didn't change its two target Claims. A + C is kept: the 10 % rule is clearer than "close", and C stopped the 700 000 estimate from being rated "false" against 439 000 AME recipients. Against the baseline it's within one entry either way, so this is a clarity choice, not a measured gain.
- **The prompt is near its ceiling on this golden set.** The same 6 Claims end "unverifiable" in every run because the saved pages don't hold the answer (police shootings, Chat Control arrests, teacher recruitment, CEE per tank, a million euros per worker, short sentences). Only better searches can fix those, and the cache can't test that: it needs live runs (about $0.28 each).
- Two recurring misses are debatable golden labels: Dutreil "détourné" and La Réunion's price cap, both confirmed by the sources, rated lacks_context by franceinfo.
- Fireworks runs cost $0.02–0.11 against about $0.01 with OpenRouter's routing (a pricier provider, plus retried requests). In the app, the search is most of a Check's cost either way.

## rules drawn from bench failures removed

Iterating on these 46 entries was overfitting: the measured differences were 1–2 entries, within run-to-run noise, with no held-out set, and three rules were written from specific failures (the stations/territory example, the indirect-count rule, the 10 % threshold). All three are removed; the approximation rule is back to its wording from the tablet run. Kept: the pre-check preserving meaning (a real distortion), "no source is never proof it's false", no Alert for a wording nuance, temperature 0 and the party-group exclusion. From now on, the bench is for catching regressions, not for tuning; prompt changes should come from Alerts judged wrong in real sessions.
