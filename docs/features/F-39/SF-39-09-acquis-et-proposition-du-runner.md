# Mini-spec — F-39 / SF-39-09 · Les acquis repris, et le runner proposé au bon moment

## Identifiant

`F-39 / SF-39-09`

## Feature parente

`F-39` — L'Atelier comme harnais (lot 4 · Écran unique)

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-09-acquis-et-proposition`

---

## Objectif

Clore le lot 4 en **prouvant** que les treize acquis visuels du §4 survivent à l'écran unique, et en
proposant le runner **au moment où le bac à sable devient la limite** — jamais avant.

---

## Pourquoi

Deux dettes restent ouvertes après SF-39-08.

**La première est une promesse à tenir.** Le §4 du cadrage est explicite : « ces treize subfeatures
sont le résultat d'itérations explicites sur l'apparence et le comportement du terminal. La refonte
de l'écran les **reprend**, elle ne les redécouvre pas. Toute régression ici est bloquante. » Une
checklist de revue est un engagement humain ; elle se relit une fois, à la PR, puis cesse d'exister.
Ce qui protège un acquis dans six mois, c'est un test qui échoue.

**La seconde est une décision produit à câbler.** SF-39-07 calcule `recommendRunner` et son motif ;
personne ne les lit encore. Or c'est la lettre de **D6** : le runner est le chemin *recommandé*, et
l'interface doit le proposer « **là, dans le contexte du besoin**, pas à l'inscription ».

---

## Comportement attendu

### Cas nominal — la proposition du runner (D6)

À l'ouverture d'un projet, `GET /engine` rend `recommendRunner: true` avec un motif. Le terminal
affiche alors, **sous son en-tête et au-dessus du flux**, une bande d'information :

| Motif | Ce que dit la bande |
|---|---|
| `GIT` | « Ce projet vient d'un dépôt Git. Sur votre machine, Claude travaille sur votre clone local — vos branches, vos outils, vos variables d'environnement. » |
| `FILE_LIMIT` | « Ce projet dépasse ce que le bac à sable monte : Claude n'y voit qu'une partie des fichiers. Sur votre machine, il les voit tous. » |

Deux gestes : **« Connecter une machine »** (ouvre l'appairage existant) et **« Plus tard »**
(referme la bande). Rien d'autre : ce n'est pas un écran, c'est une phrase au bon moment.

**La bande ne revient pas dans la session** une fois refermée — pour ce projet. Rouvrir un autre
projet qui remplit les conditions la remontre : c'est le besoin qui la déclenche, pas un compteur.

### Cas nominal — les acquis §4

Aucun changement fonctionnel : SF-39-09 **constate**. Chacun des treize acquis reçoit une assertion
exécutable sur l'écran unifié, regroupée dans un fichier dédié qui cite le §4 ligne à ligne.

| # | Acquis (§4) | Ce que le test constate |
|---|---|---|
| 1 | Sortie des commandes relayée | Un bloc porte sa sortie, pas seulement sa commande |
| 2 | Rendu terminal : commande **puis** sortie | L'en-tête `$` précède la sortie dans le DOM |
| 3 | Terminal immersif plein écran | Aucun `.atelier-layout` quand un projet est ouvert |
| 4 | Markdown mis en forme | Le commentaire passe par le pipe `markdown` (pas de `**` brut) |
| 5 | Ligne vivante pendant le tour | Action en cours, étapes derrière, consommation, durée |
| 6 | Coût du tour affiché | Tokens et durée en fin de tour, quand ils sont connus |
| 7 | Transcription conservée | Un tour terminé garde ses blocs — **des deux moteurs** |
| 8 | Mise en valeur Gold | Le badge d'accent de la charte reste rendu |
| 9 | Réinitialiser l'environnement | Présent en bac à sable (et absent ailleurs, D-L4-6) |
| 10 | Aller de l'explorateur au terminal | Le geste « Fichiers » est émis |
| 11 | Un tour ne lit que ses propres events | Les blocs du tour précédent ne sont pas rejoués |
| 12 | Diagnostic d'erreur fournisseur | Crédit épuisé ≠ panne d'exécution |
| 13 | Session persistante | La transcription survit au rechargement (historique) |

S'y ajoutent les acquis F-38 côté runner : porte de confirmation, journal d'audit, coupe-circuit,
interruption, sélecteur de branche (F-31).

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| `GET /engine` échoue | Aucune bande : ne rien proposer vaut mieux que proposer au hasard. L'écran s'affiche normalement (repli SF-39-08 inchangé). |
| `recommendRunner: true` sans motif | Aucune bande : le motif **est** le message. Sans lui il n'y aurait rien à dire. |
| Motif inconnu d'une version future | Aucune bande, aucune erreur : un libellé absent ne doit pas casser l'écran. |
| Projet déjà en cible `RUNNER` | Aucune bande — le backend ne la demande jamais (SF-39-07), et l'écran ne l'invente pas. |

---

## Critères d'acceptation

- [ ] Un projet Git en bac à sable sans runner affiche la bande, avec le texte du motif `GIT`.
- [ ] Un projet dont l'arborescence dépasse le montage affiche le texte du motif `FILE_LIMIT`.
- [ ] `recommendRunner: false` ⇒ aucune bande dans le DOM.
- [ ] `recommendRunner: true` avec `recommendReason: null` ⇒ aucune bande (le motif est le message).
- [ ] Un motif inconnu ⇒ aucune bande, aucune erreur levée.
- [ ] « Connecter une machine » ouvre l'appairage déjà en place (`openRunnerPairing`).
- [ ] « Plus tard » referme la bande, et elle ne revient pas pour ce projet dans la session.
- [ ] Ouvrir un **autre** projet qui remplit les conditions la remontre.
- [ ] Un fichier de test dédié couvre les **treize** acquis §4 sur l'écran unifié, chacun nommé par son numéro et son origine (SF-30-XX).
- [ ] L'acquis 7 (transcription conservée) est constaté **pour les deux moteurs**.
- [ ] Aucun endpoint créé, aucune migration, aucun changement de contrat.
- [ ] `npm run build` et `npm test` verts.

---

## Périmètre

### Hors scope (explicite)

- **Toute modification du backend** : `recommendRunner` et son motif existent depuis SF-39-07.
- Le téléchargement du runner et le parcours d'appairage : ils existent (F-38) et ne changent pas.
- Toute mémorisation **persistée** du refus (« ne plus me proposer ») : voir « Notes et décisions »,
  D-L4-7.
- Le lot 5 et suivants (`thinking`, compaction, sous-agents, plafond de dépense).

---

## Technique

### Endpoints consommés (aucun créé)

| Méthode | URL | Origine |
|---|---|---|
| GET | `/api/workspaces/{id}/engine` | SF-39-07 — champs `recommendRunner` / `recommendReason` |

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable.

### Composants Angular

- `AtelierComponent` — porte l'état de la recommandation et son classement sans suite.
- `AtelierTerminalComponent` — rend la bande et émet ses deux gestes.
- `atelier-terminal.component.spec.ts` / nouveau `acquis-f30.spec.ts` — les treize constats.

### Design system

La bande réutilise la mise en valeur **Gold** de la charte (accent orange, acquis 8) : c'est
exactement le registre — une opportunité, pas une erreur. Aucune couleur ni police nouvelle.

---

## Plan de test

### Tests unitaires (frontend, sur mock du service)

- [ ] `AtelierTerminalComponent` — motif `GIT` ⇒ bande rendue, texte du dépôt.
- [ ] `AtelierTerminalComponent` — motif `FILE_LIMIT` ⇒ texte des fichiers non montés.
- [ ] `AtelierTerminalComponent` — `recommendRunner` faux ⇒ aucune bande.
- [ ] `AtelierTerminalComponent` — motif nul ou inconnu ⇒ aucune bande, aucune erreur.
- [ ] `AtelierTerminalComponent` — « Connecter une machine » émet `pairRunner`.
- [ ] `AtelierTerminalComponent` — « Plus tard » émet `dismissRunnerHint`.
- [ ] `AtelierComponent` — la recommandation vient de `GET /engine`.
- [ ] `AtelierComponent` — classée sans suite, elle ne revient pas sur ce projet.
- [ ] `AtelierComponent` — elle revient sur un autre projet qui remplit les conditions.
- [ ] `acquis-f30.spec.ts` — les treize acquis, un test chacun.

### Tests d'intégration

Sans objet (aucun endpoint créé).

### Isolation utilisateur

- [x] Non applicable côté écran — le seul endpoint consommé applique `requireOwned` et est couvert
  par ses propres tests d'intégration (SF-39-07).

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-07` — done (`recommendRunner` / `recommendReason`)
- `SF-39-08` — done (écran unique)

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

