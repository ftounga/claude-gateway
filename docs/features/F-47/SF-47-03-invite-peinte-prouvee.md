# Mini-spec — F-47 / SF-47-03 — L'invite peinte, prouvée par un test qui l'aurait vue manquer

## Identifiant

`F-47 / SF-47-03`

## Feature parente

`F-47` — L'autorisation qu'on ne peut pas manquer

## Statut

`done` — PR #307, mergee le 2026-09-10

## Date de création

2026-09-10

## Branche Git

`feat/SF-47-03-invite-peinte-prouvee`

---

## Objectif

> Que la garantie posée par SF-47-01 — *l'invite est peinte à l'instant où elle arrive, sans qu'aucun
> événement ne suive* — soit **vérifiée par un test qui l'aurait vue manquer**, et que le forçage du
> cycle de rendu **retente** au lieu d'abandonner en silence quand la passe synchrone est refusée.

---

## Déclencheur

L'invite ne s'affiche toujours pas en production après SF-47-01. Dix itérations d'instrumentation
ont établi une chaîne saine de bout en bout : la gateway émet `confirm_request` et le journalise,
le navigateur le reçoit, `showConfirmation()` est appelé, le signal est posé, `nudgeRender()` /
`ApplicationRef.tick()` **réussit**, et le composant terminal **reçoit la valeur par son `@Input`** —
puis reçoit `null`. Reste inconnu, faute de relevé horodaté renvoyé par le PO, si ce `null` suit de
quelques millisecondes (effacement anormal) ou de deux minutes (expiration normale).

Cette subfeature ne prétend pas trancher cette question. Elle traite ce qui a été **établi** en la
cherchant, et qui vaut indépendamment de la réponse :

**Constat 1 — la garantie de SF-47-01 n'était pas testée.** Les tests livrés avec elle espionnent
`ApplicationRef.tick` et vérifient qu'il a été **appelé**. Aucun ne vérifie qu'une invite est
**peinte**. Le seul test de gabarit existant (`.atelier-ask-recall` présent) appelle
`fixture.detectChanges()` juste avant de regarder — c'est-à-dire qu'il peint lui-même ce qu'il
prétend vérifier. Mesuré sur la branche : sans ce `detectChanges()`, `.terminal-ask` **et**
`.atelier-ask-recall` sont tous deux absents du DOM, et **aucun test existant ne rougit**.

**Constat 2 — `ApplicationRef.tick()` ne repeint que les vues *attachées* à l'`ApplicationRef`.**
Dans un `TestBed`, la vue d'un `ComponentFixture` n'y est pas attachée (`appRef.components.length`
vaut `0`) : `tick()` y est un **no-op silencieux**. Un test qui se contente de l'espionner ne peut
donc, par construction, rien prouver du rendu. Rattachée comme elle l'est en production
(`appRef.attachView(fixture.componentRef.hostView)`), la même séquence peint bien l'invite — ce qui
**disculpe le rendu du terminal** dans la topologie de production, et laisse la branche « effacement
anormal » comme seule hypothèse ouverte.

**Constat 3 — `nudgeRender()` abandonne en silence.** Le `catch` absorbe le refus d'un `tick`
ré-entrant en pariant que « le cycle qui tourne lira le signal de toute façon ». Ce pari n'est vrai
que si ce cycle n'a **pas déjà traversé** la vue de l'Atelier ; s'il l'a fait, plus rien ne repeint,
et le flux se tait pour deux minutes. Aucune reprise n'existe aujourd'hui.

---

## Comportement attendu

### Cas nominal — l'invite est peinte, et le test le voit

1. Le flux relaie `confirm_request` (moteur `LOCAL_MACHINE` **ou** `HOSTED_SANDBOX`).
2. `showConfirmation()` pose le signal, puis force un cycle de rendu (inchangé, SF-47-01).
3. **Sans aucun événement suivant, sans aucun cycle de rendu déclenché par le test**, le DOM
   contient `.terminal-ask` (l'invite dans le flux) **et** `.atelier-ask-recall` (le rappel).

### Cas nominal — le forçage retente

4. **Nouveau** : le forçage programme systématiquement **une** seconde passe sur la macrotâche
   suivante. Une macrotâche ne peut pas s'imbriquer dans un cycle en cours : la passe de reprise
   n'est jamais refusée, là où la passe synchrone peut l'être.
5. Jamais plus d'une reprise en vol à la fois : une seconde demande de forçage arrivée avant que la
   reprise se soit exécutée ne l'empile pas.
6. La reprise est annulée à la destruction de l'écran : un cycle de rendu programmé sur un écran
   détruit ne repeint rien et n'a rien à faire dans la file.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| La passe synchrone est refusée (`tick` ré-entrant) | Absorbée comme aujourd'hui, **puis** la reprise s'exécute et repeint | — |
| La reprise est refusée à son tour | Absorbée ; l'invite reste posée, aucune erreur remontée à l'utilisateur | — |
| L'écran est détruit entre le forçage et la reprise | La reprise est annulée, aucun cycle sur une vue détruite | — |
| Deux demandes d'autorisation se succèdent sur le même tour | Une seule reprise en vol ; le rappel ne clignote pas | — |

---

## Critères d'acceptation

- [ ] Sur le moteur `HOSTED_SANDBOX` : après `confirm_request` et **sans** aucun événement suivant ni
      `detectChanges()` du test, `.terminal-ask` est présent dans le DOM.
- [ ] Idem sur le moteur `LOCAL_MACHINE` (machine connectée) — le moteur de l'incident.
- [ ] Dans les deux cas, `.atelier-ask-recall` est présent lui aussi.
- [ ] Ces tests **rougissent** si le forçage du cycle de rendu est retiré — vérifié en le neutralisant
      sur la branche : 3 rouges, dont les deux nouveaux, et 759 verts.
- [ ] Une passe synchrone qui n'a rien peint est suivie d'une reprise, au plus tard sur la macrotâche
      suivante, et l'invite y est peinte.
- [ ] Deux forçages rapprochés ne programment qu'une seule reprise (invariant de code : garde sur
      `renderNudgeRetry`).
