# Mini-spec — F-206 / SF-206-04 Écran : chantiers ouverts dans la bande, bascule depuis une suite

## Identifiant
`F-206 / SF-206-04` — Feature parente : F-206. Statut : `ready` — 2026-10-16 — Branche `feat/SF-206-04-ecran-chantiers-ouverts`. Contrat importé de SF-206-01 (`GET /journey` → `chantiers[]`, `guidedProposal.newChantier`, `POST /journey/chantiers`, `POST /journey/chantiers/{n}/focus`) et SF-206-03 (`nextSteps[].chantier`, `nextStepsChantier.others`) — mergées.

## Objectif
Voir les chantiers ouverts d'un sujet, savoir lequel est affiché, et basculer sur un autre — depuis la bande du parcours ou depuis une suite « Et maintenant ? » qui le concerne.

## Comportement nominal
1. **Bande du parcours** (Guidé actif) : dès 2 chantiers ouverts, une rangée d'onglets « Chantier N · Phase » au-dessus des phases ; le focus est marqué (`aria-pressed=true`, filet orange) ; un chantier qui attend l'utilisateur (plan à valider, diagnostic, clôture) porte « à vous ». Cliquer un autre onglet bascule (`POST …/chantiers/{n}/focus`) ; la bande montre alors ce chantier (phases, plan, cartes).
2. **Cartes numérotées** (dès 2 chantiers) : « Prêt à planifier · Chantier N », « Plan du chantier N », « Chantier N : toutes les vérifications sont vertes » ; l'indication de clôture dit « les autres chantiers restent ouverts ».
3. **Proposition d'un chantier de plus** (`guidedProposal.newChantier`) : carte « Cette demande ouvre un chantier de plus. » [Ouvrir ce chantier et continuer] [Pas maintenant] ; accepter relance l'agent (« ✓ Nouveau chantier ouvert — investigation lancée. »).
4. **« Et maintenant ? »** : une suite d'un autre chantier que le focus porte « Chantier N · Étape M · titre » (ou « Chantier N ») et un bouton **« Basculer sur le chantier N »** (bouton distinct de l'option — cocher reste le geste principal) ; une suite du focus garde « Étape M · titre » ; « Hors plan · approfondissement » inchangé. L'en-tête montre l'avancement de chaque chantier (« Chantier 2 : 3/10 · Chantier 1 : 1/2 »), un seul chantier : « Chantier : 3/10 étapes » (inchangé).
5. Le focus est lu dans le parcours **en direct** : après une bascule, les libellés suivent sans nouveau tour.

## Cas d'erreur
| Situation | Comportement |
|---|---|
| Bascule refusée (chantier clos entre-temps) | snack « Le parcours du sujet n'a pas pu être mis à jour. » ; parcours relu |
| Backend antérieur (champs absents) | écran F-196/F-176 inchangé (pas d'onglets, pas de bouton) |
| Un seul chantier ouvert | aucun onglet, cartes non numérotées |
| Bascule pendant un tour | permise (lecture/geste du parcours, ne touche pas au tour) |

## Contraintes de validation
Aucune saisie. Couleurs : tokens existants (`--cg-orange-2`, `--cg-divider`, `--cg-text-secondary`, `--cg-accent`) — aucune couleur nouvelle ; polices Inter / JetBrains Mono ; espacements multiples de 4 px.

## Critères d'acceptation
- [ ] 2 chantiers ouverts : 2 onglets, le focus marqué ; clic sur l'autre ⇒ `POST /journey/chantiers/1/focus`.
- [ ] 1 chantier : aucun onglet.
- [ ] Carte de plan numérotée dès 2 chantiers.
- [ ] Proposition `newChantier` : carte « chantier de plus », accepter ⇒ `acceptGuided`.
- [ ] Suite `chantier=1`, focus 2 : « Chantier 1 · Étape 2 · … » + bouton « Basculer sur le chantier 1 » qui émet 1 ; suite du focus : pas de bouton.
- [ ] Le service n'envoie aucun identifiant de compte.

## Plan de test
- `terminal-journey-chantiers-ouverts.spec.ts` (nouveau) : onglets (0/2), émission de la bascule, cartes numérotées, carte « chantier de plus », service (`focus`, `openChantier` : URL, corps, sans identifiant de compte).
- `terminal-next-steps.component.spec.ts` (ajouts) : libellé « Chantier N · Étape M », bouton de bascule (présent/absent, émission), en-tête multi-avancement.
- Suite complète `npm test` + `npm run build`.
- Isolation : aucune donnée nouvelle côté front ; routes protégées par la gateway (404 d'autrui testé en SF-206-01).

## Impacts
- Composants : `TerminalJourneyStripComponent`, `TerminalNextStepsComponent`, `AtelierTerminalComponent` (`onSwitchChantier`, focus passé au panneau), `JourneyService`, modèles `journey.models.ts`, `atelier.models.ts`.
- Préoccupations transversales : ☐ Auth ☐ Tenant ☐ Plans ☐ Navigation (aucune route ajoutée, aucun guard).

## Hors périmètre
Ouvrir un chantier depuis le menu (le geste passe par la proposition de l'agent ou l'API) ; actions sur un chantier non focus sans bascule (on bascule d'abord).
