# CADRAGE F-223 — Terminal lisible et « Valider » actif tout de suite

**Statut :** cadrage du 2026-10-31 (issu des audits du 31/10), à compléter par l'agent de livraison (découpage en SF)

**Demande PO (2026-10-31)** : deux défauts qu'il subit « depuis des mois » :
- le bouton Valider ou Amendement reste grisé 30 s à 1 min ;
- à plusieurs endroits du terminal, le texte est très sombre sur fond sombre.

## Bug 1 : Valider grisé (cause prouvée)
- Le plan arrive en plein tour (`AtelierChatService.java:4143-4153`, `onJourney`) et la carte s'affiche aussitôt.
- Mais les boutons sont liés à `[busy]="journeyBusy() || submitting"` (`atelier-terminal.component.html:1660,1674`) et à `[disabled]="busy()"` (`terminal-journey-strip.component.ts:36-37,121-122,133-134,192,195`).
- Ils restent donc désactivés jusqu'à la fin du tour (`atelier.component.ts:2404`). Or l'agent rédige encore sa présentation et ses suites, sur la consigne « présente-le » de `JourneyToolExecutor.java:98-102`.
- Le défilement n'y est pour rien : le bouton se dégrise quand le tour se termine.

**Correctif :**
- Ne bloquer les boutons que pendant l'envoi du geste (`journeyBusy`).
- « Valider et lancer » pendant un tour : la validation part tout de suite, et la reprise est différée à la fin du tour (mécanisme existant `deferredPrecisions` / `flushDeferredPrecisions`). « Valider sans lancer » reste actif en permanence.
- Si un bouton reste désactivé, dire pourquoi.
- La consigne de `set_subject_plan` devient « une phrase au plus, puis termine ton tour ».
- La carte du plan prend au plus environ 40 vh, avec défilement interne, et sa rangée d'actions reste toujours visible. Plan replié par défaut quand l'écran est bas. Aujourd'hui, la carte fait 417 px ; à 1366×700, il ne reste que 119 px pour la conversation, et sur téléphone le bouton est sous la saisie.

## Bug 2 : contrastes (mesurés, seuil WCAG AA 4,5:1)
Le terminal a un fond bleu marine (`--cg-primary` #1A3A5C) sans couleur de texte définie : les variables `--cg-*` ne sont pas redéfinies pour ce fond, et les jetons Material fuient dans les panneaux clairs posés dessus.

| Endroit | Contraste | Correctif |
|---|---|---|
| Titres des étapes, « Plan v2 · 4 étapes » (`journey-plan__title` et `__toggle`, couleur héritée) | 1,24 | Couleur claire (par exemple `--cg-divider`, 9,1) sur `.journey-strip`, `.journey-card` et `.journey-closed` |
| « Et maintenant ? » (`h3` global `styles.scss:102-105`, sur `.cap` dans `terminal-next-steps.component.ts:313`) | 1,16 | `.cap__title { color: inherit }` |
| Bouton grisé (encre Material à 38 %) | 1,19 | Redéfinir `--mdc-filled-button-disabled-*` dans `.terminal-view` |
| Texte secondaire (`--cg-text-secondary` #6B7A8D) : phases, cadenas, Vérifier / Retour arrière / Preuve, statut et risque, onglets | 2,66 | Encre secondaire pour fond sombre (par exemple #B8C2CF, 6,5) |
| Risque externe (`--cg-error`) | 2,34 | `--cg-orange-2` (5,05) |
| « fait » et phases passées (`--cg-success`) | 4,05 | Vert plus clair, par exemple #4CC38A ; à ajouter à `DESIGN_SYSTEM.md` |
| Tableau des chantiers : « Avancer jusqu'au blocage », « Mettre au focus », « Faire cette étape » en blanc sur blanc ; « Voir la preuve », « Relancer l'attente » | ≈1,0 et 1,28 | Dans `.board` (`terminal-chantiers-board.component.ts:400`), remettre `--mdc-outlined-button-*` et `--mdc-text-button-label-text-color`. Vérifier `terminal-actions-panel` et `session-bilan-panel` |

**Hors périmètre** : le blanc sur or des boutons pleins de toute l'application (2,63) est un point de charte. Il est signalé, pas changé.

**Preuve exigée** : contrastes recalculés sur les couleurs rendues, captures à 1440×900, 1366×700 et 390×844.
