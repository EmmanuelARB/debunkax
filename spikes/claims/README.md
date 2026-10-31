# Claim filter spike

Builds a labelled French dataset of TV-debate/interview sentences, trains a small
on-device classifier for "is this sentence a Claim?" (CONTEXT.md's definition: *"any
factual-sounding assertion"*), and compares it against the current keyword heuristic
(`app/src/main/java/app/debunkax/domain/ClaimDetector.kt`, ADR 0002). See `RESULTS.md`
for the numbers and recommendation, and `LABELING.md` for the labelling guidelines.

Everything here is Python, run from a venv at `.venv` (Python 3.12, installed via
`uv python install 3.12` since the system Python is 3.14 and some wheels don't have
3.14 builds yet). CPU only.

## Setup

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh   # if uv isn't installed yet
uv python install 3.12
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python pandas scikit-learn numpy scipy \
    webvtt-py pysbd tqdm joblib pytest torch --index-url https://download.pytorch.org/whl/cpu
uv pip install --python .venv/bin/python transformers datasets onnx onnxruntime \
    optimum sentencepiece accelerate evaluate
```

## Rebuilding the data (not committed: subtitles are copyrighted)

`data/` and `out/` are gitignored. To rebuild from scratch:

1. **Find candidate videos with *manual* French subtitles.**
   `python3 search_candidates.py` searches YouTube (via `yt-dlp ytsearch`) for debates,
   panels and interviews on BFMTV, franceinfo, France 24, LCP, Public Sénat, France
   Inter, RTL, CNews and LCI, and writes `data/candidates.csv`.

   `python3 fetch_subs.py` then attempts, for every candidate from those channels, to
   download **only** the manual French subtitles (`--skip-download --write-subs
   --sub-langs fr,fr-FR,fr-orig`, never `--write-auto-subs`) into `data/raw_subs/`, and
   records what it found in `data/manifest.csv`. In practice manual French subtitles on
   YouTube are common on BFMTV's debate/interview replays and rare-to-absent on the
   other channels checked (they publish automatic captions only) — see RESULTS.md.
   Two yt-dlp quirks this script works around: (a) YouTube sometimes serves manual
   subtitles under the language code `fr-FR` while the plain `fr` code is actually the
   *automatic* caption track for the same video — asking for `fr,fr-FR,fr-orig` with
   `--write-subs` (never `--write-auto-subs`) is what keeps this manual-only; (b)
   unauthenticated requests get bot-checked after a burst of calls, worked around with
   `--extractor-args youtube:player_client=android` and a `--sleep-requests` delay.

2. **Copy in the 4 already-transcribed `.fr.vtt` files** used by the STT spike:
   `cp ../stt/audio/*.fr.vtt data/raw_subs/` (gitignored
   in the main repo too, so this only works from a checkout that still has them locally).

3. **Select a curated set of videos** (`python3 select_videos.py`): of 283 candidates
   checked across the 9 channels, only 41 actually had manual French subtitles — almost
   all on BFMTV or LCP - Assemblée nationale (see RESULTS.md). This script dedupes,
   prints a rough channel/party-keyword balance check, and prunes any unselected
   `.fr.vtt` files from `data/raw_subs/`.

4. **Build the sentence corpus**: `python3 build_corpus.py` reads every `.fr.vtt` in
   `data/raw_subs/`, concatenates cues per video, sentence-splits with `pysbd` (French
   rules), dedupes, and writes `data/unlabeled.csv` (`text`, `normalized`, `source`).
   `normalized` mimics on-device STT output (uppercase, no punctuation except a
   trailing `?` on questions and apostrophes) so the filter is trained and evaluated on
   the same shape of text it will see at inference time. This produced far more than
   the >=3,000-sentence target (16,247 unique sentences), so
   `python3 subsample_corpus.py` caps each source at 80 sentences, landing on ~3,300.

5. **Label**: `python3 make_batches.py` splits `data/unlabeled.csv` into ~600-row
   batches under `data/batches/`; label each batch's `id,text` against `LABELING.md`
   and write `data/batches/labels_NN.csv` (`id,label`); `python3 merge_labels.py`
   assembles the final `data/sentences.csv` (`text`, `normalized`, `label`, `source`).

6. **Split and train**:
   ```bash
   .venv/bin/python split_dataset.py     # data/train.csv, data/test.csv (held out by source)
   .venv/bin/python eval_heuristic.py    # model (a)
   .venv/bin/python train_tfidf.py       # model (b), exports out/tfidf_lr_export.json
   .venv/bin/python train_transformer.py       # model (c), fine-tunes
   .venv/bin/python export_transformer_onnx.py # model (c), ONNX export + int8 quantization + latency
   ```

## What's committed vs. gitignored

All large/derived artifacts (raw subtitles, `sentences.csv`, model checkpoints, ONNX
files) live under `data/` and `out/`, both gitignored. The small numeric results
(`metrics/*.json`) and the compact TF-IDF+logistic-regression export
(`exports/tfidf_lr_export.json`, kept only if under 2 MB) are copied out of `out/` into
`metrics/` and `exports/`, which ARE committed, alongside every `.py` script,
`LABELING.md` and `RESULTS.md`.
