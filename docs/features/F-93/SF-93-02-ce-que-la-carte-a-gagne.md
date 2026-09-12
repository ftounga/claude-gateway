# Mini-spec — F-93 / SF-93-02 — Ce que la carte a gagné

## Identifiant

`F-93 / SF-93-02`

## Feature parente

`F-93` — La promotion a une destination

## Statut

`in-review`

## Date de création

2026-09-13

## Branche Git

`feat/SF-93-02-ce-que-la-carte-a-gagne`

---

## Objectif

Retenir, d'une lecture de carte à l'autre, **ce que la carte a gagné** — pour que la promotion cesse
d'être une corvée invisible et que *« à chaque projet, la connaissance de l'infra augmente »* devienne
un **fait qu'on constate**, pas une phrase.

---

## Pourquoi ce n'est pas déjà le cas

SF-92-02 compte les **faits** d'une carte et l'écran les affiche. Mais un compteur ne montre pas une
augmentation : il dit `16` aujourd'hui et `16` demain, et rien ne distingue une carte qui grossit
d'une carte morte.

**Ce qui manque est la mémoire de la lecture précédente.** Sans elle, aucune phrase du produit ne
peut dire ce qui a été gagné, et la promotion — qui coûte un geste à chaque tour — ne rend jamais
rien de visible à celui qui la fait.

---

## Arbitrage central — on **constate**, on ne croit pas sur parole

Deux sources possibles pour « ce que la carte a gagné » :

| | Source | Ce que ça vaut |
|---|---|---|
| A | les promotions **déclarées** par le modèle (`promu=` de SF-93-01) | immédiat et précis — mais **auto-déclaré**, et le cadrage a déjà jugé l'auto-déclaration insuffisante (§5) : un modèle qui oublie de promouvoir oubliera de le déclarer |
| B | le **delta observé** du nombre de faits, d'une lecture à l'autre | **retenu** — c'est ce que la carte porte vraiment, quelle que soit la main qui l'a écrit : un tour d'agent, le terminal du poste, ou l'utilisateur dans son éditeur |

**B est retenu**, et c'est la lecture littérale de la demande du PO : *« ce n'est pas un tableau de
bord, c'est un fait qu'on constate »*. On mesure la carte, on ne fait pas confiance au récit qu'on en
fait.

---

## Comportement attendu

### 1. Chaque lecture de carte laisse une trace de ce qu'elle a vu

À chaque relevé (`GET /governance/hosts/{ref}/map`, déjà existant), pour chaque fichier **lu
entièrement** :

| État précédent | Ce qui est retenu |
|---|---|
| aucun — **première observation** | une **référence** : `first_facts`, `first_seen_at`, et le compte courant. **Aucun gain** : une carte déjà pleine le premier jour n'a rien « gagné » — elle était là. |
| moins de faits qu'aujourd'hui | le **gain** (`last_gain`, `last_gain_at`) et le nouveau compte |
| autant de faits | le compte et la date d'observation, rien d'autre |
| **plus** de faits qu'aujourd'hui | le nouveau compte, **silencieusement** — une carte qu'on élague n'a pas « perdu » un savoir, elle a été rangée ; un chiffre négatif n'apprendrait rien et découragerait le ménage |

### 2. Une lecture **tronquée** ne compte pas — ni référence, ni gain

Le producteur coupe les contenus volumineux (`truncated`). Un compte établi sur un contenu coupé est
**faux par construction**, et il est faux **vers le bas** : retenu comme référence, il ferait
apparaître un « gain » fantôme à la première lecture complète. **Un compte incomplet n'est pas un
compte** : on ne retient rien, et la lecture suivante fera foi.

### 3. Le relevé porte ce que la carte a gagné

`GovernanceMapView` gagne un bloc `growth` :

| Champ | Ce qu'il porte |
|---|---|
| `since` | la **première** observation de ce poste (la plus ancienne des références) ; `null` si aucune |
| `sinceFacts` | ce que la carte portait à ce moment-là |
| `gained` | `facts - sinceFacts`, jamais négatif |
| `recent` | jusqu'à **6** fichiers ayant gagné, du plus récent au plus ancien : chemin, titre, gain, date |

Ce qui permet à l'écran (SF-93-03) de dire une phrase, pas d'afficher un tableau de bord :
*« depuis le 2 septembre, la carte est passée de 4 à 16 faits »*, puis *« acces.md +3, le
12 septembre »*.

### 4. Rien de tout cela ne s'affiche quand il n'y a rien à dire

Poste non gouverné, machine muette, aucune observation : `growth` est **absent** (`null`). Une
section « ce que la carte a gagné » qui afficherait « +0 » chaque jour serait pire que rien — elle
apprendrait qu'on ne gagne rien.

---

## Cas d'erreur

| # | Cas | Comportement attendu |
|---|---|---|
| E1 | machine injoignable | **aucune écriture** : on ne retient pas une carte qu'on n'a pas lue |
| E2 | fichier absent ou illisible | ce fichier n'est ni retenu ni oublié — sa ligne précédente reste telle quelle |
| E3 | lecture tronquée | ni référence ni gain (§2) |
| E4 | l'écriture de la trace échoue (base indisponible) | **le relevé est rendu quand même**, sans `growth` : la carte reste exacte, et un journal de croissance n'a jamais à empêcher de lire la carte |
| E5 | poste supprimé puis recréé sous le même nom | ce sont deux `host_id` différents : la nouvelle carte repart d'une référence neuve, ce qui est juste |
| E6 | un fichier disparaît du paquet | sa ligne reste en base et n'est plus rendue (elle n'est plus dans les fichiers attendus) |

