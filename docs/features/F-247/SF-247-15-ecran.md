# Mini-spec — F-247 / SF-247-15 L'écran « Ce que je sais » (lot 1 : réglages et faits)

## Identifiant
`F-247 / SF-247-15`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.1 réglage par client, D3, D11)

## Statut
`done` (2026-11-05) — **lot 1** (réglages, sources, familles, effacement, faits ; PR #1600) et **lot 2** (rétention à
l'écran, conversations capturées, relevé — mise en place et « Tout valider » —, dépense en €).
**Branches :** `feat/SF-247-15-ecran` (lot 1), `feat/SF-247-15-ecran-lot2-backend` puis `-frontend` (lot 2) ·
**Dépend de :** SF-247-02, SF-247-08, SF-247-12/13/14

## Objectif
Le registre étant activé d'office pour chaque client (D3), le PO voit ce que Jarvis sait de chaque client et peut le
couper (couper efface), choisir les sources et les familles, effacer tout ou un fait.

## Comportement attendu

### Cas nominal
1. API (`JarvisStateController`, sous le `user_id` du principal) :
   - `GET /api/jarvis/state/clients` : un réglage par client du compte (ligne absente = tout activé) + nombre de faits ;
   - `PUT /api/jarvis/state/clients/{hostId}` : patch (`enabled`, `disabledFamilies`, `extractFromTurns`,
     `notesEnabled`, `releveEnabled`, `capturesEnabled`, `factRetentionDays`, `captureRetentionDays`) ;
     `enabled: false` **purge** ;
   - `GET /api/jarvis/state/clients/{hostId}/facts[?famille=]` : 20 faits récents (datés, source) ;
   - `DELETE /api/jarvis/state/clients/{hostId}/facts` (« Effacer tout ») ; `DELETE /api/jarvis/state/facts/{id}`.
2. Écran : bouton « Ce que je sais » dans les réglages de `/jarvis` (pas de nouvelle route), volet en verre (patron
   « Mes agendas ») : par client, interrupteur « Activé » (couper ⇒ confirmation `MatDialog` « Couper et effacer »),
   sources (Tours, Relevé, Captures, Constats), familles (bascule d'un clic), « Voir » les faits (clé, valeur, date,
   source, effacer un fait), « Effacer tout » (confirmation). **Jamais en présentation.**

### Lot 2 — contrat API (backend d'abord, puis l'écran)
| Méthode | URL | Corps / réponse | Codes |
|---|---|---|---|
| `GET` | `/api/jarvis/state/clients/{hostId}/conversations` | `[{id, platform: TEAMS\|MAIL\|AUTRE, title, depositedAt, lineCount, lines: [{speaker, speakerKnown, saidAt, approx, text}]}]` (20 captures lues au plus, 3 premières lignes, texte borné à 200 car.) | 200, 404 hors compte |
| `DELETE` | `/api/jarvis/state/captures/{id}` | — | 204, 404 hors compte |
| `GET` | `/api/jarvis/state/spend` | `{captures: {monthEur, capEur}, notes: {monthEur, capEur}}` | 200 |
| `GET` | `/api/jarvis/state/clients/{hostId}/releve` | `{setupAt, probes: [{id, workspaceId, workspaceName, name, platform, scope, command, proposed, active, testedAt, testSummary, lastRunAt, lastRunStatus, broken}]}` (SF-247-08) | 200, 404 |
| `POST` | `/api/jarvis/state/clients/{hostId}/releve/setup` | `202 {workspaceId, turnId}` ou `409 {code, message}` (SF-247-08) | 202, 404, 409 |
| `POST` | `/api/jarvis/state/clients/{hostId}/releve/validate-all` | `[{watchId, name, activated, error}]` (SF-247-08) | 200, 404 |

**Écran (lot 2)** : en tête, la dépense du mois en € (« captures 0,42 € / 10,00 € · constats … ») ; par client, deux
champs de rétention (`mat-form-field` outline, 1-365 j, hors bornes ⇒ rien n'est envoyé) ; trois vues exclusives —
« Voir » (faits), « Conversations » (capture : plateforme, titre, date, nombre de messages, premières lignes datées par
la passerelle avec « vers », effacer une capture), « Relevé » (date de mise en place, « Mettre en place » / « Refaire
l'inventaire », « Tout valider », sondes et leur statut en clair). Un refus de mise en place est **dit** (message du
409 en `MatSnackBar`).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Client d'un autre compte | refus | 404 |
| Rétention hors 1-365, famille inconnue | refus | 400 |
| Fait d'un autre compte | refus | 404 |
| Échec réseau | message `MatSnackBar`, état d'avant rétabli | — |
| Couper refusé à la confirmation | rien n'est envoyé | — |

## Critères d'acceptation
- [x] Activé d'office ; couper efface ; isolation à deux comptes ; bornes.
- [x] Volet : client, faits, couper confirmé, famille, effacer tout.
- [x] Masqué en présentation ; design system (verre §23, Inter/Merriweather/JetBrains Mono, `MatDialog`, `MatSnackBar`, multiples de 4 px).

## Plan de test
- Intégration : `JarvisStateApiIntegrationTest` (2) ; lot 2 : `JarvisKnowledgeApiIntegrationTest` (2, isolation à deux
  comptes, dépense, routes du relevé).
- Frontend : `jarvis-state-panel.component.spec.ts` (4 + 4 lot 2 : dépense, conversations et effacement, relevé —
  refus dit et « Tout valider » —, rétention bornée) ; suite complète.

## Impact
- Backend : `jarvis/state/JarvisStateController` (nouveau). Frontend : `jarvis/jarvis-state.ts`, `jarvis/jarvis-state-panel.component.ts` (nouveaux), `jarvis.component.{ts,html}`.
- Préoccupations transversales : Contexte tenant — chaque route vérifie `RunnerHostRepository.findByIdAndUserId` ; effacement d'un fait par `findByIdAndUserId`. Navigation : aucune route ajoutée.

## Arbitrages (réversibles, lot 2)
- Trois vues exclusives par client (faits, conversations, relevé) plutôt que des onglets Material : le volet reste en
  verre §23, sans nouveau composant.
- Le banc headless 1366×768 / 360×740 est rejoué avec l'écran de `/jarvis` au déploiement (le volet hérite des règles
  mobiles du lot 1 : pleine largeur sous 767 px).

## Hors périmètre
Une nouvelle route ; la présentation (le volet n'y est jamais montré).
