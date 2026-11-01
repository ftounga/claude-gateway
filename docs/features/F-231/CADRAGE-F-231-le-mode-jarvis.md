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
| D9 | **Mesure d'adoption** : sessions Jarvis par jour, minutes parlées, coût voix en €. Elle conditionne F-232. | Leçon « mécanisme livré qui dort » |

## Coût estimé

- **Cerveau** : aucun surcoût (mêmes tours ; réponses orales plus courtes).
- **Transcription** : quelques €/mois.
- **Synthèse** : ≈ 10 €/mois (voix standard) à ≈ 100 €/mois (voix premium), pour un usage quotidien soutenu. À confirmer au choix du fournisseur.

## Découpage

| SF | Objet | Estim. |
|---|---|---|
| SF-231-01 | **La voix de Jarvis** : `SpeechSynthesisProvider`, route de synthèse en flux, voix par défaut, coût suivi en €. | 1,5 j |
| SF-231-02 | **L'écran Jarvis** : `/jarvis`, orbe, appuyer pour parler (dictée réutilisée), envoi dans le fil du sujet, lecture de la phrase orale, détail consultable. | 2 j |
| SF-231-03 | **Parler court** : consigne orale dans le message, accusé et annonce de fin des tours longs, interruption par la parole. | 1,5 j |
| SF-231-04 | **Confirmer à voix haute** : actions engageantes et outils `ask` posés en question orale, « oui » requis. | 1,5 j |
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
