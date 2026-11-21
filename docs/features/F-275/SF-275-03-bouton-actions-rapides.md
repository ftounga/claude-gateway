# Mini-spec — [F-275 / SF-275-03] Le bouton « Actions rapides » (frontend)

## Identifiant

`F-275 / SF-275-03`

## Feature parente

`F-275` — Actions rapides : lancer un workflow configuré d'un clic (cadrage `CADRAGE-F-275-actions-rapides.md`)

## Statut

`done`

## Date de création

2026-11-21

## Branche Git

`feat/SF-275-03-bouton-actions-rapides`

---

## Objectif

Mettre dans la saisie de chaque terminal (poste et sujets) un bouton-icône « Actions rapides » qui déroule les workflows configurés et lance celui qu'on clique dans le bon terminal, en deux clics au plus.

---

## Comportement attendu

### Cas nominal

- `app-terminal-quick-actions` : un `mat-icon-button` (`playlist_play`, infobulle et nom accessible « Actions rapides »), placé dans la saisie avant le trombone, **seulement** au volet principal d'un terminal non lecture seule et non Teams. Aucune bande, aucune ligne ajoutée (F-207).
- Au chargement du terminal et à chaque fin de tour : `GET …/quick-actions?skills=false` (actions perso, aucune lecture du poste) ⇒ s'il en est une suggérée maintenant (jour ISO du navigateur, MATIN < 12 h ≤ APRES_MIDI, JOURNEE toujours, pas lancée aujourd'hui), un **point orange** s'allume sur l'icône et le nom accessible le dit.
- Ouverture du menu (1er clic) : `GET …/quick-actions` (liste complète, skills compris). Sections : **Suggéré maintenant**, **Mes actions**, **Depuis vos skills** (`/nom-du-skill`). Chaque entrée : libellé + ligne d'aide « → reporting-securite · envoi direct · suggérée lun. le matin · dernière : il y a 2 j » (ou « ici », « dans la saisie », « jamais lancée »).
- Clic sur une entrée (2e clic) → le terminal émet `quickAction` ; `AtelierComponent.launchQuickAction` :
  1. enregistre le lancement (`POST …/launches`, sans bloquer) ;
  2. sujet cible ≠ terminal affiché ⇒ `openHandoff({workspaceId, name, phrase}, {send})` : le sujet s'ouvre (même chemin que `ouvrir_sujet`, bandeau « Revenir »), la phrase est déposée, et **envoyée** si `ENVOYER` ;
  3. même terminal : `ENVOYER` + saisie vide + aucun tour en cours ⇒ envoi ; sinon la phrase est **déposée** (ajoutée devant un brouillon existant, jamais écrasé) et un message le dit si l'envoi direct n'a pas eu lieu.
- Une action dont le sujet cible est introuvable part dans le terminal courant (dit dans la ligne d'aide).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Liste illisible (réseau, poste muet) | menu : « Actions illisibles pour le moment. » ; aucun point | — |
| Aucune action | menu : comment en créer (agent, en-tête de skill) | — |
| Enregistrement du lancement en échec | ignoré (le lancement a eu lieu) | — |
| Sujet cible disparu de la liste | « Sujet introuvable. » (snack, comme `openHandoff`) | — |
| Envoi direct avec brouillon / tour en cours | dépôt + snack « déposée dans la saisie » | — |

---

## Critères d'acceptation

- [x] CA1 Depuis n'importe quel terminal du poste, « Point hebdo » visant `reporting-securite` se lance en 2 clics (bouton, entrée) : le sujet s'ouvre avec la phrase (déposée ou envoyée selon l'action).
- [x] CA2 Une action créée par l'outil apparaît à la réouverture du menu, sans recharger la page.
- [x] CA3 Un skill qui déclare ses déclencheurs apparaît dans « Depuis vos skills » sans configuration.
- [x] CA4 Suggestion : l'action du jour et du moment remonte en tête et allume le point ; rien n'est envoyé.
- [x] CA5 Dernière exécution affichée sous chaque action ; relue après un lancement.
- [x] CA6 Jamais d'envoi qui écrase un brouillon ou part pendant un tour.
- [x] CA7 Le bouton n'existe ni en lecture seule (mosaïque), ni au volet de chantier, ni au terminal Teams.
- [x] CA8 Rendu conforme DESIGN_SYSTEM (jetons `--cg-*`, Material), sans débordement horizontal : captures 1366×768 et téléphone vérifiées.

---

## Périmètre

### Hors scope (explicite)

- Formulaire d'édition à l'écran (l'outil `action_rapide` et l'API suffisent).
- Compter un lancement tapé à la main.

---

## Valeurs initiales

Sans objet (aucune entité créée par l'écran).

---

## Contraintes de validation

Sans objet (aucune saisie : clics seulement).

---

## Technique

### Endpoint(s)

Consommés : `GET /api/workspaces/{id}/quick-actions[?skills=false]`, `POST /api/workspaces/{id}/quick-actions/launches`.

### Tables impactées

Aucune directement.

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

- `QuickActionsService` (+ `isSuggestedNow`, `lastRunLabel`, `suggestionLabel`) — nouveau.
- `TerminalQuickActionsComponent` — nouveau (bouton + menu).
- `AtelierTerminalComponent` — entrée `quickActions`, sortie `quickAction`, compteur de fin de tour.
- `AtelierComponent` — `launchQuickAction`, `openHandoff(…, { send, explicit })`.
- `styles.scss` — largeur du panneau `.quick-actions-menu` (overlay).

---

## Plan de test

### Tests unitaires

- [x] `quick-actions.service.spec.ts` — `isSuggestedNow` (jour, matin/après-midi/journée, déjà lancée aujourd'hui), `lastRunLabel`, `suggestionLabel`, appels HTTP (`skills=false`, lancement).
- [x] `terminal-quick-actions.component.spec.ts` — liste légère au chargement, liste complète à l'ouverture, sections, point de suggestion, `launch` émis au clic, message vide / en échec.
- [x] `atelier.component` (bloc « actions rapides » de `atelier.component.spec.ts`) — même terminal déposer / envoyer, envoi refusé si brouillon, autre sujet ⇒ ouverture + envoi, lancement enregistré.

### Tests d'intégration

- [x] Rendu réel `ng build` + captures Chrome headless 1366×768 et 390×844 (menu ouvert, point de suggestion), `scrollWidth ≤ clientWidth`.

### Isolation workspace

- [x] Non applicable côté écran — l'isolation est tenue par l'API (SF-275-01, testée) ; l'écran ne lit que le terminal affiché.

---

## Dépendances

### Subfeatures bloquantes

- SF-275-01 — done ; SF-275-02 — done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Préoccupation transversale Navigation / routing** cochée. Composants impactés : `AtelierComponent.openHandoff` (paramètre d'options ajouté, comportement par défaut inchangé : dépôt seul, refus pendant un tour), `returnFromHandoff`, `followRouteWorkspace`, `HandoffOriginComponent` (texte « à toi de l'envoyer » remplacé par « la phrase est partie » quand elle a été envoyée). Non-régression : les specs `handoff` existantes restent vertes.
- Le lancement depuis le bouton est un geste explicite : il peut quitter un terminal où un tour tourne (le tour continue côté serveur, F-198) ; la passation automatique de `ouvrir_sujet`, elle, garde sa garde.
