# Mini-spec — [F-29 / SF-05] Logo sans mention « Proxy » et allégé

---

## Identifiant

`F-29 / SF-05`

## Feature parente

`F-29` — Identité publique & conformité web

## Statut

`ready`

## Date de création

2026-08-23

## Branche Git

`feat/SF-29-05-logo-sans-mention-proxy`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Retirer du fichier de logo le texte **« CLAUDE PROXY »** et **« UNRESTRICTED AI. NO LIMITS. »** qu'il contient en dur, en le recadrant sur son emblème, et réduire son poids de 1,05 Mo à environ 70 Ko.

---

## Contexte

Découvert le 2026-08-23 en inspectant le fichier pour l'optimiser : **le logo contient en dur le texte que F-29 a passé quatre subfeatures à éliminer du site.**

Ce fichier est servi comme **favicon**, à **trois emplacements de la landing**, comme **`og:image`** (donc dans tout aperçu de lien partagé), et sur le **site vitrine corporate**. Un analyste instruisant une demande de reclassification ouvre le site et voit « CLAUDE PROXY — UNRESTRICTED AI. NO LIMITS. ». Les moteurs de classification modernes appliquent par ailleurs de la reconnaissance de texte aux images.

L'urgence est réelle : les demandes de reclassification sont en cours de soumission.

**Correctif retenu** : recadrer sur l'emblème (hexagone, tête, bulle de dialogue, orbite), qui ne contient aucun texte. L'identité visuelle est conservée. Un logo redessiné reste souhaitable à terme, mais ne doit pas retarder le retrait du texte.

---

## Comportement attendu

### Cas nominal

1. Le fichier `claude-portal-logo.png` ne contient **plus aucun texte**.
2. L'emblème reste reconnaissable et conforme à la charte (navy, orange, crème).
3. Le poids passe de **1 098 947 o** à environ **70 000 o**, la définition de 1254×1254 à 512×512.
4. Tous les usages continuent de fonctionner sans modification de code : le **nom de fichier est inchangé**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Recadrage laissant un fragment de lettre | **Refusé** — une variante à 860×860+200+55 laissait apparaître le haut de « LAUDE PROX » et a été écartée. Contrôle visuel obligatoire du fichier final. |
| Définition trop faible pour l'affichage hero | 512×512 couvre le plus grand usage (~300 px) avec marge |
| Cache navigateur servant l'ancien logo | Nom de fichier inchangé, donc cache possible côté visiteur ; sans gravité et transitoire |
| Dégradation visible à la quantification | Écart mesuré à 0,6 % (RMSE) sur 256 couleurs — imperceptible |

---

## Critères d'acceptation

- [ ] Le fichier ne contient **aucun texte** — vérifié par inspection visuelle du rendu final
- [ ] Poids ≤ 100 Ko (objectif ~70 Ko), définition 512×512
- [ ] Nom de fichier **inchangé** (`claude-portal-logo.png`) : aucun code à modifier
- [ ] L'emblème reste conforme à la charte
- [ ] Le même fichier est mis à jour dans la ConfigMap du site vitrine **si le logo y est utilisé** — à vérifier
- [ ] Build production OK, specs vertes
- [ ] Après déploiement : le logo servi en production ne contient plus de texte

---

## Périmètre

### Hors scope (explicite)

- **Redessin complet du logo** : un logo vectoriel propre, redessiné, reste souhaitable. Il fera l'objet d'une décision séparée et ne doit pas retarder le retrait du texte.
- Changement de nom de fichier, de format (WebP écarté : moins bien pris en charge par les robots d'aperçu social), ou d'identité visuelle.
- Déclinaisons (favicon multi-tailles, version monochrome, version horizontale).

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Texte dans l'image | **Aucun**, y compris fragment partiel de lettre |
| Définition | 512×512 (couvre le plus grand usage, ~300 px, avec marge) |
| Format | PNG conservé |
| Nom de fichier | Inchangé |
| Fidélité | Écart RMSE < 1 % par rapport au recadrage non quantifié |

---

## Technique

### Endpoint(s) / Tables / Migration

Aucun — remplacement d'un fichier binaire.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `frontend/public/claude-portal-logo.png` | **Remplacé** — recadré sur l'emblème, 512×512, ~70 Ko |

Commande de production du fichier, tracée pour reproductibilité :

```
convert claude-portal-logo.png -crop 760x760+250+95 +repage \
        -resize 512x512 -strip -colors 256 \
        -define png:compression-level=9 sortie.png
```

---

## Plan de test

- [ ] **Inspection visuelle du fichier final** — seul contrôle capable de détecter un fragment de lettre résiduel ; c'est ainsi que la variante 860×860 a été écartée
- [ ] `identify` : 512×512, poids ≤ 100 Ko
- [ ] `compare -metric RMSE` : écart < 1 %
- [ ] Non-régression : build production OK, specs vertes (aucun code ne change, le nom de fichier est conservé)
- [ ] Post-déploiement : le logo servi ne contient plus de texte

### Isolation utilisateur

- [x] **Non applicable** — remplacement d'un fichier statique public.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | — |
| Contexte tenant | **Non** | — |
| Plans / limites | **Non** | — |
| Navigation / routing | **Non** | Nom de fichier inchangé : aucune référence à mettre à jour (`index.html` favicon, 3 `src` de la landing, `og:image`, `twitter:image`). |

---

## Dépendances

- `SF-29-01` — done (le fichier a été renommé à cette occasion ; son contenu n'avait pas été inspecté)

---

## Notes et décisions

- **Ce que cette subfeature révèle** : SF-29-01 a renommé le fichier sans en examiner le contenu. Le texte gravé dans l'image a survécu à quatre subfeatures consacrées précisément à l'éliminer. Une chaîne de vérifications portant sur le code ne voit pas ce que contient un binaire.
- **Recadrage plutôt que redessin** : le retrait du texte est urgent (soumissions de reclassification en cours), un redessin ne l'est pas. Les deux ne s'excluent pas.
- **WebP écarté** malgré un poids inférieur (14 Ko) : moins bien pris en charge par les robots d'aperçu social, or le fichier sert d'`og:image`.
