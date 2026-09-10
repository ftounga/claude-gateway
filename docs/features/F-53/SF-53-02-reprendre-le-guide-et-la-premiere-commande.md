# Mini-spec — F-53 / SF-53-02 — Reprendre le guide, et voir la commande aboutir

## Identifiant

`F-53 / SF-53-02`

## Feature parente

`F-53` — Guide d'accueil

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-53-02-reprise-et-premiere-commande`

---

## Objectif

Rendre le guide **rattrapable** — il se rouvre depuis l'Atelier après un abandon —, **proposer la
première commande** au lieu de laisser l'utilisateur inventer quoi taper, et **dire quoi faire**
quand le tour n'aboutit pas, au lieu de laisser l'étape muette.

---

## Comportement attendu

### Cas nominal

1. Le guide masqué (abandonné ou terminé), l'écran des projets porte une entrée discrète
   **« Guide de démarrage »**. Un clic le rouvre là où il en était : les étapes déjà franchies
   restent cochées, et un parcours déjà accompli se rouvre sur sa conclusion.
2. Tant que le guide est à l'écran, cette entrée n'est **pas** proposée : elle n'aurait rien à ouvrir.
3. À l'étape **« Faites exécuter une commande »**, le guide propose une première demande —
   *« Liste les fichiers de ce projet »* — et un bouton **« Écrire dans le terminal »** la place dans
   la zone de saisie. **Rien n'est envoyé** : l'envoi reste le geste de l'utilisateur, parce qu'il
   consomme des tokens.
4. Le bouton n'apparaît que si un projet est ouvert — sans terminal, il n'y a nulle part où écrire.
5. Si un tour lancé **sur le poste** échoue, l'étape 3 le dit en une ligne et propose de
   **vérifier son poste** : ce bouton rouvre le dialogue d'appairage, où vit le diagnostic (F-45).
   L'étape reste non cochée — un tour en échec n'est pas le premier succès.
6. Le signalement d'échec disparaît dès qu'un tour aboutit, et n'est **pas mémorisé** d'une session à
   l'autre : c'est l'état d'un instant, pas un acquis.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Reprise demandée alors que le stockage local est refusé | Le guide se rouvre pour la session en cours ; rien n'est mémorisé, aucune exception |
| Échec d'un tour sur le **bac à sable** | Aucun signalement dans le guide : l'étape vise le poste, et le bac à sable n'est pas en cause |
| « Écrire dans le terminal » alors qu'un tour est déjà en cours | La zone de saisie est renseignée comme d'habitude ; l'écran gère déjà ce cas (précision de tour) |
| Aucun projet ouvert | Ni bouton d'écriture, ni bouton de vérification du poste |

---

## Critères d'acceptation

- [ ] Le guide masqué, l'écran des projets propose de le rouvrir ; le guide visible, cette entrée est
      absente.
- [ ] Rouvrir conserve les étapes déjà franchies ; un parcours déjà accompli se rouvre sur sa
      conclusion, et `Terminer` le referme à nouveau.
- [ ] L'état de reprise est mémorisé : rouvert puis rechargé, le guide est toujours là.
- [ ] L'étape 3 propose une première commande et un bouton qui la place dans la zone de saisie —
      **sans l'envoyer**.
- [ ] Le bouton n'est pas proposé sans projet ouvert.
- [ ] Un tour en échec **sur le poste** affiche une ligne d'explication et un bouton qui rouvre le
      dialogue d'appairage ; l'étape 3 **reste non cochée**.
- [ ] Un échec sur le bac à sable ne déclenche aucun signalement.
- [ ] Le signalement d'échec s'efface au premier tour abouti et n'est jamais persisté.
- [ ] Aucune couleur ni police hors `docs/DESIGN_SYSTEM.md` ; aucun `window.alert/confirm/prompt`.
- [ ] `npm run build` et `npm test` verts.

---

## Périmètre

### Hors scope

- Une entrée de reprise depuis la barre du terminal : rouvrir un guide est un geste rare et
  délibéré, et quitter le terminal ramène à l'écran des projets en un clic. Décision tracée
  ci-dessous.
- L'envoi automatique de la première commande (elle consomme des tokens : c'est un geste utilisateur).
- Tout diagnostic propre au guide : le diagnostic vit dans F-45, le guide y renvoie.
- Toute persistance serveur.

---

## Valeurs initiales

| Champ (état local) | Valeur initiale | Règle |
|---|---|---|
| `status` | inchangé | `reopen()` le ramène à `active` sans toucher aux étapes |
| échec du dernier tour | `false` | Vit en mémoire de session uniquement, jamais écrit dans le stockage |

---

## Contraintes de validation

| Champ | Obligatoire | Format / valeurs | Normalisation |
|---|---|---|---|
| commande proposée | oui | texte figé côté écran, non modifiable par l'utilisateur avant insertion | — |
| clé `localStorage` | oui | `cg_atelier_guide`, inchangée depuis SF-53-01 | — |

---

## Technique

### Endpoint(s)

Aucun — subfeature entièrement frontend.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants Angular

- `core/services/atelier-guide.service.ts` (**modifié**) — `reopen()` et l'état de session
  `lastTurnFailed` (`markTurnFailed()`, effacé par `markStep('command')`).
- `atelier/guide/atelier-guide.component.*` (**modifié**) — commande proposée, bouton d'écriture,
  ligne d'échec et bouton de vérification du poste ; sorties `writeCommand` et `checkHost`.
- `atelier/atelier.component.ts` et son gabarit (**modifiés**) — entrée de reprise dans l'écran des
  projets, écriture de la commande dans `draft`, signalement d'échec sur `onError` du moteur
  `LOCAL_MACHINE`.

---

## Plan de test

### Tests unitaires (Karma)

- [ ] `AtelierGuideService` — `reopen()` après abandon : le guide redevient visible, étapes conservées.
- [ ] `AtelierGuideService` — `reopen()` d'un parcours accompli : visible, sur sa conclusion.
- [ ] `AtelierGuideService` — la reprise est persistée (relecture après rechargement).
- [ ] `AtelierGuideService` — `markTurnFailed()` puis `markStep('command')` : le signalement s'efface.
- [ ] `AtelierGuideService` — l'échec n'est jamais écrit dans le stockage local.
- [ ] `AtelierGuideComponent` — l'étape 3 affiche la commande proposée et émet `writeCommand`.
- [ ] `AtelierGuideComponent` — sans projet ouvert, aucun bouton d'écriture.
- [ ] `AtelierGuideComponent` — en échec, la ligne s'affiche et `checkHost` est émis.
- [ ] `AtelierComponent` — l'entrée de reprise n'apparaît que lorsque le guide est masqué, et le rouvre.
- [ ] `AtelierComponent` — `guideWriteCommand()` renseigne `draft` et n'envoie rien.
- [ ] `AtelierComponent` — un `onError` sur le moteur `LOCAL_MACHINE` signale l'échec ; sur le bac à
      sable, non.

### Tests d'intégration

Sans objet : aucune route ni aucun accès serveur n'est ajouté.

### Isolation utilisateur

Non applicable — aucun accès aux données ; l'état reste local et anonyme.

---

## Analyse d'impact

### Préoccupations transversales touchées

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | inchangé |
| Contexte tenant | non | inchangé |
| Plans / limites | non | inchangé |
| Navigation / routing | non | aucune route neuve |

### Composants existants potentiellement impactés

| Composant | Impact potentiel | Non-régression prévue |
|---|---|---|
| `AtelierComponent` | Une entrée de plus dans l'écran des projets, une écriture dans `draft`, un branchement sur `onError` | Suite existante verte, y compris les tests d'erreur de flux |
| `AtelierGuideComponent` | Deux sorties de plus | Tests de SF-53-01 conservés |

---

## Dépendances

### Subfeatures bloquantes

- `SF-53-01` — **done** (PR #317).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Reprise depuis l'écran des projets seulement** : le terminal est immersif et sa barre est déjà
  dense ; y ajouter une entrée pour un geste rare la chargerait sans rien résoudre. Réversible.
- **La commande est écrite, jamais envoyée** : un envoi automatique consommerait des tokens sans que
  l'utilisateur l'ait décidé.
- **L'échec n'est pas persisté** : un guide qui rouvrirait sur l'échec de la semaine dernière
  raconterait une histoire fausse.
