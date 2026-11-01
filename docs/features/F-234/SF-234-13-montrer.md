# Mini-spec — F-234 / SF-234-13 — « Montrer » (P5, D18)

## Identifiant
`F-234 / SF-234-13`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage `CADRAGE-F-234-ecran-vivant-jarvis.md`, §0 P5, §6.2 S20, D18 ; ajout PO du
2026-11-01, livrée en tête de la vague B)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-13-montrer`

---

## Objectif

> Quand le PO **demande à voir** (« montre-moi mes clients », « ceux qui sont connectés », « les chantiers bloqués »,
> « les étapes en cours », « mes messages à envoyer »), la constellation met au premier plan ce qui est demandé et
> estompe le reste — sans outil nouveau, sans appel IA de plus, préfixe de cache intact.

## Comportement attendu

- **Consigne orale** (`JarvisOralNote.TEXT`, jointe au **message** d'un tour né à la voix — jamais la consigne
  système) : une puce de plus — si l'utilisateur demande à voir une partie de son univers, ajouter sous la ligne 🎙
  une ligne `🖥 montrer: <filtre> [client]`, filtre parmi `clients`, `connectes`, `deconnectes`,
  `chantiers-bloques`, `etapes-en-cours`, `a-envoyer`.
- **Aucun outil ajouté** : liste d'outils et consigne système **identiques** entre un tour Jarvis et un tour tapé du
  même fil (test).
- **Écran** (`scenes/jarvis-show.ts`, pur) : `parseShowLine` (accents, casse, variation-selector tolérés ; filtre
  inconnu ⇒ `null`), `resolveShow(univers, demande)` sur l'univers **déjà chargé** (SF-234-01) ⇒ `{ title, items
  (≤ 8), more }`.
  - `clients` / `connectes` / `deconnectes` : clients, note « connecté » / « déconnecté » / « hébergé ».
  - `chantiers-bloques` : chantiers dont `waitingOn` n'est pas vide, note « attend Zahi ».
  - `etapes-en-cours` : chantiers avec étape courante, note « étape 3/5 — Basculer ».
  - `a-envoyer` : destinataires `toSend` (rapprochés des interlocuteurs), titre « n messages à envoyer ».
  - Client nommé ⇒ restreint ; client inconnu ⇒ rien.
- **Lecteur de scène** : la ligne `🖥` est retenue (`state.show`), **jamais** lue comme des noms ; effacée au
  **tour suivant** (`startTurn`) ou par `clearShow`.
- **Constellation** : classe `is-showing` — ce qui est montré au premier plan (client agrandi ; poste connecté =
  **halo vert** `--cg-terminal-success`, déconnecté = halo pointillé éteint) ; chantiers et personnes montrés posés
  sur l'anneau des chantiers (même sur téléphone) ; le reste estompé (0,22). Fil d'Ariane = le titre (« 1 poste
  connecté sur 2 »), équivalent accessible.
- **Scène S20** (quand aucune action n'est en cours) : la liste écrite (nom · note, pastille de forme).
- **Masquage** : la ligne n'est **ni dite** (`oralPhrase`, `OralStream` ne lit que 🎙), **ni affichée** (détail
  Jarvis `stripShowLines` ; fil du terminal : `renderMarkdown` retire cette seule forme), **ni résumée** côté
  passerelle (`JarvisReplies.oralLine`).
- **« c'est bon »** (et « merci », « ferme », « c'est vu »…) dit à Jarvis hors tour quand quelque chose est montré ⇒
  retour à l'état normal, **sur l'appareil, sans tour**.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Filtre inconnu (« 🖥 montrer: la météo ») | rien de montré |
| Client nommé inconnu | rien de montré |
| Univers non chargé | rien de montré (la ligne reste masquée) |
| Aucun élément (aucun chantier bloqué) | titre « 0 chantier bloqué », liste vide |
| « c'est bon » sans rien de montré | comportement inchangé (part au cerveau) |

## Critères d'acceptation
1. `🖥 montrer: connectes` ⇒ seuls les postes connectés au premier plan avec halo vert ; `deconnectes` ⇒ halo éteint.
2. `chantiers-bloques` ⇒ losange du chantier qui attend, avec la personne ; `etapes-en-cours` ⇒ l'étape.
3. Filtre inconnu ⇒ rien.
4. La ligne n'est ni dite, ni affichée (Jarvis, terminal), ni résumée (passerelle).
5. Consigne système et liste d'outils identiques entre tour Jarvis et tour tapé (test passerelle).
6. « c'est bon » ⇒ retour sans tour ; le tour suivant efface aussi.

## Plan de test
- Unitaires front `jarvis-show.spec.ts` : parse (6 filtres, accents, inconnu), résolution (5 filtres, client),
  « c'est bon », masquage (oral, markdown, détail), lecteur (retenu, pas de nom allumé, effacé au tour suivant).
- Composant : constellation + scène S20 (halo vert / éteint, estompé, losange montré, retour).
- `jarvis.component.spec.ts` : « c'est bon » referme sans `streamChat`.
- Back : `AtelierChatServiceVoiceTurnTest` (consigne au message ; système et outils identiques au tour tapé),
  `JarvisRepliesTest` (la ligne n'est jamais le résumé).
- Non-régression : suites jarvis + shared, `npm run build`, suite backend complète.

## Impacts
- **Tables / endpoints** : aucun.
- **Back** : `JarvisOralNote` (texte du message + `SHOW_MARK`), `JarvisReplies`.
- **Front** : `scenes/jarvis-show.ts` (nouveau), lecteur, fil, constellation, scène, `jarvis.component.ts`,
  `jarvis-oral.ts`, `shared/markdown.pipe.ts` ; `DESIGN_SYSTEM.md` §22 (3 lignes).

## Préoccupations transversales
- Auth / Principal : non. Contexte tenant : non (univers SF-234-01, déjà filtré `user_id`). Plans / limites : non.
- Navigation / routing : non (aucune route).
- **Préfixe de cache** : **non touché** — consigne système et outils identiques (test) ; seule la consigne orale du
  message change (texte fixe).

## Arbitrages (réversibles)
- Ligne marqueur plutôt qu'outil (D18, contrainte cache du PO) ; filtre `deconnectes` ajouté (symétrique, gratuit).
- Au plus 8 éléments montrés (lisibilité téléphone), « et n de plus » écrit.
- « c'est bon » reconnu **seulement** quand quelque chose est montré et hors tour (sinon comportement F-233 inchangé).
- Le texte d'une réponse rangée garde la ligne (historique fidèle) ; seul le rendu la masque.

## Hors périmètre
Montrer des sujets ou des personnes par nom (la citation les allume déjà) ; tri, zoom, navigation (la constellation
reste un témoin) ; mesure des « Montrer » (SF-234-11).
