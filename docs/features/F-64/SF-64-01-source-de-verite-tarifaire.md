# Mini-spec — F-64 / SF-64-01 — Une seule grille tarifaire

---

## Identifiant

`F-64 / SF-64-01`

## Feature parente

`F-64` — Une seule grille tarifaire (`docs/PRODUCT_SPEC.md`)

## Statut

`ready`

## Date de création

2026-09-11

## Branche Git

`feat/SF-64-01-grille-tarifaire-unique`

---

## Objectif

> Établir `docs/TARIFS.md` comme **source de vérité unique** de la grille tarifaire (plans, montants,
> quotas, recharges, périodicité, essai), alignée sur ce que le **code** connaît, et faire pointer
> tous les autres documents vers elle au lieu de recopier des montants.

---

## Comportement attendu

### Cas nominal

Cette subfeature est **documentaire** : aucun code applicatif n'est modifié, aucun endpoint, aucune
table, aucune migration. Le « flux » est celui d'un lecteur qui cherche un prix.

1. Un lecteur (PO, commercial, développeur, agent) cherche un montant, un quota ou une périodicité.
2. Il ouvre `docs/TARIFS.md` — **le seul** document vivant qui porte des chiffres tarifaires.
3. Chaque ligne de la grille indique **d'où vient le chiffre** : clé de configuration du backend,
   produit Stripe, ou **`À CONFIRMER PAR LE PO`** quand aucune source ne tranche.
4. Tout autre document (`marketing.md`, `spec.md`, `PRODUCT_SPEC.md`, `PROJECT.md`,
   `STRATEGIE-TARIFAIRE.md`) ne contient **plus aucun montant** : il renvoie à `docs/TARIFS.md`.

### Cas d'erreur

