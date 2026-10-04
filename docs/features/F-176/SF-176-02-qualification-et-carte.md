# Mini-spec — F-176 / SF-176-02 — La qualification au premier tour et la carte [Passer en guidé] [Rester libre]

## Identifiant
`F-176 / SF-176-02` — dépend de **SF-176-01** (mergée, PR #1074).
Branche : `feat/SF-176-02-qualification`. Statut : `in-progress`. Date : 2026-10-05.

## Objectif
Qu'au premier message d'un sujet en Libre l'agent **qualifie** la demande (question / petit geste /
chantier) et, pour un chantier, **propose** le mode Guidé par une carte *[Passer en guidé] [Rester
libre]* — l'utilisateur choisit (décision Q1).

## Comportement attendu

### Cas nominal
1. **Outil `propose_guided_mode(reason)`** donné à l'agent sous la même garde d'espace que les
   attentes (droit Forge, ou Vigie pour un terminal Teams ; ouvert à l'ADMIN). Guide stable ajouté à
   la consigne système (aucun contenu volatil) : qualifier, quand proposer, ne pas reproposer.
2. **Premier message** (aucun tour rejoué — début de sujet ou « Nouveau départ ») en Libre : la
   consigne du tour porte « qualifie la demande… Chantier → propose_guided_mode ». Sinon, en Libre,
   rien (Q4).
3. L'appel pose la proposition (`guided_proposed_at`, `guided_proposal_reason`) — l'agent apprend
   « proposition affichée ; continue à comprendre, ne modifie rien de plus que demandé ». Tant que
   l'utilisateur n'a pas choisi, la consigne du tour le lui rappelle.
4. **Écran** : au-dessus de la saisie, la carte « Ce sujet ressemble à un chantier » + la raison +
   [Passer en guidé] [Rester libre]. Relue à chaque fin de tour.
5. `POST /workspaces/{wid}/journey/guided-proposal/accept` → Guidé, phase Investigation, carte
   retirée. `POST …/decline` → reste Libre, carte retirée, **l'agent ne repropose plus** sur ce sujet.
   Tout choix explicite de mode (menu) efface la proposition.
6. Journal : `GUIDED_PROPOSED`, `GUIDED_ACCEPTED`, `GUIDED_DECLINED`.
7. L'outil est permis en mode de tour « Répondre / Plan » (il n'écrit rien dans le projet).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `reason` vide | résultat d'outil en erreur, le tour continue |
| déjà guidé / déjà proposé / l'utilisateur a choisi de rester libre | résultat informatif (« déjà guidé », « attend déjà », « RESTER LIBRE : ne repropose pas ») |
| base indisponible | résultat d'outil en erreur, le tour continue |
| accept / decline sur le terminal d'autrui | 404 |
| pas de droit d'espace | ni outil, ni guide, ni consigne de qualification |

## Critères d'acceptation
- [ ] Au premier message d'un sujet Libre (outils ouverts), la consigne demande la qualification ; ensuite, rien.
- [ ] `propose_guided_mode` pose la carte ; la redemander ne la duplique pas.
- [ ] [Passer en guidé] ouvre l'Investigation ; [Rester libre] retire la carte et empêche la reproposition.
- [ ] La carte ne s'affiche qu'en Libre.
- [ ] **ISOLATION** : Bob ne peut ni accepter ni écarter la proposition d'Alice (404).

## Plan de test
- **Unitaires** : `JourneyTurnNoteTest` (premier message, après refus, proposition en attente, garde du catalogue).
- **Intégration** : `SubjectJourneyApiIntegrationTest` (proposer → carte → accepter ; proposer → écarter → plus de reproposition ; raison vide ; Bob 404).
- **Front** : `terminal-journey.spec.ts` (carte affichée et gestes émis, pas de carte en Guidé, appels gateway).
- Suites complètes backend et front.

## Impacts
- Aucune migration (colonnes posées en 145).
- Backend : `JourneyToolCatalog`, `JourneyToolExecutor` (nouveaux) ; `SubjectJourneyService`
  (`proposeGuided`, `acceptGuidedProposal`, `declineGuidedProposal`) ; `SubjectJourneyResponse`
  (`guidedProposal`, `guidedDeclined`) ; contrôleur (2 routes) ; `AtelierChatService` (outil, guide,
  dispatch, liste blanche du mode Plan, `withJourney(…, firstTurn)` posé après la relecture de l'historique).
- Front : modèle, service (`acceptGuided`, `declineGuided`), bande du parcours (carte), terminal.

### Préoccupations transversales
- Contexte tenant : **oui** — l'outil écrit sur le terminal **du tour** ; les routes passent par
  `requireOwned`. Composants : `JourneyToolExecutor` (workspace du tour), `SubjectJourneyService`
  (`find` par `user_id` + `workspace_id`), contrôleur.
- Auth / plans / navigation : non (garde d'espace réutilisée telle quelle, aucune route front).

## Hors périmètre
Plan structuré (SF-176-03), porte (SF-176-04), transitions (SF-176-05), mesure (SF-176-06).

## Arbitrages (réversibles)
- **La carte vit dans la bande du parcours, au-dessus de la saisie**, et non comme bloc du fil : elle
  porte un état vivant (en attente / choisi) relu à chaque fin de tour, comme la bande des attentes —
  sans nouvel événement SSE ni champ de transcription. Elle reste visible tant que l'utilisateur n'a
  pas choisi.
- « Premier message » = aucun tour rejoué (couvre aussi le « Nouveau départ », qui ouvre souvent un
  nouveau sujet).
- Le refus (« Rester libre ») vaut pour le sujet entier ; l'utilisateur peut toujours passer en
  Guidé par le menu.
