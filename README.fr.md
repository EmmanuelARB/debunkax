# Debunkax

Debunkax est une application Android uniquement en français. Pendant une session d'écoute, elle transcrit en français la parole qu'elle entend (principalement des débats politiques, émissions et journalisme à la télévision), détecte les affirmations (Claims) sur l'appareil, les vérifie avec le fournisseur d'IA de l'utilisateur et affiche des alertes pour les verdicts faux, trompeurs ou manquant de contexte.

## Fonctionnement

Tout fonctionne sur un seul téléphone. Le pipeline :

```
audio (16 kHz mono)
  → transcription (sur l'appareil)
  → fenêtre de transcription glissante (~30 s, mémoire seule)
  → filtre d'affirmations (sur l'appareil)
  → reconnaissance de l'orateur (sur l'appareil)
  → Vérification (la clé OpenRouter de l'utilisateur, recherche sur les sources de confiance)
  → Verdict
  → Alerte (uniquement faux / trompeur / manque de contexte)
  → Historique (Room ; jamais d'audio ni de transcription)
```

### 1. Démarrer une session d'écoute

L'utilisateur appuye sur le gros bouton pendant que l'app est au premier plan et choisit une source (ADR 0004) :

- **Microphone** — la pièce (un débat à la TV), par défaut. La session s'arrête si l'app passe en arrière-plan.
- **Audio des apps** — la lecture multimédia de cet appareil (YouTube, France.tv Pluzz…) capturée via l'API `AudioPlaybackCapture`. Elle embarque le consentement par session de la plateforme (dialogue système, service au premier plan `mediaProjection`, icône rouge d'enregistrement que l'utilisateur peut révoquer à tout moment) et continue en arrière-plan, car la vidéo joue dans une autre application.

Les deux sources produisent le même flux PCM mono 16 kHz, le reste du pipeline est identique.

### 2. Transcription (sur l'appareil)

- Principale : **ML Kit GenAI Speech Recognition** (fr-FR). Sur la tablette, l'app alimente ML Kit avec son propre flux micro par un tube.
- Repli : **sherpa-onnx** streaming zipformer, téléchargé une fois depuis sa release GitHub officielle ; utilisé sur émulateurs et appareils non pris en charge.

La sortie est composée de finals (énoncés complets), découpés en phrases avant le filtre. Seule une fenêtre glissante d'environ 30 s de transcription est conservée en mémoire ; c'est le contexte envoyé à l'IA. **L'audio brut est jeté dès qu'il est transcrit. Rien n'est jamais enregistré.**

### 3. Filtre d'affirmations (sur l'appareil)

Chaque phrase passe par `TfidfClaimDetector` — TF-IDF + régression logistique en Kotlin pur, modèle dans `assets/claim_model.json` (entraîné par `spikes/claims/`). Il conserve les affirmations qui ressemblent à des faits (Claims) et écarte le reste, ce qui limite les coûts d'API.

### 4. Reconnaissance de l'orateur (sur l'appareil)

Un modèle de reconnaissance vocale compare la fenêtre audio courante aux **62 empreintes vocales enregistrées** (chefs de file, autres personnalités politiques, journalistes) livrées dans les assets de l'app. Si la meilleure correspondance franchit le seuil (0,45) avec une marge de 0,05 sur la suivante, l'affirmation porte le nom de cette personne ; sinon elle reste **« Orateur non identifié »**. Un orateur n'est jamais deviné à partir de ce qui a été dit (ADR 0003). Le nom de l'orateur courant s'affiche au-dessus de la transcription en direct.

### 5. La vérification (l'unique appel réseau portant du contenu utilisateur)

Pour chaque affirmation acceptée, une **vérification** est deux requêtes vers la clé **OpenRouter** de l'utilisateur — OpenRouter uniquement, DeepSeek V4.1 Flash uniquement (ADR 0005) :

1. une **pré-vérification** sans recherche, un passage de contrôle bon marché ;
2. une **vérification avec recherche**, avec la recherche web **Exa** restreinte à la liste des **sources de confiance** (grande presse nationale — AFP, Le Monde, franceinfo, Libération, Le Figaro, BFM TV, France 24, RFI — et services de vérification et sources officielles : INSEE, Légifrance, Assemblée nationale, Sénat, Cour des comptes…).

Pour une phrase à la seconde personne issue d'un orateur reconnu (« vous avez voté… »), la vérification reçoit aussi un **interlocuteur probable** (ADR 0006) : une personne nommée directement dans la phrase ou juste avant, sinon le dernier politicien, autre que l'orateur, reconnu par la voix dans les 30 s précédant la phrase (les journalistes sont écartés). Si l'interlocuteur reste incertain, le verdict est invérifiable — jamais une supposition.

L'IA renvoie un **verdict** — faux, trompeur, manque de contexte, invérifiable ou exact — ainsi qu'un court contexte et au moins une source. En cas d'absence de connexion ou de problème de clé, l'affirmation devient une **affirmation en attente** : elle n'est jamais vérifiée dans cette session et ne déclenche jamais d'alerte.

### 6. Politique d'alerte

Seuls les verdicts **faux, trompeurs ou manquant de contexte** déclenchent une **alerte** : l'orateur, la phrase telle qu'entendue, un court contexte et un moyen d'obtenir plus d'information factuelle. Une alerte se ferme facilement, et une alerte plus récente remplace celle à l'écran. « Invérifiable » et « exact » n'interrompent jamais.

Si l'utilisateur en demande plus, une **explication** — le compte rendu approfondi et sourcé du verdict — n'est générée que sur demande.

### 7. Historique

