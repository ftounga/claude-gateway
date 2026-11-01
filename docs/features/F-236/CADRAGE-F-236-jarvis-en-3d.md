# CADRAGE F-236 — Jarvis en 3D

**Statut :** cadrage du 2026-11-01, **go PO du 2026-11-01**. Famille « Jarvis » : F-230 → F-235, **F-236**.
**Nature : un projet**, pas une feature isolée (consigne PO du 2026-11-01) : **4 itérations** livrables et visibles,
**27 SF** de 2 jours au plus (≈ 48,5 j). Cadrage **directement livrable** : toutes les décisions techniques ont un
défaut tranché (§16). Le PO n'est sollicité que pour ses **goûts visuels** : trois questions (§17) et le **visa des
planches de captures** à la fin de chaque itération (§15).

**Référence visuelle validée comme direction** : `docs/features/F-236/maquette-jarvis-3d.html` (copie de la maquette
Three.js 0.160 du 2026-11-01) — **à reprendre et dépasser**.

---

## 1. Demande PO (2026-11-01)

L'écran `/jarvis` actuel (orbe Canvas 2D `jarvis-orb.component.ts`, constellation SVG `scenes/`, scènes F-234 livrées
12/13) est jugé **« pauvre, pas précis, n'occupe pas assez l'écran »**. Le PO veut une animation **3D élaborée
« vraiment à la Jarvis »**.

**Consignes du PO, intégrées à ce cadrage** :

| # | Consigne | Où |
|---|---|---|
| P1 | **Le maximum de fonctionnalités visibles** (« sinon le terminal suffisait »), interface **futuriste** ; zoom/dézoom partout où c'est pertinent ; **liaisons lumineuses entre TOUS les composants** (clients, sujets, chantiers, étapes, personnes, tickets, messages, postes, veilles, sous-agents) qui s'allument, s'éteignent, deviennent transparentes selon le contexte ; **profondeur, transparence, calques**. Aller plus loin, inventer. | §8, §9, §11, §13 |
| P2 | **Voir les deux cerveaux** (F-235) : le cerveau rapide est le petit cœur ; quand il confie une demande à Opus, le cœur **se transforme** (champ neuronal qui grossit et se densifie, connexions qui se multiplient, couleur et lumière qui changent) ; passage **spectaculaire** ; le grand cerveau reste **déployé** pendant qu'il travaille, puis **se replie**. | §10 |
| P3 | **PC : occuper toute la place** (panneaux latéraux holographiques, couches d'information, tableaux flottants, chronologie). **Téléphone : version allégée**, l'essentiel. | §12 |
| P4 | **Plusieurs itérations** : socle, enrichissements, raffinements ; chacune livrable et visible ; SF ≤ 2 j. | §15 |
| P5 | **Le poids n'est pas un frein** (l'application peut doubler) : module 3D chargé paresseusement sur `/jarvis`, **aucune économie d'effets sur PC**. | §4, §6.4 |
| P6 | **Nouvelles fonctionnalités** au-delà de l'existant, avec leur intérêt, classées par itération. | §13 |
| P7 | **Joli, lumineux, très beau, transitions soignées partout** : section « Direction artistique et transitions » ; captures de référence validées par le PO à chaque itération ; une SF de polissage visuel à la fin de chaque itération. | §7, §15 |

**Ce qui ne change pas** : le « cerveau des scènes » F-234 (bus, registre, lecteur, rapprochement de noms, univers,
`🖥 montrer`, mesure) et les états F-233/F-235 sont **réutilisés tels quels**. F-236 change ce qui **dessine** et
ajoute des **vues** ; il ne touche ni au raisonnement, ni à la voix, ni aux consignes.

### 1.1 Règles qui bornent ce projet (non négociables)

| Règle | Conséquence |
|---|---|
| **Zéro coût IA, préfixe intact** | Aucun appel modèle ; aucune consigne système, description d'outil, consigne orale modifiée. Tout est déduit d'états déjà publiés ou de lectures existantes. |
| **Isolation `user_id`** | Lectures existantes déjà filtrées (`/api/jarvis/universe`, `/api/jarvis/watches`, historique du fil). La seule lecture nouvelle (carte d'activité 7 j, SF-236-26) est un agrégat **en lecture seule**, filtré `user_id`, avec test d'isolation. |
| **Aucun appel réseau pendant un tour** (F-234 D1) | Inchangé. Les vues « hors tour » (chronologie de la journée, replay) lisent l'historique **à la demande, hors tour**. |
| **Rien ne sort de l'appareil** | Rendu, rapprochement, replay : 100 % navigateur. La vue cockpit communique entre fenêtres du **même** navigateur (`BroadcastChannel`). |
| **Le 2D reste** | Le rendu F-234 devient le **repli** (WebGL absent, rendu logiciel, `prefers-reduced-motion`, mode sobre, choix manuel, perf insuffisante). |
| **Aucun effet sonore** (D-PO-5 F-234) | Reconduit : la seule voix reste celle de Jarvis. |

### 1.2 Décisions antérieures amendées (signalées explicitement)

| Décision | Aujourd'hui | Amendement F-236 | Pourquoi |
|---|---|---|---|
| **F-234 D6** « Canvas 2D + SVG, **pas de WebGL** » | WebGL interdit | **WebGL (Three.js)** pour le rendu principal ; 2D en repli. Les craintes de D6 deviennent des **budgets mesurés** (§6) et une **qualité adaptative** avec repli automatique. | Demande PO (P1-P3). |
| **F-234 D8** « aucune couleur nouvelle » | Crème / orange sur navy | **Palette holographique de Jarvis**, bornée au rendu 3D de `/jarvis` : DESIGN_SYSTEM **§23** (§7.1). | Cyan, vert, violet lumineux indispensables sous bloom. |
| **F-234 D-PO-1** « seul le sujet est touchable » | Client, chantier inertes | **Étendu sans navigation** : toucher **tout** nœud (client, chantier, étape, personne, ticket, message, veille) = la caméra s'y rend + **fiche holographique** (inspecteur, SF-236-19). Toucher un sujet = `chooseSubject` (inchangé). **Toujours aucune navigation hors de Jarvis.** | P1 « toucher et voir » ; regarder ≠ quitter. |
| **F-234 D7** « téléphone : client courant déployé + autres en lisière » | 2D radiale | **Reconduit** sur téléphone (§12) ; sur PC tout l'univers peut être déployé. | Lisibilité téléphone. |
| **F-234 D14** « une seule scène à la fois » | Une scène | **Reconduit au centre de l'écran** ; sur PC, les **panneaux latéraux** (§12) montrent en plus l'état permanent (tour en cours, journée). | P3. |
| **Budget d'`angular.json`** | `initial` 500 ko / 1 Mo | **Inchangé** : le 3D est un chunk paresseux, le bundle initial ne bouge pas. Le chunk 3D n'a **pas de plafond serré** (P5), seulement une **garde d'accident** (§6.4). | P5. |

---

## 2. Vérifications faites (2026-11-01, `origin/main` `e58d2674`)

- **Numéro** : F-236 libre dans `docs/PRODUCT_SPEC.md` (dernier pris : F-235) et dans `docs/features/`.
- **Aucune 3D livrée** : pas de `three` dans `frontend/package.json` ; la carte vivante F-173/F-174 (cytoscape)
  dessine l'infra d'un dépôt, pas l'univers du PO. Aucun doublon.
- **Le cerveau des scènes suffit** : `ReaderState` porte déjà nœuds cités (+ priorité), scène + données dédiées,
  pulsations, « À envoyer » +n, demande « montrer », étapes faites, gel de coupure, noms dits. **Aucun champ nouveau
  n'est nécessaire au lecteur.**
- **Replay possible sans endpoint nouveau** : `GET /api/workspaces/{id}/chat` (historique, filtre `user_id`) rend
  `AtelierMessageResponse(…, terminal, …, via, brain)` ; `terminal` porte la trace du tour affichée au terminal. La
  mini-spec SF-236-22 vérifie que les étapes (`AtelierStreamAction`) y sont rejouables ; à défaut, elle ajoute un
  champ en lecture seule (additif).
- **Veilles** : `GET /api/jarvis/watches` (`JarvisAccountWatchController`) existe, filtré `user_id`.
- **Route** : `/jarvis` déjà paresseuse (`app.routes.ts`) ⇒ le moteur 3D vit dans un **second** chunk paresseux.
- **Version Three.js** : **`three@0.186.1`** (2026-09-24) ; `0.187.0` (2026-10-30) écartée car trop récente. Types :
  `@types/three@0.186.0`.

---

## 3. Points d'accroche entre le cerveau (F-233/F-234/F-235) et le moteur 3D

**Principe** : un **directeur de scène pur** (`JarvisStageDirector`, aucun import de `three`, testé sous Karma) lit les
signaux existants et produit un **plan de scène déclaratif** (`StagePlan` : caméra, tons et opacités des nœuds et
liens, panneaux, effets, état des cerveaux) ; le **moteur 3D** ne fait que **dessiner et interpoler** ce plan. Le repli
2D continue de lire le bus. **Le bus reste l'unique point d'accroche** avec le flux du tour.

```
Flux du tour ─► JarvisComponent ─► JarvisSceneBus (inchangé) ─┬─► JarvisStageDirector (pur) ─► StagePlan ─► moteur 3D (three, hors zone)
                  │ state, level, delegations, conversation,    │                                    └─► BroadcastChannel ─► fenêtre cockpit
                  │ wake, brain                                  └─► constellation SVG + app-jarvis-scene (repli 2D, inchangés)
```

### 3.1 Signaux lus (aucun n'est modifié)

| # | Source | Signal / méthode | Usage 3D |
|---|---|---|---|
| H1 | `scenes/jarvis-scene-bus.service.ts` | `universe` (`JarvisUniverse` : clients → sujets → chantiers, personnes, `toSend`, `toSendCount`) | Disposition déterministe, graphe de liaisons ; relu à `done` ⇒ interpolé, jamais de saut. |
| H2 | idem | `state` (`ReaderState` : `lit[]`, `scene` (`SceneView` `id`/`libelle`/`count`/`detail`/`entity`/`pinned`/`data`), `pulses`, `toSendAdded`, `show`, `stepsDone`, `frozen`, `said[]`) | Nœuds et liens allumés, caméra, scène 3D par `scene.id`, éclairs, enveloppe, gel. |
| H3 | idem | `focus` (`ShowFocus`, `jarvis-show.ts`) | « Montrer » en 3D. |
| H4 | idem | `ambient` (`hostDown`, `liveSubjects`, `calling`, `cost`, `alert`) | Ambiance A1-A5, alertes. |
| H5 | idem | `subjectId`, `turnActive`, `sober` | Sujet courant (D19 : `null` ⇒ « Jarvis global »), tour, mode sobre ⇒ repli 2D sobre. |
| H6 | `jarvis.component.ts` | `state` (`repos`/`ecoute`/`reflexion`/`parole`) | États du cœur (§9.1). |
| H7 | idem | `orbLevel()` (micro ou voix, 0-1) | Ondes de voix entrantes / sortantes. |
| H8 | idem | `delegations` (`{id,label,subjectId}[]`), `fastBrainOn`, `done.brain` (`FAST`/`FAST_DELEGATED`/`OPUS`) | **Les deux cerveaux** (§10). |
| H9 | `jarvis-conversation.ts` | `conversation.mode()` (`idle`/`active`/`paused`) | Mise en veille. |
| H10 | `jarvis-wake.ts` | rappel `onWake` déjà branché par l'écran | Réveil (l'écran relaie `director.wake()`). |
| H11 | `jarvis.component.ts` | `touchSubject(id)`, `talk()` | Toucher un sujet / le cœur (mêmes méthodes). |
| H12 | idem | `this.jarvis.scenes(this.scenes.takeStats())` à `done` | Inchangé ; mesure du rendu par le même canal `UsageEvent` (événement `RENDER`). |
| H13 | `GET /api/jarvis/watches` (existant) | lu à l'ouverture | Horloge des veilles, radar des alertes. |
| H14 | `GET /api/workspaces/{id}/chat` (existant) | lu **hors tour, à la demande** | Chronologie de la journée, replay, « Pendant votre absence » en film. |
| H15 | Rôle courant (déjà utilisé par `ambient.cost`) | ADMIN seulement | Coût en € (A4) ; masqué en mode présentation. |

