# Cadrage — F-72 — Connecter un poste, puis y ajouter des projets

> Le défaut vécu par le PO en testant. Les décisions sont **déjà prises** et portées par la ligne
> F-72 de `docs/PRODUCT_SPEC.md` ; ce cadrage ne les rouvre pas, il découpe.

**Date** : 2026-09-12 · **Feature parente** : `F-72` (`docs/PRODUCT_SPEC.md`)

---

## Le défaut, dit simplement

Le PO crée « EDENRED » en pensant déclarer un **client**. L'écran crée un **projet**
(`createWorkspace`). Le dialogue d'appairage lui demande alors à quel **poste** ce projet
appartient — aucun n'existe, il doit en créer un et **retaper le même nom**. Puis on lui demande
encore un **dossier**. Trois questions pour une seule intention, **deux entités nommées EDENRED**,
et un utilisateur perdu.

**Le modèle est juste, c'est le parcours qui ne l'a pas suivi.** F-48 a fait du **poste** l'unité ;
la **création**, elle, est restée celle d'avant — on part du projet, et le poste est ramassé en
chemin dans la fenêtre d'appairage.

## Ce que le PO a tranché (repris tel quel, non rouvert)

| # | Décision |
|---|----------|
| D1 | **Deux gestes, dans cet ordre** : (1) « Connecter un poste », (2) « Ajouter un projet » sur la carte du poste. |
| D2 | À la fin du geste (1), **aucun projet n'existe — et c'est normal** : on vient de brancher une machine. |
| D3 | Le geste (2) se répète **autant de fois qu'on veut, sans jamais réappairer**. C'est tout le bénéfice de F-48. |
| D4 | Le bouton « Nouveau projet » **à la racine disparaît**. Il ne reste que « Connecter un poste » et, sur chaque carte, « Ajouter un projet ». |
| D5 | **Le nom n'est demandé qu'une fois** — à la connexion du poste. Le projet prend le nom de son dossier. |
| D6 | Les **dépôts GitHub** et les **archives** n'ont pas de machine : ils passent par le poste « Hébergé » (F-71). |
| D7 | **Découverte sans création** : la carte d'un poste montre les dossiers de la racine **encore non ouverts**, en retrait, avec un clic pour les activer. |
| D8 | Cette liste **écarte le bruit** (les vingt motifs de SF-38-21 et `.runnerignore`) et se **tronque en le disant** au-delà d'un seuil. |
| D9 | **Aucun enjeu financier** dans D7 : c'est le **poste** qui est facturé (F-65), pas les projets. Le seul critère est la **lisibilité**. |
| D10 | **Hors périmètre** : reprendre automatiquement les projets créés par l'ancien parcours — ce sont des essais, ils se suppriment (F-69). |

## Découpage

| SF | Titre | Côté | Dépend de |
|----|-------|------|-----------|
| SF-72-01 | Ouvrir un projet sur un dossier du poste, en un seul geste | backend | SF-71-02 (livrée) |
| SF-72-02 | « Connecter un poste » — le parcours qui part de la machine | frontend | — |
| SF-72-03 | « Ajouter un projet », et les dossiers qu'on n'a pas encore ouverts | frontend | SF-72-01 |
| SF-72-04 | « Nouveau projet » disparaît de la racine ; GitHub et archives passent par « Hébergé » | frontend | SF-72-02, SF-72-03 |

Ordre de livraison : **backend d'abord** (SF-72-01), puis le frontend dans l'ordre 02 → 03 → 04.
SF-72-04 vient en dernier : retirer la seule porte de création avant d'avoir ouvert les deux autres
laisserait l'application sans moyen de créer quoi que ce soit.

## Ce qui existe déjà et qu'on ne réécrit pas

| Brique | D'où elle vient | Ce que F-72 en fait |
|---|---|---|
| `GET /runner-hosts/{id}/folders` (sous-dossiers, `used`, `truncated`) | SF-71-02 | **réutilisé tel quel** — c'est la source des deux écrans |
| Exclusion du bruit (`.runnerignore`, 20 motifs) | SF-38-21, appliquée **par le runner** | rien à faire : ce qui est exclu ne quitte jamais la machine |
| Dialogue de mise en service (réseau, code, binaire, commande) | F-38, F-45, F-46, F-55 | **un mode de plus**, pas un second dialogue |
| Carte de poste, fil d'Ariane, suppression, signe de vie | F-49, F-68, F-69, F-70 | inchangés |
| Poste « Hébergé » (vue, pas ligne) | F-71 | reçoit les deux gestes sans machine |

## Registres de couleur — ce qu'on ne touche pas

Trois registres cohabitent déjà et F-72 **n'en ajoute pas un quatrième** :

| Registre | Porte quoi | Où |
|---|---|---|
| Identité du poste (§9, F-49 / SF-49-03) | **quelle machine** | filet de carte + `app-host-badge` |
| Statut (§5) / mission (§10, F-60) | **où en est la mission** | `app-mission-badge`, pastilles `badge--*` |
| Signe de vie (§11, F-70) | **un onglet vit** | `app-live-badge`, `currentColor` |

Les dossiers **non encore ouverts** de D7 sont un **quatrième objet**, pas un quatrième registre :
ils se distinguent par le **retrait** et par le gris « inactif » déjà prévu au §5 — jamais par une
couleur nouvelle, jamais par un ton d'identité qu'ils n'ont pas.

## Arbitrages du cadrage

| # | Question | Décision | Réversible |
|---|----------|----------|------------|
| A1 | Les dossiers non ouverts sont-ils relus à chaque rafraîchissement de l'accueil (15 s) ? | **Non** : une fois par poste au chargement, et sur demande explicite. Un sondage de 15 s ferait lire la machine du client 240 fois par heure pour une liste qui ne bouge pas. | oui |
| A2 | Où vivent « dépôt GitHub » et « archive » quand la carte « Hébergé » est vide (elle n'est alors pas rendue, SF-71-01 D3) ? | La carte « Hébergé » est **toujours présente sur l'accueil**, vide ou non, puisqu'elle porte désormais deux gestes. La gateway n'est pas touchée : c'est l'écran qui complète. | oui |
| A3 | Le nom du projet créé sur un dossier est-il demandé ? | **Non** (D5) : c'est le **nom du dossier**. À la racine, le nom du poste. Renommer reste possible (F-28 / SF-28-16). | oui |
