# Mini-spec — [F-236 / SF-236-24] Vue cockpit sur écran externe + veilleur de nuit

## Identifiant
SF-236-24

## Feature parente
F-236 — Jarvis en 3D (cadrage §13 NF-12 / NF-13, §15 ; itération 4 « Au-delà »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-24-cockpit-veilleur`

---

## Objectif

Garder Jarvis en permanence sur un second écran pendant qu'on travaille au terminal (vue cockpit qui suit l'écran
principal en moins de 100 ms, sans réseau), et poser l'écran la nuit (veilleur : sombre, horloge, veilles, alertes,
réveil à l'alerte).

## Comportement attendu

### Cas nominal

1. **Lien local (pur, `hud/jarvis-cockpit-link.ts`)** : l'écran principal (meneur) relaie ce qu'il passe à sa scène
   (`stageSignals()`) à la fenêtre `?cockpit=1` (suiveur) par un `BroadcastChannel` du navigateur — **aucun réseau**.
   Le suiveur s'annonce (`hello`) ; le meneur ne publie qu'en présence d'un suiveur et lui renvoie aussitôt l'état
   courant ; un meneur qui arrive après s'annonce (`leader`). Chacun annonce son départ (`bye`).
2. **Vue cockpit** (`?cockpit=1`) : ne parle pas, n'écoute pas (ni démarrage automatique, ni mot d'éveil) ; dessine
   l'univers, les deux cerveaux, les scènes, la chronologie et les alertes relayés ; panneaux du cockpit PC ;
   repère « Vue cockpit ». Meneur parti ⇒ « Écran principal fermé », la fenêtre revient à ses propres signaux.
3. **Ouverture** : « Cockpit sur un 2ᵉ écran » (réglages, PC) ; avec la Window Management API
   (`getScreenDetails`), la fenêtre s'ouvre plein cadre sur un **autre** écran ; sinon le navigateur la place.
4. **Veilleur de nuit** (« Veilleur de nuit » dans les réglages, ou `?veilleur=1`) : voile sombre sur la scène,
   grande horloge, 3 prochaines veilles (lues **une fois** à l'entrée, `GET /api/jarvis/watches`, filtrée
   `user_id`), alertes de l'ambiance (veille qui sonne, poste muet, sujet qui appelle — jamais le coût). Une alerte
   **nouvelle** ou un toucher réveille l'écran 60 s. « Quitter le veilleur » ou Échap.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| `BroadcastChannel` absent | Pas de bouton ; `?cockpit=1` dessine ses propres signaux. |
| Fermeture de l'une des fenêtres | Aucune erreur pour l'autre (test). |
| Permission d'écrans refusée / un seul écran | Fenêtre placée par le navigateur. |
| Veilles illisibles | « Aucune veille à venir. » |

---

## Critères d'acceptation

- [x] CA1 — Le cockpit suit l'écran principal en < 100 ms (test sur un `BroadcastChannel` réel ; latence mesurée
  `linkLatency`).
- [x] CA2 — Aucun réseau ajouté par le lien (canal local ; test : publication seulement si un suiveur écoute).
- [x] CA3 — Fermeture de l'un sans erreur pour l'autre (tests).
- [x] CA4 — Veilleur : luminosité basse, réveil à l'alerte nouvelle (tests), horloge, veilles.
- [x] CA5 — Banc `veilleur` (capture dans la planche SF-236-27).

## Plan de test

- Unitaires : `hud/jarvis-cockpit-link.spec.ts` (codage, présence, annonce, départs, ordre, canal réel < 100 ms,
  placement) ; `hud/jarvis-night.spec.ts` (alertes, prochaines veilles, réveil, composant).
- Composant : `jarvis.component.spec.ts` (`?cockpit=1` : ni voix ni écoute, signaux relayés, meneur parti ;
  `?veilleur=1`).
- Isolation utilisateur : `GET /api/jarvis/watches` existant (filtré `user_id`), inchangé.

## Périmètre

### Impacté
`hud/jarvis-cockpit-link.ts`, `hud/jarvis-night.{ts,component.ts,component.scss}`, `jarvis.component.{ts,html,scss}`,
banc (`veilleur`).

### Hors scope (explicite)
- Contrôler l'écran principal depuis le cockpit (lecture seule).
- Économie d'énergie du moteur 3D en veilleur (le voile suffit ; l'arrêt onglet caché existe déjà).
- Compteurs `cockpit` / `veilleur` de la mesure : ajoutés avec SF-236-26 (clés fermées côté serveur).

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Navigation / routing | Oui | `/jarvis` : paramètres nouveaux `?cockpit=1`, `?veilleur=1` ; `?sujet=`, `?alerte=`, `?source=compagnon`, `?replay=`, `?presentation=1` inchangés (tests verts). Aucune route. |
| Contexte tenant | Non | Lecture existante des veilles (filtrée `user_id`). |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Le cockpit reçoit tout `stageSignals()` (univers compris) à chaque changement. | Même appareil, même origine ; < 100 ms mesuré. | Différentiel (complexité). | Oui. |
| A2 | Deux écrans principaux : le cockpit suit celui qui a parlé en dernier. | Pas d'état partagé à arbitrer. | Premier ouvert. | Oui. |
| A3 | Veilleur = voile DOM sur la scène (3D et 2D). | Simple, sans toucher au moteur. | Baisser la cadence du moteur. | Oui. |
| A4 | Veilles lues une fois à l'entrée du veilleur. | Endpoint existant ; pas de sondage. | Rafraîchir toutes les N minutes. | Oui. |
