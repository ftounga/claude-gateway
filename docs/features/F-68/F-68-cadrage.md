# Cadrage — F-68 · La Forge s'ouvre sur les missions

> Dérivé de la ligne **F-68** de `docs/PRODUCT_SPEC.md` (cadrée avec le PO le 2026-09-12). Ce
> document n'ouvre **aucun périmètre nouveau** : il tranche les pièges de navigation et il découpe.

---

## 1. Ce qui manque

Le PO a posé la question lui-même, en testant : *« Forge et Postes, c'est quoi la différence ? »*.

Deux onglets listaient des projets. L'écran des postes s'en excusait déjà, dans son propre message
de refus : *« La vue des postes fait partie de la Forge »*. Quand un écran doit expliquer qu'il
appartient à l'autre onglet, c'est l'onglet qui est de trop.

F-68 supprime la question : **la vue des postes devient la page d'accueil de la Forge**, et
l'onglet séparé disparaît. On arrive sur ses **missions** — chacune avec sa couleur client, son
état, ses projets — et l'on **entre dans un projet** pour travailler. Le parcours suit enfin la
façon de penser d'un consultant : *chez qui je vais, puis sur quoi je travaille*.

## 2. Le piège, et comment on l'évite

Supprimer un onglet, c'est retirer un repère. Un bouton « retour » ne le remplace pas : il dit
*d'où l'on vient*, jamais *où l'on est*. D'où le **fil d'Ariane** — « Forge › CAGIP › mon-projet » —
qui dit les deux d'un coup d'œil, **et chez qui**.

**Quatre règles, non négociables, opposables en review :**

| # | Règle | Conséquence concrète |
|---|---|---|
| R1 | **Aucun lien partagé ne se brise** | `/atelier`, `/atelier/:id`, `/atelier/:id/fichiers` restent **mot pour mot** ceux de F-58. `/postes` continue de répondre : il **redirige** vers la nouvelle adresse d'accueil. |
| R2 | **On ne refait pas l'écran** | Ce que la vue des postes affiche est fixé par F-49 et SF-49-03 : identité du client, état, projets. F-68 **réorganise la navigation**, ne touche ni aux cartes, ni au tri, ni aux données lues. |
| R3 | **Pas de quatrième registre de couleur** | Trois cohabitent déjà : l'identité du poste (§9), le statut de mission (§5), la charte générale. Le fil d'Ariane n'en introduit **aucun** : texte secondaire, accent de la charte au survol, et il **réemploie** la pastille du poste — il ne la redessine pas. |
| R4 | **Chaque niveau est cliquable** | Décision explicite du PO. Le dernier niveau — la page courante — reste un lien, marqué `aria-current="page"` : cliquer dessus ne mène nulle part ailleurs, ce qui est exactement le comportement attendu. |

## 3. Ce que « page d'accueil de la Forge » veut dire

- L'entrée **Forge** de la barre de navigation mène désormais à la **vue des missions**.
- L'entrée **Postes** **disparaît** de la barre — elle ne se dédouble plus.
- L'écran des projets (`/atelier`) **reste** : c'est là qu'on crée un projet et qu'on ouvre un
  dépôt. Il n'est simplement plus la **porte d'entrée**, il est une étape du chemin.
- Le terminal d'un projet (`/atelier/:id`) reste l'écran de travail, et **rien** n'y est retiré :
  pastille du poste (SF-49-03), état de mission (F-60), moteur d'exécution, dossier local.

## 4. Adresse d'accueil — l'arbitrage

Deux options tenaient :

| Option | Pour | Contre |
|---|---|---|
| Garder `/postes` comme adresse canonique | Zéro changement d'URL | L'adresse dit « postes » alors que l'onglet du même nom n'existe plus — la confusion que F-68 supprime resterait dans la barre d'adresse |
| **Retenue** — `/forge` canonique, `/postes` **redirige** | L'URL dit ce que la page est, et le fil d'Ariane la nomme pareil | Une redirection de plus à maintenir (une ligne) |

**Réversible** : la redirection s'inverse en une ligne si le PO préfère l'autre nom.

## 5. Découpage

| Subfeature | Objet | Périmètre |
|---|---|---|
| **SF-68-01** | La Forge s'ouvre sur les missions, et le fil d'Ariane dit chez qui | Frontend seul |

**Aucune subfeature backend** : aucune donnée nouvelle, aucun endpoint, aucune table, aucune
migration. Le fil d'Ariane se construit **entièrement** avec ce que les lectures existantes rendent
déjà (`hostName`, `hostId`, nom du projet) — en ajouter serait réimplémenter ce qui existe.

## 6. Hors périmètre

- **Changer ce que la vue affiche** (F-49, SF-49-03) — cartes, tri, données lues : intouchés.
- **Renommer `/atelier`** (décision F-58) — interdit.
- Supprimer un projet ou un poste (F-69), plafond de terminaux (F-70), poste « Hébergé » (F-71).
- Toucher au choix d'écran d'accueil **de l'application** : `/` et la redirection après connexion
  ne bougent pas. F-68 parle de l'accueil **de la Forge**, pas de celui du produit.
