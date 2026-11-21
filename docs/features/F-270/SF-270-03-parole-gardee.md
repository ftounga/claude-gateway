# Mini-spec — [F-270 / SF-270-03] Rien n'est jeté pendant qu'il pense

## Identifiant
`F-270 / SF-270-03`

## Feature parente
`F-270` — Parler à Jarvis pendant qu'il travaille

## Statut
`done`

## Date de création
2026-11-18

## Branche Git
`feat/SF-270-03-parole-gardee`

---

## Objectif
Une phrase dite pendant que le cerveau rapide prépare sa réponse (état `reflexion`) est gardée et traitée juste après la
réponse en cours ; « laisse tomber » / « annule » / « stop » s'applique tout de suite.

## Cause traitée (cadrage C2)
- **Boucle d'écoute par défaut (`legacy`)** : en `reflexion`, aucun micro n'écoute (la détection de voix ne tourne qu'en
  `parole`, l'écoute ne rouvre qu'au `repos`) : la phrase est perdue sans trace, ou rattrapée à moitié par l'écoute
  suivante (banc de référence, « enchaînement » : « Ta plateforme » au lieu de « Qu'est-ce qui reste à faire sur
  data-platform ? »).
- **Boucle en flux (`stream`)** : `onStreamTurn` jette le tour confirmé hors `ecoute` (`voice_turn_ignored`).

## Comportement attendu

### Cas nominal
1. Conversation active, boucle `legacy`, état `reflexion` : la détection de voix (VAD, déjà chargée pour la coupure)
   tourne aussi pendant la réflexion. Une parole qui y **commence** est captée en entier, transcrite (même
   retranscription que la coupure), passée à la porte de parole.
2. Elle est **gardée** (une seule, la dernière ; trace `voice_turn_kept`), puis rejouée **dès que la réponse en cours est
   dite** (au moment où l'écoute se serait rouverte) : même chemin qu'une phrase entendue (`handle`).
3. Si elle annule (« laisse tomber », « annule », « arrête ça », « stop ») : appliquée **aussitôt** — la réponse en cours
   est abandonnée (rien de plus n'est dit), Jarvis dit « Entendu. » (trace `voice_turn_kept cancel`).
4. Une parole commencée en réflexion et encore en cours quand Jarvis se met à parler suit la coupure F-233 existante.
5. Boucle `stream` : un tour confirmé pendant la réflexion est gardé de la même façon (plus de `voice_turn_ignored`
   hors `parole`).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Bruit, toux, voix tierce (porte de parole) | rien n'est gardé, aucun « Pardon » |
| Deux phrases pendant la même réflexion | seule la dernière est gardée |
| Transcription en échec | rien n'est gardé (trace `kept_transcribe_fail`) |
| Conversation mise en pause avant la fin de la réponse | la phrase gardée est oubliée |
| Coupure par la voix désactivée sur l'appareil | comportement d'avant (pas de VAD en réflexion) |

## Critères d'acceptation
- [ ] En `reflexion` (conversation active, legacy), la VAD est démarrée ; elle s'arrête au retour au repos.
- [ ] Une phrase captée en réflexion est gardée puis traitée après la réponse (le cerveau rapide reçoit son texte).
- [ ] « Laisse tomber » en réflexion : la réponse en cours n'est pas dite, « Entendu. » l'est.
- [ ] Deux phrases gardées : seule la dernière est traitée.
- [ ] Boucle stream : tour confirmé en réflexion gardé (pas `voice_turn_ignored`).
- [ ] Banc à vraie voix : l'« enchaînement » (phrase dite 0,8 s après la précédente) est entendue en entier ;
      nouveau scénario « parler pendant qu'il pense » ; aucune régression sur le reste.

## Périmètre

### Hors scope (explicite)
- Parole pendant `parole` : coupure F-233 inchangée.
- Aucune consigne, aucun modèle, aucun outil changés.

## Valeurs initiales
| Paramètre | Valeur |
|---|---|
| Phrases gardées | 1 (la dernière) |

## Contraintes de validation
Aucune donnée saisie (texte transcrit, même porte de parole que l'écoute).

## Technique

### Endpoint(s)
Aucun.

### Tables impactées
Aucune.

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
- `jarvis.component.ts` : VAD en réflexion, `onKeptSpeech`, rejeu à la réécoute, annulation immédiate, boucle stream.

## Plan de test

### Tests unitaires
- `jarvis.component.spec.ts` : VAD démarrée en réflexion (conversation active) ; phrase gardée puis traitée après la
  réponse ; « laisse tomber » appliqué aussitôt ; la dernière seulement ; boucle stream gardée.

### Tests d'intégration
- Banc à vraie voix F-256, boucle legacy, 2 passages : « enchaînement » + scénario `pendant-qu-il-pense`.

### Isolation workspace
Sans objet (aucune donnée lue ni écrite côté passerelle en plus).

## Dépendances

### Subfeatures bloquantes
Aucune (SF-270-02 mergée avant, sans lien technique).

### Questions ouvertes impactées
Aucune.

## Notes et décisions
- **D1** La VAD de la coupure est réutilisée (déjà chargée, micro partagé) plutôt qu'une écoute ouverte en réflexion :
  aucune session de transcription en flux n'est ouverte pour rien, et la porte de parole est la même.
