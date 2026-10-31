# Speaker-ID spike: who's talking, on-device

Spike for identifying which of ~20 enrolled French political leaders said a
given Claim (a 3-10 s sentence), or answering "unknown" for anyone else
(journalists, guests, moderators). Must run on-device via sherpa-onnx, which
the app already bundles for speech-to-text.

This is speaker *verification/identification* (voice fingerprint matching),
not diarization and not speech-to-text: it compares a short audio window
against enrolled voice prints using cosine similarity on embeddings.

## Setup

Reuses the venv from `spikes/stt/` (has sherpa_onnx 1.13.8, numpy,
onnxruntime); this spike additionally needs `soundfile`:

    spikes/stt/.venv/bin/pip install soundfile
    spikes/speakers/download_models.sh   # fetches 4 candidate models into models/

## Audio layout

    spikes/speakers/audio/<slug>/1.wav   # enrollment clip A (own video)
    spikes/speakers/audio/<slug>/2.wav   # enrollment clip B (different video)
    spikes/speakers/audio/<slug>/3.wav   # held-out test clip (different video/conditions)
    spikes/speakers/audio/impostors/*.wav  # voices NOT enrolled (any length)

All wav, 16 kHz mono, one speaker alone per clip. A speaker folder with fewer
than clips 1, 2 and 3 is skipped (printed, not fatal) so the eval can run
against a partially-collected set.

## Usage

    spikes/stt/.venv/bin/python spikes/speakers/eval.py
    spikes/stt/.venv/bin/python spikes/speakers/eval.py --noise      # simulate TV-through-room-mic
    spikes/stt/.venv/bin/python spikes/speakers/eval.py --models campplus-voxceleb --windows 3,5,8
    spikes/stt/.venv/bin/python spikes/speakers/eval.py --smoke-test  # see below, no real audio needed

Results land in `spikes/speakers/results/<model>[-noise].json`, plus a
compact table on stdout.

## Models evaluated

Downloaded from the sherpa-onnx `speaker-recongition-models` GitHub release
(that's the upstream tag's own typo). All four are language-agnostic speaker
embedding models (not ASR), so training language doesn't need to be French:

- `campplus-voxceleb`: 3D-Speaker CAM++, trained on VoxCeleb (~30 MB)
- `eres2netv2-3dspeaker`: 3D-Speaker ERes2NetV2, large merged zh-cn corpus (~68 MB)
- `wespeaker-resnet34-lm`: WeSpeaker ResNet34, VoxCeleb, large-margin fine-tuned (~26 MB)
- `titanet-small`: NVIDIA NeMo TitaNet-small (~39 MB)

## What the metrics mean

For each model and each test-window length (3 s / 5 s / 8 s by default):

- **Enrollment**: clips 1 and 2 are cut into 10 s windows, each embedded and
  L2-normalized; a speaker's voice print is the normalized mean of those
  embeddings.
- **closed_set_top1_acc**: cutting held-out clip 3 into windows, cosine-score
  each against every enrolled print, top-1 = argmax. Accuracy assuming the
  speaker is always one of the enrolled ~20 (no impostors in this number).
- **open_set**: also scores impostor windows (unenrolled voices). We pick the
  highest cosine threshold that keeps the impostor accept rate
  (`open_set_far_at_threshold`) at or below `--max-far` (2% by default), then
  report `open_set_enrolled_acc_at_threshold`: the fraction of genuine
  windows that are both top-1 correct AND above that threshold. This is the
  number that matters for the app: how often we can say "this is Macron" (or
  correctly say "unknown") at a false-accept rate we can live with.
- **eer**: equal error rate between genuine trials (score of a window
  against its own true speaker's print) and impostor trials (an impostor
  window's best score against any enrolled print). Lower is better; a
  threshold-independent summary of separability.
- **top_confusions**: the (true, predicted) speaker pairs most often mixed
  up on closed-set top-1 errors — useful to see if errors cluster on a few
  similar-sounding pairs or are spread out.
- **embed_ms_5s_window**: mean wall-clock ms to embed a 5 s window on this
  CPU (desktop, single thread) — a relative speed comparison across models,
  not an Android on-device number.
- **size_mb**: the `.onnx` file size, i.e. what would ship in the APK.

`--noise` runs the same eval but degrades every scored window (never the
enrollment clips) to approximate TV audio heard through a tablet's room mic:
convolution with a synthetic exponential-decay room impulse response
(RT60 ~0.4 s), a 150-7000 Hz band-limit, then babble+white noise at 15 dB
SNR. Seeded (`--seed`, default 0) so results are reproducible.

## Smoke test

`--smoke-test` ignores `spikes/speakers/audio/` (real enrollment audio is
still being collected) and instead fakes 4 "pseudo-speakers" out of
`spikes/stt/audio/*.wav`: each of 4 debate recordings is split into thirds
used as clips 1/2/3, and a 5th recording is chopped into impostor windows.
This only proves the pipeline runs end-to-end (loading, windowing,
embedding, scoring, thresholding, JSON output) — the numbers are meaningless
since "speakers" here are actually whole multi-speaker debates, not one
person. It writes to a temp directory and never touches `results/`.

## Files

- `download_models.sh`: fetches the 4 models into `models/` (gitignored).
- `eval.py`: the harness described above.
- `models/`, `results/`, `audio/`: all gitignored (binary models, generated
  results, and audio — see the non-negotiable rule in `AGENTS.md` against
  committing audio).

## Caveats / next steps

- Enrollment currently uses only 2 clips per speaker; more clips (more
  recording conditions) would likely raise robustness.
- The open-set threshold is picked per model/window/noise-condition on the
  same impostor pool used to report FAR — fine for this spike's relative
  model comparison, but a held-out impostor split would be needed before
  trusting an absolute number.
- Resampling (only used as a safety net; inputs are expected at 16 kHz
  already) is plain linear interpolation, not band-limited — don't rely on
  it for anything other than stray non-16kHz files.
- Once real audio lands in `audio/<slug>/`, run `eval.py` (and `--noise`) to
  pick a model + threshold, then write an ADR per `AGENTS.md` before wiring
  this into the app.
