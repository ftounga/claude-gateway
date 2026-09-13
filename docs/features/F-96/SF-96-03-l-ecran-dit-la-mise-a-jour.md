# Mini-spec — F-96 / SF-96-03 — L'écran dit qu'une mise à jour attend, et ce qu'elle a fait

## Identifiant

`F-96 / SF-96-03`

## Feature parente

`F-96` — La gouvernance se met à jour

## Statut

`ready`

## Date de création

2026-09-13

## Branche Git

`feat/SF-96-03-l-ecran-de-mise-a-jour`

---

## Objectif

Faire dire à l'écran de gouvernance **qu'une mise à jour attend** — sinon personne n'ira voir — et
faire dire au plan de dépôt **ce qui a été mis à jour, ce qui était déjà bon, et ce qui a été
conservé parce qu'il avait été modifié localement**.

---

## Le défaut réparé

Deux moitiés inutiles l'une sans l'autre :

1. **Rien ne signale qu'un paquet a évolué.** La seule trace est une mention grise
   (« · une version plus récente existe ») dans la carte du poste **déjà ouvert**. Un utilisateur
   qui a trois postes n'a aucune raison d'ouvrir les deux autres : le geste « appliquer » existe et
   n'est jamais fait. Et **rien ne se met à jour tout seul** — c'est la règle, donc le signal est la
   seule chose qui déclenche le geste.
2. **Le plan de dépôt ne distingue pas deux conservations.** *« 3 laissés tels quels »* mélange
   « c'était déjà bon » et « je l'ai conservé parce que tu l'avais modifié ». Sans la distinction,
   **on ne sait jamais si sa correction est arrivée** — c'est l'exigence qui fait la valeur de la
   feature.

---

## Comportement attendu

### Cas nominal — le signal

- **Le sélecteur de poste** affiche, pour chaque poste concerné, une pastille « mise à jour » — le
  `outdated` rendu par `GET /governance/hosts` (SF-96-02), aucun appel machine.
- **La carte du poste** ouvre, quand `outdated > 0` sur au moins un paquet actif, un **bandeau**
  d'appel à l'action : « Une version plus récente de N paquet(s) existe. Rien n'a été écrit sur
  cette machine : cliquez **Appliquer** pour la déposer. » Le bandeau porte le ton d'information du
  design system (`--color-info`), jamais celui d'une erreur : il n'y a **pas** de panne.
- La ligne d'une activation périmée affiche un libellé explicite : « version N — **mise à jour
  disponible : N+1** ».

### Cas nominal — le compte rendu

Le dialogue d'annonce (`DepositPreviewDialogComponent`) et le retour du geste « appliquer » lisent
les **cinq** issues de SF-96-01 :

| Issue | Libellé à l'écran | Icône |
|---|---|---|
| `CREATE` | « sera créé » / « créé » | `note_add` |
| `UPDATE` | « **sera mis à jour** » / « **mis à jour** » | `sync` |
| `KEEP` | « déjà à jour — laissé tel quel » | `check` |
| `KEEP_LOCAL` | « **modifié localement — conservé, non mis à jour** » | `edit_off` |
| `UNKNOWN` | « indéterminé » | `help_outline` |

Le résumé d'un geste « appliquer » est une phrase de compte, dans cet ordre : *« 2 créés, 1 mis à
jour, 3 laissés tels quels — dont 1 modifié localement, conservé. »* Les deux conservations sont
**comptées séparément** ; la mention « dont … modifié localement, conservé » n'apparaît que s'il y en
a, et elle est suivie d'une phrase de rassurance : « vos modifications n'ont pas été touchées. »

### Ce qui ne change pas

- **Le geste reste celui de l'utilisateur** : aucun appel `apply` n'est déclenché par le chargement
  de l'écran, aucun rafraîchissement automatique, aucune écriture sans clic.
- L'annonce reste **lisible avant d'accepter** : ouvrir un fichier (F-75 / SF-75-02) continue de
  montrer le contenu apporté et l'existant.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| `GET /hosts` échoue | écran inchangé (erreur réseau existante) ; aucune pastille inventée |
| `outdated` absent de la réponse (ancienne gateway) | traité comme `0` — aucune pastille, aucun bandeau, rien ne casse |
| `apply` échoue | message existant, aucun compte rendu affiché |
| Issue inconnue rendue par la gateway | traitée comme `UNKNOWN` : « indéterminé », jamais « mis à jour » |

---

## Critères d'acceptation

1. Un poste dont un paquet actif est périmé porte une pastille « mise à jour » dans le sélecteur.
2. La carte du poste affiche le bandeau d'appel à l'action quand au moins un paquet est périmé, et
   **rien** sinon.
3. Le dialogue d'annonce affiche « sera mis à jour » pour une entrée `UPDATE`.
4. Une entrée `KEEP_LOCAL` s'affiche **distinctement** d'une entrée `KEEP`, avec une icône et un
   libellé qui disent qu'elle a été **modifiée localement et conservée**.
5. Le compte rendu du geste « appliquer » compte séparément créés, mis à jour, laissés tels quels,
   et modifiés localement conservés.
6. Aucune requête d'écriture n'est émise sans clic de l'utilisateur (vérifié au test).
7. Une réponse sans `outdated` ne produit ni pastille ni bandeau et ne lève aucune erreur.
8. Aucune couleur ni police hors `docs/DESIGN_SYSTEM.md` ; le bandeau réutilise le registre
   d'information existant de l'écran.

---

## Plan de test minimal

**`governance.component.spec.ts`**
- pastille « mise à jour » présente/absente selon `outdated` ; bandeau présent/absent ;
- `outdated` absent de la réponse → aucun rendu, aucune erreur ;
- compte rendu d'`apply` : phrase exacte avec 2 créés / 1 mis à jour / 3 laissés dont 1 modifié ;
- aucun `apply` au chargement.

**`deposit-preview-dialog.component.spec.ts`**
- libellés des cinq issues ; agrégation par fichier (`updated`, `keptLocal`) ; issue inconnue → `UNKNOWN`.

---

## Tables / endpoints / composants impactés

- **Frontend** : `governance.models.ts` (`GovernanceDepositAction` + `UPDATE` / `KEEP_LOCAL`,
  `GovernanceHostSummary.outdated?`), `governance.component.ts/html/scss`,
  `deposit-preview-dialog.component.ts/html/scss`.
- **Aucune table**, **aucune route nouvelle**.

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | aucun appel nouveau, aucun en-tête |
| Contexte tenant | non | aucun identifiant d'utilisateur ne transite ; l'isolation reste côté gateway |
| Plans / limites | non | — |
| **Navigation / routing** | **non** | aucune route ajoutée ni modifiée ; le sélecteur de poste existant est conservé |

## Hors périmètre

- Un geste « forcer » : il n'existe pas, et sa place ne serait pas ici.
- Un différentiel ligne à ligne du fichier mis à jour (le visualiseur de F-75 reste tel quel).
- Toute notification hors de l'écran (courriel, bandeau global).
