# Debunkax

An Android app that listens to French speech around the user, typically TV political debates, shows and journalism, and flags claims that may be misinformation as they are heard.

## Language

**Listening Session**:
The period between the user pressing the start button and stopping it, while the app is in the foreground. The app hears nothing outside a Listening Session.
_Avoid_: Recording, always-on, background listening

**Claim**:
Any factual-sounding assertion heard during a Listening Session.
_Avoid_: Statement, fact, sentence

**Verdict**:
The outcome of checking a Claim: false, misleading, lacks context, unverifiable, or accurate.
_Avoid_: Debunk, rating, score

**Check**:
One attempt to get a Verdict for a Claim from the user's AI provider, grounded in Trusted Sources.
_Avoid_: Fact-check, query, request

**Pending Claim**:
A Claim that could not be checked yet (no connection, key problem, or the session's Check limit reached). It never raises an Alert.
_Avoid_: Failed claim, queued claim

**Explanation**:
The deeper, sourced account of a Verdict, produced only when the user asks for more information.
_Avoid_: Details, more info, analysis

**Alert**:
An interruption shown to the user about a Claim whose Verdict is false, misleading, or lacks context, with its Speaker, a short context and a way to get more factual information. It is easy to dismiss, and a newer Alert replaces the one on screen.
_Avoid_: Notification, popup, warning

**Speaker**:
The enrolled person (a political leader, or a journalist beside them) recognised by voice as having said a Claim, or unknown whenever there is doubt. Never guessed from what was said.
_Avoid_: Author, source, orateur probable

**Addressee** (French: « interlocuteur »):
The person a Claim in the second person (« tu », « vous ») is aimed at, when a person named in the sentence or just before, or the last other politician recognised by voice, tells who it is. Otherwise unknown.
_Avoid_: Recipient, target

**History**:
The saved record of every checked Claim, each with its date and time, Verdict, short context and sources. It never contains audio or transcripts, and the user can clear it.
_Avoid_: Log, archive, recordings

**Trusted Source**:
A major French news outlet, fact-checking service, or primary source (e.g. AFP, Le Monde, INSEE, official texts) that Verdicts are grounded in.
_Avoid_: Reference, citation
