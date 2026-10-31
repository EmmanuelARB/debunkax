# French debate STT results

Engine: sherpa-onnx `streaming-zipformer-fr-2023-04-14` (int8, CommonVoice-trained), streamed in 100 ms chunks, desktop CPU (2 threads). Audio: 7–8 min excerpts listed in `audio/SOURCES.md` (not committed). Reference: the broadcaster's manual French subtitles.

| Excerpt | Type | WER | CER | RTF | Peak RSS |
|---|---|---|---|---|---|
| 01 Marseille | 2-candidate debate | 0.445 | 0.316 | 0.066 | 272 MB |
| 02 Marseille | 4-candidate panel, cross-talk | 0.456 | 0.326 | 0.059 | ~280 MB |
| 03 LCP QAG | parliament | n/a (no manual subs) | | 0.059 | 279 MB |
| 04 Paris | interview | 0.277 | 0.167 | 0.057 | ~280 MB |
| 05 Nice | debate, studio noise | 0.349 | 0.225 | 0.058 | ~280 MB |

Subtitles contain 8–26% *more* words than the transcripts, so they are not condensed and the error rates are mostly real (the model drops words).

## Takeaways

- sherpa-onnx is marginal on overlapping debate speech and acceptable on single-speaker segments. It is a fallback, not the primary engine.
- Figures survive well ("vingt quatre pourcent entre deux mille vingt quatre et deux mille vingt cinq"), and these carry most Claims. Proper names are often garbled, which matters less because Speakers are never shown.
- Output is uppercase with no punctuation: sentence splitting has to come from the engine's endpointing (pauses), and the claim filter can't rely on "?".
- Speed and memory are comfortable: about 17× real time on the desktop CPU, under 300 MB.
- Not measured yet: ML Kit GenAI Speech Recognition (needs a physical Android device). This is the next measurement that matters.
