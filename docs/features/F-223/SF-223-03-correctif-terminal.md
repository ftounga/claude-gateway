# SF-223-03 — Correctif du terminal : boutons lisibles, reprise différée fiable et visible, dock borné

**Feature :** F-223 · **Statut :** done (2026-11-01) · **Estimation :** < 1 j · **Branche :** `feat/SF-223-03-correctif-terminal`

## Objectif
Fermer les écarts restés ouverts après SF-223-01/02 : boutons pleins lisibles sur le terminal, reprise différée
qui n'envoie que ce que l'utilisateur a voulu et qui se voit, carte du plan qui ne mange jamais le fil, tests
qui vérifient pour de vrai.

## Comportement nominal
1. **Boutons pleins or, texte navy** sur le terminal sombre (`.terminal-view`, hors peau Teams) : libellé
   `--cg-navy` `#0B1020` sur `--cg-accent` (7,19:1 ; blanc 2,63:1 ; `--cg-primary` 4,42:1 insuffisant).
   Documenté dans `DESIGN_SYSTEM.md` §21. Le reste de l'application est inchangé.
2. **Jetons au lieu de couleurs en dur** sur le fond sombre : « Et maintenant ? » (`#9FB0C4` → `--cg-terminal-ink-2`,
   `#F9A825` → `--cg-orange-2`, `#5FD08F` → `--cg-terminal-success`), « À qui la main » et la liste des
   chantiers (`--cg-text-secondary` 2,66:1 / `--cg-error` 2,34:1 → `--cg-terminal-ink-2` / `--cg-orange-2`).
3. **La reprise différée mémorise, AU CLIC, le brouillon et le chantier visé.** À la fin du tour :
   - même chantier affiché et brouillon inchangé → la reprise part avec ce brouillon (comportement SF-176-09) ;
   - brouillon modifié depuis le clic → la reprise part SEULE, la saisie en cours est rendue intacte (rien
     n'est envoyé à la place de l'utilisateur) ;
   - autre chantier affiché → rien ne part, un message le dit (« La reprise du chantier N n'est pas partie… »).
4. **La reprise différée se voit** : sous la carte de décision, une ligne « Reprise en attente : « ✓ Plan v2
   validé — exécution lancée. » partira à la fin du tour. » avec **[Annuler]**.
5. **Dock borné** : la zone de décision (`.terminal-dock__decision`) prend au plus 40 vh et défile à
   l'intérieur ; la rangée d'actions du plan reste collée en bas, toujours visible.
6. **Écran bas suivi en continu** : le repli par défaut du plan suit `matchMedia('(max-height: 760px)')` à chaque
   changement (redimensionnement, rotation), pas seulement au premier rendu ; un choix explicite de
   l'utilisateur (déplier / replier) prime.

## Cas d'erreur
| Situation | Comportement |
|---|---|
| `matchMedia` absent ou qui lève | écran considéré haut, aucun écouteur |
| Changement de sujet avant la fin du tour | reprise abandonnée (inchangé) |
| Annuler la reprise | rien ne part ; le geste (validation) reste enregistré |
| Lecture seule / plafond atteint à la fin du tour | rien ne part (inchangé) |

## Critères d'acceptation
- [x] Bouton plein dans `.terminal-view` : libellé ≥ 4,5:1 sur son fond or (mesuré en Chrome sur couleurs calculées).
- [x] Aucune couleur en dur `#9FB0C4`/`#F9A825`/`#5FD08F` dans `terminal-next-steps` ; hand et liste des chantiers ≥ 4,5:1 sur le navy.
- [x] Brouillon modifié pendant le tour : un envoi = message seul, puis saisie restaurée.
- [x] Chantier changé pendant le tour : 0 envoi, message affiché.
- [x] Ligne « Reprise en attente » visible pendant l'attente, disparaît à l'envoi ; [Annuler] → 0 envoi.
- [x] `.terminal-dock__decision` : `max-height` 40vh, `overflow-y: auto` ; actions du plan `position: sticky`.
- [x] Un changement de la requête média replie / déplie le plan sans recharger.
- [x] Le test « liste bornée » n'a plus de branche conditionnelle.

## Plan de test
- **Unitaires (Karma, Chrome réel)** : `terminal-journey-resume.spec.ts` (brouillon mémorisé, brouillon modifié,
  chantier changé, ligne affichée, annuler, liste bornée sans `if`, requête média continue via `LOW_SCREEN_MEDIA`
  factice) ; `terminal-contrastes-f223.spec.ts` (bouton plein or, hand, liste des chantiers).
- **Intégration** : suite front complète (`npm run build && npm test`).
- **Isolation utilisateur** : non applicable — aucun nouvel accès données ni endpoint (gestes existants, `requireOwned`).
- **Captures** : `captures/f223-1440x900.png` (plan déplié, étapes qui défilent, actions collées, bouton or/navy, reprise en attente) et `captures/f223-1366x700.png` (écran bas : plan replié, actions visibles). Banc : DOM du terminal rendu en Chrome par Karma + feuille globale du build, sans polices d'icônes (les noms de ligature apparaissent). **390×844 non fournie** : le banc n'a pas la coquille mobile (fenêtre défilante, composeur collant) — rendu non représentatif ; la borne 40 vh et `flex-shrink: 0` sont vérifiées par spec.

## Impact
`styles.scss`, `atelier-terminal.component.(html|ts)`, `atelier-terminal-dock.component.scss`,
`terminal-journey-strip.component.ts`, `terminal-next-steps.component.ts`, `terminal-hand.component.ts`,
`terminal-journey-chantiers.component.ts`, specs, `DESIGN_SYSTEM.md` §21.
Aucune table, aucun endpoint, aucun backend.

**Préoccupations transversales :** aucune (ni auth, ni tenant, ni quota, ni route).

## Arbitrages (gates 🟠 réversibles, décidés par défaut)
- **Navy = `--cg-navy` `#0B1020`**, pas `--cg-primary` (4,42:1 sur l'or, sous l'AA). Borné au terminal sombre ; le
  blanc sur or du reste de l'application reste un point de charte non tranché.
- **Brouillon modifié pendant le tour → reprise seule + saisie rendue.** Alternative écartée : envoyer le brouillon
  courant (envoie à la place de l'utilisateur un texte qu'il n'a pas validé) ; ou bloquer la reprise jusqu'à
  Entrée (le clic ne ferait plus avancer).
- **Chantier changé → reprise abandonnée et dite.** Alternative écartée : l'envoyer quand même (l'agent
  l'appliquerait au chantier affiché, pas à celui validé).
- **Borne 40 vh sur la zone de décision**, le tiroir du dock (superposé au fil, 60 vh) inchangé.

## Hors périmètre
Blanc sur or hors terminal ; refonte du tiroir du dock ; backend.
