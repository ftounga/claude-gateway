# Mini-spec — [F-29 / SF-06] Logo redessiné

---

## Identifiant

`F-29 / SF-06`

## Feature parente

`F-29` — Identité publique & conformité web

## Statut

`ready`

## Date de création

2026-08-23

## Branche Git

`feat/SF-29-06-logo-redessine`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Remplacer le logo recadré en urgence (SF-29-05) par un emblème **redessiné**, sans aucun texte, conforme à la charte et correctement cadré.

---

## Contexte

SF-29-05 a retiré en urgence le texte « CLAUDE PROXY / UNRESTRICTED AI. NO LIMITS. » gravé dans le logo, par un simple recadrage — solution correcte mais contrainte : cadrage serré, composition héritée d'une image conçue pour porter du texte sous l'emblème.

Le redessin, explicitement placé hors scope de SF-29-05 comme « souhaitable, non urgent, décision séparée », est ici réalisé : nouvel emblème généré à partir d'un prompt spécifiant la charte et l'interdiction absolue de tout texte.

---

## Comportement attendu

### Cas nominal

1. Le fichier `claude-portal-logo.png` porte le nouvel emblème : bouclier hexagonal, tête de profil, bulle de dialogue à trois points, étincelle, orbite fléchée, tracés de circuit.
2. **Aucun texte** dans l'image.
3. Palette conforme au design system : navy `#0B1020`, orange `#E07B39`, crème `#F5EFE3`.
4. Nom de fichier **inchangé** : aucune référence à modifier.
5. L'emblème reste identifiable en favicon (32 px).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Lettre ou pseudo-texte introduit par le générateur d'images | **Refusé** — contrôle visuel obligatoire ; c'est le mode de défaillance principal des générateurs, malgré la consigne |
| Emblème illisible en favicon | Contrôle par planche de rendu à 16/32/48 px avant intégration |
| Palette hors charte | Contrôle visuel contre `DESIGN_SYSTEM.md` |
| Poids excessif pour un `og:image` | Objectif ≤ 100 Ko |

---

## Critères d'acceptation

- [ ] **Aucun texte** dans l'image — vérifié visuellement sur le rendu final
- [ ] Palette conforme à la charte
- [ ] 512×512, poids ≤ 100 Ko
- [ ] Écart de quantification < 1 % (RMSE) par rapport au redimensionnement non quantifié
- [ ] Emblème identifiable à 32 px — vérifié sur planche de rendu
- [ ] Nom de fichier inchangé : les 6 références restent valides
- [ ] Build production OK, specs vertes
- [ ] Après déploiement : le logo servi est bien le nouveau

---

## Périmètre

### Hors scope

- Déclinaisons du logo (favicon multi-tailles, version monochrome, version horizontale, version pour fond clair)
- Format vectoriel (SVG) : le générateur produit du bitmap ; une vectorisation serait un travail distinct
- Modification des emplacements ou des dimensions d'affichage du logo dans l'application

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Texte | **Aucun**, y compris fragment partiel |
| Définition | 512×512 |
| Format | PNG (WebP écarté en SF-29-05 : moins bien pris en charge par les robots d'aperçu social) |
| Quantification | 256 couleurs — 128 ne gagne que 18 Ko en dégradant la fidélité |
| Nom de fichier | Inchangé |

---

## Technique

### Endpoint(s) / Tables / Migration

Aucun — remplacement d'un fichier binaire.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `frontend/public/claude-portal-logo.png` | **Remplacé** — emblème redessiné, 512×512, ~85 Ko |

Commande de production, tracée pour reproductibilité :

```
convert source.png -strip -resize 512x512 -colors 256 \
        -define png:compression-level=9 claude-portal-logo.png
```

---

## Plan de test

- [ ] **Inspection visuelle du rendu final** — seul contrôle capable de détecter un pseudo-texte
- [ ] **Planche de rendu 16/32/48 px** — lisibilité en favicon
- [ ] `identify` : 512×512, ≤ 100 Ko
- [ ] `compare -metric RMSE` < 1 %
- [ ] Non-régression : build production OK, specs vertes (aucun code ne change)
- [ ] Post-déploiement : le fichier servi est identique à la source

### Isolation utilisateur

- [x] **Non applicable** — fichier statique public.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | — |
| Contexte tenant | **Non** | — |
| Plans / limites | **Non** | — |
| Navigation / routing | **Non** | Nom de fichier inchangé : favicon, 3 `src` de la landing, `og:image` et `twitter:image` restent valides sans modification. |

---

## Dépendances

- `SF-29-05` — done (retrait d'urgence du texte, que cette subfeature finalise)

---

## Notes et décisions

- **Contrôle visuel maintenu comme critère bloquant** : c'est lui qui a écarté une variante de recadrage en SF-29-05, et c'est le seul capable de détecter un pseudo-texte produit par un générateur d'images. Aucun contrôle automatisé ne remplace ce coup d'œil.
- **256 couleurs plutôt que 128** : 85 Ko contre 67 Ko, pour une fidélité de 0,49 % contre 0,67 %. Les 18 Ko économisés ne justifient pas la perte sur un fichier servi en `og:image`.
- **Bitmap conservé** : une version vectorielle serait préférable à terme (netteté à toute taille, poids moindre) mais relève d'un travail de design distinct.