Room conserve chaque affirmation vérifiée : date et heure, verdict, court contexte, sources, orateur. Il ne contient jamais d'audio ni de transcription, et l'utilisateur peut le vider.

## Confidentialité

- **Jamais d'audio ni de transcription sur disque.** Seules les affirmations, leurs verdicts, contextes, explications, sources et horodatages (l'historique).
- **Les sessions micro n'écoutent qu'au premier plan**, démarrées par l'utilisateur. Aucun service caché en arrière-plan, aucune écoute au démarrage, écran maintenu éveillé pendant une session. Les sessions audio d'apps sont l'exception documentée (ADR 0004).
- **Sa propre clé** : OpenRouter (OAuth PKCE), stockée chiffrée dans le Keystore Android, avec demande de non-rétention des données lorsque le fournisseur le supporte.
- **Rien ne quitte le téléphone** à part : les vérifications (affirmation + fenêtre de transcription) vers le fournisseur de l'utilisateur ; la vérification de mise à jour au lancement contre la page publique des releases ; et le téléchargement unique du modèle vocal sherpa-onnx. Aucun backend, aucune mesure d'audience, aucun rapport de plantage.
- **Uniquement en français** : audio, interface et textes générés.

## Stack

- Kotlin, Jetpack Compose, Android natif. minSdk 31 (Android 12), appareils cibles avec environ 6 Go de RAM ou plus.
- Distribution : APK signé, téléchargement direct (pas encore de Play Store).

## Carte du code

`app/src/main/java/app/debunkax/` :

- `domain/` — modèle et règles en Kotlin pur : Claims, Verdicts, AlertPolicy, CheckPrompts, TrustedSources, TfidfClaimDetector, interfaces de dépôt et de moteur
- `session/` — ListeningSession, la boucle de la parole à la vérification, à l'alerte et à l'historique ; le service de capture audio d'apps
- `speech/` — ML Kit, sherpa-onnx, FallbackSpeechEngine, moteurs de démonstration scriptée et audio
- `check/` — le client OpenRouter, et la liste des modèles
- `history/` — Room
- `keys/` — coffre-clés Tink et PKCE OpenRouter
- `settings/` — DataStore
- `speaker/` — reconnaissance de l'orateur (empreintes vocales, appariement, chronologie par session)
- `ui/` — écrans Compose
- `update/` — vérification de release
- `AppContainer.kt` — câblage manuel des dépendances, modes démo de débogage et surcharges de test

`spikes/` : expériences Python derrière les ADR : `stt/` (précision de la transcription), `claims/` (jeu de données et entraînement du filtre), `verdicts/` (jeu d'or des verdicts et banc d'essai), `speakers/` (extraits vocaux, évaluation, et `export_prints.py`, qui copie le modèle et les empreintes dans les assets de l'app — à exécuter avant la compilation).

## Compilation et tests

Le [Makefile](Makefile) enveloppe les conventions habituelles d'Android (`make help` pour l'aide) :

```sh
make debug                    # APK de débogage (arm64 + x86_64)
make release                  # APK de release signé (arm64 uniquement ; nécessite keystore.properties)
make release EMULATOR_ABI=1   # inclut aussi x86_64 (test de fumée sur émulateur)
make test                     # tests unitaires JVM (dont Robolectric)
make connected-test           # tests Compose instrumentés (DEVICE=<serial>)
make bench                    # le banc de verdicts payant (spikes/verdicts/README.md)
make lint                     # Lint Android
make install DEVICE=...       # installe l'APK de débogage sur un appareil
make run DEVICE=...           # installe et lance
make clean
```

Les commandes Gradle équivalentes, pour référence :

- Exporter `JAVA_HOME=$(dirname $(dirname $(readlink -f /usr/bin/java)))` (JDK 17) avant Gradle (le Makefile le fait à votre place).
- `./gradlew testDebugUnitTest assembleDebug` — tests unitaires (JVM, Robolectric pour Room) et l'APK de débogage.
- `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest` — tests Compose instrumentés, utilisant les surcharges de test de `AppContainer`.
- `./gradlew assembleRelease` — arm64 uniquement. Ajouter `-PemulatorAbi` pour inclure x86_64. La signature lit `keystore.properties` (voir `docs/release.md`).
- `./gradlew testDebugUnitTest --tests '*VerdictBench*' -Pbench` — le banc de verdicts payant (`spikes/verdicts/README.md`).
- Interrupteurs uniquement en débogage dans Réglages : « Mode démo » (transcription scriptée et faux verdicts) et « Démo audio réelle » (rejoue `files/demo.wav` à travers le moteur de transcription).

## Documentation

- `CONTEXT.md` — le glossaire des termes du domaine (Listening Session, Claim, Check, Verdict, Pending Claim, Alert, Explanation, History, Trusted Source). Utiliser ses termes exactement.
- `docs/STATUS.md` — l'état actuel : ce qui est vérifié, ce qui ne l'est pas, et l'étape suivante.
- `docs/adr/` — décisions d'architecture.
- `docs/possible-evolutions.md` — fonctionnalités délibérément écartées de la première version.

## Licence

Debunkax est un logiciel libre sous licence GNU General Public License, version 3 ou (à votre choix) toute version ultérieure. Voir `LICENSE`.

Hors de cette licence : les extraits d'émissions télévisées dans `spikes/` (transcriptions de `spikes/verdicts/` et fixtures de test) appartiennent à leurs diffuseurs et sont cités à des fins de recherche et d'évaluation. Les empreintes vocales de `app/src/main/assets/speakers/prints.json` sont dérivées d'enregistrements publics des personnes qu'elles nomment et ne servent qu'à la reconnaissance du locuteur.
