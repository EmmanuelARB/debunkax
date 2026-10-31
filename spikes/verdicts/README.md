# Verdict bench

Compares the 6 models in `app/src/main/java/app/debunkax/check/Models.kt` on Verdict accuracy,
JSON validity, latency and cost, against a golden set of Claims taken from real, published
fact-checks by Trusted Source outlets (`golden.json`).

## Running it

The bench is a normal JUnit test (`app/src/test/java/app/debunkax/bench/VerdictBench.kt`) that
uses the app's real `ClaudeCheckClient` / `OpenRouterCheckClient` and real prompts. It is skipped
with `org.junit.Assume` unless an API key is present, so it never runs — and never costs anything —
during a normal `./gradlew testDebugUnitTest`.

```sh
export JAVA_HOME=$(dirname $(dirname $(readlink -f /usr/bin/java)))
ANTHROPIC_API_KEY=sk-ant-... OPENROUTER_API_KEY=sk-or-... \
  ./gradlew testDebugUnitTest --tests '*VerdictBench*' -Pbench
```

Either key alone is enough; only the models whose provider has a key present are benched. Requests
run sequentially, one model at a time, with a 400 ms pause between Checks — a full run against all
6 models costs a little under $1 at current prices and takes on the order of 15-20 minutes (see the
price table in `VerdictBench.kt`).

### Testing a prompt change cheaply: the search cache

```sh
./gradlew testDebugUnitTest --rerun --tests '*VerdictBench*' -Pbench \
  -PbenchModels=deepseek/deepseek-v4.1-flash -PbenchCache=search-cache
```

`-PbenchModels` limits the run to those model ids (comma-separated). `-PbenchCache=<name>` saves each
entry's search results in `results/<name>.json` the first time, then replays them: the searched
request goes out without the search plugin, with the saved pages added to the system message, much
as OpenRouter's web plugin does. `-PbenchProvider=<slug>` (e.g. `fireworks`) sends every OpenRouter request to one provider, keeping ZDR, and
retries when it rate-limits: OpenRouter otherwise spreads DeepSeek over providers running different
quantizations. The CSV's `served_by` column says who answered. `--rerun` is needed because Gradle otherwise skips a test run whose
inputs haven't changed.

Why: Exa returns different pages from one run to the next, and the Verdict follows the pages, so
false Alerts moved from 3/20 to 6/20 between two nearly identical prompts. With the cache and
temperature 0, two replays of the same prompt differ on about 4 of 46 entries. A replay with
DeepSeek costs about $0.01, against $0.28 for a live run. Run each prompt 2–3 times and compare.

The cache freezes the searches, so it can't tell whether a prompt change makes the model search
better, and it doesn't apply to Claude models (their search runs inside Anthropic's API). Refresh it
(delete the file) when the golden set or the Trusted Sources change.

### Gradle-free run

`VerdictBench.kt` also has a top-level `fun main()` that does the same run without JUnit. From an
IDE (Android Studio / IntelliJ) with the project synced, set `ANTHROPIC_API_KEY` /
`OPENROUTER_API_KEY` in the run configuration's environment and use the gutter "Run" icon on
`main()` in `app/src/test/java/app/debunkax/bench/VerdictBench.kt`. It still relies on Gradle to
resolve the module's dependencies (OkHttp, kotlinx-serialization, coroutines) — there's no
Gradle-free classpath here — but it skips the `testDebugUnitTest` task entirely.

### Pointing at a different golden set

```sh
BENCH_GOLDEN=/path/to/other-golden.json ./gradlew testDebugUnitTest --tests '*VerdictBench*' -Pbench
```

or pass `-DBENCH_GOLDEN=...` as a JVM system property. Defaults to `spikes/verdicts/golden.json`.

## Real sessions (held-out set)

`sessions/` holds Claims from real tablet sessions, with the Verdict each got and a judgment of every Alert
(`ok`, `wrong`, `doubtful`). `./gradlew testDebugUnitTest --tests '*SessionReplay*' -Pbench --rerun` re-checks
their Alerts with DeepSeek and live search (about $0.30 for the two sessions) and writes `results/sessions-*.tsv`.
The transcript around each Claim was never saved, so the replay checks the checked text alone. Judge general
changes on these; don't tune on them.

## Output

Each run writes to `spikes/verdicts/results/`:

- `<timestamp>.csv`: one row per model x golden entry, with the predicted label, whether it was an
  exact match, whether it matched once false/misleading/lacks_context are merged into "alert",
  latency, token usage, cited source URLs, whether every cited URL's domain is in
  `TrustedSources.domains`, and the estimated cost of that one Check.
- `<timestamp>-<model-slug>.md`: a summary per model with exact-match accuracy, alert-merged
  accuracy, not-a-claim precision, JSON/parse failure rate, out-of-allowlist citation rate, median
  and p90 latency, and estimated cost per 100 Checks.

## The golden set

`golden.json` has 39 Claims and 10 non-Claims (49 entries total):

- Every Claim comes from a real, published fact-check by a Trusted Source outlet (AFP Factuel,
  franceinfo Vrai ou Fake, Les Décodeurs, CheckNews, Les Surligneurs), published 2024-2026. Each
  entry records the claim rewritten speech-to-text style (uppercase, no punctuation beyond
  elision apostrophes, numbers spelled out, never naming who said it), the original quote, an
  invented-but-plausible line of context, the mapped Verdict, why it was mapped that way, the
  source URL/outlet/date, a topic and a political side (kept only for balance analysis — never
  sent to a model).
- The 10 non-Claims (opinions, promises, questions, insults) were written by hand in the same
  speech-to-text style.
- Verdict floors met: false 9, misleading 10, lacks_context 10, accurate 8, not_a_claim 10.
  **Exception: `unverifiable` has only 2 entries, short of the 4 asked for.** Real fact-checkers
  very rarely publish a piece whose conclusion is "we simply can't know either way" — that's not a
  headline — so after an extensive search only two clean, dated (2024-2026), clearly-unverifiable
  cases turned up: `retailleau-cra-dangereux` (no report evaluates detainee dangerousness) and
  `knafo-recidivistes-15-milliards` (no data lets anyone measure the claimed share of the budget).
  A non-skipped test (`GoldenSetTest`) checks the other floors on every run so the set can't rot;
  it does not fail over the unverifiable shortfall since the brief conditions that floor on
  "if any exist" and forcing weaker entries in just to hit 4 would lower the set's quality.
- Political sides: far_right 9, right 10, centre 12, left 8 — roughly balanced.
- Topics: economy, energy, security, institutions, international, immigration, other (7 of the 8
  listed topics; no health-topic fact-check cleared the "clear conclusion" bar within the window).

`GoldenSetTest.kt` (a normal, always-run unit test) validates the schema and re-checks the verdict
and political-side balance on every test run.

## Known limitations

- 34 of the 36 Claims come from franceinfo Vrai ou Fake, which is itself a Trusted Source: a model can often find the very article that settled the Claim, so scores will be optimistic compared with brand-new claims that no one has checked yet. AFP Factuel, Les Décodeurs and CheckNews couldn't be fetched when the set was built.
- Removed at review because they aren't checkable facts under the Claim definition (a model answering "not a claim" would have been scored wrong): an evaluative statement on nuclear power and independence, a household-debt analogy, and a conditional IMF-supervision prediction.

The bench costs real money, so it only runs with `-Pbench` (or `BENCH=1`). Keys come from the environment or from the developer keys in `local.properties`.
