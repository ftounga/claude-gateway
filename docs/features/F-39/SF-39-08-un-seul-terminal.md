# Mini-spec — F-39 / SF-39-08 · Dans l'Atelier, il n'y a qu'un terminal

## Identifiant

`F-39 / SF-39-08`

## Feature parente

`F-39` — L'Atelier comme harnais (lot 4 · Écran unique)

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-08-un-seul-terminal`

---

## Objectif

Faire disparaître les modes « Assistant » et « Terminal » de l'écran : un projet ouvert **est** un
terminal, quel que soit le moteur qui l'anime.

---

## Pourquoi

Le mot « Terminal » a désigné deux choses — un **comportement** côté demandeur (« on voit les
commandes, on voit les retours, ça continue jusqu'à ce que ce soit fini ») et un **moteur** côté
implémentation. C'est ce dédoublement, décrit au §1.1 du cadrage, qui a tenu deux semaines sans être
vu. Décision **D1** : un seul écran, moteur transparent.

Aujourd'hui, un projet en cible « ma machine » — le cas que F-38 a rendu possible et que D6 désigne
comme le chemin **recommandé** — retombe sur le mode « Assistant » : un fil de bulles de chat avec
une liste d'étapes, pendant que le sandbox hébergé, lui, a droit à la vue terminal immersive
(F-30 SF-30-07). Le moteur le plus abouti a le pire écran. Les acquis §4 de F-30 sont donc
**inaccessibles là où ils comptent le plus**.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur ouvre un projet. L'écran interroge `GET /api/workspaces/{id}/engine` (SF-39-07) et
   affiche **la vue terminal immersive**, quel que soit le moteur rendu.
2. L'en-tête du terminal porte une pastille de moteur, en clair :
   - `LOCAL_MACHINE` → « ma machine » (+ état du runner : connecté / hors ligne) ;
   - `HOSTED_SANDBOX` → « bac à sable hébergé ».
3. L'utilisateur saisit sa demande dans l'invite. L'écran choisit **seul** le chemin d'envoi :
   `LOCAL_MACHINE` → `POST /workspaces/{id}/chat/stream` (boucle maison, outils relayés) ;
   `HOSTED_SANDBOX` → `POST /workspaces/{id}/agent/stream` (Managed Agents).
4. Dans les deux cas, le rendu est **le même** : ligne d'invite `>` pour la demande, `$` pour chaque
   commande, sa sortie dessous, ligne vivante pendant le tour, transcription conservée dans le fil à
   la fin du tour.
5. « Quitter » referme le projet et revient à la liste. Il n'y a plus d'autre mode vers lequel
   basculer.

**Correspondance moteur → chemin d'envoi** (unique, dérivée de SF-39-07, jamais choisie) :

| `engine` | Flux | Ce que voit l'utilisateur |
|---|---|---|
| `LOCAL_MACHINE` | `chat/stream` | commandes exécutées sur sa machine par son runner |
| `HOSTED_SANDBOX` | `agent/stream` | commandes exécutées dans le bac à sable hébergé |

**Ce qui n'est pas un mode et reste donc à l'écran** : la **cible d'exécution** du projet
(« bac à sable hébergé » ⇄ « ma machine ») est un **réglage de projet** (F-38 / SF-38-05), pas un
mode d'agent. Elle est déplacée dans l'en-tête du terminal avec tout ce qui l'accompagne (pastille
d'état du runner, appairage, journal d'activité, coupe-circuit) : les faire disparaître avec
l'ancienne mise en page serait une régression des acquis F-38.

**Transcription de la boucle maison.** Les étapes du flux `chat/stream` (`action`, `output`) sont
converties en **blocs de terminal** (`AtelierTerminalBlock`) — un bloc par étape, la sortie
rattachée à l'étape qui la produit — puis conservées dans le tour à la fin (`terminal`), exactement
comme le fait déjà le flux d'agent. C'est ce qui rend les acquis §4 applicables aux deux moteurs.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| `GET /engine` échoue (réseau, 5xx) | L'écran **retombe** sur la cible d'exécution déjà connue du détail du projet (`RUNNER` ⇒ `LOCAL_MACHINE`, sinon `HOSTED_SANDBOX`) et s'affiche normalement. Un relevé manqué ne doit jamais fermer l'Atelier. |
| `GET /engine` rend 403 / 404 | Traité comme aujourd'hui par les écrans d'accès : l'upsell Gold et « projet introuvable » restent inchangés — cet appel ne les déclenche pas lui-même. |
| Envoi refusé par le backend | Les messages existants sont conservés (`streamErrorMessage`, `mapAgentError`), à ceci près que « Le mode Terminal est réservé à l'offre Gold » devient « L'exécution est réservée à l'offre Gold » : le mot « mode » n'a plus de référent. |
| Runner hors ligne en `LOCAL_MACHINE` | L'écran l'affiche (pastille rouge) et laisse envoyer : le refus, s'il vient, vient du backend avec sa cause. Ne pas inventer un refus client sur un relevé différé de 15 s. |
| Un lien `?mode=terminal` (F-30 / SF-30-10) | **Accepté et ignoré** : le paramètre n'a plus d'effet, mais un lien déjà partagé ouvre toujours le bon projet. |

---

## Critères d'acceptation

- [ ] Un projet ouvert affiche la vue terminal immersive, que son moteur soit `LOCAL_MACHINE` ou `HOSTED_SANDBOX`.
- [ ] Le sélecteur « Assistant / Terminal » n'existe plus dans le DOM.
- [ ] `setAgentMode`, `assistantModeDisabled`, `terminalModeDisabled`, `alignModeWithSource` et `alignModeWithTarget` ont disparu du composant : plus aucune règle de moteur côté écran.
- [ ] Le moteur affiché vient de `GET /engine` ; sur échec de l'appel, il est déduit de la cible du projet et l'écran s'affiche quand même.
- [ ] En `LOCAL_MACHINE`, l'envoi passe par `chat/stream` ; en `HOSTED_SANDBOX`, par `agent/stream`.
- [ ] Les étapes de `chat/stream` apparaissent comme des blocs terminal (commande puis sortie), et sont conservées dans le tour terminé.
- [ ] La pastille de moteur dit « ma machine » ou « bac à sable hébergé » — jamais « Assistant » ni « Terminal ».
- [ ] Le réglage de cible d'exécution, la pastille d'état du runner, l'appairage, le journal d'activité et le coupe-circuit sont accessibles depuis l'en-tête du terminal (non-régression F-38).
- [ ] « Réinitialiser » n'est proposé qu'en `HOSTED_SANDBOX` : il n'y a pas d'environnement à recréer sur la machine de l'utilisateur.
- [ ] « Quitter » referme le projet et revient à la liste, quel que soit le moteur.
- [ ] `/atelier/{id}?mode=terminal` ouvre le projet sans erreur (compatibilité des liens F-30).
- [ ] Les 13 acquis §4 du cadrage restent visibles sur l'écran unifié (revue bloquante ; vérification exécutable en SF-39-09).
- [ ] `npm run build` et `npm test` verts.

---

## Périmètre

### Hors scope (explicite)

- **Toute modification du backend** : SF-39-08 ne consomme que des endpoints existants.
- La **proposition contextuelle du runner** (D6, `recommendRunner`) — c'est SF-39-09.
- La **vérification exécutable** des 13 acquis §4 — c'est SF-39-09.
- Le retrait de la cible `SANDBOX` de la boucle maison (D7) — c'est SF-39-16.
- L'écran **Chat** (F-02), qui garde le chat sur fichiers sans exécution (D7) : il n'est pas touché.
- L'explorateur de fichiers `/atelier/{id}/fichiers`, qui existe déjà et reste le chemin d'accès aux
  fichiers depuis le terminal.

---

## Technique

### Endpoints consommés (aucun créé)

| Méthode | URL | Origine |
|---|---|---|
| GET | `/api/workspaces/{id}/engine` | **SF-39-07** (contrat importé tel quel) |
| POST | `/api/workspaces/{id}/chat/stream` | existant (boucle maison) |
| POST | `/api/workspaces/{id}/agent/stream` | existant (Managed Agents) |

### Contrat importé de SF-39-07

```ts
type AtelierEngine = 'LOCAL_MACHINE' | 'HOSTED_SANDBOX';
type AtelierRunnerRecommendation = 'GIT' | 'FILE_LIMIT';

