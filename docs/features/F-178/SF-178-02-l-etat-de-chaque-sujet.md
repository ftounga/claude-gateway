# Mini-spec — F-178 / SF-178-02 — L'état de chaque sujet

## Identifiant
`F-178 / SF-178-02` — feature parente `F-178` (cadrage, décision D2). Branche :
`feat/SF-178-02-sujets-etat`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Au terminal du poste, l'outil `sujets_etat` répond en **une lecture** à « où en est chaque sujet ? »,
sans `bash` ni ouverture des fichiers de chaque sujet.

## Comportement attendu

### Cas nominal
1. **Déclaration** : `sujets_etat` (aucun paramètre, description fixe) n'est déclaré qu'au terminal du
   poste d'un poste réel (`PosteToolCatalog`). Ajouté à la panoplie du mode Réponse/Plan (lecture seule).
2. **Exécution** (`PosteToolExecutor` → `SubjectsStateService.describe(userId, hostId)`), un bloc par sujet
   (`listByHost`, refiltré user + poste), le plus récemment actif d'abord :
   - **Dernière activité** : max `atelier_messages.created_at` du sujet (filtré `user_id`) ;
   - **Parcours** (F-176) : `Libre`, ou `Guidé · phase X · chantier n°N « titre »` ;
   - **Attentes ouvertes** (F-175) : nombre + les 3 plus anciennes (« … » (à faire|demandé)) ;
   - **Dernier bilan** (F-155) : date · tours · [€ admin] · suggestions ;
   - **Semaine** (F-143) : [€ admin] · tours de la semaine.
3. Bornes : 30 sujets détaillés, 3 attentes, 100 caractères par description.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Appel dans un sujet / terminal Teams | refusé (« n'existe qu'au terminal du poste »), rien n'est lu |
| Poste sans sujet | « Aucun sujet sur ce poste pour l'instant » |
| Lecture en échec | message d'indisponibilité, jamais d'exception vers la boucle |
| Utilisateur non administrateur | aucun montant en euros (même règle que le coût du tour) |

## Contraintes de validation
Aucun paramètre d'entrée. Périmètre = poste du terminal possédé, jamais un id venu du modèle.

## Critères d'acceptation
- [x] Au terminal du poste, « où en est chaque sujet ? » obtient un bloc par sujet avec les 5 informations.
- [x] Dans un sujet, `sujets_etat` n'est pas déclaré, et un appel forcé est refusé sans lecture.
- [x] Aucun sujet/attente d'un autre utilisateur ou d'un autre poste n'apparaît.
- [x] Aucun montant pour un non-administrateur.

## Plan de test
- `SubjectsStateServiceTest` (3) : blocs/ordre/intrus écartés/valeurs neutres ; non-admin sans € ; poste vide.
- `AtelierChatServiceHostTerminalTest` (+3) : déclaration conditionnelle ; exécution sur le poste possédé ;
  refus dans un sujet.
- `AtelierRecallRepositoryTest` (+1, H2) : `lastActivityByWorkspace` filtré `user_id`.
- Non-régression : suite complète backend (requêtes dérivées validées au démarrage du contexte).

## Impacts
Backend : nouveau paquet `atelier.poste` (`PosteToolCatalog`, `PosteToolExecutor`, `SubjectsStateService`) ;
`AtelierChatService` (mutateur, déclaration, aiguillage avant le volet Radar, mode Réponse/Plan) ;
requêtes : `AtelierMessageRepository.lastActivityByWorkspace`, `SubjectJourneyRepository.findByUserIdAndWorkspaceIdIn`,
`SessionBilanRepository.findFirstByUserIdAndWorkspaceIdOrderByCreatedAtDesc`. Aucune table, aucune migration,
aucun endpoint.

### Préoccupations transversales
- **Contexte tenant** : ✔ — lecture multi-sujets. Composants : `SubjectsStateService.describe` (sujets via
  `listByHost(userId, hostId)` refiltré), les 3 requêtes ci-dessus et les requêtes existantes réutilisées
  (`TerminalActionRepository.findByUserIdAndHostIdAndStatusIn`, `UsageTurnRepository.aggregateCostByProject`)
  — toutes sous `user_id`. Garde au catalogue + revérification à l'exécution.
- **Plans / limites** : non (lecture d'agrégats existants, aucune garde de quota touchée).

## Hors périmètre
Lectures Radar/pages/consommation (SF-178-03) ; doctrine (SF-178-04) ; écran dédié.

## Arbitrages (réversibles)
- Montants en € réservés à l'administrateur (`AdministratorEntitlement`, lu en base — le tour tourne hors
  `SecurityContext`) ; sinon nombre de tours. Aligné sur `TurnCostView`.
- Terminal du poste lui-même non listé (ce n'est pas un sujet ; ses attentes sont déjà jointes au tour, F-175).
