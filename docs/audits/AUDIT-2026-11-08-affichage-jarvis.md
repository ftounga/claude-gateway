# Audit — l'affichage de /jarvis (2026-11-08)

**Demande PO du 2026-11-08** : « visuellement j'ai l'impression qu'il n'y a rien ». Passe **globale** sur tout ce qui a
été cadré et livré pour l'affichage de `/jarvis` (F-231, F-234, F-236, F-237, F-238, F-243, F-245, F-247, F-248,
F-250), confrontée au code de `main` (`cfb67285`, identique à la prod `staging-8c5d00ab` pour l'écran), aux données de
prod (lecture seule) et à un **rendu effectif** de la vraie page `/jarvis` en Chrome sans tête (sans
`--hide-scrollbars`). Feature de correction : **F-253** (`docs/features/F-253/CADRAGE-F-253-jarvis-montre-vraiment.md`).

## 1. Récapitulatif pour le PO

**La cause en une phrase** : depuis le cerveau rapide (F-235), presque toutes vos paroles sont traitées par un chemin qui
**ne parle pas à l'écran** — tout ce qui « vit » (nœuds qui s'allument, scènes, « Montrer », mur de tickets,
chronologie) n'était branché que sur les tours d'Opus, devenus rares. Et l'outil d'affichage du cerveau rapide ne sait
montrer que les pièces **d'un sujet déjà choisi** : sans sujet choisi (cas par défaut), il annonce « Voici » ou « Je vous
affiche… » et **rien n'apparaît**.

| Ce qui était promis | Ce que vous avez eu le 07/11 | Verdict | Corrigé par |
|---|---|---|---|
| « Montre-moi mes clients / mes sujets » : la constellation se réorganise (F-234 P5 « Montrer ») | « Voici. » puis « Ce n'est pas possible sur cette pièce. » | **DÉCLENCHEUR IMPOSSIBLE** (le cerveau rapide n'a pas l'ordre) | SF-253-02 |
| « Les vidéos chez CAGIP » : la galerie holographique (F-237) | « Je vous affiche la galerie… », rien | **CASSÉ** (pièces d'un seul sujet ; pas de portée client ; sujet par identifiant interne) | SF-253-03 |
| Jarvis dit ce qui s'affiche vraiment | « ok » renvoyé au modèle **avant** que l'écran ait vérifié ⇒ il se contredit | **CASSÉ** | SF-253-04 |
| Poste éteint : Jarvis répond quand même sur l'état (F-247) | 3 délégations envoyées au poste éteint ⇒ « La recherche n'a pas abouti » | **CASSÉ** | SF-253-05 |
| Les nœuds cités s'allument, le nom brille quand il est dit (F-234 D13) | rien pendant une réponse du cerveau rapide | **JAMAIS BRANCHÉ** (chemin rapide) | SF-253-07 |
| Le sujet choisi est mis en avant en 3D, « Journée du sujet » au cockpit (F-236) | la 3D et le cockpit ignorent le sujet choisi jusqu'au 1ᵉʳ tour d'Opus | **CASSÉ** | SF-253-07 |
| Scènes par action (commande, tickets, fichiers…), panneaux, mur de tickets, chronologie du tour (F-234/F-236) | n'apparaissent que pendant un tour d'Opus ; une délégation n'en relaie qu'une partie | **DÉCLENCHEUR IMPOSSIBLE** hors Opus ; **CASSÉ** en délégation (plan, attente, points, création, passation, souvenir) | SF-253-07 / SF-253-08 |
| Les pancartes (F-248) | 0 pancarte sur 20 tours (`cards_count` vide) | **OK** mais rares (ne viennent que des faits lus/cités) | — |
| Le visage (F-250) | rendu 3D « bas » (`render 3d low face=1 srv=1`) | **OK** (3D seulement) | — |
| « Ce que je sais » (F-247) | bouton absent en 2D et au téléphone (seulement dans « Réglages », 3D) | **CASSÉ** en 2D | SF-253-08 |
| Rendez-vous cités surlignés dans la frise (F-248 §4.7) | jamais | **JAMAIS BRANCHÉ** | SF-253-08 |
| Mesure de ce que l'écran a montré | `render_3d = render_2d = scenes_shown = 0` tous les jours de novembre | **CASSÉ** (envoi à la sortie d'écran seulement) | SF-253-08 |
| Tours vides | 4 tours `empty` sur 20, dont 2 délégués d'office au poste éteint, sans cause lisible | **NON DIAGNOSTICABLE** | SF-253-06 |

