# Mini-spec — F-206 / SF-206-02 Outils, GUIDE, porte agrégée et note de tour multi-chantiers

## Identifiant
`F-206 / SF-206-02` — Feature parente : F-206. Statut : `ready` — 2026-10-16 — Branche `feat/SF-206-02-outils-porte-note`. Dépend de SF-206-01 (mergée).

## Objectif
L'agent travaille sur plusieurs chantiers ouverts : ses outils visent un chantier par son numéro (défaut = focus), il peut proposer un chantier de plus sans clore les autres, la porte s'ouvre dès qu'un chantier est en Exécution sur un plan validé, et la note du tour lui montre tous les chantiers ouverts.

## Comportement nominal
1. **Outils** : `set_subject_plan`, `submit_diagnosis`, `update_plan_step`, `reopen_investigation` acceptent `chantier` (entier, facultatif ; absent = focus). Le résultat d'outil cite le chantier quand il est donné (« Chantier 2 : … »).
2. **`propose_guided_mode` sur un sujet déjà guidé** : pose une proposition de **chantier de plus** (carte, choix de l'utilisateur) ; accepter ouvre le chantier suivant sans clore les autres (SF-206-01). Résultat : « Proposition affichée : [Ouvrir le chantier]… ». Déjà proposée → « attend déjà ». « Rester libre » sur un sujet guidé n'interdit pas les propositions suivantes (SF-206-01).
3. **GUIDE** (littéral stable) : paragraphe « PLUSIEURS CHANTIERS » réécrit (ouverts en même temps, focus, `chantier: N`, `propose_guided_mode` ouvre un chantier de plus) ; paragraphe « LA PORTE » complété (agrégation).
4. **Porte agrégée** (`JourneyGate.isClosed`) : en Guidé, fermée seulement si **aucun** chantier ouvert (focus ou autre) n'est en Exécution sur sa version validée courante. Le message nomme les chantiers quand il y en a plusieurs : « Ce terminal est en mode Guidé : aucun de ses chantiers ouverts n'est en Exécution sur un plan validé (Chantier 1 · Plan, Chantier 2 · Investigation) : cette action attend la validation d'un plan. » Avec un seul chantier : message **inchangé à l'octet près**.
5. **Note du tour** : un seul chantier ouvert → note **inchangée**. Plusieurs : une ligne d'en-tête (nombre, focus), le focus détaillé (« == Chantier N (focus) — titre == » + bloc existant), puis chaque autre chantier résumé sur une ligne (phase, version du plan et son état, avancement, attentes de l'utilisateur), au plus 6 lignes puis « … et K autre(s) ». Une proposition de chantier de plus en attente est rappelée. La note reste dans le message du tour, jamais dans la consigne système (préfixe stable F-134/F-171).

## Cas d'erreur
| Situation | Résultat d'outil |
|---|---|
| `chantier` n'est pas ouvert | erreur d'outil « Le chantier N n'est pas ouvert. » (pas d'exception, le tour continue) |
| `chantier` non entier / ≤ 0 | ignoré → focus |
| `propose_guided_mode` guidé sans chantier ouvert (incohérent) | « Le sujet est déjà en mode guidé » (inchangé) |

## Contraintes de validation
`chantier` : entier ≥ 1 ; note bornée (`MAX_CHARS` pour le plan du focus, 6 résumés au plus, titres ≤ 80 caractères dans les résumés).

## Critères d'acceptation
- [ ] `update_plan_step` avec `chantier: 2` fait avancer le chantier 2 sans toucher au focus 1.
- [ ] `propose_guided_mode` sur sujet guidé → carte ; [accepter] → chantier 2 ouvert, 1 intact.
- [ ] Chantier 1 en Exécution validé + focus 2 en Investigation → porte **ouverte**, `gateClosed=false`.
- [ ] Aucun chantier en Exécution → porte fermée, message qui nomme « Chantier 1 · Plan, Chantier 2 · Investigation ».
- [ ] Un seul chantier : messages de porte et note identiques à avant (tests existants verts).
- [ ] GUIDE et définitions d'outils sont des littéraux stables (aucune valeur volatile).

## Plan de test
- Unitaires `JourneyGateTest` (nouveau) : agrégation, message multi, message mono inchangé, Libre, Clos.
- Unitaires `JourneyTurnNoteTest` (ajouts) : bloc multi (focus détaillé, autres résumés, borne), mono inchangé.
- Intégration `SubjectJourneyChantiersIntegrationTest` (ajouts) : outils avec `chantier`, proposition sur sujet guidé → accept, `gateClosed` agrégé ; isolation : les outils prennent `userId`/terminal **du tour** (aucun identifiant lu dans l'entrée), un chantier d'un autre terminal n'est pas atteignable (`chantier` est un numéro relatif au terminal du tour).
- Non-régression : `AtelierChatServiceJourneyTest`, `AtelierChatServiceSystemPromptTest`, suites F-176/F-196.

## Impacts
- Composants : `JourneyGate`, `JourneyTurnNote`, `JourneyToolCatalog`, `JourneyToolExecutor`, `SubjectJourneyService.proposeGuided`.
- Tables / endpoints : aucun (réponse `gateClosed`/`gateMessage` recalculée).
- Préoccupations transversales : **Auth / sécurité du poste ☑ — la porte de modification**. Appels de `JourneyGate` listés et testés : `AtelierChatService.journeyGateRefusal` (boucle de l'agent, toutes cibles : gateway et runner) → `JourneyGate.refusal` ; `SubjectJourneyResponse.from` → `JourneyGate.isClosed` / `message` (écran, `GET /journey` et toutes les routes du parcours). Aucun autre appelant (`grep JourneyGate`). Tenant ☐ (inchangé) ; Plans/limites ☐ ; Navigation ☐.

## Hors périmètre
Cap `{chantier, etape}` (SF-206-03) ; écran (SF-206-04) ; porte « par chantier » (la décision PO est l'agrégation).
