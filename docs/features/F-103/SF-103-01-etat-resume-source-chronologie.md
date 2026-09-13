# Mini-spec — F-103 / SF-103-01 — État, résumé sourcé, chronologie

## Identifiant

`F-103 / SF-103-01`

## Feature parente

`F-103` — Le Radar, la page sujet (cadrage commun : `docs/features/F-99/CADRAGE-le-radar.md` §4, §8,
§12 bis ; maquette : `docs/features/F-99/maquette-radar.html`, **maquette 2 « Un sujet »**)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-103-01-page-sujet`

---

## Objectif

Donner à chaque sujet du Radar **sa page dans la Vigie** (`/vigie/:hostRef/sujets/:id`) : son état,
sa prochaine étape et son échéance, **un résumé dont chaque phrase renvoie à sa preuve**, et la
**chronologie multi-sources** avec ses citations et ses liens profonds.

---

## Comportement attendu

### Cas nominal

1. **La route** `/vigie/:hostRef/sujets/:subjectId` ouvre la page sujet (elle redirigeait vers
   l'onglet Radar depuis SF-106-04). Changer de sujet dans l'adresse recharge la page sans recréer
   l'écran.
2. **Lecture** : `GET /api/radar/hosts/{hostId}/subjects/{subjectId}` (F-99, existant) et, en
   parallèle, l'annuaire `GET /api/radar/hosts/{hostId}/people` (pour nommer l'auteur d'une preuve)
   et la liste des clients `GET /api/runner-hosts/spaces` (pour le nom du client dans le fil
   d'Ariane). Un annuaire ou une liste de clients illisible **n'empêche pas** la page : l'auteur
   n'est pas nommé, le client s'appelle « Client ».
3. **En-tête** : fil d'Ariane *Client › Radar › Sujets* (liens vers `/vigie/:hostRef` et son onglet
   Radar), nom du sujet, **pastille d'état écrite** (§5, jamais la couleur seule), mention « votre
   correction » quand l'état est souverain (SF-99-02).
4. **Trois cases** : *Où on en est* (état en mots, dernière activité), *Prochaine étape* (ou « non
   connue »), *Échéance connue* (ou « aucune »), chacune avec **ses renvois** (numéros de preuve) et
   la mention « votre note » quand la valeur est souveraine.
5. **Le résumé** est rendu **phrase par phrase** ; chaque phrase porte ses renvois en exposant
   (`¹ ²`). Les numéros sont attribués **dans l'ordre d'apparition** (résumé d'abord, puis état,
   prochaine étape, échéance, signal de clôture), et **le même numéro** désigne la même preuve
   partout sur la page. Un renvoi est un lien vers l'entrée de la chronologie (`#preuve-<id>`).
6. **La chronologie**, plus récente d'abord : date en mots (« aujourd'hui 14:32 », « hier 09:10 »,
   « 12 sept. »), **icône et libellé de la source** (message Teams, réunion Teams, enregistrement
   hors Teams, votre nouvelle, courriel collé), auteur s'il est connu, numéro de renvoi, **citation
   courte**, et le **lien profond** : « Ouvrir dans Teams » (message), « Ouvrir le moment · HH:MM:SS »
   (réunion, à la seconde), « Ouvrir la transcription » (enregistrement). Le lien s'ouvre dans un
   nouvel onglet (`rel="noopener noreferrer"`).
7. **États particuliers**, en bandeau au-dessus du résumé :
   - **`clos ?`** : « Le Radar pense que ce sujet est terminé » + les renvois du signal de clôture ;
   - **réveillé** (sujet clos avec nouvelle activité) : « Ce sujet clos se réveille » ;
   - **en sommeil** : « Rien n'a bougé depuis le … » ;
   - **fusionné** : « Ce sujet a été fusionné dans un autre » + lien vers la page du sujet cible.
8. **Téléphone** (< 900 px) : une seule colonne.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Sujet inconnu, d'un autre poste ou d'un autre compte | « Ce sujet n'existe pas, ou plus » + lien vers le Radar du client ; rien d'autre n'est dit | 404 |
| Poste d'autrui / inconnu | même écran que le sujet introuvable | 404 |
| Client retiré de la Vigie | « Ce client n'est pas dans la Vigie » + lien vers `/vigie` | 409 `host_not_in_space` |
| Pas de droit Vigie | la présentation de l'espace (`app-space-pitch`), jamais une erreur | 403 |
| Gateway injoignable | « Le sujet n'a pas pu être lu » + *Réessayer* | 0 / 5xx |
| Lien profond qui n'est pas `https://` | citation affichée, **aucun lien** rendu | — |
| Résumé vide | « Pas encore de résumé : il s'écrit à la prochaine analyse. » | — |

---

## Critères d'acceptation

- [ ] `/vigie/h1/sujets/s1` charge la page sujet (plus de redirection) sous la route authentifiée.
- [ ] La page appelle `GET /api/radar/hosts/h1/subjects/s1` et affiche nom, état en mots, prochaine
      étape, échéance.
- [ ] Chaque phrase du résumé porte ses numéros de renvoi ; une même preuve a le même numéro partout ;
      chaque renvoi pointe `#preuve-<id>`.
- [ ] La chronologie est rendue plus récente d'abord, avec libellé de source, citation, auteur nommé
      quand l'annuaire le connaît, et le bon libellé de lien profond par source (seconde de réunion).
- [ ] Un lien profond non `https://` n'est pas rendu.
- [ ] 404 → écran « introuvable » ; 409 → « pas dans la Vigie » ; 403 → présentation de l'espace ;
      erreur réseau → *Réessayer* qui relit.
