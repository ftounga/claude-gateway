# CADRAGE F-231 — Le mode Jarvis

**Statut :** cadrage du 2026-11-01 · Famille « Jarvis » : F-230, **F-231**, F-232.

**Demande PO (2026-11-01)** :
- « Tout ce qu'on fait déjà en discutant avec lui dans le terminal », à la voix ;
- en mobilité, au bureau et chez le client, **toujours à voix haute** ;
- mot d'éveil « Jarvis » ;
- un mode plein écran avec un visuel neuronal, un « cerveau » ;
- voix masculine posée ;
- les actions qui engagent sont **répétées puis confirmées par « oui »** ;
- budget voix : « peu importe ».

Téléphone : **Android**.

## Constat (existant)

- **Voix en entrée** : `TranscriptionProvider` (Whisper, F-128) et dictée (F-145), en prod.
- **Voix en sortie** : rien.
- **Mobile** : PWA installable (F-152), notifications qui sonnent (F-153, F-217), le tour survit à l'écran (F-84).
- **Permissions** par outil `allow / ask / deny` (F-121-02).

## Décisions

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Le même cerveau.** Jarvis est une interface vocale sur **le terminal du sujet** (même fil, mêmes outils, même modèle). Ce n'est ni un second agent ni un modèle vocal temps réel. | Justesse, parité avec Claude Code |
| D2 | **Voix de sortie** derrière une interface `SpeechSynthesisProvider` (Provider Independence). Première implémentation au meilleur rendu français, voix masculine posée par défaut ; le fournisseur se change sans code métier. | Budget « peu importe », sans dépendance |
| D3 | **Réponse orale courte.** En mode Jarvis, une consigne **dans le message** (jamais dans la consigne système : préfixe stable) demande une phrase orale en tête de réponse (≤ 2 phrases). Le détail reste à l'écran. Tour long : accusé immédiat (« je m'en occupe ») puis annonce de fin. | On ne lit pas 10 minutes de travail |
| D4 | **Confirmation orale** des actions qui engagent (lancer un chantier en autonome, envoyer un mail, valider une étape, et tout outil en `ask`) : Jarvis reformule, attend « oui » ; « non », silence ou doute → rien. | Une erreur de transcription ne déclenche jamais rien |
| D5 | **Mot d'éveil sur l'appareil.** Modèle « Jarvis » exécuté dans le navigateur (aucun son ne sort avant le mot), actif tant que l'écran Jarvis est ouvert (support voiture, PC). Téléphone verrouillé : « Ok Google, ouvre Jarvis » lance l'application directement en mode Jarvis. | Une PWA ne peut pas écouter en arrière-plan (Android comme iOS) |
| D6 | **Écran Jarvis** : route `/jarvis` en plein écran, orbe neuronal animé (états écoute / réflexion / parole, réagit au niveau sonore), sujet et chantier courants affichés et changeables à la voix (« passe sur CAGIP »), interruption par la parole. Couleurs de `DESIGN_SYSTEM.md`, téléphone d'abord. | Exigence PO |
| D7 | **Alerte de veille (F-230)** : la notification ouvre `/jarvis?alerte=…`, Jarvis la lit et attend la réponse. | Boucle complète |
| D8 | **Confidentialité, décision à acter** : la voix part déjà chez le fournisseur de transcription (F-145) ; avec Jarvis, **le texte des réponses part aussi chez le fournisseur de synthèse**, données client comprises. Même régime que F-145, à valider explicitement par le PO. | Prolonge la décision D2 de F-128 |
| D10 | **Retour au terminal : pas de réconciliation à faire, une trace et une reprise.** Jarvis écrit dans **le fil du sujet** (D1) : quand le PO revient sur l'écran, tout est déjà dans le terminal, au même endroit que s'il avait tapé. S'y ajoutent : (a) chaque message et chaque tour nés à la voix portent la marque « 🎙 via Jarvis » ; (b) au retour sur un terminal où Jarvis a agi depuis la dernière visite, un encart « Pendant ton absence, via Jarvis » liste les actions (étapes validées, chantiers lancés, attentes créées) et **les messages à envoyer** ; (c) le point de situation (F-225) les reprend. | Le PO doit retrouver ce qui a été fait sans relire le fil |
| D11 | **Messages pour des tiers : Jarvis prépare, le PO envoie.** Jarvis n'envoie rien à un tiers (règle existante : aucun outil n'écrit à un tiers). Un message demandé à la voix devient l'encart mail ou message existant (F-203), avec Copier et « Marquer envoyé » (F-211 : l'attente liée passe à DEMANDE). Jarvis le dit à l'oral : « Message pour X prêt, à envoyer depuis le terminal ». Sur demande (« envoie-le-moi »), il se l'envoie **au PO lui-même** (`email_me`), qui peut le transférer depuis son téléphone sans ouvrir l'application. Dans le tableau du client, un compteur « À envoyer (n) » liste les encarts non marqués envoyés, tous sujets confondus. | Rien ne se perd entre la voix et l'envoi |
| D9 | **Mesure d'adoption** : sessions Jarvis par jour, minutes parlées, coût voix en €. Elle conditionne F-232. | Leçon « mécanisme livré qui dort » |

