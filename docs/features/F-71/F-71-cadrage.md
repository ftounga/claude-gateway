# Cadrage — F-71 — Le poste « Hébergé », et le dossier qu'on désigne sans le taper

> Deux manques du modèle *poste*, relevés en testant. Les deux sont **tranchés par le PO** ; ce
> cadrage ne rouvre rien, il découpe.

**Date** : 2026-09-12 · **Feature parente** : `F-71` (`docs/PRODUCT_SPEC.md`)

---

## Ce qui manque, dit simplement

**(1) Les projets sans machine n'ont nulle part où aller.** Un dépôt GitHub (`source = GIT`) ou une
archive importée (`source = ARCHIVE`) n'a pas de poste — le modèle l'accepte depuis F-48
(`workspaces.host_id` est nullable) — mais l'accueil de la Forge est **organisé par postes** depuis
F-68. Ces projets existent, sont facturés, portent des terminaux, et **n'apparaissent sur aucune
carte**.

**(2) Le dossier d'un nouveau projet se tape à la main.** Le dialogue d'appairage offre un champ
libre « Dossier du projet sous la racine ». Une faute de frappe crée un projet qui **n'échoue qu'au
premier usage**, quand plus personne ne fait le lien avec la frappe.

## Ce que le PO a tranché (repris tel quel, non rouvert)

| # | Décision |
|---|----------|
| D1 | Les projets sans machine sont regroupés sous un **poste virtuel « Hébergé »**, qui représente le **bac à sable** et non une machine. |
| D2 | Ce n'est **pas un vrai poste** : ni appairage, ni runner, ni suppression. |
| D3 | Il n'apparaît **que s'il contient quelque chose**. |
| D4 | **Aucune ligne en base** : c'est une **vue**, pas une entité. |
| D5 | Le dossier d'un nouveau projet se **désigne d'un clic** : le runner liste les sous-dossiers de la racine. |
| D6 | L'explorateur existe depuis SF-38-17 (`list_files`) — on le **réutilise**. |
| D7 | Runner **non connecté** : on le **dit clairement**, on n'offre pas un champ vide. |
| D8 | **Hors périmètre** : créer un dossier depuis l'application. |

## Découpage

| SF | Titre | Côté | Dépend de |
|----|-------|------|-----------|
| SF-71-01 | Le poste « Hébergé » — une vue, pas une ligne | backend | — |
| SF-71-02 | Les sous-dossiers d'un poste, listés par le runner | backend | — |
| SF-71-03 | La carte « Hébergé » et le dossier qu'on clique | frontend | SF-71-01, SF-71-02 |

Ordre de livraison : **backend d'abord** (SF-71-01 puis SF-71-02), frontend ensuite.

## Registres de couleur — ce qu'on ne touche pas

Trois registres cohabitent déjà et F-71 **n'en ajoute pas un quatrième** :

| Registre | Porte quoi | Où |
|---|---|---|
| Identité du poste (§9, F-49 / SF-49-03) | **quelle machine** | filet de carte + `app-host-badge` |
| Statut (§5) / mission (§10, F-60) | **où en est la mission** | `app-mission-badge`, pastilles `badge--*` |
| Signe de vie (§11, F-70) | **un onglet vit** | `app-live-badge`, `currentColor` |

Le poste « Hébergé » n'est pas une machine : il **ne reçoit aucune couleur d'identité** (§9 réserve
ces dix tons à l'identification d'une machine), aucune mission (il n'en a pas), et garde le gris
neutre déjà prévu au §5 pour « archivé / inactif ». **L'absence de couleur n'est pas un registre de
plus.**

## Arbitrages du cadrage

| # | Arbitrage | Pourquoi | Réversible |
|---|---|---|---|
| A1 | `id = null` + `virtual = true` dans la vue d'ensemble, plutôt qu'un UUID constant | Un UUID constant **ressemble** à une entité et finirait par être envoyé à un endpoint qui répondrait 404. Un `id` nul est inexploitable par construction. | oui |
| A2 | Les sous-dossiers sont **dérivés de `list_files`**, sans nouvel outil runner | D6. Un outil `list_dirs` obligerait chaque runner déjà installé à être mis à jour pour que l'écran marche — exactement la friction que F-48 a supprimée. | oui |
| A3 | L'explorateur **navigue** (on entre dans un sous-dossier) au lieu de n'offrir que le premier niveau | Un `~/dev/clients/EDENRED` est le cas réel du PO ; s'arrêter au premier niveau rendrait le champ libre indispensable, donc D5 caduque. Le confinement du runner sur `project` (SF-48-02) rend chaque niveau aussi sûr que la racine. | oui |
| A4 | Les dossiers **déjà ouverts** sont marqués et non proposés deux fois | C'est le défaut vécu par le PO (deux entités du même nom). Marquer ne coûte qu'une lecture déjà faite. | oui |
| A5 | Les dossiers **cachés** (`.git`, `.claude`…) sont écartés de la liste | Ce sont des dossiers d'outillage, jamais des projets ; les proposer ferait cliquer sur `.git`. Écartés **à l'écran**, pas par une garde — le runner reste seul juge de ce qu'il expose. | oui |
