# Labelling guidelines

We label sentences from French TV political debates, talk shows and interviews for
**check-worthiness**, matching CONTEXT.md's definition of a Claim: *"any factual-sounding
assertion heard during a Listening Session."* The filter's job is to decide, from
speech-to-text-shaped text alone (no audio, no tone of voice), whether a sentence is
worth sending to a paid Check. Missing a Claim is worse than sending one extra Check
(ADR 0002's leaning-towards-recall stand-in makes the same trade-off), so **when in
doubt between "vague opinion" and "a checkable assertion with something concrete in
it," label it a Claim (1)**.

## Label = 1 (Claim): a checkable factual assertion

- States a figure, statistic, date, amount, ranking or proportion ("plus de 2 milliards
  d'euros", "24% entre 2024 et 2025", "premier employeur de la ville").
- States that an event happened, a law/decree/report exists, or describes its content
  ("une proposition de loi a été votée", "le rapport de la Cour des comptes dit que...").
- States or implies a comparison, record or trend that can be checked against evidence
  ("la dette a explosé", "jamais vu ça", "le chômage recule depuis deux ans").
- Attributes a concrete, checkable action or record to a person or group, even without
  naming them by title used to search ("il a été condamné", "elle a détourné des fonds
  publics") — Alerts never name the speaker, but the Claim itself is still checkable
  from context; the no-naming rule is enforced later, at Alert time, not here.
- A short declarative sentence can still be a Claim if it asserts a checkable state of
  affairs, even without a number ("la police s'en va, le trafic reprend sa place" —
  causal claim about what happens, checkable in principle).

## Label = 0 (not a Claim)

- **Opinion / preference / value judgement** with no checkable content: "il faut
  renforcer la police", "c'est un danger pour nos enfants", "c'est faire de la
  prévention" (describes a *goal*, not a fact).
- **Promise / proposal**: "je veux arrêter l'expérimentation", "nous allons stopper
  l'économie souterraine" (future intent, not a claim about the world as it is).
- **Question**: any sentence that is asking, not asserting — including rhetorical
  questions used as an attack ("vous avez fait quoi ?", "à quel moment ?").
- **Insult / accusation with no checkable substance**: "vous mentez", "vous avez un
  problème".
- **Procedural / moderation talk**: turn-taking, addressing the chair, interruptions
  ("laissez-moi terminer", "s'il vous plaît", "monsieur le président").
- **Greeting / courtesy / filler**: "merci madame la présidente", "oui, alors, écoutez".
- **Vague statement** with no concrete, verifiable content, even if declarative: "c'est
  un problème de santé publique", "ça s'appelle du travail".
- Fragments shorter than about 4 words carry no checkable content on their own (already
  filtered out of the corpus, kept here for completeness).

## Borderline examples and how they were decided

1. **"Les trafiquants de drogue sont des criminels."** → **0**. Reads like a factual
   sentence (subject – verb – predicate) but it is a value/legal characterization with
   nothing to check against evidence (whether they're "criminels" in a sense the
   speaker means is definitional, not verifiable the way a figure is).

2. **"Vous avez un problème puisque vous en avez dans votre groupe parlementaire."** →
   **0**. Implies a checkable fact (someone in the group has a record) but is phrased
   as an insinuation with no concrete referent (who, what record) to check. Compare to
   example 6, which does name a concrete, checkable act.

3. **"Et quand je les croise, ils me disent dès que la police s'en va, le trafic
   reprend sa place."** → **1**. Framed as personal anecdote ("ils me disent"), but it
   asserts a checkable causal claim (crime resumes when police leave) of exactly the
   kind political debates make and that fact-checkers do assess ("selon vous" caveats
   don't make a claim unfalsifiable). Borderline call: labelled 1 because it is a
   testable causal/factual claim, not because the anecdote itself is verifiable.

4. **"Nous on a fait un plan de lutte contre le narcotrafic, monsieur, un plan de lutte
   avec des propositions."** → **1**. Asserts that a concrete thing (a plan) was made;
   checkable (does such a plan exist, what's in it), even though vague on numbers.

5. **"C'est un danger la drogue."** → **0**. Grammatically a declarative sentence, but
   purely evaluative ("danger") with no checkable claim about drugs.

6. **"Oui, non mais des trafiquants d'argent, oui, parce qu'avec Marine Le Pen, elle a
   quand même détourné plus de 2 milliards d'euros."** → **1**. Buried in cross-talk and
   disfluencies, but contains a concrete, checkable factual assertion (a specific person
   diverted a specific amount). Sentence segmentation is noisy here; label the content,
   not the syntax.

7. **"Donc si vous changez de doctrine, changer de doctrine, plutôt que de faire croire
   aux gens que la police va régler les problèmes alors que c'est un problème de santé
   publique, vous allez peut-être passer dans une autre étape."** → **0**. Long and
   dense, sounds "factual-ish" because of its length, but it is an opinion/policy
   argument end to end — no discrete fact is being asserted.

8. **"Quelles sont les propositions du RN et de ce monsieur pour stopper l'économie
   souterraine aucune, que des mesures répressives qui depuis 40 ans monsieur..."** →
   **0**. Starts as a question and the STT-style run-on keeps the interrogative framing
   throughout (rhetorical attack), even though "depuis 40 ans" smells like a figure.
   The heuristic would likely fire on "40" and "depuis ... 20xx"-style cues; humans
   should still label by intent (attack, not assertion).

9. **"Et vous êtes un danger pour Marseille."** → **0**. Personal attack, not a claim
   about a state of the world beyond the speaker's opinion of the target.

10. **"Une proposition de loi pour mettre un terme aux salles de shoot."** → **1**
    (elliptical, no verb — continues the previous turn) since it names a concrete,
    checkable legislative act. Elliptical fragments that clearly continue a claim from
    context are labelled on their content, same as a full sentence, rather than
    penalized for the transcript's segmentation.

## Practical notes

- Labelling works from the **original punctuated `text` column**, not the
  STT-style `normalized` column — normalization is only meant to match what the
  on-device filter will actually see at inference time.
- Cross-talk, disfluencies and interruptions are common (manual TV subtitles keep
  them); label the checkable content that survives, not the fluency of the sentence.
- Sentence-splitting is imperfect (pysbd on run-on spoken French): some "sentences"
  are really two clauses stitched together, sometimes from two different speakers
  glued by an em-dash. Label by whether checkable content is present anywhere in the
  segment.
