# Mini-spec — F-191 / SF-191-02 La bande des tâches

## Identifiant
`F-191 / SF-191-02` — Feature parente : F-191 « Le fil des tâches ». Statut `ready` — 2026-10-11 — Branche `feat/SF-191-02-la-bande`.
Contrat API **importé de SF-191-01** (`GET /api/workspaces/{id}/fil`, voir `SF-191-01-tours-du-poste.md`).

## Objectif
Afficher au-dessus de la saisie de tout terminal une bande, repliée par défaut, qui liste les tâches en cours (et finies depuis moins de 10 min) du poste, et ouvre le sujet d'un clic.

## Comportement attendu

### Cas nominal
- Composant `app-terminal-fil` (`atelier/terminal/terminal-fil.component.ts`), posé dans `atelier-terminal.component.html` **sous** les bandes existantes (parcours F-176, attentes F-175) et au-dessus de l'invitation F-185 et du formulaire de saisie. Absent en lecture seule (mosaïque).
- Interroge `GET /api/workspaces/{id}/fil` à l'ouverture puis **5 s après chaque réponse** (jamais deux requêtes en vol ; onglet caché ⇒ prochaine lecture 15 s plus tard sans appel). Changement de terminal ⇒ relecture immédiate.
- **Repliée** (défaut) : une ligne — pastilles « N attend(ent) votre accord » (ambre), « N en cours » (or), « ✓ N terminée(s) » (vert), mini-barres (une par tâche, 6 au plus), chevron.
- **Dépliée** : une ligne par tâche — sujet (« racine » sous le nom pour le terminal du poste, filet or et mention « ici » pour le terminal courant), demande tronquée, **barre** = étapes faites / prévues (barre animée si pas de plan, pleine verte si fini, ambre si en attente), état (outil courant en clair : Lecture, Écriture, Commande, Recherche, Exploration, Sous-tâche… ; « attend votre accord » / « attend votre réponse » ; « ✓ terminé » / « échec » / « interrompu »), durée. Sous-agents listés sous la tâche (libellé, en cours/terminé, durée).
- Ordre servi par l'API (attente → en cours → finis).
- **Absente** quand la liste est vide.
- **Clic** sur une tâche d'un autre terminal ⇒ `router.navigate(['/atelier', workspaceId])` ; sur la tâche du terminal courant ⇒ rien (déjà là). Pas d'« Interrompre ».
- Durées calculées sur l'horloge serveur (`serverTime`), rafraîchies à chaque lecture.
- Mobile (≤ 640 px) : ligne sur trois niveaux, barres des sous-agents masquées.
- `prefers-reduced-motion` : pas d'animation de barre.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Erreur réseau / 5xx | bande inchangée (dernier état connu), relecture au cycle suivant |
| 403 / 404 (terminal sans accès) | bande masquée, plus de relecture pour ce terminal |
| Réponse sans `turns` | traitée comme liste vide |

## Critères d'acceptation
- [ ] Rien ne tourne ⇒ aucune bande dans le DOM.
- [ ] Repliée par défaut avec compteurs justes (attente / en cours / terminées) ; un clic sur l'en-tête déplie.
- [ ] Dépliée : barre à `planDone/planTotal` ; barre indéterminée si `planTotal = 0` ; outil courant libellé en français.
- [ ] Le terminal courant est marqué « ici » ; la racine porte « racine ».
- [ ] Clic sur une tâche d'un autre sujet ⇒ navigation vers `/atelier/<id>`.
- [ ] Une seule requête en vol ; arrêt des relectures à la destruction du composant.
- [ ] Couleurs de la charte uniquement (`--cg-accent`, `--cg-success`, ambre §12 `#F9A825`, `--cg-navy-2`, `--cg-divider`) ; polices Inter / JetBrains Mono ; espacements multiples de 4 px.

## Plan de test
- Unitaires (fonctions pures) : compteurs, libellés d'outil, durées, pourcentage de barre.
- Composant (`TestBed` + `HttpTestingController`) : absente si vide ; repliée puis dépliée ; marques ici/racine ; navigation au clic ; pas de navigation sur « ici » ; 404 ⇒ masquée sans relecture ; relecture après 5 s ; arrêt à la destruction.
- Non-régression : suites `atelier-terminal` existantes vertes (la lecture ne relance qu'après réponse : aucune minuterie dans les tests qui ne répondent pas).
- Isolation : assurée côté serveur (SF-191-01) ; l'écran n'envoie aucun identifiant de compte.

## Impacts
- Composants : nouveau `TerminalFilComponent`, nouveau `TerminalFilService`, modèle `core/models/terminal-fil.models.ts` ; `atelier-terminal.component.html` (une balise).
- Endpoints : consomme `GET /api/workspaces/{id}/fil` (SF-191-01, mergée).
- Tables : aucune.
- Préoccupations transversales : Navigation ☑ — chemins impactés : la bande navigue vers `/atelier/:id` (route existante, déjà utilisée par Vigie, Postes, carte, mosaïque) ; aucune route, aucun garde ajouté ou modifié. Auth ☐ ; Tenant ☐ (serveur) ; Plans ☐.

## Hors périmètre
Bandeau de fin hors sujet courant et son (SF-191-03) ; interrompre / répondre depuis la bande ; remplacer la mosaïque.

## Arbitrages (décidés par défaut, réversibles)
- **Lecture après réponse** (chaîne de `setTimeout`) plutôt qu'un intervalle fixe : jamais deux lectures en vol, et aucune minuterie tant que la première réponse n'est pas arrivée.
- **État replié/déplié non mémorisé** : repliée à chaque ouverture de terminal, comme demandé (« repliée par défaut »).
- **Panneau sombre** (`--cg-navy-2`) dans toutes les peaux, y compris le terminal Teams clair : la bande est un objet du poste, pas du sujet.
