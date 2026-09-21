# Mini-spec — F-139 / SF-139-01 — Le savoir qui vieillit se signale

## Identifiant
`F-139 / SF-139-01` — feature parente `F-139`

## Objectif
Qu'un fait d'infrastructure ancien soit présenté comme **à re-vérifier**, et non affirmé.

## Le défaut
La règle du paquet impose déjà à chaque ligne de carte sa date : `constaté le AAAA-MM-JJ`. **Rien ne
s'en sert.** Or une infrastructure bouge : un certificat expire, une version change, un droit est
retiré. Un fait de six mois rappelé comme un fait frais fait **répondre faux avec assurance** — le
pire mode d'échec possible chez un client, parce qu'il ne se voit pas.

Et le risque grandit avec le succès de F-136 : plus la carte sert, plus un fait périmé pèse.

## Comportement attendu
1. Un fait rappelé (F-137) dont la date de constat dépasse le **seuil** est marqué **à re-vérifier**.
2. Le bloc porte une consigne courte : *ce qui est marqué ainsi a vieilli ; vérifie-le avant de
   l'affirmer.*
3. Un fait **sans date** n'est pas marqué : on ne devine pas un âge.
4. Le seuil est **configurable** (`app.governance.map.fact-max-age-days`, défaut 120 jours).

| Cas d'erreur | Comportement |
|---|---|
| Date illisible ou impossible (`2026-13-45`) | fait **non marqué** — une date qu'on ne sait pas lire ne prouve pas la vieillesse |
| Date dans le futur | non marqué : c'est une faute de saisie, pas un fait périmé |
| Aucun fait périmé | **aucune consigne ajoutée** — rien ne doit s'écrire pour rien |

## Critères d'acceptation
- [ ] Un fait plus vieux que le seuil est marqué **à re-vérifier**.
- [ ] Un fait récent ne l'est pas.
- [ ] Un fait sans date ne l'est pas.
- [ ] Une date illisible ou future ne marque rien.
- [ ] La consigne n'apparaît **que** si au moins un fait est marqué.
- [ ] Le seuil est configurable.
- [ ] Le marquage ne change **pas** la consigne système (le cache reste intact).

## Hors scope
Marquer les faits dans le **sommaire** (F-136) — il ne porte volontairement aucune date, pour que le
préfixe reste stable · la re-vérification automatique · tout écran.

## Technique
| Classe | Changement |
|---|---|
| `HostFactLookup` | lit `constaté le AAAA-MM-JJ`, marque au-delà du seuil |
| `HostMapKnowledgeProvider` | fournit la date du jour et le seuil configuré |

Aucune table, aucune migration, aucune route.

## Plan de test
- [ ] Vieux ⇒ marqué ; récent ⇒ non marqué ; sans date ⇒ non marqué.
- [ ] Date illisible, date future ⇒ non marqué.
- [ ] Consigne présente seulement s'il y a un marquage.
- [ ] Le seuil est respecté à la limite exacte.

## Préoccupations transversales
| Préoccupation | Cochée | Composants |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | aucun changement d'accès aux données |
| **Plans / limites** | **oui** *(marginal)* | Le bloc de rappel grossit de quelques dizaines de caractères ; ses bornes (12 faits / 3 000 caractères) sont inchangées et continuent de s'appliquer. |
| Navigation / routing | non | — |
