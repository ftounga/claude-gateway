# Mini-spec — [F-236 / SF-236-18] Personnes et attentes, mur de tickets, étagère « À envoyer »

## Identifiant
SF-236-18

## Feature parente
F-236 — Jarvis en 3D (cadrage §12.1 « tableaux flottants », §13 NF-5 / NF-6, §15 ; itération 3 « Le cockpit PC »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-18-personnes-tickets`

---

## Objectif

Voir qui j'attends et à qui un message est prêt, retrouver les tickets vus pendant la session, et pouvoir poser ces
tableaux dans l'univers à côté de leur client.

## Comportement attendu

### Cas nominal

1. **Personnes et attentes (NF-5)** — `peopleMap` (pur) : par client, chaque personne **connue** (`people`),
   **attendue** par un chantier (`waitingOn`) ou **destinataire** d'un message prêt (`toSend`) ; un nom attendu qui
   désigne une personne connue (nom, variante, prénom) ne fait qu'une ligne ; la clé est celle du **nœud personne**
   de l'univers 3D (filament au survol). Ligne : nom, client · rôle, mot « attendue (n) · message prêt » ; liste des
   chantiers en infobulle. Attendus d'abord, puis destinataires, puis le reste. Panneau gauche.
2. **Étagère « À envoyer »** — par client, les destinataires des messages prêts (jamais d'objet ni de corps).
   Panneau droit ; filament vers le client.
3. **Mur de tickets (NF-6)** — les tickets **vus pendant la session** (mémoire de session SF-236-09 : clés seulement,
   30 min), groupés par poste, plus récents d'abord. Panneau droit. Aucun appel (pas de relecture d'état).
4. **Tableaux flottants (L4)** — chacun des trois tableaux se **détache** (bouton) : il quitte le panneau
   (« Posé dans l'univers. ») et flotte à côté de son client (celui du sujet courant, sinon le premier concerné),
   suivi image par image hors zone, empilé, gardé dans la zone libre entre les panneaux ; « Rattacher » le remet.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Aucune personne / message / ticket | Section absente. |
| Client hors écran ou nœud non posé | Le tableau se pose en haut de la zone libre. |
| Personne attendue inconnue de `people` | Ligne avec le nom tel qu'attendu, sans rôle. |
| Cockpit quitté (fenêtre étroite, 2D) | Tableaux flottants retirés avec le cockpit ; boucle arrêtée. |

---

## Critères d'acceptation

- [x] CA1 — Graphe des personnes = `people` + `waitingOn` + `toSend` (tests ; clé = nœud 3D vérifiée sur la
  disposition).
- [x] CA2 — Tickets limités à la session (mémoire de session ; test du mur).
- [x] CA3 — Détacher / rattacher ; tableau ancré au client projeté, borné dans la zone libre (tests).
- [x] CA4 — Capture `cockpit-personnes` (deux tableaux détachés) non vide, sans erreur console.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Tableau flottant | 260 px de large, ≤ 200 px de haut, décalé de 40 px à droite du client, empilé de 150 px |
| Étagère | destinataires seulement (jamais objet ni corps) |

## Plan de test

- Unitaires : `hud/jarvis-people-map.spec.ts` (graphe, ordre, étagère, mur, placement flottant).
- Composant : `hud/jarvis-cockpit.component.spec.ts` (sections, tickets de la scène, détacher, ancrage, rattacher).
- Isolation utilisateur : sans objet (univers déjà filtré `user_id`, mémoire locale à l'écran, aucun appel).
- Visuel : banc `cockpit-personnes`.

## Périmètre

### Impacté
`hud/jarvis-people-map.ts`, `hud/jarvis-cockpit.component.{ts,html,scss}` (sections, tableaux flottants,
`NgTemplateOutlet`), `jarvis-stage-3d.component.ts` (`session` lisible), banc (`cockpit-personnes`).

### Hors scope (explicite)
- « Qui attend quoi de moi » : l'univers ne porte que ce que **j'**attends (`waitingOn`) ; rien n'est inventé.
- Déplacer un tableau à la main (glisser) ; aucun endpoint, table, appel IA ni consigne.

## Préoccupations transversales
Aucune cochée.

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Tableaux flottants en **DOM** ancrés par projection (pas en 3D texturée). | Texte net, même verre que les panneaux (T6). | Plan texturé dans la scène. | Oui. |
| A2 | Ancre = client du sujet courant, sinon le premier concerné. | Un seul client lisible à la fois. | Un tableau par client. | Oui. |
| A3 | Personnes dans le panneau gauche ; étagère et tickets à droite (cadrage §12.1). | Gauche = état du monde, droite = ce qui est en cours / à traiter. | Tout à gauche. | Oui. |
| A4 | « Qui attend quoi de moi » non affiché. | Aucune donnée dans l'univers ; ne rien inventer. | Déduire des messages. | Oui. |