interface AtelierEngineStatus {
  engine: AtelierEngine;
  runnerConnected: boolean;
  runnerLastSeenAt: string | null;
  recommendRunner: boolean;
  recommendReason: AtelierRunnerRecommendation | null;
}
```

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable.

### Composants Angular

- `AtelierComponent` — perd la notion de mode ; charge le moteur, route l'envoi, adapte le flux de
  la boucle maison en blocs terminal.
- `AtelierTerminalComponent` — reçoit le moteur, l'état du runner et les gestes runner ; masque
  « Réinitialiser » hors bac à sable.
- `AtelierService` — méthode `engineStatus(id)`.
- `atelier.types.ts` — `chatStepsToBlocks()` (adaptateur), retrait de `AtelierAgentMode`.

### Design system

Aucune couleur ni police nouvelle : la pastille de moteur réutilise les classes `badge` /
`badge--neutral` / `badge--success` / `badge--error` déjà en place, et les gestes runner sont
déplacés **sans changer leur balisage**.

---

## Plan de test

### Tests unitaires (frontend, sur mock du service)

- [ ] `chatStepsToBlocks` — une étape `bash` avec sortie produit un bloc commande + sortie.
- [ ] `chatStepsToBlocks` — une étape `read` sans sortie produit un bloc sans sortie (`hasOutput` faux).
- [ ] `chatStepsToBlocks` — un type inconnu reste présentable (aucune étiquette inventée).
- [ ] `AtelierComponent` — moteur `LOCAL_MACHINE` ⇒ l'envoi appelle `streamChat`, pas `streamAgent`.
- [ ] `AtelierComponent` — moteur `HOSTED_SANDBOX` ⇒ l'envoi appelle `streamAgent`.
- [ ] `AtelierComponent` — `GET /engine` en erreur ⇒ moteur déduit de la cible, écran affiché.
- [ ] `AtelierComponent` — cible basculée ⇒ moteur rechargé.
- [ ] `AtelierComponent` — le tour de boucle maison terminé conserve sa transcription (`terminal`).
- [ ] `AtelierTerminalComponent` — pastille « ma machine » vs « bac à sable hébergé ».
- [ ] `AtelierTerminalComponent` — « Réinitialiser » absent en `LOCAL_MACHINE`.
- [ ] `AtelierTerminalComponent` — gestes runner rendus seulement en cible `RUNNER`.

### Tests d'intégration

Sans objet (aucun endpoint créé ; le backend est couvert par SF-39-07).

### Isolation utilisateur

- [x] Non applicable côté écran — l'isolation `user_id` est appliquée par le backend sur chacun des
  endpoints consommés, tous déjà couverts par leurs propres tests d'intégration.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-07` — done (contrat `GET /engine`)

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