### 3.2 Ce qui est ajouté côté écran

| Fichier (nouveau) | Rôle |
|---|---|
| `frontend/src/app/jarvis/stage3d/jarvis-stage-director.ts` | **Pur** : (H1…H15) → `StagePlan`. |
| `…/stage3d/jarvis-layout-3d.ts` | **Pur** : positions 3D déterministes (hachage stable des clés). |
| `…/stage3d/jarvis-link-graph.ts` | **Pur** : graphe de **toutes** les liaisons (§8.2) et leur état (absent / latent / contexte / actif / allumé). |
| `…/stage3d/jarvis-camera-rules.ts` | **Pur** : ressort amorti critique, règles de caméra (§9.3). |
| `…/stage3d/jarvis-brains.ts` | **Pur** : machine d'état des deux cerveaux (§10). |
| `…/stage3d/jarvis-render-capability.ts`, `jarvis-quality.ts` | **Purs** : 3D ou 2D, niveau de départ, qualité adaptative. |
| `…/stage3d/jarvis-motion.ts` | **Pur** : jetons de durée et de courbe (§7.4), partagés CSS/JS. |
| `…/stage3d/jarvis-stage-3d.component.ts` + `engine/*.ts` | Moteur Three.js, chargé par `import()` dynamique, rendu **hors zone**. |
| `…/stage3d/scenes3d/*.ts` + `index.ts` | Une fonction de rendu par scène ; `SCENES_3D: Record<sceneId, Scene3d>` ; absente ⇒ **S-GEN 3D**. |
| `…/stage3d/hud/*.component.ts` | Panneaux latéraux, tableaux flottants, chronologies, inspecteur (DOM holographique). |
| `jarvis.component.html` | 3D en **plein écran derrière** le HUD ; commandes secondaires dans un volet « Réglages ». |

**Règle pour l'avenir (prolonge F-234 D17)** : toute scène ajoutée au registre F-234 **déclare son rendu 3D** dans
`SCENES_3D` **ou** retombe sur S-GEN 3D ; un test échoue sinon ; ligne ajoutée à
`project-governance/checklists/review-checklist.md`.

---

## 4. Choix techniques (tranchés)