- [ ] Sujet fusionné → lien vers la page du sujet cible ; `clos ?`, réveillé, en sommeil → bandeau.
- [ ] **Isolation** : aucune donnée n'est lue sans le poste de l'URL ; la gateway (F-99) vérifie
      possession du poste, activation Vigie et `user_id` + `host_id` sur chaque lecture — prouvé par
      les tests d'isolation existants de F-99, rejoués.
- [ ] Aucune couleur hors charte (§2, §5).

---

## Périmètre

### Hors scope (explicite)

- Les personnes, « ce que le Radar ne sait pas » et à qui demander (SF-103-02).
- La réponse préparée pour le manager (SF-103-03).
- L'annuaire (SF-103-04).
- Les **gestes** sur le sujet (*fait / pas moi / reporter / clore*, confirmer un `clos ?`) : F-102
  (SF-102-02). La page sujet les **lit**, elle ne les porte pas.
- L'onglet Radar principal et ses liens vers la page sujet : F-102, livrée en parallèle.
- L'annulation depuis la chronologie (F-104 / SF-104-02).
- Le §17 de `DESIGN_SYSTEM.md` (F-102 / SF-102-03) : cette page n'emploie que les pastilles §5.

---

## Valeurs initiales

Aucune : lecture seule.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `hostRef` (URL) | Oui | — | identifiant de poste | — | — |
| `subjectId` (URL) | Oui | — | UUID ; un identifiant malformé rend 400 côté gateway → écran « introuvable » | — | — |
| `deepLink` (lu) | Non | 2048 | rendu seulement s'il commence par `https://` | — | — |

---

## Technique

### Endpoint(s)

Aucun nouveau. Lus :

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/subjects/{subjectId}` | JWT | propriétaire du poste, droit Vigie |
| GET | `/api/radar/hosts/{hostId}/people` | JWT | idem |
| GET | `/api/runner-hosts/spaces` | JWT | utilisateur |

### Tables impactées

Aucune écriture. Lues via F-99 : `radar_subjects`, `radar_subject_facts`, `radar_evidence`,
`radar_evidence_links`, `radar_people`, `radar_subject_roles`, `radar_subject_aliases`.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `core/models/radar-subject.models.ts` (nouveau) — la page d'un sujet telle que la gateway la rend.
- `core/services/radar-subject.service.ts` (nouveau) — lecture d'un sujet.
- `vigie/radar-subject/radar-subject-view.ts` (nouveau) — fonctions pures : libellés d'état et de
  source, numérotation des renvois, dates en mots, libellé et sûreté du lien profond.
- `vigie/radar-subject/radar-subject-page.component` (nouveau) — la page.
- `app.routes.ts` — la route de sujet charge la page (la redirection `vigieSubjectRedirect` est
  retirée).

### Préoccupations transversales

- **Navigation / routing : oui.** Composants vérifiés : `app.routes.ts` (la route
  `vigie/:hostRef/sujets/:subjectId` reste déclarée **avant** `vigieMatcher`, qui n'avale pas les
  chemins à 4 segments), `ShellComponent` et `shared/space-links.ts` (Vigie active et client lu sur
  `/vigie/h1/sujets/s1`, specs existantes inchangées), `VigieComponent` (non touché ; ses onglets
  `?onglet=` restent la destination du fil d'Ariane), `app.routes.spec.ts` (le test de redirection
  devient un test de chargement).
- **Contexte tenant : non** — aucun nouveau moyen de résoudre le poste ; la gateway (F-99 /
  F-106) garde `RadarScopeResolver.requireInVigie`.
- Auth / Principal, plans / limites : non.

---

## Plan de test

### Tests unitaires

- [ ] `radar-subject-view.spec.ts` — libellé et classe de chaque état ; libellé et icône de chaque
      source ; numérotation (ordre d'apparition, même numéro pour la même preuve, preuves de l'état
      après celles du résumé) ; date en mots (aujourd'hui, hier, date courte) ; libellé du lien par
      source (seconde de réunion) ; `safeLink` refuse `javascript:` et `http://`.
- [ ] `radar-subject.service.spec.ts` — l'adresse lue.
- [ ] `radar-subject-page.component.spec.ts` — nominal (en-tête, cases, phrases et renvois,
      chronologie, auteur nommé, lien profond) ; résumé vide ; fusionné (lien vers la cible) ;
      `clos ?` ; 404 ; 409 ; 403 ; erreur réseau puis *Réessayer* ; annuaire en échec n'empêche
      pas la page.

### Tests d'intégration

- [ ] `app.routes.spec.ts` — la route de sujet est déclarée sous la route authentifiée et charge un
      composant (plus de `redirectTo`).
- [ ] Backend : `RadarReadApiIntegrationTest`, `RadarIsolationIntegrationTest`,
      `RadarVigieSpaceApiIntegrationTest` rejoués (contrat lu inchangé).

### Isolation workspace

- [x] Applicable — portée par la gateway (F-99 / F-106) : `RadarIsolationIntegrationTest` (deux
      postes du même compte, deux comptes) et `RadarVigieSpaceApiIntegrationTest` (poste hors
      Vigie → 409) rejoués. Le frontend ne porte aucun identifiant de compte.

---

## Dépendances

### Subfeatures bloquantes

- F-99 (SF-99-01 → 05) — `done` ; F-101 — `done` (alimente les phrases et preuves) ;
  F-106 / SF-106-04 — `done` (adresse réservée).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Numérotation des renvois côté écran** : la gateway rend déjà les identifiants de preuve de
  chaque valeur (`RadarViews`), l'écran ne devine rien ; il ne fait que numéroter.
- **Auteur nommé par l'annuaire** plutôt qu'en enrichissant la vue du sujet : aucun changement de
  contrat pendant que F-102 lit la même vue en parallèle.
