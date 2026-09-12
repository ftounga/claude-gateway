# Mini-spec — F-85 / SF-85-02 — Le refus parle la langue de l'utilisateur

## Identifiant

`F-85 / SF-85-02`

## Feature parente

`F-85` — Un fichier refusé dit pourquoi, et quoi faire

## Statut

`done`

## Date de création

2026-09-12

## Branche Git

`feat/SF-85-02-refus-lisible`

---

## Objectif

Un fichier refusé rend un message en trois temps — **ce qui a été refusé**, **ce qui passe**, **quoi
faire** — écrit dans les mots de l'utilisateur (« Word », « PDF », « images ») et non en types MIME.

---

## Le défaut corrigé

Aujourd'hui : `« Format refusé (« application/vnd.openxmlformats-officedocument.wordprocessingml.document »).
Formats acceptés : application/pdf, image/png, image/jpeg, image/tiff. »`

Exact, et inutilisable. C'est le défaut du `-Djavax.net.ssl.trustStore=<fichier>` de F-80, corrigé le
matin même : **nommer le remède sans donner le moyen**. Le message dit ce qui est accepté sans dire
ce qu'il faut faire, et le dit dans un vocabulaire que l'utilisateur n'a pas.

Après :

> Les fichiers Word (.docx) ne sont pas acceptés. Formats acceptés : PDF, images (PNG, JPEG, TIFF).
> Exportez votre document en PDF : dans Word, Fichier > Enregistrer sous > PDF.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur choisit — ou **dépose** — un fichier refusé sur l'écran bibliothèque ou dans le
   composeur de conversation.
2. L'écran le refuse **avant** de l'envoyer, en lisant la liste blanche du serveur déjà connue
   (SF-85-01), et affiche le message en trois temps.
3. Si le fichier part quand même (liste blanche pas encore connue) et que le serveur répond `415`,
   l'écran **traduit** le refus dans le même message, avec la même fonction.
4. **Le serveur garde son message exact** : il s'adresse à un appelant d'API. Aucune ligne de
   `DocumentService` ni de `UploadService` n'est modifiée.

### La traduction vit à un seul endroit

`frontend/src/app/shared/file-format-names.ts` :

| Fonction | Rôle |
|---|---|
| `mediaTypeName(type)` | `application/pdf` → `PDF`. **Retombe sur le type technique** si inconnu |
| `refusedFormatName(file)` | `rapport.docx` → `Word (.docx)`. Retombe sur `.ext`, puis sur le type MIME déclaré |
| `acceptedFormatsSentence(types)` | → `PDF, images (PNG, JPEG, TIFF)` |
| `fileRejectionMessage(file, types)` | le message en trois temps, ou `null` si le fichier passe |

**Jamais un nom faux.** Un type absent de la table rend son écriture technique — la même règle que le
libellé d'interpréteur de SF-45-05, qui n'écrit jamais « inconnu ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Type refusé **inconnu** de la table (`application/x-chose`) | Le message le nomme par son écriture technique, et n'invente rien. Les deux autres temps (ce qui passe, quoi faire) restent lisibles | — |
| Fichier **sans extension** et sans type déclaré | « Ce fichier n'est pas accepté. » puis les deux autres temps. Aucune affirmation sur ce qu'il est | — |
| Liste blanche **pas encore connue** (appel SF-85-01 en échec) | Aucun refus côté écran : le fichier part, et le message du **serveur** s'affiche tel quel — comme avant F-85. On ne fabrique pas une liste de formats qu'on ne connaît pas | 415 |
| `415` reçu alors que la liste est connue | Message traduit, identique à celui du refus local | 415 |
| Fichier trop volumineux (`413`) | Inchangé : ce n'est pas un refus de format | 413 |

---

## Critères d'acceptation

- [ ] **Le test de la feature** : un `.docx` refusé rend un message qui ne contient **aucun type
      MIME** (aucune séquence `type/sous-type`) et qui **nomme le PDF**.
- [ ] Le message contient les trois temps, dans l'ordre : ce qui est refusé, ce qui passe, quoi faire.
- [ ] Un type **inconnu** de la table retombe sur son écriture technique — jamais un nom faux, jamais
      « inconnu ».
- [ ] Le **glisser-déposer** d'un `.docx` sur la bibliothèque rend **exactement** le même message que
      le sélecteur (même fonction, comparé caractère pour caractère dans le test).
- [ ] Les formats acceptés sont énoncés en noms courants, **dérivés** de la liste du serveur : la
      phrase change si le serveur change, sans toucher à l'écran.
- [ ] Le serveur rend **toujours** son message exact : les tests backend existants de
      `DocumentService` / `UploadService` sont inchangés et verts.
- [ ] Un fichier accepté n'est **jamais** refusé par la garde locale (non-régression).
- [ ] Suites backend et frontend vertes.

---

## Périmètre

### Hors scope (explicite)

- **Changer le message du serveur.** Il s'adresse à un appelant d'API ; c'est l'écran qui traduit.
- Changer une liste blanche, accepter `.docx` nativement, convertir côté serveur, le pipeline OCR.
- Le message du **fichier de projet** (« Les fichiers binaires (PDF, image) s'ajoutent via la
  bibliothèque après OCR ») : il est déjà en langue courante **et** dit quoi faire. Le réécrire pour
  le plaisir de passer par la fonction commune n'apporterait rien et risquerait une régression.
- La mention des formats **avant** l'essai : c'est SF-85-03.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|---|---|---|---|---|---|
| type MIME lu sur le fichier | Non (peut être vide) | — | libre | — | minuscules, partie avant `;` |
| extension lue sur le nom | Non | — | libre | — | minuscules, après le dernier `.` |

---

## Technique

### Endpoint(s)

Aucun créé ni modifié. **Le backend n'est pas touché.**

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `shared/file-format-names.ts` — créé : **le** seul endroit où un type MIME devient un mot.
- `DocumentsComponent` — garde locale, traduction du `415`, **zone de dépôt**.
- `ChatComponent` — garde locale et traduction du `415` sur les pièces jointes.

---

## Plan de test

### Tests unitaires

- [ ] `file-format-names.spec` — `.docx` : message sans aucun type MIME, qui nomme le PDF.
- [ ] `file-format-names.spec` — les trois temps, dans l'ordre.
- [ ] `file-format-names.spec` — type inconnu ⇒ écriture technique, jamais un nom faux.
- [ ] `file-format-names.spec` — fichier sans extension ni type ⇒ aucune affirmation inventée.
- [ ] `file-format-names.spec` — phrase des formats acceptés dérivée de la liste (elle change avec).
- [ ] `file-format-names.spec` — un fichier accepté rend `null`.

### Tests d'intégration

- [ ] `documents.component.spec` — sélecteur : `.docx` refusé localement, rien n'est envoyé.
- [ ] `documents.component.spec` — **dépôt** : même fichier, **même message** (égalité stricte).
- [ ] `documents.component.spec` — `415` du serveur traduit dans le même message.
- [ ] `documents.component.spec` — liste inconnue ⇒ le message du serveur s'affiche tel quel.
- [ ] `chat.component.spec` — pièce jointe `.docx` refusée localement avec le même message.

### Isolation utilisateur

- [x] Non applicable — aucune donnée n'est lue ni écrite : la subfeature transforme un texte dans le
      navigateur. Les appels existants (dépôt d'un document, téléversement d'une pièce jointe) sont
      inchangés et restent isolés par le JWT côté serveur.

---

## Dépendances

### Subfeatures bloquantes

- `SF-85-01` — statut : `done` (la liste blanche du serveur est lisible par l'écran).

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Auth / Principal** | Aucun appel ajouté, aucun en-tête touché, aucun `Principal` | traité |
| **Contexte tenant** | Aucune donnée lue ; les deux appels existants (`POST /api/documents`, `POST /api/upload`) sont inchangés | traité |
| **Plans / limites** | Le refus de **taille** (`413`) est laissé strictement inchangé : ce n'est pas un refus de format | traité |
| **Navigation / routing** | Aucune route, aucun guard | traité |

---

## Notes et décisions

- **Arbitrage — la garde locale n'invente rien quand elle ne sait pas.** Si la liste blanche n'est pas
  connue (appel SF-85-01 en échec), l'écran ne refuse rien et laisse parler le serveur. Un message
  fabriqué à partir d'une liste supposée serait pire que le message technique : il serait faux.
  **Réversible.**
- **Arbitrage — une zone de dépôt est ajoutée sur l'écran bibliothèque.** Le cadrage exige que le
  glisser-déposer donne le même message ; or déposer un fichier sur cet écran ne faisait rien du tout
  (le navigateur quittait la page). Le dépôt passe par **la même fonction** que le sélecteur : les
  deux messages ne peuvent pas diverger, et c'est ce que le test vérifie. Aucune couleur nouvelle :
  l'état « survol » réutilise `--cg-accent` et `--cg-divider`. **Réversible** : deux écouteurs.
- **Arbitrage — le nom courant est choisi sur l'extension avant le type MIME.** Le navigateur déclare
  parfois `application/octet-stream` pour un `.docx` ; l'extension, elle, est ce que l'utilisateur
  voit dans son explorateur. On la lit d'abord, et on retombe sur le type MIME, puis sur rien du tout
  — jamais sur une supposition.
- **Le serveur garde son message.** `DocumentService` reste mot pour mot celui d'avant, y compris le
  type déclaré entre guillemets : un PDF annoncé `application/octet-stream` par un client d'API se
  diagnostique avec ce message-là.
