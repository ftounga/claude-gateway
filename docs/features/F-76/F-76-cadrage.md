# Cadrage — F-76 — Voir travailler ses terminaux

**Date** : 2026-09-12
**Source de vérité** : `docs/PRODUCT_SPEC.md`, ligne F-76 — les décisions du PO y sont **déjà
prises** et ne sont pas rouvertes ici.

---

## Ce que le PO a tranché (recopié, non discuté)

1. **Voir**, pas écrire. Quatre agents peuvent travailler en parallèle depuis F-70 ; l'écran n'en
   montre qu'un. F-76 montre **ce qu'ils font**.
2. **Forme retenue — l'aperçu vivant** : les **dernières lignes** du terminal et **ce qui s'y passe
   à l'instant** (« exécute `npm test` », « attend une autorisation »), rafraîchi en continu.
   **Écarté** : rejouer quatre flux complets — plus fidèle, mais illisible dans une tuile.
3. **Deux densités, même source** : quelques lignes sous le nom de chaque projet **actif** sur les
   cartes de `/forge` ; l'aperçu complet dans les **tuiles** d'une **vue de supervision** à part.
   Un clic entre dans le terminal.
4. **Exigence non négociable** : une tuile **en attente d'autorisation se signale franchement**.
   C'est ce qui a échappé à l'utilisateur pendant douze heures le 2026-09-08 (F-47).
5. **Chaque tuile porte la couleur du client** (SF-49-03) : quatre terminaux, c'est souvent quatre
   clients.
6. **Hors périmètre** : écrire dans une tuile ; la mosaïque à quatre terminaux interactifs.

---

## Ce qui existe déjà (constaté dans le code)

| Fait | Où | Conséquence pour F-76 |
|---|---|---|
| Le registre des terminaux vivants est **en base**, tenu par un battement de cœur de 30 s, et rend déjà `workspaceName` / `hostId` / `hostName` | `LiveTerminalService`, `GET /api/terminals/live` | C'est **le** porteur naturel de l'aperçu : une ligne existe déjà par terminal vivant, avec son projet et son poste. Rien à inventer, quatre colonnes à ajouter. |
| `GET /api/runner-hosts/overview` porte déjà `liveTerminal` par projet et `liveTerminals` par poste, rafraîchi toutes les 15 s | `RunnerHostOverviewService` | La première densité (les cartes de `/forge`) se branche sur un appel **déjà joué**, sans nouveau canal. |
| La transcription d'un tour (commandes **et** sorties) est **déjà persistée** | `atelier_messages.terminal_json` | Ranger un aperçu borné de quelques lignes n'ouvre **aucune** classe de donnée nouvelle : même propriétaire, même isolation, volume dérisoire à côté. |
| La demande d'autorisation vit **en mémoire du pod** qui tient le tour | `RunnerConfirmationGate` (`ConcurrentHashMap`) | Sous HPA, un écran servi par un autre replica **ne la verrait pas**. Une source serveur exigerait d'écrire en base depuis le chemin chaud du flux. Voir l'arbitrage A1. |
| L'écran du terminal sait **tout** : blocs de commande et de sortie au fil de l'eau, `pendingConfirmation`, tour en cours | `atelier.component.ts`, `AtelierExecStreamingItem`, `AtelierPendingConfirmation` | L'onglet qui travaille est le seul à connaître l'instant présent, et il **parle déjà** à la gateway toutes les 30 s. |
| Trois registres de couleur, et **pas un quatrième** : §5 statut, §9 identité du poste, §10 état de mission, §11 vie sans couleur | `DESIGN_SYSTEM.md` | L'attente d'autorisation emprunte la pastille **§5 « En attente »** (`#FFF8E1` / `#F9A825`), déjà dans la charte. Aucune couleur nouvelle. |
| Un terminal détruit son composant en changeant de route ; « plusieurs terminaux » = plusieurs **onglets** | `atelier.component.ts` (`ngOnDestroy`) | La vue de supervision est un **écran de plus**, pas un onglet de terminal : elle ne prend **aucune** place au registre (elle n'ouvre aucun projet). |

---

## La question qui décide de tout : d'où viennent les dernières lignes ?

Deux sources possibles, et une seule est tenable aujourd'hui.

**(a) La gateway écoute son propre flux.** Le tour passe par `AtelierChatService` : la gateway voit
les commandes, les sorties et les demandes d'autorisation. Mais ce qu'elle en sait vit **dans la
mémoire du pod qui tient le flux** — `RunnerConfirmationGate` en est l'exemple exact. Pour qu'un
autre replica puisse l'afficher, il faudrait **écrire en base depuis le chemin chaud du flux**, à
chaque sortie de commande. On paierait une écriture par événement pour un aperçu de six lignes.

**(b) L'onglet qui travaille dit ce qu'il fait.** Il le sait mieux que quiconque — c'est lui qui
affiche ces lignes — et il **parle déjà** à la gateway : le battement de cœur de F-70 part toutes
les 30 s. L'aperçu voyage avec lui. Une écriture par battement, sur une ligne qui existe déjà.

**Retenu : (b).** Avec une correction qui vise l'exigence non négociable : **un changement
d'activité part immédiatement**, sans attendre le battement. « Attend une autorisation » n'est pas
une ligne de plus, c'est le seul état que l'utilisateur doit voir tout de suite.

Contrepartie assumée, et **dite dans la mini-spec** : un onglet fermé n'envoie plus rien, donc sa
place expire et sa tuile disparaît. C'est exactement le hors-périmètre de F-70 (« un agent qui
travaille onglet fermé ») ; F-76 ne le rouvre pas.

