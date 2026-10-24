# Mini-spec — F-215 / SF-215-03 Savoir-faire « cours vidéo », briques graphiques et relecture

## Identifiant
`F-215 / SF-215-03` — Feature parente : F-215 « Le cours vidéo libre (court et long) » — Statut : `in-review` — 2026-10-25
Branche : `feat/SF-215-03-savoir-faire-cours-video`

## Objectif
Quand une vidéo est demandée, l'agent dispose **à la demande** (jamais dans le préfixe stable) d'une **méthode** éprouvée
(celle de l'essai du 2026-10-24), de **briques graphiques** et d'**icônes officielles multi-cloud** déposées sur le poste,
et d'un outil de **relecture** (`hyperframes lint` + images clés à regarder) à passer avant toute publication.

## Comportement attendu
### Cas nominal
1. **Genre `GUIDE`** (sur le modèle du genre `AGENT` de F-182) : `GovernanceFileKind.GUIDE`, déposé comme un skill dans
   chaque sujet du poste, sous `.claude/guides/` — **jamais annoncé** dans la consigne système (contrairement aux skills).
   Le paquet `savoir-durable` apporte `.claude/guides/cours-video.md` (version suivante du paquet ; un poste activé le
   reçoit au prochain « Remettre à jour » — D5 F-96 inchangée). Accepté par l'édition admin, affiché « guide » à l'écran.
2. **Contenu du guide** (une méthode, jamais un contenu — principe directeur du cadrage) : faits extraits des sources →
   narration par chapitre → `voix_off` (un appel par chapitre) → mots horodatés → animations GSAP calées au mot dans
   une composition HTML HyperFrames (`data-composition-id`, `data-start`/`data-duration`, timeline GSAP **en pause**
   enregistrée dans `window.__timelines`) → `video_render_check` (lint sans erreur + images clés **regardées** avec
   `read_file`) → `video_render` → `video_render_status` → `video_publier`. Règles : texte ≥ 20 px en 1080p, une idée
   par plan, toute notion technique **affichée** (CIDR, ports, noms DNS, ressources), jamais d'adresse ni de nom
   inventé (inconnu = générique, ex. `10.180.171.x`), charte de l'application par défaut (D1) remplaçable sur demande.
   Pièges : `<audio>` sans `id` ⇒ vidéo muette ; conteneur flex qui mange les espaces autour de `<b>` ⇒ envelopper le
   texte dans un `<span>` ; chevauchements et textes coupés vus seulement sur les images.
3. **Doctrine d'outils** : une seule ligne **fixe et courte**, présente seulement quand le poste annonce la capacité
   `video_render` (propriété stable du poste — préfixe caché intact) : elle dit que le guide existe et qu'il se lit
   avant de fabriquer une vidéo.
4. **`video_briques`** (outil gateway, déclaré avec la capacité `video_render`) — `dossier` (dossier de la
   composition, relatif au sujet), `icones?` (noms d'icônes, 40 au plus), `chercher?` (mot-clé) :
   - `chercher` seul ⇒ liste des noms d'icônes officielles correspondants (AWS, Azure, GCP, Kubernetes, on-prem,
     génériques), sans rien déposer ;
   - sinon ⇒ dépose (ou rafraîchit) le **guide** `.claude/guides/cours-video.md` (même ressource que le paquet : le
     guide est toujours là, même sans activation de la gouvernance), les briques `<dossier>/briques/charte.css`,
     `briques.js`, `LISEZMOI.md`, et chaque icône demandée en `<dossier>/icons/<nom-court>.png` ; rend la liste de ce
     qui est posé, les icônes introuvables (avec une suggestion de recherche), et le rappel « lis le guide ».
   - Les icônes viennent de la lib `diagrams` du service `diagram-renderer` (F-142) : nouvelle route `POST /icons`
     (recherche par mots-clés, lecture par nom), fournisseurs bornés à une liste blanche, noms validés (aucune
     traversée de chemin), PNG ≤ 512 Kio.
5. **Briques** (point de départ, l'agent compose librement) : `charte.css` (jetons navy/orange/crème, thème sombre
   par défaut et `.clair`, scène 1920×1080, en-tête de chapitre + barre de progression, sous-titres) ; composants :
   zone/sous-réseau, icône + libellé, flèche qui se dessine (`stroke-dashoffset`), paquet qui circule, table de
   routage, requête DNS tapée, badge OK/KO/info, sous-titres calés ; `briques.js` : timeline en pause enregistrée,
   `show/pop/hide/draw/move/typeIn/captions`, `motA()` (instant d'un mot dans un `.mots.json`).
6. **GSAP local** : la chaîne de rendu du poste installe aussi `gsap@3.13.0` (épinglé) à côté d'HyperFrames ; avant
   un `video_render_check` ou un `video_render`, le runner copie `gsap.min.js` dans `<dossier>/briques/` s'il manque
   (la composition le charge en local : aucun CDN au rendu).
7. **`video_render_check`** (runner, capacité `video_render`, même porte que `bash`) — `dossier`, `instants`
   (1 à 12 secondes) : lance `hyperframes lint` puis `hyperframes snapshot --at … --no-end --describe false` (aucune
   analyse d'image envoyée à un tiers) dans `<dossier>/relecture/`, et rend : verdict du lint (erreurs/avertissements +
   sortie), la liste des images PNG à **ouvrir avec `read_file`**, et le rappel de les regarder (lisibilité,
   chevauchements, textes coupés). Si la chaîne n'est pas encore prête (premier usage), la préparation démarre et
   l'outil dit de relancer dans une minute (borné, jamais un blocage du tour).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `video_briques` sans `dossier` (et sans `chercher`), chemin absolu ou avec `..` | erreur d'outil explicite |
| plus de 40 icônes, nom invalide | erreur d'outil explicite (nom invalide ignoré et listé) |
| icône inconnue | listée « introuvable » + suggestion `chercher` ; le reste est déposé |
| service de rendu absent ou muet | briques et guide déposés, icônes « indisponibles pour l'instant » (dit) |
| poste hors ligne / runner ancien (pas de dépôt binaire) | erreur d'outil « dépôt impossible » |
| sujet hébergé (pas de poste) | outil non déclaré ; appel égaré refusé |
| `video_render_check` : dossier introuvable, `instants` absents/hors bornes | `invalid_input` / `not_found` |
| chaîne pas prête | message « préparation en cours, relance dans une minute » (ou échec + remède de la chaîne) |
| lint en erreur | verdict « ERREURS » + sortie du lint : corriger avant de rendre |
| snapshot en échec | sortie de l'outil rendue, aucune image annoncée |

## Critères d'acceptation
- [ ] CA1 — Le paquet semé porte `.claude/guides/cours-video.md` de genre `GUIDE` ; le guide contient la chaîne
  complète, les règles (≥ 20 px, une idée par plan, notions affichées, jamais inventé) et les deux pièges.
- [ ] CA2 — Le genre `GUIDE` est accepté par l'édition admin et affiché « guide » ; il est déposé dans chaque sujet.
- [ ] CA3 — La consigne système ne contient **aucun** contenu du guide ; une seule ligne fixe le cite, présente si et
  seulement si le poste annonce `video_render`.
- [ ] CA4 — `video_briques` dépose guide + briques + icônes demandées, liste les introuvables, cherche sans déposer.
- [ ] CA5 — `diagram-renderer` `POST /icons` : recherche et lecture, liste blanche, noms sûrs, borne de taille.
- [ ] CA6 — `video_render_check` : lint + snapshot (Gemini coupé), images listées ; même porte que `bash` ; capacité
  exigée ; noms identiques des deux côtés (contrat).
- [ ] CA7 — `gsap@3.13.0` installé avec la chaîne et copié dans `<dossier>/briques/` avant contrôle et rendu.

## Contraintes de validation
| Champ | Obligatoire | Format |
|---|---|---|
| `video_briques.dossier` | Oui (sauf `chercher`) | relatif, segments `[A-Za-z0-9._-]`, sans `..`, 200 caractères au plus |
| `video_briques.icones` | Non | 40 au plus ; nom `fournisseur/…/icone` (`[a-z0-9._-]`), fournisseur ∈ aws, azure, gcp, k8s, onprem, generic |
| `video_briques.chercher` | Non | 2 à 60 caractères ; 40 résultats au plus |
| `video_render_check.dossier` | Oui | résolu par `PathResolver` (modèle poste) |
| `video_render_check.instants` | Oui | 1 à 12 nombres ≥ 0 |

## Technique
- Backend : `GovernanceFileKind.GUIDE`, `GovernancePackageService` (message de genre), `GovernancePackageSeeder` (+ ressource
  `governance/savoir-durable/guides/cours-video.md`) ; `atelier/video/VideoKitToolCatalog` (outil + ligne de doctrine),
  `VideoKitToolExecutor`, `VideoIconSource` + `HttpVideoIconSource` (route `/icons`, même `app.diagrams.base-url`) ;
  ressources `video/briques/{charte.css,briques.js,LISEZMOI.md}` ; `VideoRenderToolCatalog.CHECK` ; `AtelierChatService`
  (déclaration, aiguillage, étape, porte, ligne de doctrine).
- Runner : `RenderToolchain` (gsap épinglé, `Prepared.gsap`), `RenderTools` (`video_render_check`, copie de gsap).
- diagram-renderer : `icons.js` + route `POST /icons`.
- Frontend : `governance.models.ts` (genre `GUIDE` → « guide »).
- Aucune table, aucune migration (colonne `kind` en `varchar(16)` sans contrainte), aucune route HTTP publique.

## Plan de test
- Backend unitaires : `GovernancePackageSeederTest` (guide présent, genre GUIDE, contenu clé) ; `VideoKitToolExecutorTest`
  (dépôt nominal, introuvables, recherche seule, service absent, dossier invalide, > 40 icônes, dépôt en échec, hébergé) ;
  `HttpVideoIconSourceTest` (parse, erreurs) ; `AtelierChatServiceVideoKitToolsTest` (déclaration selon la capacité,
  ligne de doctrine présente/absente, aucun contenu du guide dans la consigne, `video_render_check` relayé + porte de bash).
- Intégration : `GovernanceSeededPackageIntegrationTest` (le paquet en base porte le guide).
- Runner : `RenderToolsTest` (gsap installé et copié ; check nominal : lint puis snapshot avec `--describe false`,
  images listées ; lint en erreur ; instants invalides ; chaîne pas prête ⇒ « relance ») ; `ToolRouterTest`.
- Contrat : `VideoRenderToolsContractTest` (nom `video_render_check`).
- diagram-renderer : `tests/test_icons.js` (recherche, lecture, liste blanche, traversée refusée).
- Frontend : spec du libellé « guide ».
- **Isolation** : `video_briques` n'écrit que dans le sujet du tour (Workspace déjà vérifié possédé, chemin nettoyé) ;
  `video_render_check` résout tout par le `PathResolver` du projet.

## Préoccupations transversales
- **Contexte tenant** : dépôt via `ProjectFileDeposit` sur le Workspace du tour (possédé) — composants : `VideoKitToolExecutor`,
  `AtelierChatService`. Relecture sous le projet du tour — `RenderTools`.
- **Plans / limites** : aucun (outils gratuits ; le service d'icônes est interne).
- Auth / navigation : non.
- **Préfixe stable** : une ligne fixe gardée par une capacité stable ; le guide est lu à la demande.

## Hors périmètre
Série d'épisodes, charte commune de série, fiche des sources (SF-215-04) ; Plume vidéo (SF-215-05) ; garde dure
bloquant `video_publier` sans relecture (la relecture reste une doctrine, F-168) ; rendu hébergé.

## Notes et décisions (arbitrages réversibles)
- **A1** — Le guide est **aussi** déposé par `video_briques` (même ressource que le paquet) : la gouvernance étant opt-in,
  un poste non activé n'aurait sinon jamais la méthode. Contenu identique ⇒ le dépôt de gouvernance le reconnaît intact.
- **A2** — Icônes servies par `diagram-renderer` (lib `diagrams` déjà installée, F-142) plutôt qu'embarquées dans le
  runner (≈ 12 Mo) ou installées par pip sur le poste (Python absent des postes verrouillés).
- **A3** — GSAP installé par npm sur le poste à côté d'HyperFrames (comme l'essai), jamais vendu dans notre dépôt.
- **A4** — Relecture = nouvel outil runner `video_render_check` (préfixe `video_render` ⇒ même capacité) plutôt que
  des commandes bash : l'environnement de la chaîne (Chrome, ffmpeg) est celui du rendu, quel que soit le shell du poste.
- **A5** — `video_render_check` passe la porte de `bash` (il lance Node et Chrome), comme `video_render`.
- **A6** — Briques re-déposées à chaque appel : la charte se personnalise dans un `theme.css` chargé après `charte.css`
  (consigne du guide), jamais en éditant `briques/`.