- [ ] La destruction de l'écran annule la reprise en attente (`ngOnDestroy`).
- [ ] Aucun accès à des données : isolation `user_id` inchangée (aucun appel réseau ajouté).

---

## Périmètre

### Hors scope (explicite)

- Trancher entre « effacement anormal » et « expiration normale » : la question est **escaladée**
  au PO, elle demande le relevé horodaté de l'image `staging-trace10` (voir §Notes, Q1).
- Déplacer l'invite hors du flux (modale) — décision F-33 / SF-33-03 maintenue.
- Toucher au délai de 120 s.
- La valeur par défaut de la porte → **SF-47-04**, sur décision du PO du 2026-09-10.
- Toute instrumentation laissée dans `main` : le traçage vit dans des images jetables.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| — | — | — | Aucune donnée saisie n'est ajoutée par cette SF | — | — |

Notes :
- Aucune entrée utilisateur nouvelle, aucun champ persisté, aucun endpoint.

---

## Technique

### Endpoint(s)

Aucun. Subfeature **frontend uniquement**.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `AtelierComponent` (`frontend/src/app/atelier/atelier.component.ts`) — `nudgeRender()` programme
  une reprise sur macrotâche ; champ `renderNudgeRetry` ; annulation dans `ngOnDestroy()`.
- `AtelierComponent` (`atelier.component.spec.ts`) — tests de rendu réels, vue rattachée à
  l'`ApplicationRef` comme en production.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierComponent` — bac à sable : `.terminal-ask` **et** `.atelier-ask-recall` peints sans
      aucun événement suivant, et sans aucun `detectChanges()` du test.
- [ ] `AtelierComponent` — machine connectée (le moteur de l'incident) : idem.
- [ ] `AtelierComponent` — l'invite est peinte **au plus tard à la macrotâche suivante**, même quand
      la passe synchrone n'a rien peint.

Les deux premiers sont le garde-fou dur : neutraliser le forçage les fait rougir. Le troisième
décrit la séquence à deux passes ; il ne peut pas être rendu strict sans espionner
`ApplicationRef.tick`, ce qui perturbe l'ordonnanceur de rendu d'Angular et rend le test instable —
la garde `renderNudgeRetry` et l'annulation à la destruction restent des invariants de code, relus
en revue.

### Tests d'intégration

Sans objet (aucun endpoint).

### Isolation workspace

- [x] Non applicable — raison : aucune lecture ni écriture de données, aucun appel réseau ajouté.

---

## Dépendances

### Subfeatures bloquantes

- `F-47 / SF-47-01` — statut : done (le forçage du cycle de rendu, et le rappel).
- `F-47 / SF-47-02` — statut : done (le compte à rebours, qui force lui aussi un cycle).

### Questions ouvertes impactées

- [x] `OQ-14` — porte activée par défaut en cible `RUNNER` : **tranchée par le PO le 2026-09-10**,
      traitée par **SF-47-04**, non touchée ici.

---

## Notes et décisions

- **D1 — Le test rattache la vue à l'`ApplicationRef`.** C'est la topologie de production
  (`bootstrapApplication` → `AppComponent` → `router-outlet` → `AtelierComponent`) ; sans ce
  rattachement, `tick()` est un no-op et le test ne peut rien prouver. Le rattachement ne déclenche
  **aucun** cycle automatique : le test continue de n'appeler aucun `detectChanges()`, et c'est bien
  le code de l'écran qui doit peindre.
- **D2 — Reprise sur macrotâche plutôt que microtâche.** Une microtâche s'exécute avant la fin du
  cycle en cours et serait refusée pour la même raison. `setTimeout(0)` s'exécute après, et rend en
  prime la zone instable — ce qui déclenche un cycle de plus, gratuitement.
- **D3 — La reprise est systématique, pas conditionnelle.** Une passe `tick()` qui « réussit » peut
  n'avoir rien repeint (vue non attachée) : rien ne distingue de l'extérieur un succès d'un no-op.
  Programmer la reprise sans condition coûte un cycle par demande d'autorisation — deux par tour au
  pire —, ce qui est sans commune mesure avec deux minutes d'attente silencieuse. **Réversible** :
  la condition s'ajoute au même endroit le jour où le coût gêne.
- **D4 — Rien n'est ajouté au diagnostic tant que le relevé n'est pas revenu.** On ne devine pas une
  cause racine pour pouvoir écrire un correctif : on ferme le trou qu'on a mesuré, et on escalade ce
  qui reste.

### Question escaladée au PO

- **Q1** — Sur l'image `staging-trace10` déployée le 2026-09-09, **quel est l'écart horodaté** entre
  la ligne de trace qui note l'arrivée de la valeur sur l'`@Input` du terminal et celle qui note son
  passage à `null` ? Quelques millisecondes désignent un effacement anormal (à chercher dans les
  huit points d'effacement de `AtelierComponent`) ; deux minutes désignent une expiration normale,
  et alors l'invite était bien peinte et la cause est ailleurs qu'en Angular. **Aucun correctif de
  cause racine ne peut être écrit avant cette réponse.**
