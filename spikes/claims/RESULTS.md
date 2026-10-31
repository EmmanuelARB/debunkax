# Claim filter: dataset and model comparison

## CLEF CheckThat! French availability

French has never been one of the languages in CheckThat!'s check-worthiness task
(Task 1), across every edition checked:

| Edition | Task 1 languages |
|---|---|
| 2021 | English, Arabic, Spanish, Turkish, Bulgarian |
| 2022 | English, Arabic, Bulgarian, Dutch, Turkish |
| 2023 | Arabic, Dutch, English, German, Italian, Spanish, Turkish |
| 2024 | English, Dutch, Arabic (CT–CWT–24, CC BY-SA 4.0) |
| 2025 | Task 1 became "Subjectivity" (a different task); no French check-worthiness set |

The wider CheckThat! *lab* did add French in 2024, but only for other tasks
(subjectivity, persuasion, role/authority detection), never for check-worthiness. So
there is no usable French check-worthiness dataset to reuse from CheckThat! 2021-2025 —
this spike's own dataset is the only option.

## Dataset

- **Sourcing**: `yt-dlp`, subtitles only (`--skip-download --write-subs`, manual
  captions only — never `--write-auto-subs`), from official YouTube channels of BFMTV,
  franceinfo, France 24, LCP, Public Sénat, France Inter, RTL, CNews and LCI, searched
  via `ytsearch` across debate/interview/talk-show queries (`search_queries.txt`). Plus
  the 4 pre-existing `.fr.vtt` files from `spikes/stt/audio/` (3 of the 4 videos turned
  out to also have full-length manual subtitles once re-checked; those supersede the
  original ~8-minute trimmed excerpts to get more sentences per video without
  duplicating content — see `select_videos.py`).
- **Manual-subtitle availability was very uneven**: of 283 distinct candidate videos
  checked across the 9 channels, only **41** actually had manual French subtitles (not
  just YouTube's automatic captions) — **24 on BFMTV** (debate/interview replays) and
  **17 on LCP - Assemblée nationale** (Questions au Gouvernement and hemicycle
  sessions). franceinfo, France 24, Public Sénat, France Inter, RTL, CNews and LCI
  contributed **zero** hits among the candidates checked; their debate/interview
  replays on YouTube ship with automatic captions only, as the STT spike had already
  found for LCP/Sénat (`spikes/stt/audio/SOURCES.md`) and this spike now confirms for
  the other five channels too, at least among the videos searched.
- **Political balance**: a rough title-keyword tally over the 41 videos found
  guests/topics tagged RN (7), LFI (5), LR (3), PS (2), Renaissance (1), and 26 not
  attributable to one party by title alone (mostly LCP Questions au Gouvernement
  sessions, where government and several opposition groups all speak in the same
  video, and general-topic BFMTV panels). No single camp dominates, though RN and LFI
  guests get more individual debate/interview airtime on BFMTV, matching known French
  TV debate coverage patterns rather than a sourcing bias (queries were topic-based,
  not party-based).
- **One source dropped for language**: one BFMTV video ("Libé 2027" debate, id
  `HK_fjJILkBM`) turned out to have manual subtitles that switch between French and
  English mid-sentence throughout (likely a bilingual co-production caption track).
  All 80 sentences from it were dropped before labelling to keep the corpus
  French-only, per AGENTS.md.
- **Corpus construction**: cues from each video's manual `.fr.vtt` are concatenated,
  sentence-split with `pysbd` (French rules), deduped exactly (case-insensitive) across
  the whole corpus. This gave **16,247** unique sentences across 42 sources — far more
  than the >=3,000 target and more than reasonable to hand-label for a spike, so
  `subsample_corpus.py` capped each source at 80 sentences (keeping in-video order for
  local context).
- **Final labelled set**: **3,222 sentences** across **41 sources** (after dropping the
  mixed-language source), **916 positive / 28.4% Claims**. Labelled in 6 batches of
  ~600 against `LABELING.md`'s guidelines (10 worked borderline examples included),
  favouring recall per ADR 0002's own stand-in-heuristic trade-off (missing a Claim
  costs more than a spare Check). Split into train (2,502 rows) / test (720 rows) by
  **source** (whole videos held out — 9 held-out videos never seen during training or
  threshold selection).
