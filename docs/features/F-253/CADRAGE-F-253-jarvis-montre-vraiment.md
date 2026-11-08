# Cadrage — F-253 Jarvis montre vraiment

**Demande PO du 2026-11-08** : « visuellement j'ai l'impression qu'il n'y a rien ». Le PO a demandé, et nous avons cadré
et livré (F-234, F-236, F-237, F-238, F-248, F-250), une interface 3D holographique qui montre les faits, ce que Jarvis
fait, les pièces, l'architecture, des pancartes. Lors de son essai du 07/11 (16:13–16:20 UTC) rien n'est apparu. Il veut
**une passe globale sur tout l'affichage**, pas seulement les défauts déjà trouvés.

Audit préalable (artefact obligatoire, SF-253-01) : `docs/audits/AUDIT-2026-11-08-affichage-jarvis.md`.

## 1. Cause

Depuis F-235, le **cerveau rapide** traite presque toutes les paroles ; ses événements (`text`, `show`, `cards`,
`understood`, `delegated`, `done`) ne nourrissent ni la scène F-234 ni la 3D F-236, branchées sur les tours d'Opus. Son
outil `afficher` (F-237) ne sait montrer que les pièces **d'un** sujet désigné par identifiant interne, et la passerelle
lui répond « ok » avant toute vérification. Enfin, le poste éteint reçoit quand même les délégations.

## 2. Principes (inchangés)

- **Gateway-First / Provider-First** : on relaie, on vérifie, on montre ; aucun moteur d'IA ajouté. Le seul appel
  nouveau (SF-253-05) est le **même** fournisseur derrière `AIProvider`, outils **en lecture seule**.
- **Justesse avant coût** : Jarvis ne dit jamais « Voici » si rien ne va apparaître ; le modèle reçoit le **vrai**
  résultat de l'affichage.
- **Préfixe de cache** : les définitions d'outils restent **figées** (octet pour octet d'un appel à l'autre) ; tout ce
  qui est volatil (comptes de pièces, état de l'écran) va dans le **message**.
- **Isolation `user_id`** : toute lecture (univers, pièces, noms rapprochés, état) est filtrée par le compte.
- **Bancs Jarvis** (V8) : toute SF qui modifie la consigne ou un outil du cerveau rapide passe **2 pré-bancs verts**
  consécutifs avant merge (jamais allégés).
- Style : palette §23 et verre holographique de F-236 ; visage style B (cyan) inchangé ; aucune couleur nouvelle.

## 3. Ce qui change

| # | Défaut (preuve à l'audit) | Correction |
|---|---|---|
| 1 | « Montre-moi mes clients / mes sujets » impossible (audit #8, #29) | `afficher` gagne l'action **`univers`** (filtre fermé : `clients`, `sujets`, `connectes`, `deconnectes`, `chantiers-bloques`, `etapes-en-cours`, `a-envoyer`, client facultatif) ; l'écran l'applique avec le « Montrer » F-234 déjà livré (S20), en 2D comme en 3D. |
| 2 | Pièces du sujet courant seulement ; pas de portée client (#27) | Contexte : pièces **comptées par sujet et par type** sur tous les sujets du compte (dans le message) ; `pieces` et `afficher` acceptent un **client** comme portée ; l'index lit les sujets du client (`GET /api/jarvis/pieces?clientId=`, filtré `user_id`). |
| 3 | Sujet désigné par identifiant interne (#27) | La passerelle **rapproche** le nom dit (« agenor », « Kajip », « data platform ») d'un sujet ou client du compte, tolérant accents, casse, tirets et petites fautes de transcription ; ambigu ⇒ refus explicite rendu au modèle. |
| 4 | « Voici. » dit et « ok » rendu avant vérification (#30) | La passerelle **vérifie** l'ordre contre les données (pièces comptées, univers, état de l'écran `## Écran`) et rend au modèle le **vrai** résultat (« affiché : 19 vidéos de data-platform » / « refusé : aucune vidéo chez CAGIP ») ; « Voici. » n'est dit que si un ordre accepté va montrer quelque chose ; un refus de la page malgré tout est dit une fois, sans contradiction. |
| 5 | Délégation vers un poste éteint (#46) | Poste éteint ⇒ pas de délégation ; Jarvis le **dit** (« Le poste de CAGIP est éteint ; je vous réponds avec ce que je sais. ») et répond par **Opus côté passerelle** avec les outils de lecture seule (`pieces`, `etat`, `conversations`, `agenda`, univers), sans délégation possible. |
| 6 | Tours vides non diagnosticables (#47, #48) | Par tour vide : `stop_reason`, noms d'outils, chaque motif de rejet de la porte de phrases (journal + `jarvis_turn_timings`) ; événements `show`, `show_refused` au journal d'écoute. |
| 7 | La réponse rapide n'anime pas l'écran ; le sujet choisi n'atteint pas la 3D (#3, #4, #17, #21) | La réponse rapide alimente la scène (`turnStarted`, `text`, `saying`, `done`) : nœuds cités allumés, nom en surbrillance ; le sujet choisi est passé à la scène dès l'ouverture et à chaque choix. |
| 8 | Mesures fausses, délégation partielle, accès 2D (#7, #12, #25, #37, #40, #43) | Mesure du rendu envoyée aussi à `pagehide` (`keepalive`) ; scènes d'une délégation comptées et relayées comme un tour direct ; « Ce que je sais » au pied de page 2D ; rendez-vous cités surlignés dans la frise. |

## 4. Découpage

| SF | Contenu | Estimation | Bancs |
|---|---|---|---|
| **SF-253-01** | Audit global, cadrage, PRODUCT_SPEC, banc de rendu réel `frontend/scripts/banc-jarvis-montre/` | 0,5 j | — |
| **SF-253-02** | Jarvis montre l'univers (action `univers`, consigne) | 1,5 j | 2 × |
| **SF-253-03** | Les pièces d'un client et les noms dits (comptes, portée client, rapprochement) | 2 j | 2 × |
| **SF-253-04** | Vérifier avant de dire « Voici » | 1,5 j | 2 × |
| **SF-253-05** | Poste éteint : le dire, et répondre par Opus en lecture seule | 2 j | 2 × |
| **SF-253-06** | Tours vides et affichages tracés | 1 j | — |
| **SF-253-07** | La réponse rapide anime l'écran ; le sujet atteint la 3D | 1 j | — |
| **SF-253-08** | Mesures justes, délégation complète, accès en 2D, frise surlignée | 1 j | — |

Ordre : 01, puis 02→06 (passerelle, consigne), 07 et 08 (écran) en parallèle. Aucune table nouvelle prévue (colonnes
de diagnostic au besoin : migration `253-…`).

## 5. Hors périmètre

Les propositions nouvelles de l'audit (§5 : scène propre au cerveau rapide, pancartes client/chantier, cadrage de la
partie nommée d'un schéma, bouton présentation, rappel du mode sobre, palette cyan généralisée) ; F-238 itérations 1-2 ;
toute modification de l'écoute (F-251) ou de la latence (F-252).

## 6. Décisions

- **D1** — `univers` est une action de l'outil `afficher` existant (pas un outil nouveau) : une valeur d'énumération de
  plus, définition figée.
- **D2** — La vérification se fait **côté passerelle** sur les données qu'elle détient ; pas d'aller-retour navigateur
  dans le tour (latence).
- **D3** — Poste éteint : la réponse Opus en lecture seule est comptée au budget Jarvis (F-244), jamais au quota du
  terminal ; aucune action engageante possible.
- Aucune question PO : les défauts sont prouvés et les corrections ramènent des capacités déjà cadrées.