Les « cas d'erreur » d'une subfeature documentaire sont les situations où la grille pourrait
**induire en erreur**. Chacune a un comportement imposé.

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Un montant est **introuvable** dans le dépôt (ni configuration, ni produit Stripe documenté) | La cellule porte `À CONFIRMER PAR LE PO` — **jamais** un chiffre inventé, ni un chiffre repris d'une des deux grilles en litige | — |
| Deux sources donnent des montants **contradictoires** | La grille retient la source **exécutable** (la configuration qui produit l'affichage réel), cite explicitement la source écartée et la marque comme périmée | — |
| Un chiffre **publié** contredit un chiffre **appliqué** (ex. durée d'essai) | La contradiction est **nommée** dans la grille et **enregistrée dans `docs/OPEN_QUESTIONS.md`** ; elle n'est pas tranchée ici | — |
| Un plan existe dans le code mais n'est **pas vendable** (pas de price ID) | Il figure dans une section « retirés / non vendables » avec la raison, pour qu'on ne le redécouvre pas dans six mois | — |
| Une future feature (F-63, F-65) changera la grille | La place est **réservée** (section explicite, marquée « réservé — ne rien y inscrire avant livraison »), sans anticiper aucune valeur | — |

---

## Critères d'acceptation

- [ ] `docs/TARIFS.md` existe et porte, en tête, la mention explicite qu'il est la **source de vérité
      tarifaire unique**, et la règle « aucun autre document ne recopie un montant ».
- [ ] La grille liste les **quatre plans du code** (`SOLO`, `PRO`, `GOLD`, `BYOK`) avec, pour chacun :
      mode fournisseur, prix mensuel affiché, prix annuel affiché, quota mensuel de tokens.
- [ ] Les montants et quotas cités correspondent **exactement** aux valeurs par défaut de
      `backend/src/main/resources/application.yml` (`app.billing.stripe.display-prices`,
      `yearly-display-prices`, `app.quota.plans`), vérifiées ligne à ligne.
- [ ] La grille liste les **deux recharges du code** (`DAY`, `STANDARD`) avec leur nombre de tokens
      crédités, repris de `TopUpCatalog`.
- [ ] La grille liste l'**option Atelier** (prix affiché, et le fait qu'elle n'apporte aucun quota).
- [ ] La grille liste l'**essai** (durée appliquée + tokens alloués) et **nomme** la contradiction
      avec la durée annoncée publiquement.
- [ ] Toute valeur absente du dépôt est marquée **`À CONFIRMER PAR LE PO`** — aucun montant nouveau
      n'apparaît nulle part dans le diff.
- [ ] Chaque ligne de la grille cite sa **source** (clé de configuration, produit Stripe, ou néant).
- [ ] Une section « plans et packs non vendables » explique le sort de `DAILY` (retiré, SF-09-04).
- [ ] Deux sections **réservées** existent pour **F-63** (décompte au coût réel) et **F-65**
      (supplément par poste), déclarées vides et non renseignables avant livraison.
- [ ] `docs/marketing.md` §4 ne contient plus aucun montant et renvoie à `docs/TARIFS.md`.
- [ ] `docs/spec.md` (§2 et §9) ne contient plus aucun montant et renvoie à `docs/TARIFS.md`.
- [ ] `docs/PROJECT.md` §11.10 renvoie à `docs/TARIFS.md`.
- [ ] `docs/STRATEGIE-TARIFAIRE.md` §5 constate que l'incohérence est levée et renvoie à la grille.
- [ ] `docs/OPEN_QUESTIONS.md` porte une question ouverte listant **tous** les points
      `À CONFIRMER PAR LE PO`.
- [ ] Un `grep` de montants en euros sur les documents **de référence** (`marketing.md`, `spec.md`,
      `PROJECT.md`, `PRODUCT_SPEC.md`) ne remonte plus aucun tarif. Les **notes datées d'arbitrage**
      (`STRATEGIE-TARIFAIRE.md`, ADR, `docs/features/**`) gardent leurs chiffres — les en priver les
      rendrait illisibles — mais portent un avertissement « ne fait pas autorité » et un renvoi.
- [ ] **Aucun fichier de `backend/`, `frontend/`, `k8s/` n'est modifié** — en particulier aucun price
      ID, aucune valeur Stripe.

---

## Périmètre

### Hors scope (explicite)

- **Décider d'un prix, d'un quota, d'une remise ou d'une durée d'essai** — c'est le PO. Cette
  subfeature constate, cite et signale ; elle ne tranche pas.
- **Modifier quoi que ce soit dans Stripe** (produit, prix, price ID) — interdit.
- **Modifier la configuration** `application.yml` : changer une valeur par défaut serait changer un
  prix. Même un commentaire de renvoi est écarté (voir « Notes et décisions », D2).
- **Corriger la durée d'essai** (5 jours appliqués vs 14 annoncés) : c'est un arbitrage commercial,
  enregistré en question ouverte.
- **F-63** (changement du décompte du quota) et **F-65** (supplément par poste) : la grille leur
  réserve la place, elle ne les anticipe pas.
- **Réécrire les archives** `docs/features/F-XX/**` : ce sont des documents datés qui rendent compte
  d'une décision à un instant donné ; les corriger a posteriori détruirait la trace.

---

## Valeurs initiales

Sans objet — aucune entité créée, aucun état initial de ressource modifié.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|---|---|---|---|---|---|
| Cellule « montant » de la grille | Oui | — | soit un montant **déjà présent dans le dépôt**, soit la chaîne `À CONFIRMER PAR LE PO` | — | euros, séparateur décimal virgule |
| Cellule « source » de la grille | Oui | — | clé de configuration (`app.…`), variable d'environnement (`STRIPE_…`), nom de produit Stripe, ou `aucune source` | — | — |
| Code de plan | Oui | — | `SOLO`, `PRO`, `GOLD`, `BYOK`, `DAILY` (retiré) — valeurs de `PlanCode` | Oui | majuscules |
| Code de recharge | Oui | — | `DAY`, `STANDARD` — valeurs de `TopUpCatalog` | Oui | majuscules |

Notes :
- **Aucun montant ne peut être introduit par cette subfeature.** Un chiffre qui n'existe nulle part
  dans le dépôt ne peut apparaître qu'en tant que `À CONFIRMER PAR LE PO`.
- Les montants d'affichage sont **cosmétiques** côté code (le débit réel est porté par le price
  Stripe) : la grille le dit, pour qu'on ne lise pas la configuration comme une facture.

---

## Technique

### Endpoint(s)

Aucun. Aucun contrat d'API n'est touché.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — aucune donnée, aucun schéma.

### Composants Angular (si applicable)

Aucun. `frontend/src/app/billing/` lit les montants depuis `GET /billing/plans` ; il ne code en dur
aucun prix (vérifié). Rien à aligner.

### Fichiers touchés

| Fichier | Opération |
|---|---|
| `docs/TARIFS.md` | **créé** — la grille |
| `docs/marketing.md` | §4 — montants remplacés par un renvoi |
| `docs/spec.md` | §2 et §9 — montants remplacés par un renvoi |
| `docs/PROJECT.md` | §11.10 — renvoi ajouté |
| `docs/STRATEGIE-TARIFAIRE.md` | §5 — incohérence constatée levée |
| `docs/OPEN_QUESTIONS.md` | OQ-16 ajoutée |
| `docs/PRODUCT_SPEC.md` | statut F-64 + entrée d'historique + renvoi |
| `docs/features/F-64/SF-64-01-source-de-verite-tarifaire.md` | cette mini-spec |

---

## Plan de test

Une subfeature documentaire ne se teste pas par `mvn test` : elle se teste par des **vérifications
exécutables sur le dépôt**, chacune reproductible en une commande.

### Tests unitaires (vérifications de contenu, une par affirmation de la grille)

- [ ] **T1** — chaque montant mensuel de la grille est retrouvé dans `application.yml`
      (`display-prices`).
- [ ] **T2** — chaque montant annuel de la grille est retrouvé dans `yearly-display-prices`.
- [ ] **T3** — chaque quota de la grille est retrouvé dans `app.quota.plans` / `trial-tokens`.
- [ ] **T4** — chaque code de plan de la grille existe dans `PlanCode` et `PlanCatalog`.
- [ ] **T5** — chaque code de recharge et son nombre de tokens correspond à `TopUpCatalog`.
- [ ] **T6** — l'option Atelier : prix et absence de quota vérifiés dans `application.yml`.
- [ ] **T7** — aucun montant de la grille n'est absent du dépôt : toute valeur non retrouvée en
      T1→T6 porte `À CONFIRMER PAR LE PO`.

### Tests d'intégration (cohérence inter-documents)

- [ ] **T8** — un `grep` de montants en euros sur les documents **de référence** (`marketing.md`,
      `spec.md`, `PROJECT.md`, `PRODUCT_SPEC.md`) ne remonte aucun tarif ; les notes datées
      (`STRATEGIE-TARIFAIRE.md`, `docs/features/**`) en gardent, avec l'avertissement idoine.
- [ ] **T9** — les anciennes valeurs en litige (29, 119, 15, 9, 49, 7) ont disparu de
      `docs/marketing.md` et `docs/spec.md`.
- [ ] **T10** — `docs/marketing.md`, `docs/spec.md`, `docs/PROJECT.md`, `docs/STRATEGIE-TARIFAIRE.md`
      et `docs/PRODUCT_SPEC.md` contiennent chacun au moins un renvoi vers `docs/TARIFS.md`.
- [ ] **T11** — `git diff --stat` ne montre **aucun** fichier hors `docs/`.

### Non-régression applicative

- [ ] **T12** — le diff ne touche ni `backend/`, ni `frontend/`, ni `k8s/` : le comportement de
      l'application est **inchangé par construction**, aucune suite de tests n'est donc à rejouer.
      (Vérification : `git diff --name-only origin/main` ne contient que des chemins `docs/`.)

### Isolation workspace / `user_id`

- [x] Non applicable — raison : aucun accès aux données, aucun endpoint, aucune requête. La
      subfeature ne produit que du texte versionné.

---

## Dépendances

### Subfeatures bloquantes

Aucune.

### Features en aval (à ne pas anticiper)

- **F-63** — changera le **décompte** du quota (un token de sortie pèse le sien). Ne change aucun
  montant affiché ; la grille lui réserve une section vide.
- **F-65** — ajoutera un **supplément par poste supplémentaire**, apportant sa part de quota. La
  grille pose dès maintenant ce que l'abonnement couvre (**un poste**) et réserve une section vide
  au supplément. Aucun montant n'y est écrit.

### Questions ouvertes impactées

- [ ] **OQ-07** (réglages Stripe) — non tranchée ici ; la grille s'appuie sur sa décision existante
      (price IDs externalisés, montants dans Stripe).
- [ ] **OQ-16** (créée par cette subfeature) — liste les montants et durées `À CONFIRMER PAR LE PO`.

---

## Notes et décisions

**D1 — La source de vérité est un document neuf, pas l'un des deux documents en litige.**
Promouvoir `marketing.md` ou `PRODUCT_SPEC.md` aurait fait du vainqueur un document à double office
(argumentaire *et* référence), et laissé penser que l'autre reste « presque » valable. Un fichier
dédié, `docs/TARIFS.md`, n'a qu'un métier : porter les chiffres. Les deux autres en deviennent
symétriquement des lecteurs.

**D2 — Le diff ne touche pas `application.yml`, même en commentaire.**
Un renvoi vers `docs/TARIFS.md` y aurait sa place. Écarté : toute modification de `backend/**` fait
de cette livraison documentaire une livraison applicative (build, suite complète, image à
reconstruire) pour un commentaire. Le lien est posé dans l'autre sens — la grille cite ses clés de
configuration, chemin de fichier compris — ce qui suffit à retrouver l'une depuis l'autre.

**D3 — La configuration fait foi contre les deux documents, et c'est un constat, pas un arbitrage.**
`display-prices` est ce que l'application **affiche réellement** à l'utilisateur aujourd'hui ; la
grille de `marketing.md` n'est appliquée nulle part. Retenir la configuration, ce n'est pas choisir
un prix : c'est écrire ce qui est facturé. Le PO reste libre de changer l'un ou l'autre — la grille
dit simplement lequel des deux est vrai aujourd'hui.

**D4 — Les montants d'affichage ne sont pas les prix réels, et la grille doit le dire.**
Le débit est porté par le price Stripe ; `display-prices` est cosmétique. Une grille qui présenterait
ces valeurs comme la facture mentirait à la première divergence. Chaque montant porte donc sa nature
(« montant affiché, défaut de configuration ») et l'avertissement qu'il doit **concorder** avec
Stripe — vérification qui appartient au PO, seul à voir le tableau de bord Stripe.

**D5 — La durée d'essai est signalée, pas corrigée.**
`app.billing.trial-days` vaut **5** ; la page d'accueil et `marketing.md` annoncent **14 jours**.
Les deux corrections possibles (aligner le code sur la promesse, ou la promesse sur le code) sont
des décisions commerciales de sens opposé. Hors périmètre : OQ-16.

**D6 — Les archives `docs/features/**` ne sont pas réécrites.**
Elles portent des montants (199 € dans F-28, 24 € dans F-43…) mais ce sont des comptes rendus datés.
Les corriger après coup reviendrait à falsifier la trace d'une décision. La règle « un seul document
porte les chiffres » vaut pour les documents **vivants**.