---

## Critères d'acceptation

- [x] Une première lecture crée une **référence** et ne rend **aucun gain**.
- [x] Une seconde lecture avec plus de faits rend un gain daté, par fichier.
- [x] Une lecture avec **moins** de faits ne rend pas de gain et ne casse pas la référence.
- [x] Une lecture **tronquée** n'écrit rien.
- [x] Une machine injoignable n'écrit rien.
- [x] `growth` est `null` quand aucune observation n'existe.
- [x] `gained` n'est jamais négatif.
- [x] `recent` est borné à 6 entrées, les plus récentes d'abord.
- [x] Une panne d'écriture ne fait pas échouer le relevé.
- [x] **Isolation** : la table porte `user_id` en tête de son index d'unicité ; deux utilisateurs
      observant le même poste ne se voient pas (cas impossible aujourd'hui — un poste appartient à un
      seul utilisateur —, mais l'index le garantit et le test le vérifie).

---

## Plan de test

### Unitaires

1. `GovernanceMapGrowthServiceTest` — référence, gain, égalité, diminution, tronqué, panne d'écriture,
   bornage de `recent`, `gained` jamais négatif.
2. `GovernanceMapReadingServiceTest` — `growth` absent si rien d'observé, présent après deux
   lectures ; aucune écriture si la machine est injoignable.

### Intégration

3. `GovernanceMapGrowthIntegrationTest` — contexte Spring réel, migration `078` appliquée, deux
   observations successives : la seconde porte le gain, et la ligne est **écrite en base** (ce qui
   vérifie que la transaction à part est bien obtenue, et pas seulement annoncée).
4. `GovernanceMapApiIntegrationTest` — le relevé d'un poste non gouverné ne porte **aucun** bloc de
   croissance.

### Isolation utilisateur

5. Une observation écrite pour Alice n'est jamais lue pour Mallory (même poste, même chemin).

---

## Impacts

| Zone | Détail |
|---|---|
| Tables | **`governance_map_growth`** (nouvelle) — migration `078-governance-map-growth.xml` |
| Endpoints | `GET /governance/hosts/{ref}/map` — **payload enrichi**, aucune route nouvelle |
| Backend | `GovernanceMapGrowth` (entité), `GovernanceMapGrowthRepository`, `GovernanceMapGrowthRecorder`, `GovernanceMapGrowthService`, `GovernanceMapReadingService`, `GovernanceMapView`, `GovernanceMapGrowthView`, `GovernanceMapGainView` |
| Frontend | **aucun** — SF-93-03 |

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | route existante, `GovernanceHostController.map` inchangé dans sa sécurité |
| Contexte tenant | **oui** | `GovernanceMapGrowthService` n'écrit et ne lit que par `(user_id, host_id, path)` ; le poste arrive **déjà vérifié possédé** par `GovernanceHostScope.require` dans `GovernanceHostController.map` ; l'index d'unicité porte `user_id` en tête |
| Plans / limites | non | aucun quota |
| Navigation / routing | non | aucune route |

---

## Contraintes de validation

| Champ | Contrainte | Motif |
|---|---|---|
| `path` | 512 caractères, aligné sur `governance_package_files.path` | même chemin, même borne |
| `recent` | 6 entrées | autant que de fichiers de carte livrés ; au-delà ce n'est plus un fait qu'on constate, c'est une liste |
| `facts`, `first_facts`, `last_gain` | entiers ≥ 0 | un compte de faits ne peut pas être négatif |

---

## Décisions prises en cours de dev

| # | Décision | Motif | Réversible ? |
|---|---|---|---|
| D1 | L'écriture vit dans une **classe séparée** (`GovernanceMapGrowthRecorder`) | Une annotation transactionnelle ne s'applique pas à un appel qu'un objet se fait à lui-même : le proxy n'est pas traversé. `REQUIRES_NEW` sur une méthode privée de la même classe aurait été **annoncé sans être obtenu** — et le `save` aurait rejoint la transaction `readOnly` du relevé. | oui |
| D2 | Une machine devenue **muette en chemin** n'écrit rien du tout, même pour les fichiers déjà lus | Un relevé partiel ferait dire « la carte a gagné » sur la moitié des fichiers et mentirait dès la lecture suivante. | oui |
| D3 | Le bloc est calculé sur les **lignes observées ce tour-ci**, pas sur toute la table | Un fichier retiré du paquet ne doit plus peser dans la phrase ; et le total reste cohérent avec les fichiers réellement lus. | oui |

---

## Hors périmètre

- **Afficher** ces gains : SF-93-03.
- Un **journal** de toutes les observations : une ligne par fichier suffit, et un journal grossirait
  sans fin pour une question dont la réponse tient en une phrase.
- Juger la **qualité** d'un fait — daté ? sourcé ? : F-95.
- Attribuer un gain à un **projet** ou à un tour : le delta observé ne le sait pas, et le prétendre
  serait faux.
