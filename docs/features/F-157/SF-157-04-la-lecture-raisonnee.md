# Mini-spec — F-157 / SF-157-04 — La lecture raisonnée

## Identifiant
`F-157 / SF-157-04` — feature parente `F-157` — dépend de **SF-157-01** → **03**

## Objectif
Répondre à la question que **ni les mesures ni les témoins** ne peuvent trancher :
*« qu'est-ce qui empêche cette capacité de se déclencher, et quelle optimisation n'a pas été faite
ici ? »*

## La demande
> PO : *« Si ça se trouve, tu as besoin de lire le code pour savoir quelle optimisation n'a pas été
> faite. »*

Une optimisation absente **ne laisse aucune trace** — c'est son absence qu'il faut voir. Aucun
compteur ne la révèle, aucun témoin non plus : un témoin prouve qu'un appel **est là**, pas qu'un
meilleur chemin **manque**.

## La décision : on relaie, on n'analyse pas
Claude sait lire du code. Écrire un analyseur statique maison serait **réimplémenter une capacité
que le fournisseur fournit** (`PROJECT.md` §3.3) — et le résultat serait plus faible. On **relaie**,
à travers l'interface abstraite `AIProvider` : aucune dépendance directe à Anthropic.

## Trois garde-fous, non négociables
1. **C'est une hypothèse, jamais un verdict.** Le résultat est rendu comme tel, et l'écran le dira.
   *Une hypothèse qui se déguise en constat est pire qu'un silence* : on développerait sur une
   supposition.
2. **Le coût est annoncé avant et mesuré après.** F-156 ne coûtait rien ; ceci coûte. Le taire ferait
   exactement ce que le bilan reproche aux sessions — et cette feature perdrait le droit de donner
   des leçons.
3. **Une capacité à la fois, à la demande.** Pas de lecture en masse : on lit ce qu'on a décidé de
   comprendre.

## Comportement attendu
1. On lit **les fichiers déclarés** de **cette** capacité, déjà chargés par SF-157-02 — aucune
   nouvelle lecture, aucun fichier hors carte.
2. La question posée est **fixe et bornée** : elle nomme la capacité, sa condition d'activation, ce
   qu'elle évite, et ce que le diagnostic a déjà constaté. Une question stable rend deux lectures
   comparables.
3. Le contenu envoyé est **borné** : un plafond de caractères, dépassement **tronqué et dit** — un
   fichier coupé sans le dire ferait raisonner sur un extrait qu'on croit complet.
4. La réponse est rendue avec **ce qu'elle a coûté** : jetons d'entrée, de sortie, et le modèle.
5. **Aucune écriture** : ni dans le dépôt, ni dans la spec, ni en base.
6. Un échec fournisseur **n'empêche rien** : l'hypothèse manque, le diagnostic reste.

| Cas d'erreur | Comportement |
|---|---|
| Aucun fichier lu pour cette capacité | refus **nommé**, aucun appel fournisseur — donc **zéro coût** |
| Fournisseur indisponible | échec **nommé**, aucun constat modifié |
| Réponse vide | traitée comme une absence d'hypothèse, pas comme une hypothèse vide |

## Critères d'acceptation
- [ ] L'appel passe par **`AIProvider`**, jamais par un client Anthropic direct.
- [ ] Seuls les fichiers **de cette capacité**, **déjà lus**, sont envoyés.
- [ ] Le contenu est **borné** ; une troncature est **dite dans le texte envoyé**.
- [ ] La réponse porte **le coût réel** (jetons d'entrée, de sortie, modèle).
- [ ] Sans fichier lisible → **aucun appel fournisseur**, et le refus le dit.
- [ ] Panne fournisseur → échec nommé, **aucun constat modifié**.
- [ ] Le résultat est marqué **hypothèse**, jamais verdict.
- [ ] **ISOLATION** : aucune lecture nouvelle ; on ne reçoit que ce que SF-157-02 a lu.

## Hors scope
L'**écran** (**SF-157-05**) · la persistance des hypothèses · le déclenchement automatique · toute
modification du code.

## Technique
| Élément | Changement |
|---|---|
| `SourceHypothesis` (record) | le texte, le coût, le modèle, et le fait que c'est une hypothèse |
| `ReasonedReader` | la question fixe, les bornes, l'appel `AIProvider`, le coût |
| `DiagnosticProperties` | modèle et plafonds (défauts explicites) |

**Aucune migration, aucune route.**

## Plan de test
- [ ] Appel nominal : question qui nomme la capacité, sa condition, le constat ; coût rendu.
- [ ] Seuls les fichiers de la capacité sont envoyés ; jamais ceux d'une autre.
- [ ] Troncature : contenu coupé **et dit** dans le message.
- [ ] Aucun fichier lu → **aucun appel fournisseur**.
- [ ] Panne fournisseur → échec nommé, sans exception qui remonte.
- [ ] Réponse vide → pas d'hypothèse.
- [ ] Le résultat se présente comme une **hypothèse**.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun endpoint ici |
| **Contexte tenant** | **oui** | **aucune lecture nouvelle** : `ReasonedReader` ne reçoit que les fichiers déjà lus par `SourceReader` sous `requireOwned`. Aucun identifiant ne circule vers le fournisseur. |
| **Plans / limites** | **oui** | **premier appel fournisseur du diagnostic.** Le coût est **rendu** à l'appelant (jetons, modèle) pour que SF-157-05 l'affiche ; le décompte de quota reste celui du chemin d'appel existant. Aucune garde nouvelle : la route sera **admin** (SF-157-05). |
| Navigation / routing | non | aucune route |
