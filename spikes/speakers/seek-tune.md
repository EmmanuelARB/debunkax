# seek-tune evaluation

Repo: https://github.com/cgzirim/seek-tune (cloned to a
temporary directory for this review, not committed here). MIT license.

## 1. What it actually does

It's a from-scratch Go reimplementation of the Shazam algorithm (README:
"implementation of Shazam's song recognition algorithm"), plus a
Spotify/YouTube downloader and a web client. Read `server/shazam/*.go`:

- `spectrogram.go`: low-pass filter at 5 kHz, downsample by 4, then a
  windowed (Hanning/Hamming, 1024-sample, 50% overlap) STFT via a custom
  `FFT` (`fft.go`) to build a magnitude spectrogram. `ExtractPeaks` splits
  each frame's spectrum into 6 fixed frequency bands and keeps, per band,
  the bin whose magnitude beats the frame's average — a classic
  "constellation map" of (time, frequency) peaks.
- `fingerprint.go`: `Fingerprint` pairs each peak ("anchor") with up to 5
  following peaks ("targets") in the same track, and `createAddress` packs
  `(anchorFreqBin, targetFreqBin, deltaTimeMs)` into one 32-bit hash. Each
  hash maps to `{AnchorTimeMs, SongID}` (`models.Couple`).
- Storage: SQLite by default (`server/db/sqlite.go`) or MongoDB
  (`server/db/mongo.go`) — a table/collection of hash -> list of
  `(songID, anchorTimeMs)`, plus a songs table with title/artist/YouTube ID.
- Matching (`shazam.go`, `FindMatchesFGP`): fingerprints the query clip the
  same way, looks up its hashes in the DB, and for each candidate song bins
  `dbTime - sampleTime` into 100 ms buckets; the song whose hashes agree on
  one consistent time offset most often wins (`analyzeRelativeTiming`).
  This is exact-recording matching: it works because the *same* mastered
  audio produces the *same* peaks at a *fixed* relative offset, no matter
  where in the song the query clip starts.
- Dependencies (`server/go.mod`): pure Go standard-library-ish DSP (own FFT,
  no numeric libs beyond `gonum`), `mattn/go-sqlite3`, MongoDB driver,
  Spotify/YouTube clients for building the catalogue. No ML, no embeddings,
  no on-device/mobile target — it's a desktop/server CLI + web app.

## 2. Could it identify WHO is speaking?

No, and not as a near-miss — it's the wrong kind of fingerprint for that
job. The constellation hash encodes the exact spectral content of one
specific recording (which frequencies happen to peak together at which
exact millisecond offset). Two performances of the same song by two
different singers, or two sentences said by the same politician, produce
completely different peak patterns and therefore different hashes — there
is no notion of "voice similarity" here, only "is this the identical
waveform (allowing for noise/cropping) as something already in the DB."
Debunkax's problem is the opposite: recognizing an already-enrolled
*voice* across arbitrary, never-before-heard sentences. That needs a
representation invariant to *what* is said, which is exactly what
TitaNet-small speaker embeddings (already chosen, see
`docs/adr/0003-speaker-recognised-by-voice.md` and
`spikes/speakers/README.md`) provide and Shazam-style hashing does not.

## 3. Could it help another way?

- **(a) Identify which broadcast/show, to narrow candidate speakers**:
  closest conceptual fit (matching a clip against a catalogue of known
  recordings) but needs a pre-built fingerprint DB covering every possible
  broadcast — impossible for live/first-airing TV, and any such DB would be
  a server or pre-populated dataset, conflicting with "no Debunkax backend."
  Not usable.
- **(b) Detect replays/repeated jingles/ads to skip them**: the one
  legitimate reuse. A jingle or ad heard once in a session could be
  fingerprinted on the device and matched against later occurrences purely
  locally (enroll-then-match, no external DB, no network) — a valid
  on-device dedup filter. But it only catches near-identical repeats, and
  Debunkax has no stated need for this (not in `docs/possible-evolutions.md`).
- **(c) Aligning with a known recording for eval (ground-truth sync)**:
  useful for dev tooling only. To score `spikes/speakers/` against a
  reference broadcast recording, fingerprint matching (or plain
  cross-correlation) could auto-find the time offset instead of hand-syncing.
  Runs offline, no rule conflict — but it's dev-tooling, and a
  `scipy.signal.correlate`/`librosa` script does the same job in far less
  code than porting this Go project.
- **(d) Nothing else identified**: the rest of the codebase (Spotify/YouTube
  downloader, SQLite/Mongo catalogue, web client) is song-identification
  plumbing with no separate value here.

## 4. Verdict

**Not useful** for the stated goal (speaker recognition) — wrong
fingerprint category entirely, confirmed by reading `fingerprint.go` and
`shazam.go`: it matches identical recordings via exact spectral-peak timing,
not voices via learned embeddings. **Marginally useful** as an *idea*
(constellation fingerprinting) for a possible future on-device jingle/replay
dedup (3b) and for offline dev-time audio alignment (3c) — but even there,
porting this specific Go/SQLite/MongoDB project isn't worth it: the
algorithm is simple enough to reimplement in ~100 lines if ever needed, and
existing Python tools (`scipy.signal.correlate`, `librosa`) already solve
(3c) more directly. Recommendation: do not adopt or reference this repo in
the app or the speaker-ID spike; no ADR needed since nothing changes.
