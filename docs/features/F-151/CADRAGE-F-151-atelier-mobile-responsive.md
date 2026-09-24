# F-151 — Atelier mobile (responsive du chemin critique)

> Cadrage du 2026-09-24, à la demande du PO (« version mobile : piloter les terminaux depuis le
> téléphone en me déplaçant »).
> Source : audit `docs/audits/AUDIT-2026-09-24-version-mobile-remote-control.md` (§2 Lot A, §5 palier 1).
>
> **Ce document cadre ; il ne livre aucun code.** Palier 1 des 3 paliers de la version mobile
> (F-151 responsive → F-152 PWA installable → F-153 notifications).

## 0. Objectif

Rendre **confortable au doigt** le **chemin critique** du pilotage à distance — pas toute l'application.
Le pilotage lui-même **existe déjà** (F-84) : un téléphone connecté au compte **voit et pilote déjà** les
terminaux du PC (attach / steer / interrupt / confirm, relais cross-pod). Ce qui manque est purement
visuel : trois écrans débordent sous ~820 px. F-151 étend le **responsive déjà fait pour `/forge` et la
Vigie** à ces écrans. **Pur frontend.**

## 1. Ce qui existe déjà (vérifié — NE PAS reconstruire)

- **Pilotage à distance = F-84** (« le tour vit côté serveur ») : le tour tourne sur la gateway, le SSE
  n'est qu'une vue, l'attente d'autorisation est un **état interrogeable**, le runner est lié à
  l'**utilisateur pas à l'appareil**, relais cross-pod. Un téléphone au navigateur **pilote déjà**. **On
  ne touche pas au pilotage.**
- **Le précédent responsive existe et fait autorité** : `_forge-layout-shell.scss:148` — bascule
  `grid-template-columns` → `minmax(0,1fr)` sous **819 px**, liste plein écran vs détail plein écran avec
  lien retour, **la même URL sert les deux tailles** (`DESIGN_SYSTEM.md:686`). La Vigie applique la même
  forme (`DESIGN_SYSTEM.md:777`, empilement par paliers 640/860/1020 px). **Patron à étendre, pas à
  inventer.**
- **F-126** (« questions repérables navigateur ») = **navigation intra-page** (rail « Vos questions »,
  ancres) — **ce n'est pas** une adaptation mobile et **pas** une notification. Sans rapport avec F-151.

## 2. Ce qui manque (constat de l'audit, cité `fichier:ligne`)

- **Shell global** : `shell.component.scss` — `.app-nav` porte **7 liens horizontaux, 0 `@media`, pas de
  hamburger** → déborde à 400 px. Porte d'entrée de l'app en mobile.
- **Shell Atelier** : `atelier.component.scss:9` = `grid-template-columns: 280px 1fr`, **0 `@media`** → la
  sidebar fixe de 280 px laisse ~120 px au terminal. **C'est l'écran du besoin.**
- **Barre d'outils du terminal** : nombreux boutons (slash, mentions, dictée, dépôt, Teams…) + boutons de
  décision (**autoriser / refuser / interrompre**) non dimensionnés pour le pouce à 400 px.

## 3. Décisions de conception

- **D1 — Étendre le patron `/forge`, ne pas inventer.** Réutiliser le point de rupture **819 px** et la
  forme « liste plein écran ↔ détail plein écran, même URL » du DESIGN_SYSTEM. Aucune nouvelle route.
- **D2 — Charte stricte.** Jetons `--cg-*` uniquement, aucune couleur/police hors `DESIGN_SYSTEM.md` ; or
  de marque réservé aux gestes (charte §8) ; cibles tactiles conformes (≥ 44 px recommandé).
- **D3 — Zéro régression desktop.** Tout est sous `@media (max-width: 819px)` (ou paliers dédiés) ; le
  rendu ≥ 820 px est **byte-identique**. Aucune logique métier touchée.
- **D4 — Pur frontend, display-only.** Aucun endpoint, aucune migration, aucun DTO, aucun changement de
  protocole runner, **aucun composant cluster**. Gateway-First et Provider Independence intactes par
  construction (aucun appel modèle touché).
- **D5 — Jamais de défilement horizontal** (règle DESIGN_SYSTEM/responsive) : les barres d'outils passent
  à la ligne ou se replient (menu « … »), jamais de scroll-x.

## 4. Découpage en subfeatures (≈ 3)

| SF | Titre | Contenu | Impact |
|----|-------|---------|--------|
| **SF-151-01** | Shell global responsive (nav → menu repliable) | `shell.component.{scss,html,ts}` : sous 819 px, les 7 liens deviennent un **menu repliable/hamburger** accessible (aria-expanded, fermeture au clic hors zone, au changement de route et à Échap) ; ≥ 820 px inchangé. | `shell.component.*` + spec |
| **SF-151-02** | Shell Atelier responsive (280px 1fr → drawer / 1 colonne) | `atelier.component.scss` : sous 819 px, la sidebar projets (280 px) devient un **drawer**/liste plein écran ; le terminal prend toute la largeur ; navigation liste↔terminal sur le modèle `/forge` (même URL). | `atelier.component.{scss,html,ts}` + spec |
| **SF-151-03** | Barre d'outils terminal + boutons de décision au pouce | Barre d'outils (slash/mentions/dictée/dépôt/Teams) repliée sous un « … » ou passée à la ligne ; **boutons autoriser/refuser/interrompre** dimensionnés tactile et toujours atteignables sans scroll-x. | composant terminal (`.scss/.html`) + spec |

**Ordre** : SF-151-01 → SF-151-02 → SF-151-03 (indépendantes en pratique ; cet ordre livre la valeur de
la nav d'abord, puis l'écran du besoin, puis le geste). Chaque SF est démo-able isolément.

## 5. Préoccupations transversales

- **Navigation / routing** : SF-151-01/02 introduisent un repli d'affichage **sans nouvelle route ni
  guard** (même URL aux deux tailles, patron `/forge`). Analyse d'impact à confirmer en mini-spec :
  vérifier que les liens existants du shell et les redirections restent joignables au format replié.
- **Auth / tenant / plans** : non touchés (pur affichage).

## 6. Garde-fous

Gateway-First, Provider Independence, isolation `user_id` (aucun accès données touché), DESIGN_SYSTEM
strict, **aucun composant cluster**, aucune migration, aucun endpoint, aucun changement runner.

## 7. Hors périmètre

- Rendre responsive **toute** l'app (seul le chemin critique de pilotage l'est ici).
- L'installabilité / l'icône sur l'écran d'accueil → **F-152**.
- Toute notification (in-tab ou push) → **F-153**.
- Toute modification du pilotage F-84.

## 8. Drapeaux

Aucun drapeau de déploiement pour F-151 (pur frontend, aucun secret, aucune migration).
