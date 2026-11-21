# Mini-spec — [F-270 / SF-270-01] L'état durable des travaux vivants

## Identifiant
`F-270 / SF-270-01`

## Feature parente
`F-270` — Parler à Jarvis pendant qu'il travaille (cadrage `CADRAGE-F-270-parler-a-jarvis-pendant-qu-il-travaille.md`)

## Statut
`done`

## Date de création
2026-11-18

## Branche Git
`feat/SF-270-01-travaux-vivants`

---

## Objectif
Tout travail de Jarvis en fond (délégation, tour direct né à la voix, course autonome) a un état durable, partagé entre
les pods et lisible par l'écran : sur quoi, depuis quand, dans quelle phase.

## Point d'appui (D5 — brancher, ne pas refaire)
Le registre **existe** depuis F-272 / SF-272-01 : table `jarvis_delegations`, `JarvisDelegationStore` (offre, prise
atomique entre pods, fin, balayage). Cette SF l'**étend** (origine, phase, issue) ; elle ne crée pas de second registre.
Les courses autonomes F-219 restent dans `chantier_autonomy_runs` : elles sont **lues** (jamais recopiées).

## Comportement attendu

### Cas nominal
1. Une délégation du cerveau rapide est un travail d'origine `DELEGATION` (inchangé : offre puis prise par son tour).
2. Un tour direct né à la voix est annoncé par l'écran (`POST /api/jarvis/works`, identifiant tiré par l'écran) : ligne
   d'origine `DIRECT`, sans demande reformulée ; le tour Opus du fil la **prend** (même prise atomique) quand il ne
   prend pas de délégation, et la clôt à sa fin, comme une délégation (annonce F-272 / SF-272-05 comprise).
3. Phase d'un travail vivant : `THINKING` dès son départ ; `EXECUTING` au premier outil du tour qui agit sur le poste
   (même règle que la marque de mutation du tour : `bash`, écriture de fichier).
4. À la fin du tour : issue `DONE`, `FAILED`, `CANCELLED` (tour interrompu) ou `WAITING_YES` (la ligne 🎙 finit par
   « Vous confirmez ? »).