| # | Sujet | Décision | Écarté |
|---|---|---|---|
| T1 | **Moteur** | `three@0.186.1` en dépendance **npm** (+ `@types/three@0.186.0`), importé **directement** dans un composant Angular standalone, **sans surcouche** ; imports ciblés `three/examples/jsm/...`. | `angular-three` (surcouche couplée à Angular) ; CDN + importmap (CSP, version non figée) ; Babylon.js (plus lourd, aucun gain). |
| T2 | **Zone Angular** | Rendu, boucle `requestAnimationFrame`, gestes **dans `NgZone.runOutsideAngular`** ; retour en zone **seulement** pour `touchSubject` / `talk` / ouverture d'un volet. Les signaux sont lus par `effect()` qui écrit le plan, jamais l'inverse. | Boucle en zone (détection de changements à 60 Hz). |
| T3 | **Chargement** | 2D affiché **immédiatement** ; vérification de capacité puis `import()` du chunk 3D (préchargé dès l'ouverture) ; à la première image 3D, **fondu 2D → 3D** (§7.4). Échec ⇒ 2D, sans message. | Écran noir d'attente. |
| T4 | **Post-traitement** | `EffectComposer` : `RenderPass` → **bloom sélectif** (`UnrealBloomPass` sur un calque « émissif » ; le texte n'est jamais flouté) → **profondeur de champ** légère (`BokehPass`, PC Haut seulement) → **passe de finition** maison (`ShaderPass` : vignette, grain fin, aberration chromatique de bord ≤ 1 px, lignes de balayage) → `SMAAPass` (PC) → `OutputPass`. Bloom pleine résolution sur PC Haut, demi-résolution sur Moyen, coupé en Bas (lueur portée par sprites additifs). | `postprocessing` (pmndrs) : inutile, les passes `three` suffisent. |
| T5 | **Étiquettes** | **CSS2DRenderer** (DOM) : polices de la charte, nettes à tout ratio de pixels, accents sans atlas, étiquettes de nœud = **vrais boutons** (clavier, lecteur d'écran, cible ≥ 44 px). Bornes : PC ≤ 48 visibles ; téléphone ≤ 16. Mise à jour si déplacement > 0,5 px ; estompées par la profondeur. | `troika-three-text` (SDF) : texte inaccessible et non cliquable au clavier ; la netteté DOM est meilleure. |
| T6 | **Panneaux holographiques** | **DOM** en « verre holographique » (§7.2), projetés depuis leur ancre 3D, toujours **face à l'écran** ; pool de panneaux ancrés (PC 8, téléphone 2) ; panneaux latéraux et tableaux flottants = composants Angular en surimpression. | `CSS3DRenderer` (texte incliné, flou sur Android) ; panneaux en texture WebGL (texte flou). |
| T7 | **Particules** | Cœur(s) = `Points` + `ShaderMaterial` : position de repos **et** position déployée dans deux attributs, interpolation, respiration, onde, teinte et scintillement pilotés par **uniformes** (CPU ≈ 0). Connexions neuronales = `LineSegments` précalculées (k plus proches voisins), révélées par un uniforme de seuil. | Un objet par particule. |
| T8 | **Nœuds et liens** | Sujets, chantiers, étapes, personnes, tickets, messages = **`InstancedMesh`** par forme (sphère, octaèdre = losange F-234, cube arrondi, carte plate, enveloppe) ; clients (≤ 12) = maillages + halo + anneau. Liens = **`LineSegments2` + `LineMaterial`** (largeur en pixels, couleur par sommet, mélange additif, dégradé source → cible) : une géométrie par **famille de liaison** (§8.2). | `THREE.Line` (1 px, invisible sur téléphone). |
| T9 | **Impulsions et éclairs** | **Pool fixe** (PC 1 024, téléphone 128) dans un `Points` unique, tampons préalloués ; **zéro allocation par image**. | Un `Sprite` créé/détruit par impulsion (maquette) ⇒ ramasse-miettes, saccades. |
| T10 | **Capacité ⇒ 3D ou 2D** | 2D si : pas de **WebGL2** ; `failIfMajorPerformanceCaveat` ; `prefers-reduced-motion: reduce` ; `saveData` ; **mode sobre** ; choix manuel « Vue 2D » (par appareil, `localStorage` sous try/catch) ; contexte perdu deux fois. Niveau de départ : **Haut** si `pointer: fine` et largeur ≥ 1024 px ; **Bas** si `deviceMemory ≤ 3` ou `hardwareConcurrency ≤ 4` ; **Moyen** sinon. | — |
| T11 | **Contexte WebGL perdu** | Bascule **immédiate** en 2D ; une restauration tentée ; seconde perte ⇒ 2D pour la session. | — |
| T12 | **Déterminisme (banc)** | Horloge et PRNG **injectés** ; `?banc=<scène>&t=<ms>` actif **hors build de production** seulement, sur `jarvis-universe.fixture.ts` ⇒ captures reproductibles. | — |
| T13 | **Cockpit multi-écran** | Fenêtre `/jarvis?cockpit=1` ouverte par l'écran principal ; état relayé par **`BroadcastChannel`** (même navigateur, même origine) ; placement sur l'écran externe par la **Window Management API** quand disponible (Chrome), sinon l'utilisateur la déplace. Aucun serveur. | WebSocket / serveur de relais (inutile). |

---

## 5. Architecture du rendu : calques et profondeur

Six **calques** du fond vers l'avant ; chaque calque a son opacité, sa vitesse de parallaxe et son traitement :

| Calque | Contenu | Parallaxe | Flou de profondeur (PC) | Téléphone |
|---|---|---|---|---|
| **L0 Le vide** | Fond navy, nébuleuse procédurale très lente, champ d'étoiles (3 profondeurs), poussière en suspension | 0,1 | fort | étoiles seules (1 profondeur) |
| **L1 Le sol** | Sol polaire (grille radiale), reflet doux de l'univers, cercles de distance gradués | 0,6 | moyen | grille simple |
| **L2 L'univers** | Cœur(s), clients, sujets, chantiers, étapes, personnes, tickets, messages, veilles, sous-agents | 1 | net au point de focus | idem, déploiement restreint |
| **L3 Les filaments** | Toutes les liaisons, impulsions, éclairs, comètes | 1 | net | liaisons du focus seulement |
| **L4 Les hologrammes ancrés** | Panneaux attachés aux nœuds (tickets, commande, étapes, enveloppe, inspecteur) | 1 (ancrés) | — | ≤ 2 |
| **L5 Le HUD** | Panneaux latéraux, tableaux flottants, chronologies, sous-titres, barre, compteurs | 0 (fixe) | — | barre + sous-titres + compteur |

La **profondeur** se lit par : brouillard exponentiel, opacité décroissante des liens éloignés, taille des
étiquettes indexée sur la distance, flou de profondeur (PC), parallaxe des calques au moindre mouvement de caméra.

---

## 6. Budget de performance (mesuré)

### 6.1 Cibles

| Mesure | Téléphone (Android milieu de gamme ; référence : le téléphone du PO) | PC (référence : le PC du PO, plein écran) |
|---|---|---|
| Images/s, scène chargée | **p50 ≥ 50**, p10 ≥ 40 | **p50 ≥ 60**, p10 ≥ 50 |
| CPU par image (mise à jour + soumission, hors GPU) | **p90 ≤ 4 ms** | p90 ≤ 6 ms |
| Ratio de pixels | plafond **1,5** (Moyen) / 1 (Bas) | plafond **2** (Haut) |
| Particules (cœurs déployés compris) | 2 500 / 900 | **12 000** |
| Connexions neuronales (grand cerveau) | 1 500 | **8 000** |
| Impulsions simultanées | 128 | 1 024 |
| Étiquettes / panneaux ancrés | 16 / 2 | 48 / 8 |
| Appels de dessin | ≤ 40 | ≤ 120 |
| Allocations en régime | **0 par image** | **0 par image** |
| Démarrage | 2D tout de suite, 3D ≤ 1,5 s (4G) | 3D ≤ 1 s |
| Fuite (10 ouvertures/fermetures) | tas JS + mémoire GPU stables ± 5 Mo | idem |

Mesure : banc `?banc=perf` (60 s, histogramme i/s et ms CPU, `renderer.info`) + DevTools à distance ; rapport joint
à chaque PR touchant le moteur.

### 6.2 Batterie et onglet caché

- **Onglet caché / écran verrouillé** : la boucle **s'arrête**.
- **Repos** (aucun tour depuis 20 s) : téléphone plafonné à **30 i/s** ; PC inchangé.
- **Veille** : téléphone **20 i/s**, bloom coupé ; PC 30 i/s, bloom réduit.
- Le bus F-234 garde son horloge logique (250 ms, déjà suspendue onglet caché) ; le moteur n'en ajoute pas.

### 6.3 Qualité adaptative à trois niveaux

Moyenne glissante de l'intervalle entre images sur 2 s ; **descend** si > 20 ms pendant 2 s ; **remonte** si < 13 ms
pendant 10 s ; **au plus un changement / 5 s** ; chaque changement est **fondu** (§7.4, T-Q). Niveau Bas < 30 i/s
pendant 5 s ⇒ **repli 2D** (fondu), compté. Varient avec le niveau : ratio de pixels, résolution du bloom, profondeur
de champ, nébuleuse, poussière, nombre de particules / connexions / impulsions, étiquettes, panneaux.

### 6.4 Poids (P5 : pas un frein)

| Morceau | Impact | Garde |
|---|---|---|
| Bundle initial | **+0 ko** | budget `initial` d'`angular.json` inchangé |
| Chunk `/jarvis` existant | ≈ +10 ko gzip (directeur, règles, capacité, chargeur) | mesuré dans la PR |
| **Chunk 3D** (three, passes, lignes épaisses, moteur, scènes, HUD) | estimé **≈ 300-450 ko gzip** à la fin du projet ; **aucun effet sacrifié au poids** | **garde d'accident** `frontend/scripts/check-jarvis-3d-budget.mjs` : alerte si > 800 ko gzip (import involontaire), jamais un objectif d'économie |

Un appareil en repli 2D **ne télécharge jamais** le chunk 3D.

---

## 7. Direction artistique et transitions

### 7.1 Palette et lumière — DESIGN_SYSTEM §23 « Palette holographique de Jarvis » (amendement proposé)

> **Amendé par le goût du PO (V1, 2026-11-02)** : normal = **orange de la marque** `#E07B39` (texte `#F0954F`),
> attente = **cyan** `#5FD4FF`. Le tableau ci-dessous est la proposition d'origine ; la référence est DESIGN_SYSTEM §23.

**Décision actuelle** : §22 (F-234 D8), aucune couleur nouvelle sur `/jarvis`. **Variante** : palette **bornée au
rendu 3D de `/jarvis`** (comme §15 et §21 pour leurs surfaces) ; le repli 2D garde §22. Écrite dans
`docs/DESIGN_SYSTEM.md` par **SF-236-01**, contrastes mesurés, **avant** tout composant qui l'utilise.

| Rôle (grammaire) | Jeton | Valeur | Contraste texte sur navy (≈) | Charte ? |
|---|---|---|---|---|
| Fond, brouillard | `--cg-navy` | `#0B1020` | — | oui (§2) |
| Lueur centrale du fond | `--cg-holo-abyss` | `#0E2340` → `#0B1020` (dégradé radial) | — | nouveau |
| **Normal** — cerveau rapide, liens, nœuds, étiquettes | `--cg-holo` | `#5FD4FF` | ≈ 10:1 | nouveau |
| Normal atténué (orbites, trame, liserés) | `--cg-holo-dim` | `#5FD4FF` à 35 % | — | nouveau (dérivé) |
| Cœur incandescent (centre des cœurs) | `--cg-holo-core` | `#E8FBFF` | ≈ 17:1 | nouveau |
| **Cerveau principal au travail** (Opus) | `--cg-holo-deep` | `#9B7BFF` → centre `#F3EDFF` | ≈ 6,5:1 | nouveau |
| **Connecté / réussi** | `--cg-holo-green` | `#4CF2A0` | ≈ 12:1 | nouveau |
| **Attend le PO** (« oui », question, à envoyer, attente) | `--cg-orange-2` | `#F0954F` | 8,2:1 | **oui** — l'orange de la marque devient la couleur de l'attente |
| **Alerte** | `--cg-holo-red` | `#FF5A6E` | ≈ 5,8:1 | nouveau (`--cg-error` trop sombre sous bloom) |
| Éteint / déconnecté | `--cg-holo-off` | `#2A4256` | — (forme) | nouveau |
| Texte des panneaux | `--cg-cream` | — | ≥ 12:1 | oui |

**Grammaire des couleurs d'état (une seule source : `jarvis-motion.ts` + jetons CSS)** : **cyan** = normal ·
**violet** = le cerveau principal travaille · **vert** = connecté / réussi · **orange** = attend le PO · **rouge** =
alerte · **gris bleuté** = éteint. Aucune autre signification, nulle part.

**Lumière** :
- **Bloom sélectif** : seuls les objets émissifs (particules, filaments, halos, anneaux) brillent ; le texte reste net.
  Intensité de base 1,1 ; montée temporaire à 1,6 lors des moments forts (passage de cerveau, réveil, « oui »).
- **Halos** : sprite radial additif par nœud, rayon et opacité indexés sur l'état (latent 0,2 ; contexte 0,45 ;
  allumé 1 avec respiration ±8 %).
- **Dégradés** : tout lien est un dégradé de la couleur de sa source vers celle de sa cible ; le fond a une lueur
  radiale sous le cœur qui suit son intensité (le cœur « éclaire » la pièce).
- **Transparences** : 5 paliers seulement — 0,04 (latent) · 0,18 (contexte) · 0,45 (actif) · 0,75 (panneau) · 1
  (allumé). Aucune valeur intermédiaire arbitraire.

**Polices** : **aucune nouvelle** (défaut) — titres HUD et étiquettes en **Space Grotesk** 600 capitales espacées
(`.08em` à `.3em`), clés/commandes/compteurs en **JetBrains Mono**, sous-titres et corps en **Inter**. La maquette
utilisait Chakra Petch / IBM Plex Mono : non retenues (question de goût V2, §17).

**La couleur ne porte jamais seule l'information** (§22 reconduit) : la **forme** dit le type (client = sphère à
anneau, sujet = lune, chantier = losange, étape = perle, personne = carré, ticket = carte, message = enveloppe,
veille = cadran, sous-agent = petit cœur), le **nom** est écrit, l'état a un **mot**.

### 7.2 Matières

| Matière | Où | Recette |
|---|---|---|
| **Particules** | Cœurs, nuages, poussière, impulsions | Point additif à texture radiale, taille atténuée par la distance, **scintillement** (bruit lent par particule), traînée courte sur les impulsions rapides. |
| **Verre holographique** | Tous les panneaux | Dégradé vertical de la teinte d'état (13 % → 0) sur navy à 72 %, **flou d'arrière-plan** 10 px (PC seulement), liseré 1 px lumineux, **coins chanfreinés** (`clip-path`), reflet en biais qui traverse lentement (12 s), ombre lumineuse externe. |
| **Lignes de balayage** | Panneaux, HUD | Trame horizontale 1/3 px à 4 % qui **défile** lentement (8 s) ; balayage lumineux à l'apparition (§7.4). |
| **Filaments** | Toutes les liaisons | Ruban fin à **noyau blanc** et **halo coloré**, légère ondulation (0,5 Hz), dégradé source → cible ; les impulsions sont des **perles** qui le parcourent. |
| **Fil de fer** | Icosaèdre du cœur, globe web, cadrans | Arêtes additives, opacité 0,35, rotation lente. |
| **Fresnel** | Sphères des clients, enveloppe du grand cerveau | Bord plus lumineux que le centre (effet de bulle d'énergie). |
| **Champ neuronal** | Cerveaux | Points + connexions aux k plus proches voisins, **éclairs synaptiques** qui courent sur les connexions. |
| **Nébuleuse et poussière** | Calque L0 (PC) | Bruit fractal très lent, teinte abysse ; poussière en suspension qui accroche la lumière près du cœur. |
| **Sol polaire** | Calque L1 | Grille radiale, cercles gradués qui s'illuminent quand une onde les traverse. |

### 7.3 Règles de qualité visuelle

1. **Jamais de saut brutal** : toute propriété visible (position, échelle, couleur, opacité, intensité, largeur de
   lien, nombre de particules affichées) passe par une **interpolation** ou un **ressort**. Le directeur produit des
   **cibles** ; seul le moteur interpole. Test : aucune propriété du plan rendu ne varie de plus de son pas maximal
   entre deux images (hors coupure volontaire).
2. **Transitions interruptibles** : une transition interrompue repart **de l'état courant** vers la nouvelle cible,
   jamais de retour au début, jamais de « téléportation ».
3. **Indépendance de la cadence** : durées en millisecondes, ressorts intégrés en temps réel ; même rendu à 30 et
   60 i/s.
4. **Au plus trois transitions majeures simultanées** (caméra, cerveau, scène) ; les autres attendent ou se fondent.
5. **Cohérence des couleurs d'état** partout (3D, HUD, panneaux, chronologies) : une seule source de jetons.
6. **Hiérarchie de mouvement** : un seul « héros » à la fois (ce dont on parle) ; le reste bouge plus lentement et
   plus faiblement.
7. **Rien ne bouge brusquement pendant que le PO parle** (§9.3).
8. **Anti mal des transports** : aucun roulis, focale fixe, accélérations douces, ≤ 1 déplacement de caméra / 2,5 s.
9. **Lisibilité d'abord** : aucun texte flouté, aucun texte incliné, contraste ≥ 5,8:1 pour tout texte.
10. **Le noir n'existe pas** : jamais d'écran vide ni de clignotement noir (chargement, repli, qualité, erreurs).

### 7.4 Catalogue des transitions

**Courbes nommées** (`jarvis-motion.ts`, exposées aussi en propriétés CSS `--jv-ease-*`) :

| Nom | Courbe | Usage |
|---|---|---|
| `sortie` (ease-out expo) | `cubic-bezier(.16, 1, .3, 1)` | apparitions, allumages |
| `entree` (ease-in expo) | `cubic-bezier(.7, 0, .84, 0)` | disparitions, extinctions rapides |
| `glisse` (ease-in-out) | `cubic-bezier(.65, 0, .35, 1)` | déplacements, changements de teinte |
| `souffle` (sinus) | `cubic-bezier(.37, 0, .63, 1)` | respirations, veille |
| `rebond` (léger, 3 %) | `cubic-bezier(.34, 1.3, .64, 1)` | apparition de panneaux, stabilisation du grand cerveau |
| `ressort` | ressort amorti **critique** (ω = 5,5 rad/s, aucun dépassement) | caméra |

| # | Transition | Durée | Courbe | Déroulé |
|---|---|---|---|---|
| **T-E1** | **Entrée de l'écran** (première du jour) | **2,6 s** | séquence | 0-300 ms fond qui s'éclaire depuis le centre (`sortie`) · 200-900 le cœur s'allume d'un point à sa taille (`rebond`) · 500-1 800 la caméra **recule depuis l'intérieur du cœur** (`glisse`) · 900-2 000 les orbites se **tracent** (`sortie`) · 1 100-2 300 clients allumés **en cascade** (70 ms d'écart ; connectés vert 300 ms puis cyan) · 1 900-2 600 le HUD glisse depuis les bords (`sortie`, 40 ms d'écart). **Sautée au premier toucher** (fondu 300 ms vers l'état final). |
| T-E2 | Entrée (ouvertures suivantes du jour) | 0,9 s | `sortie` | cœur + univers en fondu-échelle 0,96 → 1, HUD en 400 ms. |
| **T-S** | **Sortie de l'écran** | 0,5 s | `entree` | HUD s'efface (200 ms) · l'univers **se replie vers le cœur** (350 ms) · le cœur se réduit à un point lumineux (150 ms) · navigation. |
| T-F | Fondu 2D ↔ 3D (chargement, repli) | 0,4 s | `glisse` | fondu croisé ; jamais de noir. |
| **T-C1** | Cœur : repos → écoute | 0,35 s | `sortie` | les anneaux se tournent vers la caméra, ondes entrantes. |
| T-C2 | Cœur : écoute → réflexion | 0,6 s | `glisse` | particules accélèrent, nuage intérieur en tourbillon, liens s'éclairent faiblement. |
| T-C3 | Cœur : réflexion → parole | 0,25 s | `sortie` | pulsation calée sur la voix, rayons autour du cœur. |
| T-C4 | Cœur : → repos | 1,2 s | `souffle` | tout ralentit et se calme. |
| T-C5 | Cœur : → « oui » attendu (orange) | 0,5 s | `glisse` | coquille qui vire à l'orange, anneau orange qui se trace. |
| T-C6 | Coupure (interruption) | 1 s gel + 0,4 s | `entree` | gel sur l'instant, lueur à 50 %, puis la scène s'efface. |
| **T-B1** | **Passage au cerveau principal** (aller) | **1,6 s** | séquence | §10.2. |
| T-B2 | Grand cerveau déployé (régime) | continu | `souffle` 3 s | §10.3. |
| **T-B3** | **Retour au cerveau rapide** (repli) | **1,2 s** | séquence | §10.4. |
| **T-Z1** | **Zoom** vers un nœud | 1,4 s | `ressort` | trajectoire en arc autour du cœur (jamais à travers), le point visé reste centré, profondeur de champ qui se règle sur lui (PC). |
| T-Z2 | **Dézoom** vue d'ensemble | 1,8 s | `ressort` | recul + légère montée (élévation 18°), le reste de l'univers se rallume (`sortie`, 400 ms). |
| T-Z3 | Travelling (passation, « le point ») | 1,8 s | `glisse` | arc de 60° max. |
| T-Z4 | Retour automatique après geste | 1,5 s | `ressort` | vers la cible du directeur. |
| **T-P1** | **Apparition d'un panneau** | 0,42 s | `rebond` | échelle 0,92 → 1, opacité 0 → 1 (`sortie`), **balayage lumineux** de haut en bas (300 ms), le filament d'ancrage se trace depuis le nœud (250 ms) ; plusieurs panneaux : 90 ms d'écart. |
| T-P2 | **Disparition d'un panneau** | 0,28 s | `entree` | échelle 1 → 0,96, balayage inverse, filament qui se rétracte vers le nœud. |
| T-P3 | Panneau qui vole (enveloppe → « À envoyer ») | 1,1 s | `glisse` | arc, réduction 1 → 0,25, traînée ; le compteur **gonfle** 1 → 1,12 → 1 (`rebond`, 0,5 s). |
| T-P4 | Panneau latéral (PC) replié / déplié | 0,45 s | `glisse` | glissement + fondu du verre. |
| **T-L1** | **Allumage d'un lien** | 0,5 s | `sortie` | le filament se **trace** de la source vers la cible, puis sa lueur monte ; première impulsion au bout de 300 ms. |
| T-L2 | **Extinction d'un lien** | 0,9 s | `souffle` | la lumière **retombe** plus lentement qu'elle n'est montée ; les impulsions en vol finissent leur course. |
| T-L3 | Passage en transparence (contexte ↔ latent) | 0,6 s | `glisse` | opacité entre paliers (§7.1). |
| T-L4 | Rupture (attente levée) | 0,7 s | `entree` | le filament se scinde en deux fragments qui s'éteignent. |
| **T-K** | **Changement de client** | 1,8 s | séquence | 0-600 ms le lien de l'ancien client s'éteint (T-L2), ses sujets **se replient** (`entree`, 400 ms) · 200-1 600 caméra en arc vers le nouveau client (`ressort`) · 900-1 500 le nouveau lien se trace (T-L1) · 1 100-1 800 ses sujets se **déploient** (`sortie`, 40 ms d'écart) · fil d'Ariane qui se réécrit lettre à lettre (300 ms). |
| T-M | « Montrer » | 1,2 s + 0,5 s | `ressort` puis `sortie` | dézoom, puis mise en avant des éléments (50 ms d'écart) ; le reste passe en latent (T-L3). |
| **T-V1** | **Mise en veille** | 2,5 s | `souffle` | lumière 100 % → 30 %, bloom qui décroît, caméra qui recule de 15 %, rotation qui ralentit, HUD à 40 %. |
| **T-V2** | **Réveil** (mot « Jarvis » ou toucher) | 0,6 s | `sortie` | **onde de choc** sphérique depuis le cœur (600 ms), lumière 30 → 100 % (400 ms), anneaux qui se redressent face à la caméra, HUD qui se rallume (300 ms) ; les cercles du sol s'illuminent au passage de l'onde. |
| T-Q | Changement de niveau de qualité | 0,6 s | `glisse` | particules et effets ajoutés/retirés en fondu (jamais d'un coup). |
| T-R | Replay (début/fin) | 0,8 s | `glisse` | l'univers vire au **sépia holographique** (désaturé, cyan pâle) et un cadre « REPLAY » se trace ; retour inverse. |

---

## 8. L'univers et le graphe de liaisons

### 8.1 Les nœuds (disposition déterministe)

| Nœud | Forme | Source | Apparition |
|---|---|---|---|
| **Cœur rapide** / **grand cerveau** | sphère de particules / champ neuronal | H6, H8 | toujours (§10) |
| **Client = poste** | sphère à anneau + halo ; pastille « connecté » | `universe.clients` | toujours (≤ 12) |
| **Sujet** | lune | `client.subjects` | client courant/cité/touché (téléphone) ; tous sur demande (PC) |
| **Chantier** | losange + barre `done/total` + phase | `subject.chantiers` | sujet courant/cité ; PC : tous les chantiers ouverts du client focalisé |
| **Étape** | perles en **chapelet** du chantier (faites = pleines) ; l'étape courante nommée | `done/total`, `step`, plan du tour (S9) | chantier focalisé |
| **Personne** | carré | `client.people`, `waitingOn`, `toSend` | citée, en attente, destinataire ; PC : cartographie (SF-236-18) |
| **Ticket** | carte plate | clés vues dans la **session** (S4) | mémoire de session, s'estompe après 30 min sans mention |
| **Message à envoyer** | enveloppe | `client.toSend` + S1 | attaché à son destinataire |
| **Veille** | cadran | `GET /api/jarvis/watches` | attaché à son client |
| **Sous-agent** | petit cœur | S13 `satellites` | pendant la délégation |
| **Fichier** | point du nuage du dépôt | S5 (session) | près du sujet |

Clients sur **orbites inclinées** tirées d'un **hachage stable** de leur clé (le PO apprend sa carte) ; dérive très
lente (un tour / 10 min), arrêtée pendant l'écoute et sur un focus.

### 8.2 Liaisons lumineuses entre TOUS les composants

| Famille | Relie | Style | Allumée quand |
|---|---|---|---|
| L-poste | cœur ↔ client | filament cyan ; **vert** si connecté pendant « Montrer » ; **rouge grésillant** si poste muet | client cité / commande sur son poste (flux montant N6) |
| L-sujet | client ↔ sujet | filament fin | sujet courant, cité, terminal vivant (respiration) |
| L-chantier | sujet ↔ chantier | filament fin | chantier cité, étape qui avance |
| L-étape | chantier ↔ étapes (chapelet) | perles reliées | étape cochée (l'éclair court le long du chapelet) |
| L-attente | chantier ↔ personne | **orange pointillé** | attente posée ; **rupture** à la levée |
| L-interlocuteur | client ↔ personne | filament discret | personne citée |
| L-message | personne ↔ enveloppe ↔ compteur HUD | orange | message prêt (attend le PO) |
| L-ticket | client/sujet ↔ carte | cyan | ticket consulté / nommé |
| L-veille | client ↔ cadran | cyan ; **rouge** en alerte | veille posée / qui sonne |
| L-essaim | cœur ↔ sous-agent | cyan à traînée | délégation `explore`/`task` |
| **L-cerveaux** | cœur rapide ↔ grand cerveau | **violet** | délégation à Opus (§10) |
| L-passation | sujet ↔ sujet | arc lumineux | passation (S10) |
| L-fichier | sujet ↔ nuage | points | lecture / écriture |

**Cinq états de liaison** (paliers §7.1) : **absent** (non dessiné) · **latent** 4 % (la structure se devine) ·
**contexte** 18 % (voisins du focus) · **actif** 45 % (chemin du sujet courant) · **allumé** 100 % + impulsions (ce
dont on parle). **Règles** : le **chemin** du focus (cœur → client → sujet → chantier → étape → personne) est allumé
de bout en bout ; ses voisins en contexte ; le reste latent ; « Montrer » allume ce qui est demandé et met le reste en
latent ; veille : tout en latent ; liaisons éloignées plus transparentes (profondeur). Passage d'un état à l'autre :
T-L1 / T-L2 / T-L3.

---

## 9. Jarvis vivant : états, scènes, caméra

### 9.1 États de Jarvis

| État | En 3D |
|---|---|
| **Repos** | Cœur rapide qui respire (période 4 s), anneaux lents, impulsions rares vers les clients, caméra en vue d'ensemble, dérive imperceptible. |
| **Écoute** | **Ondes de la voix du PO captées par le cœur** : anneaux nés côté caméra qui **convergent** vers le cœur, amplitude = niveau du micro ; spectre circulaire **entrant** ; caméra **figée**. |
| **Réflexion** | Particules en accélération, nuage intérieur en tourbillon, impulsions qui partent vers les clients (« il cherche ») ; si Opus travaille : **grand cerveau** (§10). |
| **Parole** | Pulsation sur la voix, **ondes sortantes**, 64 rayons modulés ; le nom en train d'être dit (`said`) s'illumine en blanc et son lien s'allume. |
| **Coupure** | T-C6. |
| **« Oui » attendu** | T-C5 + S2. |
| **Veille / réveil** | T-V1 / T-V2. |
| **Jarvis global** (D19, aucun sujet) | Vue d'ensemble, aucun client mis en avant, « Jarvis — tous vos sujets ». |
| **Mode sobre** | Repli 2D sobre (inchangé). |

### 9.2 Catalogue des scènes F-234 en 3D (toutes)

| Scène | En 3D — caméra · liens · panneaux · particules |
|---|---|
| **Constellation** | Univers §8 ; nœud cité : halo ×1,8, anneau qui s'ouvre, **chemin allumé** jusqu'au cœur avec impulsions, nom en blanc ; ≤ 3 nœuds allumés (D2) ; zoom T-Z1. |
| **S-GEN** | Anneau d'impulsion depuis le cœur par appel ; libellé du registre en petit panneau ancré au cœur (« Radar · sujets du client × 3 ») ; entité allumée (sinon sujet courant). Couvre **S16, S18, S19** (vague D, D-PO-7) et **tout outil futur**. |
| **S20 Montrer** | T-M ; `connectes` vert, `deconnectes` éteints pointillés, `chantiers-bloques` losanges orange + personne attendue, `etapes-en-cours` chantiers déployés + étape en panneau, `a-envoyer` enveloppes en orbite du compteur, `clients` tout déployé ; le reste latent. |
| **S1 Enveloppe / bulle** | Zoom sur le client et la personne ; enveloppe holographique qui se forme, destinataire puis objet s'écrivent ; fin du bloc ⇒ T-P3 vers « À envoyer » (+1). Jamais le corps. |
| **S2 « Oui » / question** | Anneau orange + **arc qui se vide** au rythme de `remaining` ; coquille orange ; ondes orange lentes ; panneau orange au-dessus du cœur ; caméra recule de 20 % pour cadrer cœur + entité ; **reste devant tout**. |
| **S3 Commande** | Zoom sur le poste ; **flux de données qui remonte** du poste au cœur (N6) ; panneau mono en filigrane : commande + **2 dernières lignes masquées** (D11). |
| **S4 Tickets** | **Cartes holographiques en éventail** (≤ 6) sorties du client en arc (90 ms d'écart) ; la carte nommée dans la phrase dite s'illumine ; elles restent ensuite au **mur de tickets** (SF-236-18). |
| **S5 Fichiers** | Feuillets translucides qui s'empilent ; écriture = feuillet blanc vif qui s'écrit ; recherche = **loupe** qui balaie ; alimente le **nuage du dépôt** ; document lu = **pages qui défilent**. |
| **S5b Vidéo** | Pellicule en arc, vignette agrandie par plan extrait. |
| **S6 Web** | Barre d'adresse holographique au-dessus du cœur, **globe filaire** derrière, impulsions globe → cœur. |
| **S7 Création** | La page / le diagramme / le document **se construit par couches** sous son titre, les particules du cœur s'y déposent ; vidéo = salle de montage (`checks`) ; audio = onde. |
| **S9 Étape** | Zoom sur le chantier ; barre qui progresse (`stepsDone`) ; une perle du chapelet se **remplit** (éclair le long du chapelet), la phase s'écrit ; plan posé = le chapelet se déploie. |
| **S10 Passation** | Arc lumineux d'un sujet à l'autre, travelling T-Z3. |
| **S11 Attente** | Carré « personne · catégorie » relié au losange en **orange pointillé** ; levée = rupture T-L4 + lueur verte. |
| **S12 Mémoire / savoir** | « Tour 34 » remonte des profondeurs du cœur ; carte du savoir = constellation secondaire au-dessus du cœur ; règle proposée = ligne qui s'inscrit ; compaction = particules qui se resserrent puis se rouvrent. |
| **S13 Essaim** | **Essaim de sous-agents** : un petit cœur satellite par délégation, question en étiquette, traînée ; retour dans le cœur à la fin. |
| **S14 Points de route** | Couronne de perles sous le cœur, une par question, cyan → vert quand traitée. |
| **S15 Le point** | Vague sur tous les sujets du client (80 ms d'écart) + tour lent de 60°. |
| **S16 / S17** | S-GEN libellée / cadran de veille attaché au sujet (alimente l'horloge des veilles). |
| **A1 Poste muet** | Client **rouge** : halo lent, lien qui grésille, « poste muet ». |
| **A2 Terminaux vivants** | Lunes qui respirent, impulsions vers leur client. |
| **A3 Cloche** | Le sujet clignote une fois (orange) et lance une onde vers le cœur. |
| **A4 Coût** (ADMIN) | Compteur holographique en **€**, fin de tour, 5 s ; jamais pour un non-ADMIN ni en mode présentation. |
| **A5 La veille sonne** | Zoom sur le client, onde client → cœur, panneau « Veille Jira · CAGIP — 2 changements » ; rouge si alerte. |

### 9.3 Caméra (`jarvis-camera-rules.ts`, pur)

| Situation | Cible | Distance |
|---|---|---|
| Repos, aucun nœud cité | vue d'ensemble (sphère englobante ajustée au plus petit champ, portrait compris) | ajustée |
| Nœud cité (`lit`/`said`) ou entité de scène | le nœud | client 7 · sujet 5 · chantier 3,5 · étape/personne 2,5 (× 1,5 en portrait) |
| Grand cerveau déployé | recul de 15 % pour cadrer l'enveloppe | — |
| S2 ouvert | cœur + entité | recul 20 % |
| Changement de sujet, fin de « Montrer », 10 s sans citation | vue d'ensemble (T-Z2) | — |
| `ecoute` | **gel** (aucun déplacement ne démarre ; celui en cours finit en douceur) | — |

Règles : ressort critique, ≤ 1 déplacement / 2,5 s, aucun roulis, focale 55° fixe, trajectoires en arc autour du
cœur (jamais à travers).

### 9.4 Interaction

| Geste | Téléphone (portrait d'abord) | PC |
|---|---|---|
| Tourner l'univers | un doigt glissé (lacet libre, tangage ±35°) | glisser à la souris |
| Zoomer / dézoomer | **pincer** (distance 2,5-60) | molette |
| Vue d'ensemble | double toucher dans le vide | double clic, `Échap` |
| Toucher un **sujet** | `touchSubject` = « passe sur X » (inchangé) | clic ; `Tab` + `Entrée` |
| Toucher **tout autre nœud** | caméra + **fiche holographique** (inspecteur), aucune navigation | idem |
| Toucher le **cœur** | `talk()` | idem |
| Panneaux latéraux | — | `[` / `]`, clic sur l'onglet |
| Plein écran, cockpit | — | `F`, bouton « Cockpit » |
| Recherche « montre-moi » | — | `/` puis texte (SF-236-19) |

Sélection **en espace écran** (nœud le plus proche à ≤ 28 px ⇒ cible ≥ 44 px garantie). **Retour automatique** 8 s
après le dernier geste (T-Z4) ; pendant le contrôle manuel, les ordres du directeur attendent, **sauf S2**.
Accessibilité (D10 reconduit) : canvas `aria-hidden`, fil d'Ariane + `aria-live` limité, nœuds = boutons au clavier.

---

## 10. Les deux cerveaux (F-235) — la scène signature

### 10.1 Qui est qui

| | **Cerveau rapide** (Haiku 5.5, F-235) | **Cerveau principal** (Opus 5.5) |
|---|---|---|
| Forme | **Petit cœur** : sphère de particules rayon 1,4 ; PC 2 000 / tél. 900 particules ; connexions **éparses** (2 voisins) ; 3 anneaux | **Champ neuronal déployé** : rayon **3,2** (× 2,3) ; PC **12 000** / tél. 2 500 particules ; connexions **denses** (6 voisins : PC 8 000 / tél. 1 500 segments) ; **5 anneaux** en gyroscope ; enveloppe de Fresnel |
| Couleur, lumière | **cyan** froid, centre blanc bleuté, lumière vive et nette | **violet profond** (`--cg-holo-deep`) vers un centre **blanc chaud**, bloom +20 %, lueur du fond qui vire au violet, sol polaire teinté |
| Mouvement | rotation vive, respiration 4 s | rotation lente et majestueuse, respiration profonde 3 s, **éclairs synaptiques** continus |

**Le petit cœur ne disparaît jamais** : quand le grand cerveau se déploie, il devient le **noyau** lumineux au centre
du champ — c'est lui qui continue de converser avec le PO pendant qu'Opus travaille (F-235 : on peut parler au cerveau
rapide pendant une délégation ; ses ondes d'écoute et de parole restent visibles **au centre**).

**Quand se déploie le grand cerveau** (`jarvis-brains.ts`, pur) :
- cerveau rapide actif (`fastBrainOn`) : à chaque **délégation** (`delegations` passe de n à n+1), et lors d'un tour
  Opus direct (confirmation, « vérifie ») ;
- cerveau rapide éteint (coupe-circuit F-235, situation actuelle en prod) : **chaque tour est un tour Opus** ⇒ le
  grand cerveau se déploie au début du tour (`turnActive`) et se replie à `done` — la scène est donc **visible dès
  aujourd'hui**.

### 10.2 Le passage aller (T-B1, 1,6 s)

| Temps | Ce qui se passe |
|---|---|
| 0-200 ms — **inspiration** | Le petit cœur se **contracte** (échelle 1 → 0,85, `entree`), la lumière baisse de 20 %, les anneaux s'arrêtent net (anticipation). |
| 200-450 ms — **étincelle** | **Flash blanc** au centre (bloom 1,6), **onde de choc sphérique** violette qui traverse l'univers ; les cercles du sol s'allument à son passage ; la **comète** de la demande (libellé de la délégation) part du noyau et se met en orbite. |
| 450-1 300 ms — **éclosion** | Les particules **migrent** du rayon 1,4 vers 3,2 (`sortie`, retard aléatoire 0-250 ms par particule ⇒ effet d'éclosion), de nouvelles particules naissent en fondu ; les **connexions se multiplient** du centre vers l'extérieur (2 → 6 voisins, seuil animé) ; teinte **cyan → violet** (`glisse`, mélange perceptuel) ; les anneaux passent de 3 à 5 et se **déplient en gyroscope** ; le fond et le sol virent au violet ; l'univers s'écarte de 15 % (fait de la place), la caméra recule. |
| 1 300-1 600 ms — **stabilisation** | Léger rebond d'échelle 1,03 → 1 (`rebond`), bloom 1,6 → 1,3 ; panneau « **Cerveau principal** · <libellé de la délégation> » (T-P1) ; HUD état : « Réflexion profonde ». |

### 10.3 Déployé pendant le travail

- Respiration profonde (3 s), rotation lente, **éclairs synaptiques** permanents ; chaque action du tour (incrément de
  `pulses`) déclenche une **salve d'éclairs** qui sort du champ et court le long du lien vers l'entité concernée
  (poste, chantier, ticket…) : on **voit** le grand cerveau agir sur l'univers.
- Les scènes du tour (S3, S4, S9…) se jouent **autour** du champ ; les liens qui partent du grand cerveau sont plus
  épais et violets à la source, dégradés vers la couleur de la cible.
- **Une perle par délégation vivante** orbite l'enveloppe avec son libellé ; plusieurs délégations parallèles
  (F-235) = plusieurs perles ; le grand cerveau reste déployé **jusqu'à la dernière**.
- Pendant ce temps le **noyau** (cerveau rapide) réagit à la voix du PO (écoute, parole) au centre.

### 10.4 Le retour (T-B3, 1,2 s)

| Temps | Ce qui se passe |
|---|---|
| 0-300 ms — **rassemblement** | Tous les éclairs **convergent vers le centre** ; les connexions s'allument dans le sens extérieur → intérieur. |
| 300-900 ms — **repli** | Les particules regagnent le rayon 1,4 (`glisse`, retard aléatoire), les particules surnuméraires s'éteignent, les connexions disparaissent de l'extérieur vers l'intérieur, violet → cyan, 5 → 3 anneaux, l'univers revient à sa place, la caméra aussi. |
| 900-1 200 ms — **dépôt** | La **perle de la délégation entre dans le noyau** : flash doux cyan, **onde vers l'avant** (vers le PO) = « la réponse est prête ». Si la réponse attend la pause (file d'annonces FIFO F-235), une petite perle cyan **orbite le noyau** jusqu'à ce qu'elle soit dite. |

**Variantes** : délégation annulée (« annule ») ou tour interrompu ⇒ repli en 0,8 s, sans flash, perle qui s'éteint en
gris ; échec ⇒ perle rouge brève puis repli ; réduit sur téléphone (§12) : mêmes phases, 2 500 particules, pas de
gyroscope supplémentaire, pas d'onde de choc sur le sol.

---

## 11. « Plus de choses » : propositions intégrées aux scènes (déduites des signaux existants)

| # | Proposition | Déclencheur | En 3D | Itération |
|---|---|---|---|---|
| N1 | **Entrée cinématique** | ouverture | T-E1 / T-E2 ; « Revoir l'entrée » dans Réglages | 1 |
| N2 | **Réveil par « Jarvis »** | `onWake` | T-V2 | 1 |
| N3 | **Mise en veille** | `conversation.mode` `idle`/`paused` 60 s, « Jarvis, pause » | T-V1 | 1 |
| N4 | **Ondes de la voix du PO captées par le cœur** | `ecoute` + niveau | §9.1 | 1 |
| N5 | **La voix de Jarvis visible** | `parole` + niveau | §9.1 | 1 |
| N6 | **Flux de données qui remonte d'un poste** | S3 `output` | particules poste → cœur, débit ∝ sortie | 2 |
| N7 | **Les deux cerveaux** | H8 | §10 | 1 |
| N8 | **Essaim de sous-agents** | S13 | §9.2 | 2 |
| N9 | **Carte du dépôt en nuage** | S5, session | nuage de points par dossier, lecture cyan / écriture blanc | 2 |
| N10 | **Horloge des veilles** | `/api/jarvis/watches` | cadran sous l'univers, prochaine veille en surbrillance | 4 |
| N11 | **Pages qui défilent** | S5 document, S7 `page_lire` | §9.2 | 2 |
| N12 | **Coût en € (ADMIN)** | `ambient.cost` | A4 | 2 |
| N13 | **Alerte rouge unifiée** | A1, porte du runner, veille en alerte | halo rouge lent + lien qui grésille + mot | 2 |
| N14 | **Chronologie du tour** | `pulses`, `scene` | arc de perles par action, couleur par famille | 3 |

---

## 12. PC « cockpit » et téléphone allégé

### 12.1 PC plein écran : occuper toute la place

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ ← J·A·R·V·I·S   Sujet : Migration Forge ▾   [ RÉFLEXION PROFONDE · cerveau principal ]  ⚙ ⛶ 🖥 │
├────────────────┬───────────────────────────────────────────────────────────┬─────────────┤
│ AUJOURD'HUI    │                                                           │ TOUR EN     │
│ postes 3/5 ●●● │                   UNIVERS 3D (calques L0-L4)              │ COURS       │
│ chantiers 7    │     cœur / grand cerveau, graphe complet, panneaux        │ actions ●●○ │
│ attentes 4 ◆   │     ancrés, essaim, nuage du dépôt, mur de tickets        │ délégations │
│ à envoyer 2 ✉  │                                                           │ points de   │
│────────────────│                                                           │ route       │
│ MÉTÉO DES      │                                                           │─────────────│
│ CHANTIERS      │                                                           │ À ENVOYER   │
│ ☀ ⛅ ⛈ …       │                                                           │ (étagère)   │
│────────────────│                                                           │─────────────│
│ PERSONNES ET   │                                                           │ INSPECTEUR  │
│ ATTENTES       │                                                           │ (nœud       │
│                │                                                           │  touché)    │
├────────────────┴───────────────────────────────────────────────────────────┴─────────────┤
│ CHRONOLOGIE DE LA JOURNÉE  08:10 ●──●───●──────●──●  14:32 (maintenant)   ⏱ veilles  ◎ alertes │
│ JARVIS  « Deux tickets bloqués, Monsieur. EDR-1423 attend l'équipe Paiement… »   [⏸ Pause] │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

- Panneaux latéraux **en verre holographique** semi-transparents : l'univers 3D passe **derrière** (pleine largeur),
  ils sont **repliables** (T-P4) et redimensionnables ; leurs éléments sont **reliés par des filaments** à leurs
  nœuds 3D au survol (une ligne lumineuse traverse de la liste vers l'univers).
- **Tableaux flottants** (attentes, à envoyer, tickets) détachables du panneau latéral et posés dans l'espace 3D
  (calque L4), ancrés à un client.
- Toutes les capacités §11, §13 actives, effets complets (§6.1 PC).

### 12.2 Téléphone : version allégée — ce qui reste, ce qui disparaît

| Élément | Téléphone | Remplacement |
|---|---|---|
| Cœur rapide + états (écoute, réflexion, parole, coupure, « oui », veille, réveil) | **reste** (particules réduites) | — |
| Les deux cerveaux | **reste**, allégé (§10.4) | — |
| Univers : clients | **reste** | — |
| Sujets / chantiers / étapes | **client courant ou cité seulement** (D7) | — |
| Personnes, tickets, messages, veilles en 3D | **cités seulement** | — |
| Liaisons | **chemin du focus seulement** ; le reste absent (pas latent) | — |
| Scènes F-234 | **toutes**, un seul panneau ancré (2 pour S4) | — |
| S2 « oui », « Montrer », À envoyer | **restent intégralement** | — |
| Sous-titres, fil d'Ariane, compteur « À envoyer », interrupteur Pause | **restent** | — |
| Entrée cinématique | **1,2 s** (au lieu de 2,6 s) | — |
| Panneaux latéraux, tableau de la journée, météo, personnes, chronologies, inspecteur | **disparaissent de la scène** | **volets plein écran** depuis le bouton « ☰ Vues » (un à la fois) |
| Tableaux flottants, mur de tickets, nuage du dépôt, horloge des veilles en 3D | **disparaissent** | volets |
| Nébuleuse, poussière, profondeur de champ, verre flouté, aberration, SMAA | **disparaissent** | fond + étoiles simples |
| Sol polaire | simplifié (grille seule, sans reflet) | — |
| Essaim | ≤ 2 sous-agents visibles (+ « +n ») | — |
| Dérive automatique de la caméra | **coupée** ; la caméra ne bouge qu'au changement de sujet et sur un focus | — |
| Replay, mode présentation | **restent** (replay en vue simplifiée) | — |
| Vue cockpit | **sans objet** | — |

---

## 13. Nouvelles fonctionnalités (au-delà de l'existant), classées par itération

Toutes : **zéro coût IA**, aucune consigne touchée ; sources existantes sauf mention.

| # | Fonctionnalité | Intérêt pour le PO | Source | Itér. |
|---|---|---|---|---|
| **NF-1** | **Tableau de bord holographique de la journée** (« Aujourd'hui ») | Savoir **en 3 s** où l'on en est : postes connectés, chantiers en cours, attentes, à envoyer, alertes, veilles du jour ; toucher un chiffre = « Montrer » local. | `universe`, `ambient`, `watches` | 3 |
| **NF-2** | **Météo des chantiers** | Repérer d'un coup d'œil les chantiers en souffrance : ☀ avance (vivant / étape récente), ⛅ attend quelqu'un, ⛈ bloqué / échec, 🌫 sans mouvement ; en 3D, un petit **ciel** au-dessus de chaque losange. | `chantiers` (phase, `live`, `waitingOn`, `done/total`) | 3 |
| **NF-3** | **Chronologie des tours de la journée** | Retrouver ce qui s'est fait et quand ; toucher un tour = le **rejouer** (NF-7). | historique du fil (H14), hors tour | 3 |
| **NF-4** | **Chronologie du tour en cours** (N14) | Voir l'agent travailler action par action. | `pulses`, `scene` | 3 |
| **NF-5** | **Cartographie des personnes et de leurs attentes** | Qui attend quoi de moi, de qui j'attends quoi, à qui j'ai un message prêt : un **graphe de personnes** autour des clients, liens orange (attentes) et enveloppes. | `people`, `waitingOn`, `toSend` | 3 |
| **NF-6** | **Mur de tickets** | Les tickets vus dans la session restent épinglés au client (état lu dans le texte quand il existe) : plus besoin de redemander. | S4, session | 3 |
| **NF-7** | **Inspecteur de nœud** | Toucher n'importe quel nœud = fiche complète (client : poste, mission, sujets ; chantier : phase, étapes, attentes ; personne : attentes, messages). | `universe` | 3 |
| **NF-8** | **Recherche « montre-moi » au clavier** (PC) | Taper « edenred bloqué » ⇒ la caméra et les filtres « Montrer » s'appliquent localement, sans tour. | `universe`, `jarvis-show.ts` | 3 |
| **NF-9** | **Replay visuel d'un tour** | Revoir en 20 s ce que l'agent a fait (caméra, scènes, deux cerveaux) à vitesse ×4, en pause, image par image. | historique du fil (`terminal`, `brain`) rejoué dans un **lecteur privé** (pas le bus vivant) | 4 |
| **NF-10** | **« Pendant votre absence » en film** | À l'ouverture, un bouton « Voir ce qui s'est passé » enchaîne les replays des tours faits depuis la dernière visite. | idem | 4 |
| **NF-11** | **Mode présentation** | Montrer Jarvis à un client ou un manager **sans fuite** : noms de personnes masqués (initiales), coûts, sorties de commande et détails retirés, tour automatique lent de l'univers, plein écran. | — | 4 |
| **NF-12** | **Vue cockpit sur écran externe** | Jarvis en permanence sur un second écran pendant qu'on travaille au terminal : univers, deux cerveaux, chronologie, alertes. | `BroadcastChannel` + Window Management API | 4 |
| **NF-13** | **Mode veilleur de nuit** | Écran posé (cockpit, tablette) : luminosité basse, horloge, veilles et alertes seulement, réveil à l'alerte. | `watches`, `ambient` | 4 |
| **NF-14** | **Horloge des veilles** (N10) | Voir quand chaque veille passe (lun → ven), la prochaine en surbrillance. | `watches` | 4 |
| **NF-15** | **Radar des alertes** | Un anneau radar autour de l'univers ; chaque alerte (veille, poste muet, cloche) est un écho qui pulse à son client. | `ambient`, `watches` | 4 |
| **NF-16** | **Carte d'activité 7 jours** | Où est passé mon temps cette semaine : chaque client s'entoure d'un anneau à 7 segments dont l'éclat = nombre de tours du jour. | **agrégat nouveau en lecture seule** `GET /api/jarvis/activity?days=7` (compte de tours par sujet et par jour, filtre `user_id`, aucun contenu) | 4 |

**Écartées** : effets sonores (D-PO-5) ; images générées par IA (coût) ; WebXR / réalité augmentée ; avatar ou
imitation du film (F-235 D18).

---

## 14. Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés et vérification |
|---|---|---|
| **Navigation / routing** | **Oui** | Aucune route nouvelle : `/jarvis` (`authGuard`) avec paramètres **nouveaux** `?cockpit=1`, `?presentation=1`, `?replay=<messageId>`, et `?banc=` (hors production). Non-régression : `?sujet=`, `?alerte=` (SF-231-05), `?source=compagnon` (SF-232-02), retour `/atelier`, en 3D **et** en 2D. Toucher sujet ⇒ `touchSubject` ; aucun autre toucher ne navigue. |
| **Contexte tenant** | **Oui (SF-236-26)** | Agrégat `GET /api/jarvis/activity` : `currentUser.requireId()`, test d'isolation A/B. Lectures existantes (`universe`, `watches`, historique du fil via `atelierAccess.requireTerminalAccess`) inchangées. Le moteur ne fait **aucun** appel pendant un tour (test). |
| **Auth / Principal** | Non | A4 réutilise la règle ADMIN existante. |
| **Plans / limites** | Non | Aucun appel IA, aucun quota. |
| **Préfixe de cache / consigne** | Non (garde explicite) | Aucun fichier de consigne touché ; test byte-stable du préfixe (F-134) rejoué en fin d'itérations 1 et 4. |
| **Mesure** | **Oui** | Migration additive `236-jarvis-usage-render.xml` ; `UsageEvent` `RENDER` ; `GET /api/admin/jarvis/measure` étendu. |
| **Gouvernance** | **Oui** | `review-checklist.md` (rendu 3D ou repli S-GEN 3D pour toute scène) ; `DESIGN_SYSTEM.md` §23. |

---

## 15. Itérations et découpage — SF-236-01 → SF-236-27 (≤ 2 j chacune, ≈ 48,5 j)

Chaque SF : coût IA **nul** ; **aucune** consigne système, description d'outil ou consigne orale modifiée ;
**captures headless de référence** (Chrome headless `--use-angle=swiftshader --enable-unsafe-swiftshader`,
390 × 844 et 1920 × 1080, `?banc=<scène>&t=<ms>`, script `frontend/scripts/jarvis-3d-captures.sh`, PNG dans
`docs/features/F-236/captures/`) avec critère automatique « non vide (≥ 2 % de pixels hors fond), aucune erreur
console » ; transitions capturées en **séquences** (6 images réparties sur la durée) ; mesure de performance si le
moteur est touché.

**Visa du PO à chaque itération** : la SF de polissage produit une **planche de référence**
(`docs/features/F-236/captures/iteration-N/index.html` : captures téléphone + PC, séquences des transitions, mesures
i/s), publiée pour le PO. **C'est sa seule sollicitation** (goût visuel). Ses remarques sont traitées dans la même SF
de polissage ; l'itération suivante démarre après son visa.

### Itération 1 — « Jarvis s'allume » (socle visible, déployable seul) — 13,5 j

| SF | Titre | j | Contenu | Critères d'acceptation (vérifiables) |
|---|---|---|---|---|
| **01** | Socle du moteur, repli 2D, banc, palette | 2 | `three@0.186.1`, chunk paresseux, hors zone, boucle arrêtée onglet caché ; capacité (T10), perte de contexte (T11), « Vue 3D / 2D » ; fondu T-F ; `dispose()` ; banc `?banc=` ; script de captures ; garde d'accident du chunk ; **DESIGN_SYSTEM §23** + `jarvis-motion.ts` (jetons de couleur, courbes). | Table de vérité de capacité testée (chaque cause ⇒ 2D identique à aujourd'hui) ; chunk 3D **jamais demandé** en repli ; bundle initial +0 ; onglet caché ⇒ 0 image ; 10 ouvertures sans fuite ; `?banc=` inopérant en production ; capture non vide. |
| **02** | Le cœur et ses états, qualité adaptative | 2 | Cœur en `ShaderMaterial` ; bloom sélectif ; états repos/écoute (N4)/réflexion/parole (N5)/coupure/« oui » ; veille (N3) et réveil (N2) ; T-C1→C6, T-V1/V2 ; `jarvis-quality.ts`. | Chaque état a un rendu distinct (captures + séquences) ; ondes ∝ niveau (test) ; qualité : descente 2 s > 20 ms, remontée 10 s < 13 ms, ≤ 1 changement / 5 s, repli 2D Bas < 30 i/s 5 s (tests à horloge simulée) ; 0 allocation par image ; ≥ 50 i/s p50 sur le téléphone du PO. |
| **03** | **Les deux cerveaux** | 2 | `jarvis-brains.ts` ; champ neuronal déployé ; T-B1 aller, régime déployé, T-B3 retour, variantes (annulé, échec) ; perles de délégation ; noyau qui reste à l'écoute ; règle « coupe-circuit éteint ⇒ chaque tour Opus ». | Machine d'état testée (délégation n → n+1 ⇒ déploiement ; dernière finie ⇒ repli ; annulation ⇒ repli 0,8 s sans flash ; `fastBrainOn=false` ⇒ déploiement à `turnActive`) ; phases aux temps prévus (séquence de 6 captures aller + 6 retour) ; aucun saut de propriété (test du pas maximal) ; ≥ 50 i/s p50 téléphone pendant l'aller. |
| **04** | L'univers et le graphe de liaisons | 2 | `jarvis-layout-3d.ts`, `jarvis-link-graph.ts` ; clients, sujets, chantiers, étapes (chapelet), personnes ; familles de liaisons §8.2 (celles dont la donnée est dans l'univers) ; 5 états de liaison ; impulsions ; étiquettes CSS2D ; D7 / D19 ; relecture interpolée. | Mêmes entrées ⇒ mêmes positions ; chemin du focus allumé de bout en bout, voisins en contexte, reste latent (tests sur le plan) ; ≤ 3 nœuds allumés ; univers vide ⇒ cœur seul ; bornes d'appels de dessin et d'étiquettes (banc) ; captures portrait/paysage ; i/s cibles tenues. |
| **05** | Caméra, gestes, transitions d'écran | 2 | `jarvis-camera-rules.ts` ; T-Z1→Z4, T-K, T-E1/E2, T-S ; gestes et retour automatique ; sélection en espace écran ; touchers (sujet ⇒ `touchSubject`, autres ⇒ regarder). | Aucun déplacement ne démarre en `ecoute` ; ≤ 1 / 2,5 s ; aucun roulis ; changement de sujet ⇒ dézoom ; toucher client **ne navigue pas** ; cible ≥ 44 px à toute profondeur ; entrée sautée au premier toucher ; séquences T-E1, T-K, T-Z1/Z2. |
| **06** | HUD de base et mesure du rendu | 2 | HUD plein écran (barre, état, sous-titres, compteur, fil d'Ariane, volet « Réglages », plein écran PC) ; migration `236-jarvis-usage-render.xml` ; `UsageEvent RENDER` ; mesure admin étendue. | Toutes les commandes existantes accessibles (tests `jarvis.component.spec.ts` verts) ; `?sujet=`/`?alerte=`/`?source=compagnon` non régressés ; compteurs sans contenu, isolation `user_id` (test A/B), 403 non-ADMIN ; préfixe byte-stable. |
| **07** | **Polissage visuel 1** + planche | 1,5 | Réglage fin lumière, courbes, densités, cadrages ; planche itération 1 ; corrections du PO. | Planche publiée ; **visa PO** ; règles §7.3 vérifiées une à une sur les séquences. |

### Itération 2 — « Jarvis montre ce qu'il fait » (scènes, par usage mesuré) — 12 j

| SF | Titre | j | Contenu | Critères d'acceptation |
|---|---|---|---|---|
| **08** | Panneaux holographiques et S-GEN | 1,5 | Verre holographique (§7.2), pool, placement par quadrant libre (jamais sur le cœur ni les sous-titres), T-P1/P2 ; S-GEN 3D ; `SCENES_3D` + **garde** registre ; ligne `review-checklist.md`. | Garde : chaque `scene.id` du registre a un rendu 3D ou retombe sur S-GEN (test) ; ≤ 8 / 2 panneaux ; séquences T-P1/T-P2. |
| **09** | Commandes, tickets, fichiers (familles 1-2 : ≈ 4 800 appels / 30 j) | 2 | S3 + N6, S4 (+ mémoire de session des tickets), S5 + N9 + N11. | S3 : 2 lignes, 80 c., secrets masqués ; S4 : ≤ 6 cartes, clés exactes ; nuage limité à la session, **aucun appel réseau** ; captures. |
| **10** | Chantiers, étapes, attentes, « oui » (familles 3-5) | 2 | S9 (chapelet), S11 (L-attente, T-L4), S2, S14, S15. | S2 devant tout tant que `pinned` ; compte à rebours = `remaining` ; barre = `done + stepsDone` / `total` ; attente posée/levée ⇒ lien attaché/rompu ; captures. |
| **11** | À envoyer et Montrer (famille 6 + S20) | 1,5 | S1 + T-P3, L-message ; S20 pour les 6 filtres + T-M. | `toSendAdded` +1 ⇒ compteur +1 à l'arrivée ; jamais de corps ; chaque filtre = exactement `resolveShow` ; filtre inconnu ⇒ rien ; captures ×6. |
| **12** | Web, création, vidéo, passation, mémoire, essaim | 2 | S6, S7, S5b, S10 (T-Z3), S12, S13 + N8 (L-essaim), S16, S17. | Essaim ≤ 4 (tél. ≤ 2 + « +n ») ; un seul travelling par passation ; captures de chaque scène. |
| **13** | Ambiance et alertes | 1,5 | A1-A5, A4/N12 coût ADMIN, N13 alerte rouge unifiée, L-veille. | Ambiance au repos seulement sauf A5 ; A4 jamais non-ADMIN ; **rouge** utilisé uniquement par les alertes (test sur le plan) ; captures. |
| **14** | **Polissage visuel 2** + planche | 1,5 | Cohérence des scènes entre elles, rythme, densité téléphone. | Planche ; **visa PO**. |

### Itération 3 — « Le cockpit PC » (calques, panneaux, vues) — 12,5 j

| SF | Titre | j | Contenu | Critères d'acceptation |
|---|---|---|---|---|
| **15** | Mise en page PC multi-calques | 2 | Calques L0-L5 (§5) : nébuleuse, poussière, profondeur de champ, SMAA, passe de finition ; panneaux latéraux en verre, repliables (T-P4), filaments liste ↔ nœud au survol. | ≥ 60 i/s p50 sur le PC du PO en 1920 × 1080 (et rapport 2560 × 1440) ; panneaux repliés ⇒ univers pleine largeur ; captures PC. |
| **16** | Tableau de bord de la journée + météo des chantiers | 2 | NF-1, NF-2 (ciel au-dessus des losanges). | Chiffres = univers (tests sur la fixture) ; toucher un chiffre ⇒ « Montrer » local, **sans tour** ; règles de météo testées (table de vérité). |
| **17** | Chronologies | 2 | NF-4 chronologie du tour (N14) ; NF-3 chronologie de la journée (historique lu **hors tour**, à la demande). | Aucun appel pendant un tour (test) ; perles colorées par famille = registre ; toucher un tour ⇒ prépare le replay (itération 4) ou affiche son résumé. |
| **18** | Personnes et attentes + mur de tickets + étagère « À envoyer » | 2 | NF-5, NF-6, tableaux flottants détachables (L4). | Graphe des personnes = `people` + `waitingOn` + `toSend` (tests) ; tickets limités à la session ; captures. |
| **19** | Inspecteur de nœud + recherche « montre-moi » | 1,5 | NF-7, NF-8. | Fiche fidèle à l'univers pour chaque type de nœud ; recherche = filtres `jarvis-show.ts` + nom, sans tour ; clavier complet. |
| **20** | Téléphone allégé | 1,5 | Application de la matrice §12.2, bouton « ☰ Vues » et volets plein écran. | Chaque ligne de la matrice vérifiée (tests de configuration par profil) ; ≥ 50 i/s p50 et ≤ 4 ms CPU p90 sur le téléphone du PO avec toutes les scènes. |
| **21** | **Polissage visuel 3** + planche | 1,5 | Équilibre PC (densité, hiérarchie, lisibilité des panneaux). | Planche ; **visa PO**. |

### Itération 4 — « Au-delà » (nouvelles fonctionnalités, raffinements, mesure) — 10,5 j

| SF | Titre | j | Contenu | Critères d'acceptation |
|---|---|---|---|---|
| **22** | Replay visuel + « Pendant votre absence » | 2 | NF-9, NF-10 : historique rejoué dans un **lecteur privé** (instance séparée de `JarvisSceneReader`), T-R, ×1/×4, pause. | Le bus vivant n'est **jamais** alimenté par le replay (test) ; même tour ⇒ même suite de scènes (test déterministe) ; isolation `user_id` héritée de l'historique. |
| **23** | Mode présentation | 1,5 | NF-11 (`?presentation=1`). | Aucun nom de personne, coût, sortie ni détail rendu (test sur le plan et le DOM) ; tour automatique ; sortie par `Échap`. |
| **24** | Vue cockpit + veilleur de nuit | 2 | NF-12 (`?cockpit=1`, `BroadcastChannel`, Window Management API), NF-13. | Le cockpit suit l'écran principal en < 100 ms ; aucun réseau ajouté ; fermeture de l'un sans erreur pour l'autre ; veilleur : luminosité basse, réveil à l'alerte. |
| **25** | Horloge des veilles + radar des alertes | 1,5 | NF-14, NF-15. | `watches` illisible ⇒ rien, aucune erreur ; échos = alertes réelles (tests). |
| **26** | Carte d'activité 7 jours | 2 | NF-16 : agrégat `GET /api/jarvis/activity?days=7` (lecture seule, compte de tours par sujet/jour, aucun contenu) + anneaux. | Test d'isolation A/B ; bornes (≤ 12 clients × 40 sujets × 7 jours) ; aucun contenu dans la réponse ; préfixe byte-stable. |
| **27** | **Polissage final** + mesure J+7 + planche | 1,5 | Lecture de la mesure (§18), ajustements fondés sur l'usage, rapport de performance final, planche finale. | Planche ; **visa PO** ; rapport i/s et CPU téléphone + PC ; décisions de suite tracées. |

**Ordre** : itérations dans l'ordre ; à l'intérieur, l'ordre des SF ci-dessus (socle → scènes par usage mesuré F-234 §2
→ PC → nouveautés). Chaque itération est **déployable seule** (l'itération 1 donne déjà un Jarvis 3D complet avec les
deux cerveaux ; les scènes non encore livrées passent par S-GEN 3D).

### Plan de test commun

| Niveau | Contenu |
|---|---|
| Unitaires (Karma, sans WebGL) | Directeur, disposition, graphe de liaisons, caméra, cerveaux, capacité, qualité, sélection, `SCENES_3D` + garde, chaque scène sur la fixture, règle « aucun saut » (pas maximal par propriété). |
| Composant | `JarvisComponent` en 2D et avec un moteur 3D **simulé** (même contrat `StagePlan`) : touchers, réglages, paramètres d'URL, aucun appel réseau pendant un tour. |
| Intégration backend | Migration et `UsageEvent RENDER` (SF-06) ; agrégat d'activité (SF-26) ; isolation `user_id`. |
| Visuel | Captures et séquences headless par scène, état et transition, téléphone + PC ; planches par itération. |
| Performance | Banc `?banc=perf` 60 s sur le téléphone et le PC du PO. |

---

## 16. Décisions prises par défaut (tranchées, modifiables par le PO)

| # | Décision |
|---|---|
| D1 | Refonte du **rendu** + **vues** nouvelles ; cerveau des scènes F-234 et états F-233/F-235 **réutilisés tels quels** ; aucune consigne ni outil modifié. |
| D2 | Directeur **pur** (plan déclaratif) + moteur Three.js qui dessine et interpole ; 2D F-234 en **repli**. |
| D3 | `three@0.186.1` npm, sans surcouche, hors zone, chunk paresseux ; **le poids n'est pas un frein** (garde d'accident seulement). |
| D4 | Bloom sélectif + profondeur de champ + finition (PC) ; étiquettes **CSS2D** ; panneaux **DOM** en verre holographique face à l'écran ; particules et impulsions en tampons préalloués. |
| D5 | Budgets : téléphone ≥ 50 i/s p50 et ≤ 4 ms CPU p90 ; PC ≥ 60 i/s p50 ; DPR 2 / 1,5 / 1 ; qualité adaptative à 3 niveaux ; arrêt onglet caché ; repli 2D automatique. |
| D6 | DESIGN_SYSTEM **§23** ; grammaire cyan / violet / vert / orange / rouge / gris ; aucune police nouvelle. |
| D7 | Direction artistique §7 : 5 paliers de transparence, 6 courbes nommées, catalogue de transitions, 10 règles de qualité. |
| D8 | **Les deux cerveaux** §10 ; le petit cœur reste le noyau ; coupe-circuit éteint ⇒ le grand cerveau se déploie à chaque tour Opus. |
| D9 | **D-PO-1 étendu sans navigation** : tout nœud touchable pour regarder ; seul le sujet se choisit. |
| D10 | PC cockpit (§12.1) ; téléphone allégé selon la matrice §12.2. |
| D11 | 4 itérations, 27 SF, visa PO par planche à la fin de chaque itération, une SF de polissage par itération. |

---

## 17. Questions de goût visuel pour le PO (défaut appliqué sans réponse)

> **Réponses du PO (2026-11-02)** : **V1 = orange de la marque** (l'alternative : teinte dominante orange, **cyan
> pour l'attente**) ; **V2 = Space Grotesk** (défaut) ; **V3 = riche** (défaut). Appliquées par SF-236-01 dans
> DESIGN_SYSTEM §23 et `jarvis-motion.ts` : partout dans ce cadrage, lire « orange » pour « cyan » quand il s'agit
> de l'état **normal**, et « cyan » pour « orange » quand il s'agit de **l'attente du PO**.

| # | Question | Défaut | Alternative |
|---|---|---|---|
| **V1** | Teinte dominante | **Cyan holographique** (maquette), violet pour le grand cerveau, orange réservé à l'attente | Tout en orange de la marque (cyan pour l'attente) |
| **V2** | Police du HUD | **Space Grotesk** capitales espacées (charte) | Chakra Petch (maquette, plus « techno », +1 police hors charte) |
| **V3** | Densité du décor | **Riche** : nébuleuse, poussière, étoiles, sol polaire (PC) | Fond nu, univers seul |

---

## 18. Mesure d'intérêt (J+7, J+14 après chaque itération)

**Compteurs par jour** (migration additive `236-jarvis-usage-render.xml` sur `jarvis_usage_days`, aucun contenu) :
sessions en 3D / en 2D par raison (`no_webgl`, `reduced_motion`, `save_data`, `sober`, `manual`, `low_perf`,
`context_lost`) ; secondes par niveau ; i/s p50 (histogramme 6 classes) ; gestes ; touchers par type de nœud ;
passages de cerveau ; entrées sautées ; ouvertures de vues (journée, météo, personnes, chronologies, inspecteur) ;
replays ; présentation ; cockpit ; bascules 3D → 2D. Canal `UsageEvent` existant (événement `RENDER`). Lecture :
`GET /api/admin/jarvis/measure`.

| Signal | Lecture |
|---|---|
| Sessions 3D ≥ 80 % sur les appareils du PO | Le rendu tient. |
| `low_perf` + `context_lost` < 10 % | Budget tenu en réel ; sinon revoir le niveau Bas avant tout enrichissement. |
| i/s p50 ≥ 50 téléphone / ≥ 60 PC | Cibles tenues. |
| Bascule manuelle en 2D, mode sobre durable | **Signal négatif** (surcharge, gêne, mal des transports) ⇒ revoir densité et caméra. |
| Temps passé sur `/jarvis`, touchers, vues ouvertes vs la semaine F-234 (D16) | Intérêt réel du « plus de choses ». |
| Vues jamais ouvertes à J+14 | Ne plus les enrichir (« proposer depuis l'usage observé »). |
| Entrée sautée > 80 % | Raccourcir l'entrée. |

---

## 19. Hors périmètre

- Tout changement du **cerveau** : lecteur, registre (au-delà de la garde de rendu), rapprochement de noms, univers,
  consigne orale, `🖥 montrer`, cerveaux F-235, voix F-233.
- **Vague D** F-234 (Radar, Teams, Vigie, réunions) : S-GEN 3D en attendant (D-PO-7).
- Effets sonores (D-PO-5), WebXR / réalité augmentée, avatar, imitation du film.
- Rendu 3D dans le compagnon Android natif (F-232) : il ouvre `/jarvis` comme aujourd'hui.
- WebGPU (étape ultérieure possible, Three.js le permettra sans réécriture).
- Toute écriture côté passerelle (seules la mesure SF-06 et l'agrégat en lecture seule SF-26 touchent le backend).

## 20. Risques

| Risque | Parade |
|---|---|
| **Performance** téléphone | Budgets mesurés par SF, qualité adaptative, repli 2D automatique, 0 allocation par image, matrice d'allègement §12.2. |
| **Batterie** | Arrêt onglet caché, 30 i/s au repos, 20 i/s et bloom coupé en veille (téléphone) ; secondes par niveau mesurées. |
| **Lisibilité** (bloom, petit écran, surcharge du PC) | Texte jamais flouté, contrastes ≥ 5,8:1, bornes d'étiquettes/panneaux, 5 paliers de transparence, un seul « héros » (§7.3), fil d'Ariane conservé. |
| **Mal des transports** | Ressort sans dépassement, ≤ 1 déplacement / 2,5 s, aucun roulis, focale fixe, gel pendant l'écoute, trajectoires en arc ; `prefers-reduced-motion` ⇒ 2D ; « Vue 2D » à un toucher. |
| **Surcharge visuelle** (P1 « le maximum ») | Visa PO par itération, mesure des vues ouvertes, mode sobre et « Vue 2D » toujours disponibles. |
| **Projet long** (≈ 48,5 j) | 4 itérations **déployables seules** ; l'itération 1 suffit à un Jarvis 3D complet. |
| **Dette de deux rendus** | Directeur pur partagé, garde `SCENES_3D` ; le 2D est gelé (corrections seulement). |
| **Captures non déterministes** (rendu logiciel) | Horloge et PRNG injectés ; critère automatique « non vide + sans erreur » ; jugement visuel par le PO sur planche. |
| **Rendu logiciel** sur PC d'entreprise | Repli 2D d'office (T10), compté `no_webgl`. |