**D-L4-7 — Le refus est classé pour la session, pas persisté.** « Ne plus jamais me le proposer »
demanderait une préférence stockée, donc une table, une migration et un endpoint — pour un besoin
dont on ignore encore s'il existe. Or la bande n'apparaît que sur une **limite réellement
rencontrée** : celui qui la referme et rouvre le projet demain a, entre-temps, peut-être changé
d'avis parce que le bac à sable l'a gêné. Un classement de session est le comportement le moins
présomptueux, et le seul réversible sans migration. *Si la bande se révèle insistante à l'usage, la
persister est une subfeature d'une heure.*

**D-L4-8 — Les acquis §4 deviennent un fichier de test, pas une ligne de checklist.** Une checklist
de revue protège une PR ; un test protège six mois de PR. Le §4 dit « toute régression ici est
bloquante » : rendre ce blocage **automatique** est la seule lecture honnête de la phrase. Le fichier
cite chaque acquis par son numéro et sa subfeature d'origine, pour qu'un test qui casse dise
*lequel* des treize a été perdu.

**D-L4-9 — La bande dit le bénéfice, pas la fonctionnalité.** « Installez le runner » décrit une
corvée. « Sur votre machine, Claude voit tous vos fichiers » décrit ce que l'utilisateur y gagne, à
l'instant où il en a besoin. La différence est celle entre une campagne et une réponse.
