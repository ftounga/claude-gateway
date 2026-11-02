# Mini-spec — [F-236 / SF-236-23] Mode présentation

## Identifiant
SF-236-23

## Feature parente
F-236 — Jarvis en 3D (cadrage §13 NF-11, §15 ; itération 4 « Au-delà »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-23-mode-presentation`

---

## Objectif

Montrer Jarvis à un client ou à un manager **sans fuite** : `?presentation=1` filtre ce qui est dessiné (initiales,
ni coût, ni sortie, ni détail), fait faire à la caméra un tour lent de l'univers, plein écran, Échap pour sortir.

## Comportement attendu

### Cas nominal

1. **Filtre pur, avant le dessin** (`hud/jarvis-presentation.ts`) appliqué à `stageSignals()` :
   - univers : personnes en **initiales** (« Zahi Ben » ⇒ « Z. B. »), variantes et rôles retirés ; attentes et
     destinataires « À envoyer » prennent les initiales de la personne qu'ils désignent (les liaisons restent
     justes) ; postes, sujets, chantiers gardés ;
   - scène : libellé générique gardé, `detail` retiré ; données de détail retirées (sorties, motifs, objets,
     adresses, titres, fichiers, tickets, points de route, satellites) ; personne / destinataire en initiales ;
   - nœuds cités : clé de personne remplacée par celle de ses initiales ;
   - ambiance : **coût jamais** ; textes d'appel et d'alerte vidés (le signal reste) ; jamais administrateur (A4) ;
   - « Montrer » non rendu ; mémoire de session (tickets et fichiers vus) non dessinée.
   Mémorisé par identité (le plan n'est pas recalculé pour rien).
2. **DOM** : ni sous-titres, ni détail, ni scène 2D, ni délégations, ni cockpit (replié), ni replay, ni compteur
   « À envoyer », ni réglages ; bas de l'écran effacé ; repère « Présentation · Échap pour quitter ». En 2D, l'orbe
   seule (la constellation nomme les personnes).
3. **Tour automatique** : toutes les 9 s, la caméra regarde le nœud suivant (postes puis sujets) — en 3D seulement.
4. **Plein écran** au premier toucher (le navigateur exige un geste) ; **Échap** quitte le mode (et le plein écran).

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Univers vide | Aucun tour automatique ; rien d'autre ne change. |
| Plein écran refusé | Le mode reste, en fenêtre. |
| 2D (repli) | Filtre et DOM identiques ; pas de tour (aucune caméra). |

---

## Critères d'acceptation

- [x] CA1 — Aucun nom de personne, coût, sortie ni détail rendu : test sur le **plan** (chaque scène du banc) et
  sur le **DOM** (`?presentation=1`).
- [x] CA2 — Tour automatique : postes puis sujets (test) ; `look` toutes les 9 s.
- [x] CA3 — Sortie par Échap (test).
- [x] CA4 — Banc `presentation` (capture dans la planche SF-236-27).

## Plan de test

- Unitaires : `hud/jarvis-presentation.spec.ts` (initiales, univers, chaque scène du banc sans fuite dans le plan,
  ambiance, mémorisation, tour).
- Composant : `jarvis.component.spec.ts` (`?presentation=1` : DOM sans fuite, signaux filtrés, Échap).
- Isolation utilisateur : sans objet (aucun appel nouveau).

## Périmètre

### Impacté
`hud/jarvis-presentation.ts`, `jarvis.component.{ts,html,scss}`, `jarvis-stage-3d.component.ts` (`presentation`),
banc (`presentation`).

### Hors scope (explicite)
- Masquer les noms des postes et des sujets (ce que l'on présente).
- Compteur `presentation` de la mesure : ajouté avec l'agrégat de SF-236-26 (clés fermées côté serveur).
- Aucun endpoint, table, appel IA ni consigne.

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Navigation / routing | Oui | `/jarvis` : paramètre nouveau `?presentation=1` ; `?sujet=`, `?alerte=`, `?source=compagnon`, `?replay=` inchangés (tests verts). Aucune route. |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Postes et sujets gardent leur nom ; personnes en initiales. | Lettre du cadrage (« noms de personnes masqués ») ; on présente son travail. | Tout anonymiser. | Oui. |
| A2 | « Montrer » non rendu en présentation. | Ses listes peuvent nommer des personnes. | Masquer chaque ligne. | Oui. |
| A3 | Plein écran au premier toucher. | Les navigateurs refusent le plein écran sans geste. | Bouton dédié. | Oui. |
| A4 | Bas de l'écran (voix, compagnon) effacé ; l'orbe reste touchable pour parler. | Écran épuré pour un public. | Tout garder. | Oui. |