---

## Découpage

| SF | Titre | Portée |
|---|---|---|
| **SF-76-01** | Ce que chaque terminal vivant est en train de faire | Migration **075** : quatre colonnes sur `live_terminals` (activité, détail, lignes, instant). Le battement de cœur accepte l'aperçu, borné et nettoyé côté serveur. `GET /api/terminals/live` et `GET /api/runner-hosts/overview` le rendent. Isolation `user_id` inchangée. |
| **SF-76-02** | Le terminal dit ce qu'il fait, la carte le montre | Côté écran : dérivation de l'activité et des dernières lignes depuis l'état du terminal, envoi (immédiat sur changement d'activité, apaisé sinon), et **première densité** — l'aperçu sous le nom du projet sur les cartes de `/forge`. |
| **SF-76-03** | La vue de supervision, en tuiles | **Seconde densité** : `/forge/supervision`, une tuile par terminal vivant, couleur du client, dernières lignes, activité, **attente d'autorisation signalée franchement**, un clic entre dans le terminal. Charte §12. |

Backend (SF-76-01) mergé **avant** le frontend.

---

## Arbitrages (gates réversibles — décidés et tracés)

| # | Gate | Décision | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | D'où vient l'aperçu | **L'onglet qui travaille le pousse** avec son battement de cœur | La gateway écoute son flux : l'état vit en mémoire du pod (cf. `RunnerConfirmationGate`), donc invisible sous HPA sans une écriture en base **par événement** dans le chemin chaud | Oui — quatre colonnes additives |
| A2 | Cadence | **Immédiate sur changement d'activité**, apaisée (5 s) sur simple défilement de lignes, et de toute façon portée par le battement de 30 s | Tout envoyer au fil de l'eau : une écriture par ligne de sortie, pour un aperçu qu'on regarde du coin de l'œil | Oui — deux constantes |
| A3 | Combien de lignes | **6 lignes, 160 caractères chacune**, tronquées **au serveur** | Laisser le client décider : une tuile n'a pas la place, et une borne tenue par l'appelant n'est pas une borne | Oui |
| A4 | Le vocabulaire de l'activité | **Quatre valeurs** : `IDLE`, `THINKING`, `RUNNING`, `AWAITING_APPROVAL`. Une valeur inconnue est lue comme `IDLE` | Un texte libre : impossible d'en faire une règle d'affichage (ni tri, ni signalement franc). Un 400 sur valeur inconnue : un front en avance sur la gateway perdrait son aperçu entier | Oui |
| A5 | Comment se signale « attend une autorisation » | **Pastille §5 « En attente »** (`#FFF8E1`/`#F9A825`) **écrite**, anneau ambre autour de la tuile, tuile **remontée en tête**, et compteur écrit en en-tête | Un quatrième registre de couleur (interdit) ; un simple point (c'est précisément ce qui a échappé pendant douze heures) | Oui |
| A6 | Où vit la vue de supervision | **`/forge/supervision`**, écran à part atteint depuis l'en-tête de `/forge` | Un onglet de la barre : le PO a supprimé « Postes » en F-68 pour désencombrer ; on ne le rouvre pas. Un panneau dans `/forge` : la page d'accueil doit rester lisible sur un portable | Oui — une route additive |
| A7 | La vue de supervision prend-elle une place au registre ? | **Non** : elle n'ouvre aucun terminal, elle lit | Lui faire prendre une place : elle mangerait un des quatre flux **payants** pour regarder les trois autres | Oui |
| A8 | Aperçu du **terminal du poste** (F-74) | **Rendu lui aussi**, par un champ additif sur la réponse de la vue d'ensemble | L'ignorer : on travaille dedans, et sa tuile existerait dans la supervision sans rien sur la carte — deux densités qui se contrediraient | Oui |

Aucun gate irréversible, aucun gate de sécurité : les colonnes sont additives et portent leur
`rollback`, rien n'est supprimé, aucune clef n'est exposée, et l'aperçu est une **donnée
d'affichage du propriétaire**, du même ordre que `atelier_messages.terminal_json` déjà persisté.

---

## Hors périmètre (rappelé)

- **Écrire dans une tuile** — la vue de supervision est en lecture seule, sans champ de saisie.
- La **mosaïque à quatre terminaux interactifs**.
- Un agent qui travaille pendant que l'onglet est fermé (hors périmètre F-70, non rouvert).
- **Notifier** (courriel, notification système) qu'un terminal attend une autorisation : F-76 met le
  fait **à l'écran**, elle ne sort pas du navigateur.
- Agir sur plusieurs postes à la fois (hors périmètre F-49).
