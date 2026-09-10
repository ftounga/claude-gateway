# Mini-spec — F-56 / SF-56-01 — La passe de cohérence

## Identifiant

`F-56 / SF-56-01`

## Feature parente

`F-56` — Passe visuelle sur les écrans

## Statut

`done` — livrée le 2026-09-10 (PR #342)

## Date de création

2026-09-10

## Branche Git

`feat/SF-56-01-passe-coherence-visuelle`

---

## Objectif

Aligner sur `docs/DESIGN_SYSTEM.md` les sept divergences relevées sur les écrans nés cette
semaine — **sans changer un seul parcours, ni ajouter, ni retirer une fonction**.

---

## Comportement attendu

### Cas nominal

Rien de nouveau n'apparaît à l'écran. Ce qui change, écran par écran :

1. **Postes (F-49)** — les pastilles *Connecté* / *Hors ligne* / *Administrateur* / *Actif*
   s'affichent comme partout ailleurs dans le produit : pilule arrondie, fond teinté, texte de la
   couleur du statut (`DESIGN_SYSTEM.md` §5). Elles s'affichaient jusqu'ici en texte nu, parce que
   les classes employées (`cg-badge*`) n'étaient définies nulle part. **Aucune pastille n'apparaît
   ni ne disparaît** : ce sont exactement les mêmes, aux mêmes conditions.
2. **Gouvernance (F-51)** — les chemins de fichiers et les extraits de code emploient le jeton
   `--cg-font-mono`. Même police à l'écran ; c'est la source qui cesse de la nommer en dur.
3. **Bulle d'aide (F-54)** — les espacements reviennent sur l'échelle de la charte (§6). Les `12px`
   deviennent 16 px (marges de bloc) ou 8 px (gouttière d'icône). Le panneau garde sa taille, sa
   position et son contenu.
4. **Catalogue de gouvernance (F-51)** — sous 368 px de large, les cartes du catalogue se replient
   dans la largeur disponible au lieu de pousser la page en défilement horizontal.
5. **Assistant proxy (F-55)** — s'ouvre à la largeur du parcours d'appairage qu'il recouvre
   (560 px), plus large que lui jusqu'ici. Toutes les étapes, commandes et verdicts sont les mêmes.
6. **Appairage (F-45)** — l'échec de génération d'un code est annoncé aux lecteurs d'écran
   (`role="alert"`), comme l'est déjà l'échec de rattachement sur le même écran.
7. **Rappel de transparence (F-57)** — un espacement en dur passe au jeton `--cg-space-1`. Valeur
   identique (4 px).

### Cas d'erreur

Aucun cas d'erreur nouveau : la subfeature ne touche ni appel réseau, ni état, ni logique de
composant. Les états d'erreur existants (`forbidden`, `network`, erreurs de formulaire) sont
inchangés, y compris dans leur formulation.

| Situation | Comportement attendu |
|---|---|
| Une classe globale `.badge--*` viendrait à manquer | Impossible sans toucher `styles.scss`, hors périmètre. Un test vérifie que les pastilles des postes portent bien `badge` |
| Un écran étroit (360 px) sur le catalogue | Les cartes tiennent dans la largeur ; pas de défilement horizontal du document |

---

## Critères d'acceptation

- [x] Les pastilles de l'écran des postes portent la classe globale `badge` et son modificateur de
      statut (`badge--success`, `badge--neutral`, `badge--warning`).
- [x] Plus aucune classe `cg-badge` dans le code source du frontend.
- [x] Plus aucun littéral `'JetBrains Mono'` dans une feuille de style de composant : le jeton
      `--cg-font-mono` est employé partout.
- [x] La feuille de style de la bulle d'aide n'emploie plus que des jetons `--cg-space-*` pour ses
      marges, gouttières et remplissages.
- [x] La grille du catalogue de gouvernance ne force plus une colonne plus large que son conteneur.
- [x] L'assistant proxy s'ouvre à 560 px, comme le parcours d'appairage.
- [x] Le message d'échec de génération de code porte `role="alert"`.
- [x] **Tous les tests frontend existants passent sans avoir été modifiés.**
- [x] `npm run build` et `npm test` verts.

---

## Contraintes de validation

**Sans objet.** La subfeature n'introduit ni champ de saisie, ni valeur soumise à une règle, ni
quota, ni énumération. Les seules valeurs qu'elle manipule sont des constantes de présentation, et
elles sont toutes tranchées dans le cadrage §5 : l'échelle d'espacement de la charte (§6), le nom
de classe de pastille de la charte (§5), la largeur du dialogue d'appairage (560 px, existante).

---

## Plan de test minimal

### Tests unitaires (ajoutés)

| Test | Ce qu'il tient |
|---|---|
| `PostesComponent` — les pastilles de statut portent la classe `badge` de la charte | La correction n° 1, celle qui se voit |
| `PostesComponent` — aucune classe `cg-badge` dans le gabarit rendu | Empêche la régression par copier-coller |
| `ProxyAssistantDialog` — le dialogue est ouvert à la largeur du parcours qu'il recouvre | La correction n° 5 |
| `RunnerPairingDialog` — l'erreur de génération de code est annoncée (`role="alert"`) | La correction n° 6 |

### Tests d'intégration

Sans objet : aucune frontière réseau, aucun endpoint, aucune persistance n'est touchée.

### Isolation utilisateur

Sans objet : **aucun accès aux données**, aucune requête, aucun filtre. La subfeature est
exclusivement CSS / gabarit. L'isolation `user_id` des écrans concernés est celle, inchangée, des
services qu'ils appellent déjà.

### Non-régression

Les 4 suites de tests des écrans touchés (`postes`, `governance`, `runner-pairing-dialog`,
`proxy-assistant-dialog`, `help-chat-widget`) doivent passer **sans modification**. C'est la
garantie mécanique du « aucun parcours ne change ».

---

## Impacts

### Tables

Aucune. **Aucune migration Liquibase.**

### Endpoints

Aucun. **Aucune ligne de backend.**

### Composants Angular touchés

| Fichier | Nature |
|---|---|
| `postes/postes.component.html` | classes de pastilles |
| `postes/postes.component.scss` | sélecteur de dimension d'icône |
| `governance/governance.component.scss` | jeton de police, grille du catalogue |
| `governance/deposit-preview-dialog/*.scss` | jeton de police |
| `chat/chat.component.scss`, `settings/settings.component.scss` | jeton de police (même défaut, écrans antérieurs) |
| `help/help-chat-widget/*.scss` | jetons d'espacement |
| `atelier/runner/runner-pairing-dialog.component.html` | `role="alert"` |
| `atelier/runner/runner-pairing-dialog.component.ts` | largeur d'ouverture de l'assistant proxy |
| `atelier/notice/workstation-notice.component.scss` | jeton d'espacement |

### Préoccupations transversales

| Préoccupation | Concernée ? | Analyse |
|---|---|---|
| Auth / Principal | **Non** | Aucun composant d'authentification, aucun garde, aucun intercepteur touché |
| Contexte tenant | **Non** | Aucun accès aux données : la subfeature ne contient ni appel HTTP, ni requête |
| Plans / limites | **Non** | Aucun appel à un service de quota ; les états `forbidden` existants sont inchangés, y compris leur texte |
| Navigation / routing | **Non** | Aucune route ajoutée, retirée ou redirigée. Les `routerLink` des états vides sont inchangés |

---

## Hors périmètre

- **`docs/DESIGN_SYSTEM.md`** : la charte fait autorité, on ne la corrige pas — y compris sa
  contradiction interne sur la table des couleurs (cadrage §4, D1), qui part en question ouverte.
- **La landing publique** : hors périmètre par `PRODUCT_SPEC.md`.
- Les divergences **D2 à D6** du cadrage : antérieures à la semaine, ou relevant du goût et non de
  la charte. Elles sont écrites, pas corrigées.
- Toute retouche de texte, de libellé ou d'enchaînement d'étapes : ce serait une refonte.