**D-L4-4 — La cible d'exécution reste un réglage, et monte dans l'en-tête du terminal.** Supprimer
les *modes* ne veut pas dire supprimer le *choix de la machine* : « où s'exécutent mes outils » est
une décision de projet que l'utilisateur prend en connaissance de cause (F-38). Elle suit donc
l'écran plutôt que de disparaître avec l'ancienne mise en page — sans quoi appairage, journal
d'audit et coupe-circuit deviendraient inatteignables, ce qui serait une régression des acquis F-38
rappelés au §4 du cadrage.

**D-L4-5 — La boucle maison est adaptée au rendu terminal, pas l'inverse.** On aurait pu faire
converger les deux flux SSE côté backend vers un format commun. C'eût été un changement de contrat
sur deux endpoints en service, pour un gain d'écran. L'adaptation tient en une fonction pure
(`chatStepsToBlocks`), testable seule, et laisse les deux flux intacts — donc réversible.

**D-L4-6 — « Réinitialiser » disparaît en `LOCAL_MACHINE`.** Le geste recrée un **environnement
hébergé** ; sur la machine de l'utilisateur, il n'y a rien à recréer, et un bouton qui ne fait rien
est pire qu'un bouton absent. L'acquis SF-30-06 est donc conservé **là où il a un sens**.
