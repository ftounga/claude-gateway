# Mini-spec — [F-158 / SF-158-17] Guide d'accueil et rappel poste : non bloquants sur téléphone

> Correctif P0 mobile. Pur frontend, display-only. Desktop (≥ 820 px) inchangé.

---

## Identifiant

`F-158 / SF-158-17`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

> Rattachement : F-158 est le foyer des correctifs P0 « terminal inutilisable sur téléphone »
> (SF-158-10 containment, SF-158-11 rangées de contrôle, SF-158-16 fil railed). Les deux encarts
> visés (`app-atelier-guide` de F-53, `app-workstation-notice` de F-57) sont des **surfaces
> superposées au terminal** sur `/atelier/:id` : leur repli mobile relève de la même ligne P0.

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-17-overlays-guide-notice-mobile`

---

## Objectif

> En une phrase : sur téléphone (≤ 819 px), rendre le guide « Vos premiers pas » et le rappel
> « poste d'entreprise » **repliés par défaut** en chips compactes qu'on déploie au tap, pour que le
> terminal soit immédiatement visible et utilisable — sans rien changer au desktop.

---

## Comportement attendu

### Cas nominal

- **Guide (`app-atelier-guide`, F-53)** — Sur ≤ 819 px, le panneau s'affiche **replié** : une chip
  compacte ancrée en haut à droite « Vos premiers pas · n/3 ». Au tap, elle se déploie sur le panneau
  complet existant (étapes, actions, conclusion, « Masquer le guide »). Un bouton **réduire**
  (mobile uniquement) rereferme sur la chip. La croix « Masquer le guide » (`dismiss`) reste
  disponible dans le panneau déployé. Le terminal reste visible derrière la chip repliée.
- **Rappel poste (`app-workstation-notice`, F-57)** — Quand il est dû (`visible()`), sur ≤ 819 px il
  s'affiche **replié** : une chip compacte ancrée en bas à gauche « Note poste ». Au tap, elle se
  déploie sur le bandeau complet (texte, « Me le rappeler : … », « Compris »). Un bouton **réduire**
  (mobile uniquement) referme sur la chip. « Compris » (`acknowledge`) et le choix de périodicité
  restent disponibles dans le bandeau déployé.
- **Desktop (≥ 820 px)** — Aucun changement : les chips sont `display:none`, le panneau/bandeau
  complet est toujours rendu comme aujourd'hui, l'état « déployé » local est ignoré (forcé visible
  par CSS).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| `localStorage` indisponible (guide/notice) | Inchangé : la persistance vit dans les services (`AtelierGuideService`, `WorkstationNoticeService`), non touchés. L'état replié/déployé est un signal **local** de session, non persisté. |
| Guide déjà `dismissed`/`done` | Inchangé : `guide.visible()` gouverne toujours la présence ; la chip n'apparaît que quand le guide est visible. |
| Rappel non dû | Inchangé : `@if (visible())` gouverne toujours la présence ; ni chip ni bandeau. |

---

## Critères d'acceptation

- [ ] Sur ≤ 819 px, à l'affichage, le guide est **replié** (chip visible, panneau caché).
- [ ] Sur ≤ 819 px, à l'affichage, le rappel dû est **replié** (chip visible, bandeau caché).
- [ ] Le tap sur une chip déploie le panneau/bandeau complet ; un bouton réduire (mobile) referme.
- [ ] Les actions existantes restent joignables une fois déployé : guide → « Créer un projet »,
      « Connecter mon poste », « Écrire dans le terminal », « Vérifier mon poste », « Terminer »,
      « Masquer le guide » ; notice → « Me le rappeler : … » et « Compris ».
- [ ] La logique/les bindings existants sont intacts : `steps`, `projectOpen`, `turnFailed`,
      `@Output` du guide ; `visible()`, `acknowledge()`, `chooseInterval()` de la notice.
- [ ] Desktop (≥ 820 px) : chips absentes du rendu (CSS `display:none`), panneau/bandeau rendus à
      l'identique — garde-fou test unitaire : la chip existe dans le DOM (masquée par media query),
      l'état déployé par défaut `false`, `expand()`/`collapse()` basculent le signal.
- [ ] DESIGN_SYSTEM : jetons `--cg-*` uniquement, cibles tactiles ≥ 44 px, gouttières ≥ 16 px,
      point de rupture unique 819 px (`styles/_breakpoints.scss`), pas de scroll horizontal.
- [ ] `npm run build` vert ; Karma ciblé (guide + notice) vert.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px) — rigoureusement inchangé.
- Toute modification des services de persistance (`AtelierGuideService`, `WorkstationNoticeService`).
- Toute couleur/police nouvelle ; toute logique métier ; tout endpoint/migration/DTO.
- La coquille (F-151), la PWA (F-152), les notifications (F-153).

---

## Technique

### Endpoint(s)

Aucun. Pur frontend, display-only.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants Angular

- `AtelierGuideComponent` — ajout d'un signal local `expanded` (défaut `false`), `expand()`/
  `collapse()`, chip repliée + bouton réduire (mobile), wrapper `.guide-panel`. Feuille : chip +
  bloc `@include bp.phone`.
- `WorkstationNoticeComponent` — ajout d'un signal local `expanded` (défaut `false`), `expand()`/
  `collapse()`, chip repliée + bouton réduire (mobile). Feuille : chip + bloc `@include bp.phone`.

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés |
|---------------|-------------|---------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |

> Aucune préoccupation transversale cochée : changement purement display sur deux composants isolés,
> sans binding partagé ni route.

---

## Plan de test

### Tests unitaires (Karma, ciblés guide + notice)

- [ ] Guide — `expanded` vaut `false` par défaut (replié).
- [ ] Guide — `expand()` puis `collapse()` basculent le signal ; la chip « Vos premiers pas » et le
      compteur sont présents dans le DOM.
- [ ] Guide — non-régression : toutes les specs existantes passent (étapes, actions, dismiss, finish,
      première commande, tour en échec).
- [ ] Notice — `expanded` vaut `false` par défaut (replié) ; la chip « Note poste » est présente.
- [ ] Notice — non-régression : « Compris » referme et repart le compteur ; périodicité et « jamais ».

### Tests d'intégration

- [ ] Non applicable (pur frontend display-only, aucun endpoint).

### Isolation workspace

- [ ] Non applicable — aucun accès données, aucun `user_id`.

---

## Notes et décisions

- **Repli local, non persisté** : l'état déployé/replié est un signal de composant, propre à la
  session — le tap ne doit pas être « mémorisé » d'une visite à l'autre (la persistance qui compte,
  avancement du guide et acquittement du rappel, reste dans les services et n'est pas touchée).
- **Desktop garanti inchangé par CSS** : les chips sont `display:none` hors media 819 px, et le
  panneau/bandeau est toujours affiché sur desktop indépendamment du signal `expanded` (le
  `.--collapsed` ne masque le contenu que sous `@include bp.phone`). Aucune branche JS ne dépend de
  la largeur d'écran.
