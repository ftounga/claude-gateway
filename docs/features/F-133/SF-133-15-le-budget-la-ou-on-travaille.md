# Mini-spec — F-133 / SF-133-15 — Le budget de la semaine, là où l'on travaille

## Identifiant
`F-133 / SF-133-15` — feature parente `F-133`

## Objectif
Voir **où l'on en est de son budget** sans ouvrir la console d'administration, et **avant** de
déborder.

## Le défaut, mesuré
> *« On a déployé une feature sur le budget par semaine, je ne le vois pas sur la vue Forge du
> client ni dans le terminal. En fait je ne le vois nulle part. »*

Relevé en production le 2026-09-22 :

| | |
|---|---|
| Budgets posés | CAGIP **100 €**, défaut **100 €** |
| Dépense CAGIP sur 7 jours | 45,56 $ ≈ **41,92 €** (154 tours) |
| Part consommée | **42 %** |
| Seuil d'alerte | **80 %** |

**Rien n'est cassé** : le bandeau de la Forge (SF-133-12) ne s'affiche pas parce qu'il n'a rien à
signaler. Mais le budget n'existe que dans `/admin` → « Coût réel ». On a donc livré **l'alerte quand
ça déborde, sans le compteur quand tout va bien** : la moitié de la leçon de SF-133-12 — une
information qu'il faut aller chercher n'informe personne.

## Comportement attendu
1. Dans la **Forge**, sur le poste ouvert : *« Cette semaine : 42 € sur 100 € »*, avec une barre.
2. Dans le **terminal**, dans la barre du haut : la même chose, en compact — *« 42 € / 100 € »*.
3. **Rien** si aucun budget n'est applicable à ce client : on n'invente pas une part.
4. **Rien** pour qui n'est pas administrateur : le montant ne quitte pas le serveur (règle de F-133).
5. La barre passe à l'ambre au seuil d'approche, au rouge au dépassement — **les mêmes couleurs et le
   même seuil que l'alerte**, sans quoi l'écran dirait deux choses différentes du même fait.

| Cas d'erreur | Comportement |
|---|---|
| API en échec | **rien ne s'affiche**, aucune erreur — c'est un indicateur, pas un service |
| Poste sans dépense | *« 0 € sur 100 € »* : l'information est vraie, et utile |
| Poste inconnu du résumé | rien |

## Critères d'acceptation
- [ ] La Forge affiche dépense et budget de la semaine **du poste ouvert**.
- [ ] Le terminal affiche la même chose, en compact.
- [ ] Les deux lisent **la même source** (`GET /api/admin/cost/summary?period=week`) — aucun calcul côté navigateur, sinon deux écrans afficheraient deux chiffres du même fait.
- [ ] Sans budget applicable ⇒ **aucun affichage**.
- [ ] Non-administrateur ⇒ aucun affichage (403 traité en silence).
- [ ] Seuils et couleurs **identiques** à ceux de l'alerte (80 % / 100 %).
- [ ] Une erreur d'API n'affiche rien et ne casse pas l'écran.

## Hors scope
Le détail par tour (déjà livré, SF-133-02) · la modification du budget depuis ces écrans — elle reste
dans la console, c'est un geste rare · le mois (le budget de F-133 est **hebdomadaire**).

## Technique
| Fichier | Changement |
|---|---|
| **`WeeklyBudgetComponent`** *(nouveau)* | l'indicateur, en deux densités (`full` / `compact`) |
| **`weekly-budget.service.ts`** *(nouveau)* | lit le résumé une fois et le partage entre les écrans |
| `postes.component` | l'insère sur le poste ouvert |
| `atelier-terminal.component` | l'insère dans la barre |

Aucune table, aucune migration, **aucune route nouvelle**.

## Plan de test
- [ ] Affichage avec budget, absence sans budget, absence sur 403.
- [ ] Couleurs aux trois états (sous le seuil, approche, dépassement).
- [ ] Le service ne lit qu'une fois pour les deux écrans.
- [ ] Erreur d'API ⇒ rien, et l'écran tient.

## Préoccupations transversales
| Préoccupation | Cochée | Composants |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | la route part du JWT, filtre `user_id`, et ne porte aucun identifiant |
| **Plans / limites** | **oui** *(lecture)* | Le budget **n'arrête toujours rien** (SF-133-04) : cet indicateur informe, il ne gate pas. Aucun appel aux services de quota n'est touché. |
| **Navigation / routing** | **oui** *(deux écrans)* | Forge et terminal reçoivent un composant de plus ; aucune route ne change. Les tests des deux écrans sont complétés d'une doublure, comme pour SF-135-02. |
