# Mini-spec — F-233 / SF-233-15 — Le journal de l'écoute (diagnostic de la boucle mains libres)

## Statut
`in-review` — 2026-11-07 — branche `feat/SF-233-15-journal-de-l-ecoute`

## Objectif
> Au prochain essai du PO, savoir **où** la boucle d'écoute mains libres s'arrête : chaque transition et chaque erreur
> de la boucle est journalisée côté page et rangée sur le serveur, sans aucun contenu.

## Constat (audit `docs/audits/AUDIT-2026-11-07-ecoute-jarvis.md`)
Les données prod (`jarvis_turn_timings`, `jarvis_companion_openings`, journaux nginx) disent **qu'il n'y a plus de
tour** après le premier ou le deuxième, jamais **pourquoi** : une écoute annulée au silence, un micro qui ne s'ouvre
pas, une voix qui ne finit pas, une page rechargée ne laissent aucune trace. Le backend ne voit aucun appel `/api`
dans ses journaux.

## Comportement nominal
- **Page** (`JarvisListenTrace`) : à l'ouverture de `/jarvis`, un identifiant de page (aléatoire), la **version de la
  page** (empreinte du `main-*.js` servi) et l'indicateur compagnon. Chaque événement : numéro, ms depuis l'ouverture,
  nom (`[a-z0-9_]`), état (`repos|ecoute|reflexion|parole`), mode de conversation, détail court technique (nom
  d'erreur, durée, nombre de caractères, état d'`AudioContext`… **jamais** de texte dit ou entendu). Un événement
  identique au précédent (même nom, détail, état) incrémente un compteur au lieu d'une ligne (boucles bornées).
- **Envoi** : par lots (≥ 20 événements, ou ≥ 4 s depuis le dernier envoi au prochain événement, ou tout de suite sur
  une erreur, une pause/veille, `pagehide`, page masquée) via `fetch` `keepalive` + jeton ; best-effort, jamais
  bloquant. Au plus 1 500 événements par ouverture de page.
- **Événements** : ouverture (contrôlée par le service worker ? compagnon ? `ecoute=1` ?), transitions d'état et de
  mode, appel/refus de `listen()` et sa raison, ticket/ouverture du flux (succès/échec et cause), micro ouvert/refusé
  (nom d'erreur), état de l'`AudioContext` du niveau, fin d'écoute (silence, fin de phrase, plafond) avec temps de voix
  et pic de niveau, transcription (longueur, refus de la porte), réécoute armée/déclenchée/ignorée et pourquoi, voix
  (début, fin, durée, erreur), pause/veille et leur cause, coupure par la voix, rechargement de mise à jour, page
  masquée/visible/quittée, erreurs JS non rattrapées (nom seulement).
- **Serveur** : `POST /api/jarvis/listen-trace` (204), sous `user_id` du jeton ; ouverte à la portée compagnon
  (`JarvisCompanionScope`). Rangement dans `jarvis_listen_traces`. Purge quotidienne > 7 jours.

## Cas d'erreur
- Corps absent / sans événement → 204, rien rangé. Plus de 100 événements → seuls les 100 premiers.
- Nom d'événement hors `[a-z0-9_]{1,40}` → événement ignoré ; état/mode hors `[a-z_]{0,16}` → vidé ; détail : caractères
  hors liste blanche retirés, coupé à 120.
- Plus de 20 000 lignes sur 24 h pour un compte → le lot est ignoré (204) : un écran emballé ne remplit pas la base.
- Réseau absent : le lot est perdu (best-effort), la page continue.
- Hors `/jarvis` (accès Atelier refusé) → 403 comme les autres routes Jarvis.

## Critères d'acceptation
- [ ] Un lot posté est rangé sous le compte du jeton, champs nettoyés, compteur de répétition gardé (intégration).
- [ ] Un autre compte ne voit rien (lecture filtrée `user_id` ; aucune route de lecture exposée).
- [ ] La route est ouverte à la portée compagnon (test de portée).
- [ ] Bornes : 100 événements/lot, 20 000 lignes/24 h/compte, purge > 7 j (unitaires).
- [ ] La page journalise ouverture, transitions, réécoute, erreurs micro et voix, sans texte utilisateur (spec).
- [ ] La requête SQL de lecture est dans l'audit.

## Contraintes de validation
| Champ | Règle |
|---|---|
| `pageId` | 1–36 caractères `[A-Za-z0-9-]`, sinon `?` |
| `version` | ≤ 24 caractères `[A-Za-z0-9._-]` |
| `event` | `[a-z0-9_]{1,40}` (sinon ignoré) |
| `state`, `mode` | `[a-z_]{0,16}` (sinon vide) |
| `detail` | liste blanche `[A-Za-z0-9 _.:/>=+,-]`, ≤ 120 |
| `t` | ≥ 0, borné à 10⁹ ms ; `n` (répétitions) 1–100 000 |

## Plan de test
- Unitaires : `JarvisListenTraceServiceTest` (nettoyage, bornes, plafond quotidien, purge) ; `JarvisCompanionScopeTest`
  (route ouverte) ; `jarvis-listen-trace.spec.ts` (lots, répétitions, borne, aucune exception).
- Intégration : `JarvisListenTraceApiIntegrationTest` (204, rangé sous le compte, 2ᵉ compte isolé, corps vide).
- Isolation utilisateur : `user_id` du jeton uniquement ; aucun identifiant de compte accepté dans le corps.

## Impacts
- Table **`jarvis_listen_traces`** (migration `250-01-jarvis-listen-traces.xml`, rollback = `dropTable`).
- Endpoint `POST /api/jarvis/listen-trace` ; `JarvisCompanionScope` (+1 route).
- Front : `jarvis-listen-trace.ts` (nouveau), `jarvis.component.ts` (points de journal), `jarvis-voice.service.ts`
  (crochet de journal de la voix).

### Préoccupations transversales
- [x] **Auth / Principal** — une route ajoutée à la portée `jarvis-compagnon`. Composants : `JarvisCompanionScope`,
  `JarvisCompanionSessionGuard` (inchangé, lit la liste), `JwtAuthenticationFilter` (inchangé). Non-régression :
  `JarvisCompanionScopeTest` (routes fermées inchangées).
- [ ] Contexte tenant — non (le `user_id` du jeton, comme `/jarvis/timings`).
- [ ] Plans / limites — non.
- [ ] Navigation / routing — non.

## Hors périmètre
- Corriger la boucle (SF-233-16). Écran de lecture du journal (lecture SQL par l'équipe, voir l'audit).
- Le compagnon natif (aucune modification de l'APK).
