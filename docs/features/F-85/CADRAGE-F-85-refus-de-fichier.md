# F-85 — Un fichier refusé dit pourquoi, et quoi faire

> Cadrage du 2026-09-12, après un **essai raté devant le PO** : un prospect a tenté plusieurs
> `.docx`, a vu une erreur, a recommencé, et en est reparti avec l'idée que l'outil ne marche pas.

## 1. Ce qui s'est passé

Aucune trace côté serveur. Et pour cause : **`.docx` n'est accepté nulle part dans le produit.**

| Chemin | Types acceptés |
|---|---|
| Documents / bibliothèque (OCR) | `application/pdf`, `image/png`, `image/jpeg`, `image/tiff` |
| Pièce jointe d'une conversation | PDF, PNG, JPEG, GIF, WebP, `text/plain`, `text/markdown`, `text/csv` |
| Fichier d'un projet de la Forge | **texte et code seulement** — 60 extensions (`WORKSPACE_TEXT_EXTENSIONS`) |

Le refus est donc **normal et voulu**. **Ce qui ne l'est pas, c'est la façon dont il est dit.**

## 2. Les trois défauts

**D1 — Le sélecteur laisse choisir un fichier qu'on refusera.** L'attribut `accept` n'est posé que
sur le chemin « fichier de projet ». Ailleurs, le système d'exploitation propose **tout**, et
l'utilisateur découvre le refus après coup. Un sélecteur qui grise ce qu'il ne prendra pas évite
l'erreur au lieu de la commenter.

**D2 — Le message énumère des types MIME.** `« Formats acceptés : application/pdf, image/png,
image/jpeg, image/tiff »` : illisible pour quelqu'un qui pense en « Word », « PDF », « photo ». Le
message est **exact** et **inutilisable** — exactement le même défaut que le
`-Djavax.net.ssl.trustStore=<fichier>` de F-80, corrigé le matin même.

**D3 — Rien ne dit quoi faire.** Or la réponse existe, elle est simple, et elle marche :
**« exportez-le en PDF »**. Le produit traite très bien les PDF. Un message qui nomme la sortie
transforme un échec en étape ; un message qui l'omet transforme un prospect en client perdu.

**Et le coût est asymétrique** : Word est le format par défaut d'un document professionnel. Un
prospect qui teste un outil documentaire arrive avec des `.docx` — c'est le **premier** fichier
qu'il essaiera, systématiquement. Ce refus est donc rencontré par presque tout le monde, au moment
le plus coûteux : la découverte.

## 3. Ce que F-85 livre

### SF-85-01 — Le sélecteur ne propose que ce qui passe

Chaque sélecteur de fichier porte un `accept` **dérivé de la liste blanche du chemin concerné** —
jamais recopié à la main, sans quoi les deux divergeront au premier ajout de format.

Le **glisser-déposer** reste possible (on ne peut pas filtrer un dépôt), donc le message de refus
reste nécessaire : c'est SF-85-02.

### SF-85-02 — Le refus parle la langue de l'utilisateur

Trois éléments, dans cet ordre :

1. **Ce qui a été refusé**, nommé simplement : « Les fichiers Word (.docx) ne sont pas acceptés. »
2. **Ce qui passe**, en noms courants : « PDF, images (PNG, JPEG, TIFF). »
3. **Quoi faire**, concrètement : « Exportez votre document en PDF — dans Word, Fichier →
   Enregistrer sous → PDF. »

La traduction « type MIME → nom courant » vit **à un seul endroit**, et **retombe sur le type
technique** quand elle ne connaît pas — mieux vaut un mot obscur qu'un mot faux.

**Le serveur garde son message exact** dans le corps de l'erreur : il s'adresse à un appelant d'API,
pas à un humain. C'est l'**écran** qui traduit.

### SF-85-03 — La liste des formats est visible avant l'essai

À côté du bouton d'ajout, ce qui est accepté — en noms courants. Aujourd'hui on ne l'apprend qu'en
échouant.

## 4. La question qu'il ne faut pas confondre avec celle-ci

**Faut-il accepter `.docx` nativement ?** C'est un **autre sujet**, plus lourd : extraction du texte
d'un format bureautique, avec ses tableaux, ses images et ses en-têtes. F-85 ne le tranche pas.

F-85 fait l'hypothèse inverse et assumée : **la conversion en PDF est à un clic dans Word**, et il
vaut mieux la nommer aujourd'hui que la faire attendre. **À poser au PO séparément** si les retours
montrent que le détour est refusé par les utilisateurs.

## 5. Hors périmètre

- Changer les listes blanches, dans un sens ou dans l'autre.
- Convertir un fichier côté serveur.
- Le pipeline OCR lui-même.

## 6. Impact transversal

| Préoccupation | Composants |
|---|---|
| Aucune (ni auth, ni tenant, ni plans, ni routing) | — |
| Frontend | tous les sélecteurs de fichier — **les lister, il y en a plus qu'on ne croit** : bibliothèque, pièce jointe de conversation, fichier de projet, import d'archive |
| Backend | `DocumentService` (message inchangé), `UploadProperties`, `OcrProperties` — les listes blanches doivent être **lisibles par l'écran**, pas recopiées |

## 7. Plan de test minimal

- Un `.docx` refusé rend un message qui **ne contient aucun type MIME** et **nomme le PDF**.
- Un type inconnu de la table de traduction retombe sur son nom technique — **jamais un nom faux**.
- L'`accept` de chaque sélecteur est **dérivé** de la liste blanche : un format ajouté au serveur
  apparaît sans toucher à l'écran. **C'est le test qui empêche les deux de diverger.**
- Le glisser-déposer d'un `.docx` donne **le même message** que le sélecteur.