## Coût estimé

- **Cerveau** : aucun surcoût (mêmes tours ; réponses orales plus courtes).
- **Transcription** : quelques €/mois.
- **Mot d'éveil** : 0 € (modèle exécuté sur l'appareil, aucun service appelé).
- **Synthèse** (la voix qui parle, c'est elle qui coûte) : ≈ 10 €/mois (voix standard) à ≈ 100 €/mois (voix premium), pour un usage quotidien soutenu. À confirmer au choix du fournisseur.

## Découpage

| SF | Objet | Estim. |
|---|---|---|
| SF-231-01 | **La voix de Jarvis** : `SpeechSynthesisProvider`, route de synthèse en flux, voix par défaut, coût suivi en €. | 1,5 j |
| SF-231-02 | **L'écran Jarvis** : `/jarvis`, orbe, appuyer pour parler (dictée réutilisée), envoi dans le fil du sujet, lecture de la phrase orale, détail consultable. | 2 j |
| SF-231-03 | **Parler court** : consigne orale dans le message, accusé et annonce de fin des tours longs, interruption par la parole. | 1,5 j |
| SF-231-04 | **Confirmer à voix haute** : actions engageantes et outils `ask` posés en question orale, « oui » requis. | 1,5 j |
| SF-231-06 | **De retour au terminal** : marque « via Jarvis », encart « Pendant ton absence » (actions et messages à envoyer), reprise dans le point de situation, « envoie-le-moi » par `email_me`, compteur « À envoyer (n) » dans le tableau du client (D10, D11). | 2 j |
| SF-231-05 | **« Jarvis… »** : mot d'éveil sur l'appareil, lancement « Ok Google, ouvre Jarvis », alertes de veille lues, mesure d'adoption. | 2 j |

## Préoccupations transversales

- **Navigation / routing** : nouvelle route `/jarvis` (garde d'authentification existante), lien profond depuis les notifications, raccourci PWA. À vérifier : routes du terminal, `/atelier`, retour depuis `/jarvis`, ouverture par notification existante (F-153).
- **Plans / limites** : coût de synthèse compté et plafonné. Composants : `UsageLedgerService`, `CostBudget`.
- **Contexte tenant** : Jarvis n'agit que sur les sujets du `user_id` connecté.

## Hors périmètre

- Écoute téléphone verrouillé (F-232).
- Modèle vocal temps réel « parole à parole ».
- Mode discret automatique (le PO a choisi « toujours à voix haute »).
- iPhone (le PO est sur Android ; la PWA y fonctionne, sans le lancement par assistant).

**Point de vigilance** : « Jarvis » est un nom protégé (Marvel). Sans risque pour un usage personnel ; à renommer si le mode est vendu à des clients.
