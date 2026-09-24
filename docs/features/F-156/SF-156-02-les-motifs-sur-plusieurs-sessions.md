# Mini-spec — F-156 / SF-156-02 — Les motifs sur plusieurs sessions

## Identifiant
`F-156 / SF-156-02` — feature parente `F-156` — dépend de **SF-156-01** (la carte)

## Objectif
Passer de *« cette session a mal tourné »* à *« ceci nous coûte tant par semaine »* — et rendre le
**seuil des 20 % vérifiable** plutôt que déclaratif.

## Pourquoi l'accumulation change tout
> Cadrage F-156 : *« Un motif vu une fois est une anecdote ; vu dans huit sessions sur dix il est
> structurel. Le gain se chiffre alors en euros par semaine, pas en pourcentage d'une session. »*

Un pourcentage sur une session se discute : la session était peut-être atypique. **Des euros sur une
semaine, sur tous les projets, ne se discutent pas.** C'est le chiffre qui permet de dire au PO
« ceci vaut d'être développé » — ou de se taire.

## Ce que la période observe
1. **Le dénominateur d'abord** : combien de tours, combien de projets, **combien d'euros** sur la
   période. Sans lui, aucun gain n'est rapportable à quoi que ce soit.
2. **Pour chaque capacité de la carte** : combien de fois son **signal** a été vu, et dans **combien
   de projets sur combien d'observés**. C'est la **fréquence** — ce qui distingue l'anecdote du
   structurel.
3. **Le coût du gaspillage**, quand il est **calculable** : aujourd'hui le cache de prompt, dont le
   manque à gagner se déduit de la grille réelle. Pour les autres, l'observation porte la fréquence
   et le coût de la période, et **le verdict attend SF-156-03** — plutôt que d'inventer un chiffre.

**Cette honnêteté est délibérée.** Un gain estimé au jugé, c'est exactement ce que le seuil d'impact
interdit ; mieux vaut une observation sans montant qu'un montant sans fondement.

## Comportement attendu
1. L'enquête porte sur **une période** et **tous les projets** du compte — pas sur une session.
2. Elle rend, par capacité : le **nombre de déclenchements**, les **projets concernés sur observés**,
   et **le coût du gaspillage** quand il se calcule.
3. Une capacité dont le signal **n'a jamais été vu** est rendue avec **zéro** — c'est le cas le plus
   intéressant, pas une absence de résultat.
4. Une période **vide** rend une enquête vide, sans erreur.
5. La période est **bornée** : au-delà, on coupe et on le dit — une enquête n'est pas un export.

| Cas d'erreur | Comportement |
|---|---|
| Période inversée ou incomplète | enquête vide, **aucune lecture** |
| Période plus longue que la borne | ramenée à la borne, et l'enquête le dit |
| Coût de la période nul | les fréquences restent ; aucun montant n'est calculé |

## Critères d'acceptation
- [ ] L'enquête rend tours, projets, coût **de la période**.
- [ ] Chaque capacité de la carte reçoit une observation — **y compris celles jamais déclenchées**.
- [ ] Les signaux `TOOL` sont comptés dans le journal d'outils, les `USAGE` dans les tours, les
      `TABLE` sont **déclarés non mesurables ici** et renvoyés à SF-156-03.
- [ ] Le **coût hebdomadaire** du cache froid est calculé sur la **grille réelle**, sur toute la
      période.
- [ ] Période vide ou inversée → enquête vide, sans exception.
- [ ] La période est **bornée** (défaut **31 jours**), et le dire fait partie du résultat.
- [ ] **ISOLATION** : toute lecture filtre `user_id`.

## Hors scope
Le **verdict** absente/dormante (**SF-156-03**) · la **parité** (**SF-156-04**) · l'**écran**
(**SF-156-05**) · toute lecture du code · tout appel modèle.

## Technique
| Élément | Changement |
|---|---|
| `ProductSurvey` (record) | l'enquête : dénominateur + observations |
| `CapabilityObservation` (record) | par capacité : déclenchements, projets, coût calculé ou non |
| `ProductSurveyService` | l'agrégation sur la période |
| `UsageTurnRepository` / `RunnerAuditRepository` | lecture **par compte** sur une période |

**Aucune migration, aucun appel fournisseur.**

## Plan de test
- [ ] Dénominateur : tours, projets distincts, coût.
- [ ] Signal `TOOL` compté ; capacité jamais déclenchée → zéro, et **présente** dans le résultat.
- [ ] Signal `USAGE` (cache) : coût du manque à gagner calculé sur la grille réelle.
- [ ] Signal `TABLE` : marqué non mesurable ici, sans montant inventé.
- [ ] Projets concernés / observés corrects quand deux projets diffèrent.
- [ ] Période vide, inversée → enquête vide et **aucune lecture**.
- [ ] Période trop longue → ramenée à la borne, et le résultat le dit.
- [ ] **ISOLATION** : les lectures portent `user_id`.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun endpoint ici — l'écran et sa garde arrivent en SF-156-05 |
| **Contexte tenant** | **oui** | `ProductSurveyService` lit `UsageTurnRepository` et `RunnerAuditRepository` **par `user_id`** sur une période. La lecture est **volontairement transverse aux projets** — c'est son objet — mais jamais aux comptes. Aucun identifiant n'est accepté d'un appelant client. |
| Plans / limites | non | aucun appel fournisseur |
| Navigation / routing | non | aucune route |
