# SF-226-03 — Un seul vocabulaire partout, overlay à deux boutons, fils actifs relus en fin de tour

**Feature :** F-226 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-226-03-un-seul-vocabulaire`

Subfeature corrective : une relecture de F-226 a trouvé trois incohérences dans le code en prod (vérifiées sur `origin/main`).

## Objectif
Les gestes sur un chantier se disent avec trois verbes et trois seulement — **Lancer**, **Afficher**, **Reprendre la main** — sur tous les écrans, et la suggestion ne propose jamais un chantier déjà en course.

## Comportement attendu

### Cas nominal
1. **Vocabulaire unique** (terminal, tableau des chantiers, tableau du client, overlay, suites, info-bulles, `aria-label`, snackbars, palette `/`) :
   - **Lancer** — dans le terminal, quand le chantier part dans le volet de droite : **Lancer à côté**.
   - « Jusqu'au blocage » n'est plus un verbe concurrent : c'est un **qualificatif du lancement** — « Lancer jusqu'au blocage » / « Lancer à côté jusqu'au blocage ».
   - **Afficher** remplace « Mettre au focus » et, au tableau du client, « Ouvrir » (« Répondre » reste quand une question attend).
   - **Reprendre la main** inchangé.
   - Tableau du client : lancement groupé **« Lancer (k) »** et **« Lancer jusqu'au blocage (k) »**.
   - Plus aucun « Lancer en autonome », « Avancer jusqu'au blocage », « Mettre au focus » ni « Afficher à côté » visible. Les **états** (« en autonome — étape 3/7 », « chantiers autonomes en cours ») restent : ce ne sont pas des gestes.
   - Snackbars : « Chantier N lancé. » / « Chantier N lancé jusqu'au blocage. » ; groupé : « k chantiers lancés ».
2. **Overlay « Lancer le chantier N à côté ? »** : deux boutons d'action au plus (plus [Annuler]) :
   - chantier qui peut partir : **[Lancer à côté]** (principal) + **[Afficher ici]** si l'overlay vient d'une suite ;
   - chantier qui ne peut pas partir : **[Afficher ici]** seul (principal), avec l'explication « ce qui l'empêche » et la phrase « Affichez-le ici pour voir ce qui l'empêche et le débloquer ».
   - « Afficher à côté » (ouvrir le volet de droite sans rien lancer) disparaît : la décision `open` est supprimée.
3. **Fils actifs relus** (`activeLanes`, lus par la puce de suggestion) : à chaque **fin de tour**, après un **lancement à côté** (succès ou refus), et après un **lancement / une reprise de main depuis le tableau des chantiers** (nouvel événement `lanesChanged` du tableau relayé par le terminal). Lecture seule de `GET /api/workspaces/{id}/chat/lanes`, sans toucher au split.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Lancer à côté un chantier qui tourne déjà (course démarrée ailleurs) | snackbar « Le chantier N tourne déjà : il s'affiche à droite. », suggestion de ce chantier masquée, fils actifs relus, volet de droite ouvert | 409 `autonomy_subject_busy` |
| Autre refus du lancement (limite de 4, poste hors ligne, pas prêt) | message du serveur en snackbar, suggestion de ce chantier masquée, fils actifs relus | 409 |
| Relecture des fils actifs en échec | silencieuse : on garde la dernière liste connue | 5xx / réseau |
| Chantier non lançable dans l'overlay | seul [Afficher ici] est offert, avec le pourquoi | — |

## Critères d'acceptation
- [x] Aucun « Lancer en autonome », « Avancer jusqu'au blocage », « Afficher à côté » dans les gabarits du tableau du client, du tableau des chantiers et de l'overlay (test sur le rendu).
- [x] Tableau des chantiers : « Lancer » / « Lancer jusqu'au blocage » sur le chantier affiché, « Lancer à côté » / « Lancer à côté jusqu'au blocage » sur un autre.
- [x] Tableau du client : « Lancer », « Lancer jusqu'au blocage », « Afficher », « Lancer (k) », « Lancer jusqu'au blocage (k) » (textes, info-bulles, `aria-label`).
- [x] Overlay : 2 boutons d'action au plus ; chantier non lançable → [Afficher ici] seul, décision `switch`.
- [x] Fin de tour → `getLanes` relu et `activeLanes` à jour, sans réévaluer le split.
- [x] 409 au lancement à côté → message clair, suggestion masquée, fils relus.
- [x] Suite front complète verte (hors 7 échecs préexistants d'`atelier-terminal.component.spec`, identiques avant/après), build OK.

## Plan de test
- **Unitaires** : `parallel-launch-dialog.component.spec` (nouveau : 2 boutons, non lançable → [Afficher ici] seul, pas de « Afficher à côté ») ; `client-board.component.spec` (libellés, absence des anciens verbes, groupé « Lancer (k) ») ; `terminal-chantiers-autonomy.spec` (« Lancer » / « Lancer à côté », absence des anciens verbes, `lanesChanged` après lancement et reprise) ; `atelier.component.spec` (relecture des fils en fin de tour, après un lancement à côté, 409 → suggestion masquée) ; `client-board.models.spec` (résumé groupé).
- **Intégration** : aucune route nouvelle ni modifiée (relecture de `GET /api/workspaces/{id}/chat/lanes` existante).
- **Isolation utilisateur** : inchangée — aucun appel nouveau, les routes existantes filtrent déjà par `user_id`.
- Suite front complète + `ng build`.

## Impact
- Composants : `client-board.component.{html,ts}`, `client-board.models.ts`, `terminal-chantiers-board.component.ts`, `parallel-launch-dialog.component.ts`, `atelier.component.{ts,html}`, `atelier-terminal.component.{ts,html}`, `terminal-lane-pane.component.ts` (relais), `slash-commands.ts` (descriptions), `postes.component.html` (commentaire), specs associées.
- Tables : aucune. Endpoints : aucun nouveau (lecture `…/chat/lanes` existante, plus fréquente : une par fin de tour).
- Préoccupations transversales : **Navigation / routing** — aucune route ni guard ; « Afficher » au tableau du client garde le même chemin (focus puis terminal). Auth, tenant, plans/limites : non touchés.

## Contraintes de validation
Aucun champ saisi : sans objet.

## Hors périmètre
- Messages d'erreur rédigés par le serveur (« pas prêt à partir en autonome ») : inchangés.
- Noms des commandes `/autonome` et `/avancer` (seules leurs descriptions changent).
- Libellés d'état (« en autonome », « chantiers autonomes en cours »).
