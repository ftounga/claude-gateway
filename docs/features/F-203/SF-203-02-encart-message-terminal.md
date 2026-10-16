# Mini-spec — F-203 / SF-203-02 Terminal : encart message avec Copier en texte riche

## Identifiant
`F-203 / SF-203-02`

## Feature parente
`F-203` — Mails et messages Teams : un encart « Copier » qui garde la mise en forme (cadrage `CADRAGE-F-203-messages-a-copier.md`)

## Statut
`in-progress` — 2026-10-16

## Branche Git
`feat/SF-203-02-encart-message-terminal`

---

## Objectif
Dans le terminal de l'Atelier, chaque bloc ```` ```mail ```` / ```` ```message ```` d'une réponse s'affiche dans un encart (type, objet, corps mis en forme) avec un bouton **Copier** qui copie en texte riche, prêt à coller dans Outlook ou Teams.

## Comportement attendu

### Cas nominal
- Le terminal découpe le texte de l'agent (réponse persistée **et** flux en direct, partie « essentiel » et partie « détail ») avec un découpage **restreint aux blocs message** (`mail`, `email`, `eml`, `message`, `teams`, `slack`) : le code et les autres blocs restent rendus comme avant, dans la prose Markdown.
- Sans bloc message, le rendu est **identique** à l'existant (un seul conteneur `.terminal-agent` rendu par le pipe `markdown`).
- Avec un bloc message : prose avant / encart / prose après, dans l'ordre d'origine.
- L'encart réutilise `app-copy-block` (F-26) :
  - nouveau type `message` (icône `chat_bubble_outline`, libellé « Message ») à côté de `mail` (« E-mail ») ;
  - **objet** : si la première ligne non vide du bloc est `Objet : …` / `Subject: …` (gras toléré), elle est affichée à part (« Objet ») avec son propre bouton de copie (texte brut) et retirée du corps ;
  - **Copier** copie le **corps** : `text/html` propre (attributs `class`, `style`, `id` retirés) + `text/plain` (Markdown brut, retours à la ligne conservés) ; repli `writeText` si `ClipboardItem` indisponible ou refusé ;
  - feedback : le bouton affiche « Copié » (icône `check`) 2 s, en plus du snackbar existant.
- Dans le terminal (sombre), l'encart prend la peau sombre déjà utilisée sous 820 px (jetons `--cg-navy`, `--cg-navy-2`, `--cg-divider`) via une entrée `tone="dark"` — aucune couleur nouvelle.
- Historique : un ancien message contenant déjà un bloc ```` ```mail ```` est présenté en encart (rendu dérivé côté client, aucune réécriture des données).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Presse-papiers indisponible | snackbar « Copie impossible dans ce contexte. » (inchangé), aucune exception |
| `clipboard.write` refusé (permission) | repli sur `writeText(corps)` |
| Bloc non clos (streaming en cours) | reste dans la prose jusqu'à sa clôture |
| Bloc vide | reste dans la prose (pas d'encart) |

## Contraintes de validation
- Aucune saisie ; détection de l'objet sur la première ligne non vide uniquement.

## Critères d'acceptation
- [ ] Une réponse avec ```` ```mail ```` affiche un encart `app-copy-block` dans le terminal, prose conservée autour.
- [ ] Un bloc ```` ```ts ```` reste rendu comme avant (pas d'encart dans le terminal).
- [ ] L'objet est extrait et affiché à part ; Copier copie le corps sans la ligne d'objet.
- [ ] Le HTML copié ne contient ni `class=`, ni `style=`, ni `id=`.
- [ ] Le bouton affiche « Copié » après une copie réussie.
- [ ] Aucune couleur / police hors charte.

## Plan de test
- Unitaires `message-segments.spec` : `message`/`teams`/`slack` ⇒ type `message` ; `splitMessageBlocks` ne garde que les blocs message ; `splitSubject` (Objet, Subject, gras, absent).
- Unitaires `copy-block.component.spec` : objet affiché + corps copié sans objet ; HTML nettoyé ; « Copié ».
- Composant terminal (`terminal-bloc-message.spec`) : encart rendu pour ```` ```mail ```` (persisté et live), pas d'encart pour ```` ```ts ````, message sans bloc rendu comme avant.
- `npm run build` + `npm test`.
- Isolation utilisateur : sans objet (rendu client des messages déjà chargés, aucun accès données).

## Impacts
- Composants : `CopyBlockComponent` (objet, nettoyage HTML, « Copié », `tone`), `shared/message-segments.ts` (+ pipe `messageBlocks`), `copy-block.model.ts` (type `message`), `AtelierTerminalComponent` (gabarit `agentText`).
- Endpoints / tables : aucun.

## Préoccupations transversales
- Aucune (auth, tenant, limites, routage non touchés).

## Hors périmètre
- Encart dans le terminal Teams (la doctrine n'y est pas injectée — SF-203-01).
- Envoi direct du message depuis l'encart.
- Réécriture des anciens messages.
