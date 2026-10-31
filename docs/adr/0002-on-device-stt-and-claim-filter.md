# Speech-to-text and claim filtering on the device; only Claims leave the phone

Speech-to-text (ML Kit GenAI Speech Recognition, with sherpa-onnx as fallback; Moonshine has no French model) and the "is this a Claim?" filter (a small French classifier trained on CheckThat!-style check-worthiness data) both run on the phone. Only detected Claims, with about 30 seconds of preceding transcript held in memory, are sent to the AI provider. We chose this over cloud speech-to-text or cloud filtering because it keeps ordinary speech on the device, cuts the cost of each Check, and lets listening continue offline, with Claims kept as Pending Claims until they can be checked.

## Consequences

- Minimum device: Android 12+ with about 6 GB of RAM. Older phones are deliberately not supported.
- French speech recognition in ML Kit's basic mode is still in beta, and there are no published benchmarks on French TV-debate audio. Accuracy must be tested early on real recordings.
- How often Alerts appear depends on the filter's precision and recall, so we need a labelled set of French debate sentences.
- Continuous speech recognition drains the battery; long sessions will likely be run with the phone plugged in.
- The Claim filter is TF-IDF + logistic regression (388 KB), with recall 0.82 and precision 0.47 on held-out debates, trained on subtitle text. Measured on 160 segments produced by actually running sherpa-onnx's streaming recognizer with endpoint detection over 5 debate excerpts (`spikes/claims/RESULTS.md`, "On real speech-to-text segments"): recall does not degrade on real STT output (0.88, precision 0.55), but the endpoint-cut segments are much longer than the sentences the filter was tuned on, so a much larger share of them trigger a Check (~150-170 Checks per hour of debate audio, 1.4-1.6 Checks per real Claim) — a cost/rate concern to watch, separate from classifier accuracy.
