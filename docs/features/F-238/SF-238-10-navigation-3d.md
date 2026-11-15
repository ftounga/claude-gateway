# Mini-spec — [F-238 / SF-238-10] Navigation et visionneuse 3D

## Identifiant
SF-238-10

## Feature parente
F-238 — Architecture en 3D, itération 1 « L'architecture en 3D » (cadrage §7, §8.2, §8.3, §10.1 T-A3→A6/A9, §12, §17)

## Statut
done

## Date de création
2026-11-15

## Branche Git
`feat/SF-238-10-navigation-3d`

---

## Objectif

Ouvrir une pièce `ARCHITECTURE` en 3D au centre de l'écran de Jarvis et la piloter **à la voix** (grammaire locale
sans IA, ou outil `afficher` du cerveau rapide) comme au toucher — focus, flux, replier/déplier, éclater, vues — par
des transitions sans saut, avec la narration SF-238-12 branchée sur la phrase **entendue**.

## Comportement attendu

### Cas nominal
1. **Visionneuse** : `PIECE_VIEWERS.ARCHITECTURE` = `architecture-3d`. Quand la 3D est possible (rendu 3D actif, ni
   sobre, ni mouvement réduit) la visionneuse lit le graphe conservé (`GET /api/jarvis/pieces/diag:<id>/graph`,
   SF-238-07), pose la scène SF-238-09 **dans le panneau de la pièce** (grand, au centre : jusqu'à 1 600 px de large),
   charge l'atlas d'icônes (SF-238-08) et se **lie au store** pour résoudre les cibles dites. Graphe absent ou
   illisible, WebGL en échec, ou « en 2D » demandé ⇒ la visionneuse SVG cadrée de F-237 (repli).
2. **Sous-état `arch` de `PieceNav`** (`pieces/jarvis-piece-arch.ts`, pur) : `{focus, folded[], exploded, view, flow,
   orbit, flat}`, posé à l'ouverture d'une architecture, retiré en la quittant. Une seule action `{type:'arch',
   request, op?}` pour le toucher, la voix et l'outil ; le store résout la demande contre le graphe de la pièce
   ouverte (`ArchitectureTargets` : id, libellé, sigle/type, famille, zone) ; une demande qui arrive avant le graphe
   (premier cadrage du cerveau) attend la visionneuse ≤ 10 s.
3. **Voix locale** (`archVoiceRequest`, liste fermée, phrase entière, une architecture ouverte) : « éclate »,
   « rassemble », « vue de dessus », « de face », « vue d'ensemble », « en 2D », « en 3D », « replie tout »,
   « déplie tout », « tourne » ⇒ **sans IA**. Tout ce qui nomme un service ou une zone part au cerveau rapide.
4. **Outil `afficher`** (cerveau rapide) : enum `action` étendue **une fois** de `focus`, `flux`, `replier`,
   `deplier`, `eclater`, `rassembler`, `vue` ; champ `vue` étendu de `ensemble|dessus|face|2d|3d` ; `cible` = le nom
   tel que dit. Version **22** (la « version 4 » du cadrage date d'avant les versions 5→21) : la doctrine vit dans la
   **description fixe de l'outil** (cadrage §11.3) — navigation d'architecture, « montrer d'initiative un schéma ⇒
   cadrer l'entrée » (`afficher(flux, « entrée », appui)`), « sans schéma ⇒ proposer d'en dessiner un » — qui sert
   SF-238-13 sans nouvelle modification ; le **texte** de la consigne reste celui de la v21 (D-10-6).
5. **`## Écran`** (message, jamais la consigne) : ligne « Architecture « titre » ; focus : … ; repliées : … ; éclaté :
   … ; vue : … ; flux : … », juste après l'en-tête, bloc ≤ 1 500 caractères.
6. **Transitions** (directeur pur) : T-A3 éclater/rassembler 1,2 s `glisse` ; T-A4 replier 0,7 s `entree` (services
   aspirés au centre, capsule « ⌬ n services », plateaux intérieurs effacés, emprise gardée) ; T-A5 déplier 0,8 s
   `sortie` ; T-A6 focus 1,4 s ressort (caméra sur le service et ses voisins, fiche du service) ; T-A9 vues 1,6 s
   ressort ; zoom 0,6 s ; orbite 0,16 s. Toute transition repart de l'image affichée (règle F-236 §7.3-2).
7. **> 40 services** : les AZ jumelles (même gabarit) fusionnent dans la première (« × n », capsules « = AZ A ») ;
   un focus dans une jumelle la déplie.
8. **Gestes** : glisser = orbite (élévation 10°–90°) ; molette = zoom ; toucher un service = focus + fiche (retoucher
   = ensemble) ; toucher long = replier / déplier la zone ; double toucher sur le vide = éclater / rassembler ;
   boutons Éclater / Dessus / 2D.
9. **Narration** (SF-238-12) branchée sur la phrase **entendue** : `playingSentence` (posé au `onPlaying` de chaque
   phrase, cerveau rapide et Opus) ⇒ `ArchitectureEngine.narrate`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Graphe absent (schéma antérieur à SF-238-07) ou illisible | repli SVG cadré F-237, bouton « Voir en 3D » masqué | 404 côté graphe |
| WebGL indisponible / exception de pose | repli SVG cadré | — |
| Cible ambiguë (« la base » avec deux bases) | rien ne bouge ; Jarvis dit « Il y en a deux : RDS primaire et RDS secours. Lequel ? » | — |
| Cible inconnue | « Je ne la trouve pas. » | — |
| Ordre d'architecture sans architecture ouverte | « Ce n'est pas possible sur cette pièce. » | — |
| `vue` inconnue (ou vue de l'univers donnée à `vue`) | refusé par la passerelle (`NO_VIEW`), rien relayé | — |
| Mode sobre, ordre d'initiative qui bouge la caméra | refusé (aucun mouvement d'initiative) | — |
| Présentation | aucune pièce (inchangé F-237) | — |

## Critères d'acceptation
- [x] Replier puis déplier ⇒ état identique (réducteur) et plan identique (directeur) — tests.
- [x] Aucun saut : à 16 ms d'écart, caméra, altitudes et replis avancent de < 30 % de l'écart (pas maximal F-236) ;
      transition interrompue = repart de l'image affichée — tests.
- [x] Ligne d'état dans le bloc `## Écran` du message et **jamais** dans la consigne système — test passerelle.
- [x] Consigne + outils du cerveau rapide byte-stables d'un appel à l'autre (et d'un état d'écran à l'autre) — tests.
- [x] > 40 services ⇒ AZ jumelles fusionnées — test.
- [x] « éclate » ⇒ **sans IA** (aucun appel au cerveau) — banc `/jarvis`.
- [x] Sans graphe ⇒ visionneuse SVG F-237 — composant + banc (« en 2D »).

## Périmètre — hors scope
Téléphone (volet bas, Δ réduit, étiquettes), qualité adaptative et pause de l'univers, table de vérité complète du
repli, T-A10 (fondu 3D ↔ 2D), mode présentation (masquage CIDR/ARN) ⇒ SF-238-11. Ouverture d'initiative et banc des
10 questions ⇒ SF-238-13. Mesure (`UsageEvent RENDER`) ⇒ SF-238-14. Liaisons ancrées au bord d'une capsule avec
compteur (cadrage §8.3) : ici éteintes (latentes, sans particules) — signalé.

## Valeurs initiales
`arch` = `{focus: null, folded: [], exploded: false, view: 'ensemble', flow: null, orbit: {0,0}, flat: false}`.

## Contraintes de validation
| Champ | Règle |
|---|---|
| `afficher.action` | enum fermée (+7 valeurs, une fois) |
| `afficher.vue` pour `vue` | `ensemble`, `dessus`, `face`, `2d`, `3d` |
| `afficher.cible` | ≤ 120 caractères (inchangé), résolue sur l'appareil, jamais interprétée en verbe |
| orbite | élévation 10°–90° |
| ligne `## Écran` | ≤ 6 zones repliées nommées (+n), bloc ≤ 1 500 caractères |

## Technique
- Endpoints : aucun nouveau (lecture `/graph` de SF-238-07). Tables / migration : aucune.
- Backend : `JarvisFastBrainPrompt` (version 22, `SHOW_ACTIONS`, `ARCH_VIEWS`, `SHOW_VIEWS`, descriptions),
  `JarvisShowCommand` (vue d'architecture).
- Front nouveaux : `pieces/jarvis-piece-arch.ts`, `pieces/jarvis-piece-architecture.component.ts`,
  `stage3d/architecture/architecture-targets.ts`, `scripts/banc-architecture-3d/scenario.mjs`.
- Front modifiés : `jarvis-piece-nav.ts`, `-voice.ts`, `-screen.ts`, `-show.ts`, `-store.ts`, `-viewers.ts`,
  `-viewer.component.ts`, `-gallery.component.ts`, `-client.ts`, `architecture-stage-director.ts`,
  `engine/architecture-scene-3d.ts`, `architecture-stage.component.ts`, `architecture-banc.ts`, `jarvis-banc.ts`,
  `jarvis.component.{ts,html}`.

## Plan de test
### Tests unitaires
- Réducteur `arch` (repli/dépli identité, orbite bornée, vue remet orbite et zoom), grammaire locale, ligne `## Écran`
  (≤ 1 500), plan d'`afficher` (verbes, vue, sobre).
- Cibles : id, libellé, sigle, zone, ambiguïté (« la base »), inconnu, replier tout, flux entrée / sortant / A→B.
- Directeur : transitions nommées, plan identique après dépli, capsule, pas maximal, interruption, ressort, jumelles,
  cadrage du focus, toucher (`pickAt`).
- Store : résolution liée au graphe, refus ambigu avec phrase, demande en attente du graphe.
- Passerelle : `JarvisShowCommandTest` (enum fermée, vue), `JarvisFastBrainServiceTest` (ligne hors consigne,
  préfixe identique), version 22.
### Tests d'intégration
- Banc `/jarvis` réel (API simulée, Chrome sans `--hide-scrollbars`, swiftshader, 1920 × 1080 et 390 × 844) :
  `scripts/banc-architecture-3d/scenario.mjs` (montre + cadre l'entrée, « zoome sur la base », « éclate » sans IA,
  « replie la zone B », vue de dessus, « en 2D »).
- Banc à vraie voix F-256 (2 passages, boucle legacy) et bancs du cerveau rapide (conversation, pièces) : la consigne
  change.
### Isolation utilisateur
- Aucune lecture nouvelle : le graphe passe par `GET /api/jarvis/pieces/diag:<id>/graph` (filtre `user_id`, test A/B
  de SF-238-07) ; icônes publiques.

## Préoccupations transversales
- **Préfixe de cache / consigne** : oui — `JarvisFastBrainPrompt` (texte + outil `afficher`), version 21 → 22, une
  fois ; composants : `JarvisFastBrainService` (préfixe), `JarvisOfflinePrompt` (inchangé : sans `afficher`), bancs.
- **Navigation / routing** : non (aucune route ; la pièce vit dans `/jarvis`).
- Contexte tenant, Auth, Plans : non.

## Dépendances
- SF-237-04 (registre, `PieceNav`), SF-237-08 (`afficher`), SF-238-07/08/09/12 : livrées.
- Questions ouvertes : aucune.

## Notes et décisions
- **D-10-1** « version 4 » du cadrage ⇒ **version 22** (la consigne a vécu 5→21 depuis) ; même règle : une fois.
- **D-10-2** `rassembler` ajouté à côté d'`eclater` (une action idempotente vaut mieux qu'une bascule) ; `vue` porte
  sa valeur dans le champ `vue` existant (étendu), jamais dans `cible` (D11).
- **D-10-3** La règle « montrer d'initiative ⇒ cadrer l'entrée » est posée ici pour ne pas retoucher la consigne en
  SF-238-13.
- **D-10-4** Liaisons vers une capsule : éteintes (latentes, sans particules) plutôt qu'ancrées au bord avec compteur.
- **D-10-5** La narration lit `playingSentence` (déjà posé au `onPlaying` de chaque phrase) : aucune modification de
  l'écoute ni de la voix.
- **D-10-6** Mesuré au banc de conversation : avec la doctrine dans le **texte** de la consigne, « regarde les tickets
  bloqués d'Helios » restait sans délégation (0/2 au banc, 7/8 en sonde ; main : 10/10). Déplacée dans la description de
  l'outil `afficher` : 8/8 en sonde, 2/2 au banc ; banc de conversation 97,2 % / 98,6 % (main : 100 % / 97,2 %, même
  bruit du juge sur les formules), banc des pièces 100 % ×2 (NAVIGATE 15/15 dont 5 ordres d'architecture).
- **D-10-7** En 2D (graphe absent), « zoome sur … » cadre tout de suite la partie nommée du SVG (repli F-237).
