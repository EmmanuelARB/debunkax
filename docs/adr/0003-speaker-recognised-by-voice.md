# Claims name their Speaker, recognised by voice on the device, or say it is unknown

Supersedes the rule "Alerts never name or guess who spoke" (AGENTS.md) and the "Speaker attribution" entry of `docs/possible-evolutions.md`.

First-person Claims ("je", "nous", "mon gouvernement") can't be checked without knowing who said them, and an Alert that doesn't say who spoke is hard to understand in a debate. So each Claim now carries its Speaker: one of about 20 enrolled French political leaders, recognised on the phone from voice prints of public recordings (TitaNet-small speaker embeddings through sherpa-onnx, `spikes/speakers/`), or "unknown". A wrong name on a false Claim is still the worst possible error, so doubt always means unknown: a leader is named only when their voice scores above a threshold, clearly ahead of the runner-up, and consistently over the whole sentence. We rejected asking the user who is speaking at session start: it doesn't work in a debate, where several people speak.

## Consequences

- The app now opens the microphone itself and feeds the same audio to speech-to-text (ML Kit through a pipe, as the demo mode already did) and to speaker recognition. Audio is still discarded as soon as both have used it; voice prints are of public figures, built on the developer's machine and bundled in the app.
- The Speaker's name is part of the Check sent to the provider, and is saved with the Claim in the History.
- Only enrolled people, leaders and the journalists or presenters enrolled beside them, can be named. Anyone else is unknown. Adding a leader means enrolling their clips and re-checking the threshold, not retraining.
- The wrong-name rate in real rooms is not measured yet: the threshold (0.45) was set by ear on one tablet. It must be measured against noted speakers before any public release.