- **Self-agreement**: a random 200 already-labelled sentences were stripped of their
  label and relabelled blind, following the same guidelines a second time.
  **193/200 agreed (96.5%)**. All 7 disagreements were genuinely borderline: e.g. a
  vague, procedural-sounding aside about how hard it is to change an electrical socket
  (labelled 1 then 0), a historical comparison to "la situation d'avant la guerre 14"
  with no hard fact attached (1 then 0), "la raison, l'examen d'une loi au Sénat
  demain" — a fragment restating a procedural next step (1 then 0), and a demographic
  claim ("neuf septuagénaires" among the ten most powerful countries) that reads as an
  aside but is in fact a checkable figure (0 then 1). 5 of the 7 flips went from Claim
  to not-a-Claim on the second pass (first-pass over-eagerness on vague-but
  figure-adjacent or procedural sentences), the other 2 the other way — a mild but not
  dominant tendency to tighten up on re-labelling, not a strong systematic bias.
- **Main labelling doubts**, beyond the disagreements above:
  - **Credential/identity statements** ("vous êtes député RN", "je suis président de
    Reconquête") were treated as closer to procedural introduction than Claim, even
    though they are technically checkable — a judgment call to keep the filter focused
    on substantive assertions rather than every self-identification.
  - **Run-on, multi-speaker segments** from `pysbd` mis-splitting cross-talk (one is
    ~1,400 words long, a whole "Total profits" TV debate segment glued into one
    "sentence") were labelled 1 whenever *any* checkable content survived anywhere in
    the blob, per the guideline's explicit rule — meaning a few Claims in the dataset
    are really "a paragraph that contains a Claim," not a clean single assertion. This
    reflects how noisy real STT segmentation will be in production, but it does inflate
    the apparent length/complexity of some positive examples relative to the median.
  - **Opinion wrapped around a checkable core** ("je considère que Quentin a été tué
    par X, ET AUSSI par un système de complaisance") was labelled by the checkable
    core, not the opinion wrapper — defensible but means the boundary between "opinion"
    and "Claim" leans on judgment more than the written guidelines fully pin down.

## Models

All three evaluated on the same 720-sentence, 9-source held-out test set (`normalized`
column: uppercase, no punctuation, STT-shaped). Models (b) and (c) pick a threshold
targeting ~0.9 recall via out-of-fold (b) or held-out-validation (c) scores on train
only, never touching test; the heuristic is a fixed rule with no threshold to tune.

| Model | Precision @ ~0.9 recall target | Recall | F1 | Size | CPU latency/sentence |
|---|---|---|---|---|---|
| (a) Heuristic (ported from Kotlin, fixed rule) | 0.557 | 0.495 | 0.524 | (existing code) | negligible |
| (b) TF-IDF (word 1-2gram + char 3-5gram) + logistic regression | 0.469 | 0.816 | 0.596 | 388 KB (JSON) | <1 ms (pure vector ops) |
| (c) Fine-tuned DistilBERT-fr, int8 ONNX | 0.407 | 0.893 | 0.559 | 62.7 MB (int8) | 6.1 ms mean (P95 6.4 ms) |

Model (b) at the default 0.5 threshold: precision 0.586, recall 0.663, F1 0.622 (for
reference — not the deployed operating point).
Model (c) at the default 0.5 threshold: precision 0.662, recall 0.459, F1 0.542.

Full metrics (including out-of-fold/validation numbers used to pick each threshold)
are in `metrics/metrics_*.json`.

- **(a) Heuristic**: ported by hand to Python (`heuristic.py`), verified against every
  example in `HeuristicClaimDetectorTest.kt` (9/9 pass, `test_heuristic.py`). Its
  measured recall (0.495) is far below the "leans towards recall" intent documented in
  its own docstring — on this dataset of real, noisy TV-debate sentences it misses
  about half the Claims. It also has no tunable operating point: it is a fixed rule,
  so there is no way to trade its precision for more recall without editing the regex.

- **(b) TF-IDF + logistic regression**: word 1-2grams and character 3-5grams
  (`train_tfidf.py`), preprocessed with the *same* `RepeatedClaimMatcher.normalize()`
  already used on-device (ported once in `heuristic.py`, reused here) — so a Kotlin
  port needs no new text-processing code, only the exported vocabulary/IDF/weights
  (`exports/tfidf_lr_export.json`, 388 KB, small enough to bundle in the APK and commit
  to this repo). At recall ≈0.82 on the held-out test set (OOF on train was 0.90;
  the gap reflects source-holdout being a harder generalization test than in-sample
  cross-validation), precision is 0.47 — meaning roughly 1 in 2 Checks triggered would
  be a real Claim, a large improvement over the heuristic's current false-negative rate
  without adding any runtime dependency (dot products and a small hash-free vocabulary
  lookup are trivial to implement in Kotlin).

- **(c) Fine-tuned transformer**: `Geotrend/distilbert-base-fr-cased` (Apache-2.0,
  62.4M params, DistilBERT architecture with a French-pruned vocabulary distilled from
  mBERT — chosen over a full multilingual MiniLM/DistilBERT because a shared 250k-token
  multilingual vocabulary would make the embedding table alone larger than this whole
  model). Fine-tuned 4 epochs on 2,102 training sentences (`train_transformer.py`,
  CPU-only, ~6.5 min), exported to ONNX via `optimum` and dynamically quantized to
  int8 (`export_transformer_onnx.py`). Result: **62.7 MB**, just over the "~60 MB"
  target, and **6.1 ms** mean CPU inference per sentence — comfortably fast for
  on-device use. At a matched ~0.9 recall target its precision (0.41) is *slightly
  worse* than the much smaller TF-IDF model's (0.47), likely because 2,100 training
  sentences is not much data to fine-tune a 62M-parameter model on; the transformer
  does not show a clear accuracy advantage here to justify its 160x size difference.

## Recommendation

**Ship model (b), TF-IDF + logistic regression**, not the transformer. On this
dataset it reaches the same recall target with *better* precision than the fine-tuned
DistilBERT, at 388 KB vs. 62.7 MB, with no ONNX Android runtime dependency and no
inference-time model loading cost — it is a straightforward Kotlin port (reuse
`RepeatedClaimMatcher.normalize()`, add a hash-map vocabulary lookup, a dot product,
and a sigmoid). Given ADR 0002's "leans towards recall" trade-off, deploy it at the
0.33 threshold picked here (recall ≈0.82, precision ≈0.47 on held-out videos): still a
large recall improvement over the current heuristic (0.50) while roughly halving the
false-Check rate compared to running with no filter at all.

The transformer is not recommended for this app at this data scale: it is 160x
larger, needs an ONNX Android runtime dependency, and does not outperform the tiny
model on accuracy. It might become worthwhile if the labelled set grows substantially
(the model likely under-fit on 2.1k examples) — worth revisiting once more labelled
data accumulates, but not now.

The heuristic should be retired as the primary filter once (b) is integrated: its
measured recall on real debate sentences (0.50) means it is currently missing about
half of all Claims, worse than either learned alternative and with no way to tune
that trade-off.

## On real speech-to-text segments

Everything above is trained and measured on clean, punctuated subtitle sentences. In
the app the filter never sees that: it sees sherpa-onnx's streaming output for the
fallback engine (see `spikes/stt/RESULTS.md`: 28–46% WER on these same debates),
uppercase, no punctuation, and cut into segments by the endpoint detector's pauses
(`EndpointConfig()` at its sherpa-onnx defaults: 2.4 s / 1.2 s / 20 s rules), not by
sentence boundary. This section measures the shipped model (b) — unchanged, same
`exports/tfidf_lr_export.json`, threshold 0.330 — on that actual shape of input.

**Method** (`stt_segments.py`): the sherpa-onnx streaming zipformer (same model, same
1.13.8 library version as the Android AAR) was run over the STT spike's 5 debate
excerpts in 100 ms chunks with `enable_endpoint_detection=True` and the library's
default endpoint rules — the same configuration `SherpaSpeechEngine.kt` builds via
`EndpointConfig()` with no overrides. One segment was emitted per detected endpoint,
exactly like `SherpaSpeechEngine.runSession`: no trailing flush at end-of-file, so
speech after the last endpoint before an excerpt cuts off is genuinely dropped, same
as it would be if a Listening Session ended mid-utterance.

This produced **160 segments** from **39.8 minutes** of audio (5 excerpts, 29–34
segments each). Segments are much longer than subtitle sentences: mean 38.1 words /
14.3 s, median 45 words / 19.9 s (many segments run to the 20 s rule-3 cap — the
endpoint detector rarely finds a 2.4 s silence in cross-talk debate audio), versus
single sentences in the subtitle corpus. Each segment typically bundles several
original sentences' worth of speech from possibly more than one speaker.

**Labelling**: all 160 segments were hand-labelled against `LABELING.md`, judging the
checkable content that survives the noise (a Claim counts even if garbled or cut, as
long as the assertion is still recognizable — e.g. segment 36, "...LES MINEURS NON
ACCOMPAGNÉS IL Y EN A AUJOURD'HUI DEUX MILLE... QUI CHACUN COÛTE QUARANTE MILLE
EUROS", labelled 1 despite missing punctuation and the odd dropped word). **68/160
(42.5%) are Claims** — a much higher rate than the subtitle corpus's 28.4%, simply
because each long, merged segment has more chances to contain at least one checkable
assertion. **40/160 (25%) were flagged too garbled to confidently read** the original
assertion (very short fragments split mid-word by the endpoint detector, or long
runs where cross-talk makes the recognized text incoherent); these were still labelled
0/1 by whatever content survives legibly, per LABELING.md's rule to label by content,
not fluency, but they're the segments most likely to be labelled unreliably.

**Results** (`eval_stt_segments.py exports/tfidf_lr_export.json`), shipped model,
shipped threshold, no retraining:

| Eval set | Precision | Recall | F1 | Checks / real Claim | Checks / hour of audio |
|---|---|---|---|---|---|
| Clean subtitle test set (main table, model b) | 0.469 | 0.816 | 0.596 | ~2.1 | n/a (not timed) |
| STT segments, all 160 | 0.545 | 0.882 | 0.674 | 1.62 | 168.5 |
| STT segments, excluding the 40 too-garbled-to-judge | 0.625 | 0.882 | 0.732 | 1.41 | 147.5 |

**Recall did not degrade** — it rose from 0.82 to 0.88 (and precision rose too, from
0.47 to 0.55). Per this spike's own plan, the augmentation/retraining path (STT-noise
augmentation, leave-one-source-out threshold tuning) is gated on recall clearly
dropping below 0.82 on real STT segments; since it didn't, **no retraining was done,
and no app file changed** (Kotlin detector, `assets/claim_model.json`, and the parity
fixture are all untouched). The 8 missed Claims (false negatives) are short,
heavily-garbled fragments where the checkable content itself got mangled beyond what
the char/word n-grams can still match (e.g. segment 17's "MARIE UNE PEINE À LA
QUARAMNE DES TOURNÉE PUDMILLÉS DEUX MILLIONS D'EUROS PAR" for what was almost
certainly "...une peine ... deux millions d'euros...").

**What did change: the cost, not the accuracy.** 110 of the 160 segments (69%)
trigger a Check, against a 42.5% real-Claim base rate — **Checks per real Claim is
1.6** (1.4 excluding garbled segments) and **Checks per hour of debate audio is
~150–170**. This isn't a misclassification problem (precision/recall are fine); it's
a segmentation-length effect: the filter was tuned on single clean sentences, and
every ~14–20 s endpoint-cut segment here bundles several sentences' worth of text, so
a much larger share of segments contain *some* matching signal than would a single
sentence drawn from the same audio. A user in a `40 min` excerpt would see roughly
110 Checks fire (per this sample), which is a real cost concern flagged for future
work — but it's a rate/segmentation problem to solve separately (e.g. rate-limiting,
richer endpoint tuning, or de-duplicating near-identical consecutive Checks), not
something a classifier retrain would fix, so it's out of scope for this spike.