Ce qui **marche** (vérifié en rendu réel) : la 3D s'allume (PC et téléphone), l'univers est lu à l'ouverture et visible
au repos, le cockpit PC (Aujourd'hui, météo, personnes, À envoyer, univers, activité 7 jours, recherche « Montre-moi… »)
est rempli, la galerie s'ouvre **quand un sujet est choisi**, le visage parle en 3D.

## 2. Méthode et preuves

- **Code** : lecture de `jarvis.component.ts` (4 433 lignes), `jarvis.component.html`, `scenes/`, `stage3d/`,
  `pieces/`, `cards/`, `face/`, et côté serveur `jarvis/fastbrain/` (contrôleur SSE, service, `JarvisShowCommand`,
  `JarvisFastPieces`, consigne v19).
- **Prod (lecture seule)** :
  - transcription de l'essai (`atelier_messages`, `via = JARVIS`, 07/11 16:13–16:18 UTC) : « Montre-moi la liste de mes
    clients. » ⇒ « Voici. » ; « Vidéo chez le client CAGIP… » ⇒ « Je vous affiche la galerie des vidéos de CAGIP. » ;
  - `jarvis_turn_timings` : 20 tours, **tous `host_offline = t`**, 3 `FAST_DELEGATED` dont 2 avec
    `delegation_reason = empty`, 4 tours rejetés (`empty` ×3, `no_voice` ×1), `cards_count` vide partout ;
  - journaux du pod : `Flux de tour clos (… issue=runner_offline)` ; `Arrêt au plafond de sortie (stop_reason=max_tokens,
    modèle=claude-sonnet-5-5) : blocs=[] … jetons_sortie=0` — un tour vide dont la cause n'est lisible que dans les
    journaux bruts ;
  - `jarvis_listen_traces` : une seule décision de rendu, `render 3d low face=1 srv=1` (3D, qualité basse, visage) ;
  - `jarvis_usage_days` du PO, 1ᵉʳ→8 novembre : `scenes_shown = 0`, `render_3d = 0`, `render_2d = 0`, `show_requests = 0`
    malgré 45 ouvertures ;
  - drapeaux (`backend-config`) : `APP_JARVIS_FAST_BRAIN_ENABLED=true`, `APP_JARVIS_FAST_MODEL=claude-sonnet-5-5` ;
    pancartes, visage et alignement **allumés par défaut** (absents de la configuration = défaut `true`).
- **Rendu réel** : banc `frontend/scripts/banc-jarvis-montre/` — la **vraie** page `/jarvis` (build de
  développement), API simulée (univers du PO, données inventées), cerveau rapide scripté avec les ordres prouvés en
  prod, Chrome sans tête **sans** `--hide-scrollbars`, PC 1920×1080 et téléphone 390×844. Captures
  `docs/features/F-253/captures/avant/`. Résultat sur `main` : **8 BUG / 18** — « mes clients » et « mes sujets » ne
  montrent rien (PC, PC avec sujet, téléphone), « les vidéos de CAGIP » ne montrent rien **sans sujet choisi** (PC,
  téléphone) ; avec le sujet choisi, la galerie s'ouvre (7 éléments). Aucune erreur de page, aucun défilement horizontal.

## 3. Inventaire capacité par capacité

Légende : **OK** ; **CASSÉ** (branché mais faux) ; **JAMAIS BRANCHÉ** (le code existe, rien ne l'alimente) ;
**DÉCLENCHEUR IMPOSSIBLE** (DI : le déclencheur ne peut plus survenir depuis le cerveau rapide). Chemins relatifs à
`frontend/src/app/jarvis/` sauf mention.

### 3.1 F-231 / F-234 — l'écran vivant

| # | Promis | Déclencheur | État réel (preuve) | Verdict |
|---|---|---|---|---|
| 1 | Orbe 2D qui reflète l'état | `state()` local | `jarvis.component.html:168-177` ; le chemin rapide pose `reflexion`/`parole` (`ts:2298, 2350`) | OK |
| 2 | Constellation clients → sujets → chantiers au repos | univers lu à l'ouverture | `scene-bus.service.ts:139-153` appelé sans sujet ; 1ᵉʳ client courant (`constellation-layout.ts:64`) ; rendu réel : noms visibles | OK |
| 3 | Nœud cité allumé, fil lumineux | `scenes.text(delta)` | appelé seulement par les tours Opus (`ts:2556, 2707`), jamais par `converse()` (`ts:2297-2500`) | JAMAIS BRANCHÉ (rapide) |
| 4 | Nom en surbrillance au moment où il est dit (D13) | `sayingHooks` → `scenes.saying` | seulement `ts:3033` (Opus) ; `sayNow` rapide n'a que `cardHooks` (`ts:2356`) | JAMAIS BRANCHÉ (rapide) |
| 5 | Toucher un sujet pour le choisir | toucher | `touchSubject` `ts:3273` → `chooseSubject` `ts:1345` | OK |
| 6 | Scènes par famille d'outil (S1, S3, S-GEN…) | `onAction`/`onOutput` d'un tour Opus | `ts:2552-2553, 2688-2697` | DI hors délégation |
| 7 | S9 plan, S11 attente, S14 points, S7 création, S10 passation, S12 souvenir | événements d'un tour Opus | relayés par `send` (`ts:2750-2790`) mais **pas** par `startDelegation` (`ts:2526-2597`) | CASSÉ en délégation |
| 8 | **S20 « Montrer »** clients / connectés / déconnectés / chantiers bloqués / étapes en cours / à envoyer (SF-234-13) | ligne `🖥 montrer:` d'Opus | le cerveau rapide n'a aucun ordre équivalent (`SHOW_ACTIONS`, `JarvisFastBrainPrompt.java:419`) ; « montre mes clients » ⇒ le modèle tente `remonter` ⇒ `NOT_APPLICABLE` (`pieces/jarvis-piece-nav.ts:220`) | **DI** |
| 9 | « Montrer » local au toucher d'un chiffre du cockpit | toucher | `showLocal` `ts:3347` | OK (cockpit PC) |
| 10 | Ambiance A2 (sujets vivants), A3, A5 (veille) | relevés existants | `ts:235-241, 1173` | OK |
| 11 | Ambiance A1 (poste muet), A4 (coût) | erreur / fin d'un tour Opus | `scene-bus.service.ts:287-315` | DI (rapide) |
| 12 | Mesure des scènes (SF-234-11) | fin de tour | `jarvis.scenes(takeStats())` seulement en fin de tour Opus direct (`ts:2716`) ; ni rapide ni délégation | CASSÉ (0 en prod) |

### 3.2 F-236 — Jarvis en 3D

| # | Promis | Déclencheur | État réel (preuve) | Verdict |
|---|---|---|---|---|
| 13 | Calque 3D plein écran, repli 2D | capacité WebGL2, ni sobre ni mouvement réduit | `stage3d/jarvis-render-capability.ts:47-70` ; prod : `render 3d low` ; banc : 3D sur PC et téléphone | OK |
| 14 | Cœur 3D, onde du mot « Jarvis » | `state`, `wakeCalls` | `ts:3287, 1275` | OK |
| 15 | Univers 3D au repos | `scenes.universe()` | `stage3d/jarvis-stage-director.ts:116-132` ; capture `avant/pc-0-repos.png` | OK |
| 16 | Deux cerveaux (champ neuronal quand Opus travaille) | délégations | `stage3d/jarvis-brains.ts:43` | OK (délégation) |
| 17 | **Sujet courant mis en avant en 3D** | `scenes.subjectId()` | ni l'ouverture (`ts:1083`) ni `chooseSubject` (`ts:1345`) n'appellent `scenes.setSubject` ; seuls `ts:2526, 2681` (tours Opus) | **CASSÉ** |
| 18 | Panneaux holographiques, impulsions de scène | `reader.scene`, `pulses` | Opus seulement | DI (rapide) |
| 19 | Cockpit PC : Aujourd'hui, météo, personnes, À envoyer, univers | univers | `stage3d/hud/jarvis-cockpit.component.ts:202-204, 362-363` ; capture | OK (≥ 1280 px, 3D) |
| 20 | Mur de tickets, chronologie du tour | mémoire de session issue des scènes | `jarvis-cockpit.component.ts:229-238, 364` | DI (rapide) |
| 21 | Journée du sujet | bouton du cockpit | désactivé si `!signals().subjectId` (`jarvis-cockpit.component.html:374`) ⇒ voir #17 | **CASSÉ** |
| 22 | Inspecteur, recherche « Montre-moi… », activité 7 jours, radar | toucher / clavier | `jarvis-cockpit.component.ts:373, 392`, `html:109, 483` | OK (PC) |
| 23 | Replay, « Pendant votre absence » | `?replay=` / historique | `stage3d/hud/jarvis-replay.component.ts:79-120` | OK (tours Opus seulement) |
| 24 | Mode présentation, cockpit 2ᵉ écran, veilleur | `?presentation=1`, « Réglages » | « Réglages » n'existe qu'en 3D (`html:63`) | OK (3D) |
| 25 | Mesure du rendu (SF-236-06) | sortie d'écran | `jarvis.render()` seulement dans `ngOnDestroy` (`ts:1134-1138`) — jamais à la fermeture d'onglet, au rechargement, à la mise à jour automatique | **CASSÉ** (0 en prod) |

### 3.3 F-237 / F-238 — les pièces et l'architecture

| # | Promis | Déclencheur | État réel (preuve) | Verdict |
|---|---|---|---|---|
| 26 | Galerie holographique des pièces du sujet | bouton, `afficher galerie` | bouton grisé sans sujet (`html:34`) ; aucun sujet choisi à l'ouverture (`ts:1083`) | OK avec sujet |
| 27 | **« Montre les vidéos chez CAGIP »** (portée client, plusieurs sujets) | outils `pieces` + `afficher` | `sujet` = UUID d'un seul sujet (`JarvisFastPieces.java:55-61`), pas de portée client ; `## Pièces` seulement pour le sujet courant (`JarvisFastBrainService.java:1334`) ; page sans sujet ⇒ refus `NOTHING` **muet** (`pieces/jarvis-piece-show.ts:164`, `jarvis-piece-store.ts:444`) | **CASSÉ** |
| 28 | Navigation suivante / précédente / sélection / lecture / page / zoom / fermer | `afficher` | réducteur `pieces/jarvis-piece-nav.ts` ; refus correct quand rien n'est ouvert | OK (galerie ouverte) |
| 29 | `remonter` / `retour` | `afficher` | au niveau univers : `NOT_APPLICABLE` — c'est l'ordre tenté pour « mes clients » | DI depuis l'univers |
| 30 | **Le modèle sait si l'affichage a réussi** (« L'IA montre d'elle-même… et commente ») | — | `JarvisShowCommand.Parsed.toolResult()` rend `ok` dès la validation de forme (`JarvisShowCommand.java:66`) ; « Voici. » ajouté d'office (`JarvisFastBrainService.java:1147, 1164`) | **CASSÉ** |
| 31 | `🖥 montrer: preuves/entrées/pièces` (Opus), preuves au fil de l'eau | tour Opus | `ts:3620-3639` | OK |
| 32 | Diaporama narré, avant / après, planche contact | `afficher raconter/comparer/planche` | `ts:3486`, `jarvis-piece-show.ts` | OK avec sujet |
| 33 | `cible` d'un zoom (« le VPC de prod ») | `afficher zoom` | ×2 sans cadrer la partie nommée — point d'extension F-238 (`jarvis-piece-nav.ts:279-286`) | Non livré (F-238 itération 1) |
| 34 | F-238 itération 0 : schéma 2D cadré | rendu | SF-238-01→06 livrées, visionneuse `schema-svg` | OK |
| 35 | F-238 itérations 1-2 : architecture en 3D | — | seule la colonne `graph_json` existe | Non livrée (cadrée, 8/14 SF restantes) |

### 3.4 F-243 / F-245 / F-247 / F-248 / F-250 — vues, pancartes, visage

| # | Promis | Déclencheur | État réel (preuve) | Verdict |
|---|---|---|---|---|
| 36 | Vue d'agenda (frise) | outil `agenda` ⇒ `show agenda:…` décidé par la passerelle | `JarvisFastBrainService.java:1080`, `ts:3470` | OK |
| 37 | **Rendez-vous cités surlignés dans la frise** (F-248 §4.7, SF-248-06) | phrase jouée | `jarvis-agenda.component.ts` n'a aucune entrée de surbrillance | **JAMAIS BRANCHÉ** |
| 38 | Visionneuse de courriel | bref, `afficher courriel:` | `ts:558, 3462` | OK |
| 39 | Volet « Fil du jour » (F-245) | bouton | pied de page 2D (`html:304`) et « Réglages » 3D (`html:135`) | OK |
| 40 | **Volet « Ce que je sais »** (F-247) | bouton | seulement dans « Réglages », qui n'existe qu'en 3D (`html:59, 140`) | **CASSÉ** en 2D / téléphone |
| 41 | Pancartes : catalogue alimenté sur le chemin rapide | SSE `cards` | `JarvisFastBrainController.java:126, 201` ; sources : univers (personnes), attentes, envois, état, bref | OK |
| 42 | Pancartes affichées | phrase jouée citant une clé / lecture ciblée | `cards/jarvis-card-cite.ts:168` ; 0 en prod le 07/11 (poste éteint, aucun fait cité) | OK, rares |
| 43 | Pancarte rendez-vous hors lecture d'agenda | rendez-vous cité | gabarit présent, aucune source (`JarvisCardSources`) | JAMAIS BRANCHÉ (rare ; avec #37) |
| 44 | Visage F-250, lèvres alignées | serveur + 3D | `ts:821-825, 1044-1050`, `stage3d/engine/jarvis-face-3d.ts` ; prod `face=1 srv=1` | OK (3D seulement) |
| 45 | Bandeau des délégations vivantes | `delegated` | `ts:2519` | OK |

### 3.5 Cerveau rapide — chemins qui mènent à « rien »

| # | Constat | Preuve | Verdict |
|---|---|---|---|
| 46 | Délégation (y compris **d'office** sur tour vide) envoyée au terminal d'un **poste éteint** ⇒ « La recherche n'a pas abouti » | `jarvis_turn_timings` 16:15:25 et 16:16:09 `FAST_DELEGATED`, `delegation_reason=empty`, `host_offline=t` ; journal `issue=runner_offline` | **CASSÉ** |
| 47 | Tour vide : ni `stop_reason`, ni outils, ni motif de rejet de la porte de phrases retenus | `JarvisFastBrainService.java:1167-1168` (`REASON_EMPTY` seul) ; seule trace : le WARN brut du fournisseur | **NON DIAGNOSTICABLE** |
| 48 | Journal d'écoute : aucun événement d'affichage (montré / refusé) | `jarvis-listen-trace.ts` : aucun appel `mark('show…')` ; 0 événement d'affichage en base | **JAMAIS BRANCHÉ** |

## 4. Défauts visuels mineurs relevés au rendu

- Téléphone 390 px : l'étiquette « data-platform » de la constellation 2D est coupée au bord droit
  (`captures/avant/telephone-videos-cagip.png`) — corrigé avec SF-253-07 (marge des étiquettes).

## 5. Propositions NOUVELLES (non cadrées, non construites)

Ces capacités ne figurent dans aucun cadrage ; elles ne sont **pas** construites par F-253 :

1. **Une scène propre au cerveau rapide** (« ce qu'il vient de lire » : bloc d'état, pièces, agenda, en panneau
   holographique) — aujourd'hui la scène ne se déduit que des outils d'Opus ; F-253 se limite à rallumer les nœuds cités.
2. **Pancartes « client » et « chantier »** tirées de l'univers (avancement, étape, attente) quand le PO en parle — le
   catalogue F-248 ne prévoit que personnes, attentes, envois, courriels, état, accès.
3. **Cadrer la partie nommée d'un schéma** (`cible` : « zoome sur le VPC de prod ») — point d'extension de F-238
   itération 1 (architecture 3D), cadrée mais non livrée.
4. **Entrée visible du mode présentation** (bouton) — aujourd'hui seulement `?presentation=1`.
5. **Un rappel visible « Mode sobre actif »** — le mode sobre efface constellation, scènes et 3D sans le dire.
6. **La palette cyan (style B) étendue à tout l'écran** — F-250 l'a retenue pour le visage seulement ; le reste de
   l'écran reste dans la palette §23 (ambre).