5. `GET /api/jarvis/works` rend les travaux du compte : vivants (comme `running()` aujourd'hui), finis depuis ≤ 20 s
   (`DONE`/`FAILED`/`CANCELLED`), en attente d'un oui depuis ≤ 2 min (`WAITING_YES`), et les courses autonomes F-219 du
   compte (`RUNNING` ⇒ `EXECUTING` ; finies depuis ≤ 20 s ⇒ `DONE`/`FAILED`/`CANCELLED`). Chacun porte un **nom
   court** : la clé de ticket du libellé (`LDIC-325`), sinon `chantier N`, sinon le nom du sujet.
6. Un nouveau travail sur un fil clôt le `WAITING_YES` de ce fil (la réponse est partie).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Base indisponible (lecture) | liste vide, jamais d'erreur 500 ; journal DEBUG |
| Base indisponible (phase, fin) | ignoré (best-effort), le tour continue |
| `POST` sans sujet, sujet d'un autre compte, sujet inconnu | 404, rien n'est rangé |
| `POST` avec un identifiant déjà pris par le compte | 409, rien n'est changé |
| `POST` sans `id` ou sans `subjectId` | 400 |
| Base indisponible à l'annonce | 503 ; l'écran ouvre le tour quand même (il ne sera simplement pas suivi) |
| Ligne `DIRECT` jamais prise (l'écran n'a pas lancé le tour) | close par le balayage existant (> 2 min), comme une offre orpheline |
| Travail d'un autre compte | jamais lu, jamais pris, jamais clos |

## Critères d'acceptation
- [ ] Un travail `DIRECT` annoncé sur une instance du registre est pris par une **autre** instance, puis clos.
- [ ] Le premier outil qui agit passe le travail en `EXECUTING` (une seule écriture par tour).
- [ ] Fin du tour : `DONE` ; échec : `FAILED` ; interruption : `CANCELLED` ; ligne 🎙 « Vous confirmez ? » : `WAITING_YES`.
- [ ] `GET /api/jarvis/works` : vivants + finis ≤ 20 s + `WAITING_YES` ≤ 2 min + courses du compte ; nom court correct.
- [ ] Isolation : Bob ne lit, ne prend ni ne clôt rien d'Alice (registre et API).
- [ ] Sans magasin (tests unitaires existants), le registre en mémoire est inchangé.

## Périmètre

### Hors scope (explicite)
- L'affichage (satellites) : SF-270-05. L'écran ne lit pas encore l'API dans cette SF.
- La relation précision/nouvelle et l'enchaînement : SF-270-04.
- Le flux SSE : remplacé par une lecture `GET` (voir Notes, D2).
- `live()` (précision d'un tour vivant) lit toujours `LiveTurnRegistry` (inchangé, hors périmètre F-272 déjà noté).

## Valeurs initiales
| Paramètre | Valeur |
|---|---|
| Fini visible | 20 s |
| `WAITING_YES` visible | 2 min (expiration de la confirmation, F-271 §8) |
| Travaux rendus au plus | 12 (8 délégations + 4 courses, D8) |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `id` (POST) | UUID obligatoire |
| `subjectId` (POST) | UUID d'un sujet du compte, obligatoire |
| `chantier` (POST) | entier ≥ 1 ou absent |
| `label` (POST) | ≤ 300 caractères (coupé au-delà), facultatif |
| `origin` | `DELEGATION`, `DIRECT` (colonne) ; `COURSE` (lu) |
| `phase` / issue | `THINKING`, `EXECUTING`, `WAITING_YES`, `QUEUED` (réservé SF-270-04), `DONE`, `FAILED`, `CANCELLED` |

## Technique

### Endpoint(s)
- `GET /api/jarvis/works` — travaux du compte (authentifié, filtre `user_id`).
- `POST /api/jarvis/works` — annonce d'un tour direct né à la voix `{id, subjectId, chantier?, label?}` → 201.

### Tables impactées
- `jarvis_delegations` : colonnes `origin` (défaut `DELEGATION`), `phase`, `outcome` (migration
  `272-70-jarvis-works-f270.xml`, rollback = drop des colonnes).
- `chantier_autonomy_runs` : lecture seule.

### Migration Liquibase
`272-70-jarvis-works-f270.xml` (changeSet `270-01-jarvis-works`) — trois `addColumn`, rollback explicite. Nommé
`272-70` car `includeAll` trie par nom de fichier et la table naît en `272-01`.

### Composants Angular (si applicable)
Aucun (SF-270-02 et SF-270-05).

## Plan de test

### Tests unitaires
- `JarvisWorksTest` : nom court (clé de ticket, chantier, sujet), confirmation détectée sur la ligne 🎙.
- Tests existants du registre en mémoire inchangés et verts.

### Tests d'intégration
- `JarvisDelegationStoreIntegrationTest` (étendu) : `DIRECT` annoncé sur une instance, pris sur l'autre ; phase ;
  issues ; nouveau travail qui clôt le `WAITING_YES` du fil ; visibilité 20 s / 2 min.
- `JarvisWorksApiIntegrationTest` (MockMvc) : `GET` (délégation, direct, course), `POST` 201 / 404 / 409, sans jeton 401.

### Isolation workspace
- Même tests : Alice ≠ Bob sur le registre (prise, fin, liste) et sur l'API (sujet de Bob refusé à Alice).

## Dépendances

### Subfeatures bloquantes
F-272 / SF-272-01 (livrée, prod `staging-3fb5cb6c`).

### Questions ouvertes impactées
Aucune.

## Notes et décisions
- **D1** Le tour direct est annoncé par l'écran plutôt que transmis dans la requête du tour : la prise atomique déjà
  en place sert telle quelle (aucun paramètre ajouté au chemin du tour, ni à la consigne).
- **D2** Lecture `GET` (relue par l'écran) au lieu d'un flux SSE : l'état est en base, donc identique depuis n'importe
  quel pod ; un flux exigerait un bus entre pods. L'écran connaît déjà en direct les travaux qu'il lance (leurs flux).
- **D3** Les courses autonomes sont lues dans leur propre table : un seul état par travail, jamais deux copies.
