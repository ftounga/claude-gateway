# Cadrage — F-173 La carte vivante

> Cadrage PO le 2026-10-04 (« je veux que la carte soit beaucoup plus élaborée sur le plan
> graphique : plus ludique, plus jolie, plus utilisable ; on clique, on navigue sur plusieurs
> plans, on ouvre certains accès »). Livrée **après** F-174, dont elle consomme l'index.
> Source de vérité produit : `docs/PROJECT.md`. Subordonné à `CLAUDE.md` et `docs/DESIGN_SYSTEM.md`.

## 1. L'existant

- L'onglet **Carte** d'un poste dans la Forge (`postes.component.html:540-631`, F-92/F-98) affiche
  6 noms de fichiers Markdown, un compteur de faits et le gain récent. Un clic ouvre le texte brut
  dans un dialogue. Ni graphe, ni zoom, ni navigation ; aucune bibliothèque graphique installée.
- L'écran relit la carte **en direct sur le poste** à chaque ouverture (`GovernanceMapReadingService`) :
  3 589 lectures et 197 Mo transférés en 30 jours, alors que la gateway en garde déjà une copie
  (`host_map_files`).
- Le contenu, lui, est riche (≈ 5 100 faits pour le poste CA-GIP, cf. cadrage F-174 §1).

## 2. Objectif

Rendre la carte **lisible d'un coup d'œil et navigable** : on voit l'infrastructure du client
comme un plan, on zoome par niveaux, on ouvre la fiche d'une ressource, on voit comment l'atteindre,
ce qui piège, ce qui expire et ce qui reste à découvrir, et on demande à la Forge d'agir depuis là.

## 3. Décisions (prises par défaut, réversibles, tracées)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Données = index F-174** (`host_map_entities` / `host_map_relations`) servi depuis la gateway, **pas de lecture du poste** pour dessiner. La carte reste consultable poste hors ligne (données datées). | Plus rapide, hors ligne, et fin des 197 Mo de relectures. |
| D2 | Bibliothèque **cytoscape.js** (MIT, graphes composés, mises en page, zoom/pan natifs), chargée paresseusement sur l'onglet Carte seulement. | Le besoin est un graphe à niveaux, pas un fond géographique. |
| D3 | **Palette du design system uniquement** (§2, §9, §16) : navy, surfaces, tons d'identité de poste, rouge erreur pour les pièges, gris « inactif » pour le périmé, accent orange réservé aux gestes. Polices Inter / JetBrains Mono / Space Grotesk. Aucune couleur nouvelle. | `DESIGN_SYSTEM.md` §8 et §16. |
| D4 | Trois **niveaux** : (1) le client — organisations, personnes, environnements, grandes plateformes ; (2) une plateforme — comptes, clusters, forges, registres, réseaux qu'elle contient ; (3) la **fiche** d'une ressource — faits datés et sourcés, pièges, accès, relations. Fil d'Ariane, retour arrière, état porté par l'URL (`?onglet=carte&noeud=…`). | « On clique, on navigue sur plusieurs plans. » |
| D5 | La vue **Fichiers** actuelle reste disponible (bascule Plan / Fichiers). | Rien n'est retiré. |
| D6 | Mobile (PWA F-152) : sous 768 px, le plan devient une liste hiérarchique navigable (mêmes niveaux, même fiche). | Un graphe n'est pas utilisable au doigt sur un téléphone. |
| D7 | « Demander à la Forge » ouvre le terminal du poste avec une consigne **pré-remplie, non envoyée** (l'utilisateur relit et envoie). | Aucune action sans geste explicite. |

## 4. Découpage

| SF | Titre | Contenu | Dépend de |
|---|---|---|---|
| SF-173-01 | L'API du plan | `GET /api/governance/hosts/{h}/map/graph` (entités, relations, niveaux, fraîcheur, pièges, échéances, « à cartographier ») et `GET …/map/entities/{id}` (fiche). Filtre `user_id`. Servi depuis l'index F-174. | F-174 SF-02 |
| SF-173-02 | Le plan qui se navigue | cytoscape.js, niveaux D4, zoom/pan, clic = descendre, fil d'Ariane, état dans l'URL, bascule Plan / Fichiers, repli mobile D6. | 01 |
| SF-173-03 | La fiche d'une ressource | Panneau latéral : faits avec date et source (lien vers le fichier et la section), identifiants copiables, relations cliquables, pièges en tête. | 02 |
| SF-173-04 | La grille des environnements | Vue domaine × environnement (Sandbox → Prod) des comptes et plateformes, rôle accordé, état. | 01 |
| SF-173-05 | Le chemin d'accès | « Comment j'atteins X » : chaîne poste → proxy → VPN/bastion → compte → cluster tirée des relations, tronçons ouverts / fermés / inconnus. | 02 |
| SF-173-06 | Les signaux | Pièges (rouge) sur les nœuds, faits périmés pâlis (> `fact-max-age-days`, F-139), échéancier des accès et jetons qui expirent, liste « à cartographier ». | 02 |
| SF-173-07 | Agir depuis la carte | « Demander à la Forge » D7 sur un nœud, une échéance ou un « à cartographier » ; affichage des propositions de consolidation F-174 SF-06 avec « Demander à la Forge de consolider ». | 03, F-174 SF-06 |

## 5. Hors périmètre

- Édition de la carte depuis l'écran (le texte se modifie par la Forge, F-174 D1).
- Carte multi-postes ou multi-clients agrégée.
- Fond géographique (pays, datacenters).

## 6. Critères transverses

- Navigation / routing : ajout du paramètre `noeud` à l'onglet existant ; les autres onglets et
  `/forge/:hostRef` sont inchangés (préoccupation transversale à lister en mini-spec 02).
- Accessibilité : chaque nœud a un équivalent dans la liste hiérarchique ; contraste conforme.
- Performance : rendu fluide jusqu'à 2 000 nœuds (regroupement par niveau).
