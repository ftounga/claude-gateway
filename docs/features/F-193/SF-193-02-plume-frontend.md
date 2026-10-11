# Mini-spec — F-193 / SF-193-02 Plume : le bouton « ✨ Enrichir » et son panneau (frontend)

## Identifiant
`F-193 / SF-193-02` — Feature parente : F-193 « Plume : enrichir la demande » (cadrage `CADRAGE-F-193-plume-enrichir-la-demande.md`, maquette `design/maquette-cap-plume.html`, partie Plume).
Statut : `ready` — 2026-10-11 — Branche `feat/SF-193-02-plume-frontend`. Dépend de SF-193-01 (mergée).

## Objectif
Dans la saisie du terminal, un bouton « ✨ Enrichir » toujours présent remplace, sur geste, la phrase courte par la demande structurée de Plume, à relire et corriger, avec « Revenir à mon texte » — rien n'est envoyé sans geste.

## Comportement attendu

### Cas nominal
1. Le bouton **« ✨ Enrichir »** est dans la barre de saisie, entre le champ et « Envoyer » ; **grisé** si la saisie est vide (ou blanche), pendant un enrichissement, ou quand l'invite est bloquée (publication, plafond de terminaux).
2. Au clic : le texte d'origine est gardé ; un panneau **« Plume lit le contexte »** (au-dessus de la saisie, `role="status"`) montre les étapes : fil récent du sujet, `STATE.md` du sujet, carte du poste, rédaction de la demande (animation CSS, coupée en `prefers-reduced-motion`). Le bouton devient « ✨ Enrichissement… ».
3. Appel `POST /api/workspaces/{id}/enrich-prompt` `{text}` (contrat SF-193-01).
4. Réponse : la saisie est **remplacée** par `enrichedPrompt` dans une **zone multiligne** (textarea) éditable ; le panneau montre :
   - « Votre texte : « … » » (l'original, rappelé en tête) ;
   - le badge **« enrichi par Plume »** et « Lu : » + `sources` ;
   - les éléments **« à confirmer »** (`toConfirm`) surlignés ambre ;
   - le bouton **« Revenir à mon texte »** (restaure exactement l'original, ferme le panneau).
5. Dans la zone multiligne, **Entrée** va à la ligne ; **Ctrl/⌘ + Entrée** ou « Envoyer » envoie. Envoyer referme le panneau. Rien ne part sans ce geste.
6. Changer de terminal, ou vider la saisie, referme Plume.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| 503 (coupe-circuit) | panneau d'erreur « Plume est désactivé sur cette installation. », saisie intacte, bouton « Fermer » |
| 502 / réseau / autre | « L'enrichissement a échoué. Votre texte est intact, réessayez. » (ou le message `error` du serveur), saisie intacte |
| 400 | message `error` du serveur, saisie intacte |
| Saisie vide | bouton grisé, aucun appel |
| Clic répété pendant l'enrichissement | ignoré (bouton grisé) |

## Contraintes de validation
- Texte envoyé tel que saisi ; > 4 000 caractères ⇒ le serveur répond 400 (message affiché).

## Critères d'acceptation
- [ ] Le bouton « ✨ Enrichir » est rendu dans la saisie, grisé quand la saisie est vide.
- [ ] Un clic appelle le service une seule fois avec le texte et l'identifiant du terminal ; pendant l'appel, le panneau d'analyse est visible.
- [ ] Au retour, le brouillon devient `enrichedPrompt`, la zone multiligne apparaît, l'original et les « à confirmer » sont affichés.
- [ ] « Revenir à mon texte » restaure exactement l'original.
- [ ] Erreur : le brouillon n'est jamais modifié, un message est affiché.
- [ ] Envoyer après enrichissement émet `send` et referme le panneau.
- [ ] Couleurs et polices du design system uniquement (`--cg-accent`, `--cg-font-mono`…), espacements multiples de 4 px, utilisable sous 819 px.

## Plan de test
- `plume.service.spec.ts` : POST sur la bonne URL avec `{text}`.
- `terminal-plume.component.spec.ts` : rendu des trois phases (analyse, enrichi avec original/à confirmer/sources, erreur), émissions `restore` / `dismiss`.
- `terminal-plume.spec.ts` (terminal) : bouton grisé si vide ; clic ⇒ appel + panneau ; succès ⇒ `draftChange(enrichedPrompt)` + textarea ; « Revenir » ⇒ `draftChange(original)` ; erreur ⇒ aucun `draftChange` + message ; envoi ⇒ panneau fermé.
- Isolation : portée par le backend (404 sur le terminal d'autrui, testé en SF-193-01) ; l'écran n'appelle qu'avec le terminal ouvert.

## Impacts
- Tables / endpoints : aucun nouveau (consomme `POST /api/workspaces/{id}/enrich-prompt`).
- Composants : nouveau `core/services/plume.service.ts` ; nouveau `atelier/terminal/terminal-plume.component.ts` ; `atelier-terminal.component.{ts,html}` (bouton, état, textarea) ; feuille `atelier-terminal-plume.component.scss` (budget de 12 ko de la feuille principale).
- Préoccupations transversales : Auth ☐ ; Tenant ☐ ; Plans/limites ☐ ; Navigation ☐ (aucune route).

## Hors périmètre
Surlignage « à confirmer » à l'intérieur même du texte éditable (un textarea ne colore pas ; les éléments sont listés surlignés dans le panneau — arbitrage tracé) ; enrichissement automatique ; chat simple hors terminal ; streaming de l'analyse.
