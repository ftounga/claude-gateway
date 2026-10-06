# Mini-spec — F-177 / SF-177-02 — L'agent propose, je valide

## Identifiant
`F-177 / SF-177-02` — feature parente `F-177` *La gouvernance se pilote depuis le terminal* (cadrage,
décisions D3 et D4). Branche : `feat/SF-177-02-agent-propose`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Dans n'importe quel terminal, « mets dans la gouvernance que… » ou « crée-moi un skill » produit une
**carte de proposition** (diff) que l'utilisateur **valide d'un clic** ; rien ne s'écrit avant.

## Comportement attendu

### Cas nominal
1. **Outil `gouvernance_proposer(type, portee, nom, contenu, raison)`** — offert partout sauf au terminal
   Teams (condition stable par workspace → cache F-134 préservé).
   - `type` ∈ {`REGLE`, `SKILL`, `GABARIT`} ; `portee` ∈ {`POSTE`, `SUJET`}.
   - Fichier visé : `REGLE` → section `## <nom>` de `GOUVERNANCE.md` (ajoutée, ou remplace la section de
     même titre) ; `SKILL` → `.claude/skills/<slug>.md` (en-tête `name`/`description` ajouté s'il manque) ;
     `GABARIT` → `.claude/gabarits/<slug>.md`. Racine : celle du **poste** (`POSTE`) ou du dossier du
     **sujet** (`SUJET`).
   - L'outil **lit** le fichier actuel, calcule le contenu final et le diff, range la proposition
     (`governance_proposals`, statut `PENDING`, empreinte de base) — **n'écrit rien sur la machine**.
   - Carte `GovernanceProposalBlock` relayée (SSE `proposal`) et rangée dans la transcription (survit au
     rechargement et au bornage).
2. **Carte (front)** `app-proposal-card` : type, nom, portée, fichier, raison, diff (+ vert / − rouge),
   [Appliquer] [Modifier] [Refuser] ; statut relu à l'affichage (`GET`).
   - [Appliquer] → `POST …/apply` : relit le fichier, **refuse (409) s'il a changé** depuis la proposition,
     écrit via le runner (même chemin que le dépôt de gouvernance : cible racine sans projet pour `POSTE`),
     ligne d'audit `gouvernance_appliquer`, empreinte écrite tracée (`applied_digest`), cache de consigne
     mis à jour (règle du poste → tous les projets amorcés du poste : vaut au tour suivant partout).
   - [Modifier] → dépose « Modifie la proposition « nom » : » dans la saisie (rien n'est envoyé).
   - [Refuser] → `POST …/refuse`, rien n'est écrit.
3. **Doctrine D4** (littéral stable, là où l'outil est offert) : toute règle durable / skill / gabarit passe
   par `gouvernance_proposer` ; un `write_file` direct sur `GOUVERNANCE.md` ou sous `.claude/skills/` n'est
   pas refusé mais reçoit un **rappel** dans son résultat.

### Contrat API
| Méthode | URL | Réponse |
|---|---|---|
| GET | `/api/workspaces/{workspaceId}/governance-proposals/{id}` | `{id, status, type, scope, name, path, createdAt, decidedAt}` |
| POST | `…/{id}/apply` | idem, `status = APPLIED` |
| POST | `…/{id}/refuse` | idem, `status = REFUSED` |
Erreurs : 404 terminal/proposition d'autrui ou inconnue ; 409 `proposal_conflict` (déjà décidée, fichier
modifié) ; 503 `proposal_host_unreachable` ; 400 `proposal_invalid`.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `SUJET` au terminal du poste / `POSTE` sans poste | erreur outil, rien rangé |
| type/portée inconnus, nom/contenu vides ou trop longs (règle 4 000, fichier 20 000) | erreur outil |
| Contenu identique à l'existant | erreur outil « rien à changer » |
| Poste injoignable à la proposition / à l'application | erreur outil / 503, rien écrit |
| Fichier modifié entre proposition et clic | 409, rien écrit, la carte le dit |
| Proposition d'un autre utilisateur | 404 indiscernable |

## Critères d'acceptation
- [x] Sans clic [Appliquer], aucun fichier n'est écrit sur le poste.
- [x] [Appliquer] écrit au bon endroit (racine du poste ou sujet) et la règle du poste vaut au tour suivant
      de tout sujet du poste (cache mis à jour).
- [x] Conflit détecté si le fichier a changé ; double application refusée.
- [x] Isolation : 404 pour un autre utilisateur.
- [x] Doctrine injectée ; rappel sur `write_file` hors circuit.

## Plan de test
- **Service** `GovernanceProposalServiceTest` (7) : proposer n'écrit rien + diff ; appliquer `POSTE` à la
  racine + audit + cache de tous les projets du poste ; conflit ; skill (chemin + en-tête) ; portées
  invalides ; autre utilisateur ; remplacement de section.
- **Intégration** `GovernanceProposalApiIntegrationTest` (3) : 200 propriétaire, 409 double application,
  404 autrui, refus sans écriture (projet hébergé).
- **Boucle** `AtelierChatServiceGovernanceProposalTest` (4) : outil + doctrine offerts / absents en Teams ;
  carte relayée et en transcription ; rappel `write_file`.
- **Front** `proposal-card.component.spec` (8) : diff, [Appliquer], [Refuser], [Modifier], conflit,
  statut appliqué, lecture seule.
- **Non-régression** : suite backend complète (5 631) ; suite front (2 983).

## Impacts
- **Table** `governance_proposals` (migration `148-governance-proposals.xml`, FK workspace cascade, purge
  compte nommée). Paquet `atelier.proposal` (entité, dépôt, service, contrôleur, erreurs).
- `AtelierChatService` (outil, doctrine, rappel, carte), `AtelierTurnReport.Block.proposal`,
  `AtelierProgressListener.onGovernanceProposal`, `AtelierChatController` (SSE `proposal`),
  `AccountService` (purge).
- Front : modèles, `AtelierService` (événement), `GovernanceProposalService`, `ProposalCardComponent`,
  terminal (2 rendus + [Modifier]), `AtelierComponent` (carte vivante, 2 flux).

### Préoccupations transversales
- **Contexte tenant** : ✔ — composants : `GovernanceProposalService` (`requireOwned`, lecture
  `id + user_id + workspace_id`, poste = celui du projet possédé), `PromptSourceStore.putHostGovernance`
  (n'écrit que pour les projets du même `user_id`), `AccountService` (purge).
- Auth / Plans / Navigation : non.

## Hors périmètre
Édition en place du contenu dans la carte ; types `AGENT` (F-182) et `CROCHET` (F-181) ; paquets privés.

## Arbitrages (réversibles)
- [Modifier] = amorce dans la saisie plutôt qu'un éditeur dans la carte (moins de surface, l'agent repropose).
- Gabarits sous `.claude/gabarits/` (aucun emplacement existant).
- Règle = section `## <nom>` ; remplacement si titre identique.
- Proposition persistée en base (et non portée par l'écran) : le contenu écrit est celui que la gateway a
  calculé, jamais un texte renvoyé par le navigateur.
