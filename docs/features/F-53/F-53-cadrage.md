# Cadrage — F-53 — Guide d'accueil

> Cadrage de la feature `F-53` (`docs/PRODUCT_SPEC.md`). Écrit le 2026-09-10, après le merge de F-48
> dont il décrit le parcours.

---

## 1. Ce que la feature vise

Le premier succès du produit n'est pas « créer un projet » : c'est **voir une commande s'exécuter
sur sa propre machine**. C'est ce parcours-là qui a coûté deux jours au premier client, et c'est
lui — et rien d'autre — que le guide accompagne.

Trois étapes, dans cet ordre, parce qu'aucune ne vaut sans la précédente :

1. **Créer un projet** — un dossier de la machine, déclaré dans l'Atelier ;
2. **Connecter son poste** — l'appairage unique de F-48, qui traverse le dialogue de F-45
   (vérification réseau *avant* l'installation, fiche pour la DSI, parcours guidé) ;
3. **Voir une commande aboutir** — un tour complet exécuté sur le poste, pas sur le bac à sable.

## 2. Ce que le guide ne fait pas

- Il **ne redit pas** F-45. Le diagnostic réseau, le `407`, la fiche DSI, la commande de lancement :
  tout cela vit dans le dialogue d'appairage, et le guide s'y **rend** au lieu de le recopier.
  Le guide est un **fil**, pas un second manuel.
- Il ne visite pas le produit. Il **s'arrête au premier résultat** (`PRODUCT_SPEC`, hors périmètre).
- Il ne persiste rien côté serveur : l'état est **local** (`localStorage`), comme F-67 de legalcase.

## 3. Décisions de cadrage

| # | Sujet | Décision | Réversible |
|---|---|---|---|
| 1 | Forme | **Panneau superposé non modal**, pas un `MatDialog` : les étapes 1 et 2 s'accomplissent dans des dialogues, un guide modal se fermerait à chaque geste | oui |
| 2 | Place | **En haut à droite, sous le bandeau** — le coin bas-droit est pris par la bulle d'aide (F-54) et le bas de l'écran par la zone de saisie du terminal, qu'il ne faut surtout pas couvrir à l'étape 3 | oui |
| 3 | Avancement | Coché sur des **signaux réels** (projet existant, poste connecté, tour abouti), jamais sur un clic « suivant » : un guide qui se coche tout seul mentirait sur le premier succès | oui |
| 4 | Abandon | **À tout moment**, d'un seul geste, et le guide ne revient pas de lui-même | oui |
| 5 | Mémoire | `localStorage`, une clé, aucune donnée sensible — ni identifiant de poste, ni jeton | oui |
| 6 | Portée | Le guide vit dans l'**Atelier** uniquement : c'est le seul écran où ses trois étapes existent | oui |

## 4. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-53-01 | Le parcours du premier succès | Service de mémoire locale, panneau superposé à trois étapes, avancement sur signaux réels, abandon, conclusion |
| SF-53-02 | Reprendre le guide, et voir la commande aboutir | Reprise du guide après abandon depuis l'Atelier, première commande proposée et insérée en un clic, échec de tour qui dit quoi faire |

## 5. Hors périmètre

- Toute persistance serveur de l'avancement (une autre machine ne verra pas le guide déjà fait).
- Une visite guidée des autres écrans (Chat, Bibliothèque, Facturation).
- La vue d'ensemble des postes (F-49) et la gouvernance (F-50 → F-52).
