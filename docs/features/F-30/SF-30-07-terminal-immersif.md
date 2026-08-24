# Mini-spec — [F-30 / SF-07] Vue terminal immersive

---

## Identifiant

`F-30 / SF-07`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`ready`

## Date de création

2026-08-24

## Branche Git

`feat/SF-30-07-terminal-immersif`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire du mode Terminal un **véritable espace terminal plein écran**, au lieu de blocs sombres insérés
dans un fil de conversation.

---

## Contexte

SF-30-02 a livré le rendu des commandes et de leurs sorties, mais **à l'intérieur des bulles du fil de
chat**. Résultat : l'écran reste une conversation où sont incrustés des encarts sombres. Basculer sur
« Terminal » ne change rien de visible tant qu'on n'a pas envoyé de message.

Le retour utilisateur est sans ambiguïté : cliquer sur Terminal doit **ouvrir un espace terminal**.

**Parti pris retenu (arbitré le 2026-08-24)** : vue **immersive plein écran** — le mode Terminal
masque la liste des projets et le fil conversationnel, et occupe tout l'espace de l'écran Atelier.
Écartés : le terminal en panneau scindé (conserve la dualité qu'on veut supprimer) et la conversion
de la zone centrale seule (la sidebar continuerait de rappeler une application de chat).

---

## Comportement attendu

### Cas nominal

1. En mode **Terminal**, la vue terminal s'ouvre **immédiatement**, sans attendre un message.
2. Elle occupe tout l'espace de l'écran Atelier : ni liste de projets, ni bulles de conversation.
3. En-tête : nom du projet, mention « terminal », accès aux **Fichiers**, **Réinitialiser la sandbox**,
   et **Quitter** (retour au mode Assistant).
4. Corps : un flux continu en monospace sur fond sombre, dans l'ordre chronologique —
   - la demande de l'utilisateur en ligne d'invite `>` ;
   - le commentaire de Claude en texte clair ;
   - chaque commande en `$ commande`, suivie de sa sortie ;
   - le coût du tour (`m:ss · N tokens`) quand il est connu.
5. Bas : une invite de saisie `$` en monospace, qui envoie le message (mêmes règles qu'avant).
6. Le flux **défile automatiquement** vers le bas à l'arrivée de nouveau contenu.
7. Quitter ramène au mode Assistant et à l'écran habituel, **sans rien perdre** de la conversation.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun projet sélectionné | La vue terminal ne s'ouvre pas ; le mode reste sélectionnable mais l'écran invite à choisir un projet |
| Envoi pendant un run en cours | Refusé comme avant (`submitting()`), l'invite est désactivée |
| Erreur du flux (`forbidden`, `agent_disabled`, quota…) | Message d'erreur affiché **dans le terminal**, en ligne distincte, sans quitter la vue |
| Historique vide | Ligne d'accueil rappelant ce que fait le mode et invitant à saisir une demande |

---

## Critères d'acceptation

- [ ] Passer en mode Terminal ouvre la vue immersive **sans envoyer de message**
- [ ] La vue masque la liste des projets et le fil conversationnel
- [ ] Les tours précédents apparaissent dans le flux terminal, dans l'ordre, avec leurs transcriptions
- [ ] Une demande utilisateur s'affiche en `>` ; les commandes en `$` ; les sorties dessous
- [ ] Les erreurs du flux s'affichent dans le terminal, sans le refermer
- [ ] « Quitter » revient au mode Assistant sans perte de conversation
- [ ] Le défilement suit automatiquement le nouveau contenu
- [ ] Le mode **Assistant** est strictement inchangé
- [ ] Aucune couleur ni police hors `DESIGN_SYSTEM.md`
- [ ] Aucun endpoint, aucune table, aucune migration ; aucun appel réseau nouveau

---

## Périmètre

### Hors scope

- **Terminal interactif réel** (l'utilisateur tape ses propres commandes exécutées telles quelles) :
  l'API Managed Agents ne l'expose pas (ADR-014 §Alternatives écartées). On saisit une **demande**,
  c'est l'agent qui exécute.
- Coloration ANSI des sorties, historique de commandes (flèche haut), autocomplétion
- Persistance des tours Terminal en base : limite connue, subfeature distincte si besoin
- Modification du flux SSE ou du backend : **aucune**

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Déclenchement | Bascule de mode seule ; aucun message requis |
| Occupation | Tout l'espace de l'écran Atelier (superposition), sans nouvelle route |
| Sortie | Bouton « Quitter » explicite ; la bascule de mode reste possible |
| Palette | Navy `--cg-primary` (fond), orange `--cg-accent` (invite), rouge `--cg-error` (échec), `--cg-font-mono` |

---

## Technique

### Endpoint(s)

Aucun. Aucun appel réseau ajouté.

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/terminal/atelier-terminal.component.{ts,html,scss}` | **Nouveau** composant de présentation (vue immersive) |
| `atelier/terminal/atelier-terminal.component.spec.ts` | **Nouveau** tests |
| `atelier/atelier.component.html` | Affichage conditionnel de la vue terminal |
| `atelier/atelier.component.ts` | Entrées/sorties du composant (flux, envoi, quitter) |

> Composant séparé délibérément : l'écran Atelier concentre déjà trop de responsabilités et son SCSS
> frôle le plafond de budget. Isoler la vue terminal évite d'aggraver les deux.

---

## Plan de test

### Tests unitaires (frontend)

- [ ] Passer en mode Terminal rend la vue immersive sans envoi de message
- [ ] La liste des projets et le fil conversationnel ne sont plus rendus dans cette vue
- [ ] Les tours passés sont rendus en lignes `>` / `$` / sortie, dans l'ordre
- [ ] Le coût d'un tour est rendu quand il est présent, absent sinon
- [ ] Une erreur de flux s'affiche dans le terminal sans le refermer
- [ ] « Quitter » repasse en mode Assistant et restaure l'écran habituel
- [ ] Mode Assistant inchangé (non-régression explicite)

### Tests d'intégration

Sans objet : présentation pure, aucun appel réseau ajouté.

### Isolation utilisateur

- [ ] **Non applicable** — aucun accès aux données, aucun appel réseau nouveau.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement. |
| Contexte tenant | **Non** | Aucun accès aux données. |
| Plans / limites | **Non** | Aucun appel de quota ; le gating Gold reste inchangé (écran déjà réservé). |
| Navigation / routing | **Oui** | La vue est une **superposition dans l'écran Atelier**, pas une route : aucune route ajoutée, modifiée ou gardée. Composants vérifiés : `app.routes.ts` (inchangé), `authGuard` (inchangé), boutons de navigation vers l'Explorateur de fichiers (`/atelier/{id}/fichiers`, conservé et accessible depuis l'en-tête du terminal). Le bouton retour du navigateur conserve son comportement, la vue n'étant pas un état d'historique. |

---

## Dépendances

- **SF-30-02 / 03 / 05 / 06 (Done)** — transcription, modes, coût du tour, réinitialisation.

---

## Notes et décisions

- **Superposition plutôt que route dédiée** : une route ferait perdre l'état du composant (session en
  cours, transcription non persistée) au moindre aller-retour. La superposition garde tout.
- **On saisit une demande, pas une commande** : l'invite ressemble à un shell, mais ce qu'on tape est
  une instruction en langue naturelle. Le libellé de l'invite doit éviter de promettre un shell réel.
- **Composant séparé** : décision de structure autant que de style — l'écran Atelier dépasse 700
  lignes et son SCSS a déjà nécessité un relèvement de budget en SF-30-02.
