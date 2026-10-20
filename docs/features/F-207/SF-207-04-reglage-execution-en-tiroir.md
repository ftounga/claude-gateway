# Mini-spec — [F-207 / SF-207-04] Réglage d'exécution dans un tiroir d'en-tête

## Identifiant
`F-207 / SF-207-04` — Feature parente `F-207` — Statut `done` — Créée 2026-10-21
Branche : `feat/SF-207-04-execution-en-tiroir` — indépendante.

## Objectif
La rangée « Où s'exécutent les outils » (bac à sable / ma machine, actualiser, journal, coupe-circuit) et, quand tout va bien, la ligne d'état du poste (« Ce projet vit sur X — poste connecté ») ne prennent plus de hauteur en permanence : elles s'ouvrent en tiroir sous l'en-tête du terminal, au survol de l'en-tête.

## Comportement nominal
- À partir de 820 px, la rangée `.terminal-target` sort du flux et devient un tiroir accroché sous l'en-tête.
- **Ouvrir** : survol de l'en-tête (`.terminal-bar`) ou du tiroir, clic sur la pastille moteur (qui devient un bouton, `aria-expanded`), ou focus d'un élément du tiroir. Glissement de 160 ms, en surimpression, ombre de la charte. Fermeture 350 ms après la sortie, ou Échap.
- **Ouvert d'office** pendant un changement de cible (`switchingTarget`), pour que l'utilisateur voie la bascule aboutir.
- Contenu du tiroir : **inchangé** (mêmes boutons, mêmes sorties).
- **Ligne d'état du poste** (`.terminal-host-state`) : elle rejoint le tiroir **seulement quand l'état est nominal** (poste connu, runner connecté, aucune reprise proposée). Poste inconnu, déconnecté ou reprise disponible : elle reste dans le flux, visible, comme aujourd'hui (c'est une alerte qui appelle un geste).
- Sous 820 px : rendu inchangé.

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Lecture seule | pas de rangée (inchangé) |
| Bascule en cours | tiroir ouvert jusqu'à la fin |
| `prefers-reduced-motion` | sans glissement |

## Critères d'acceptation
- [ ] CA1 À 1366 px, au repos, poste connecté : aucune hauteur occupée par `.terminal-target` ni `.terminal-host-state` entre l'en-tête et le fil (≈ 116 px rendus au fil).
- [ ] CA1b Poste déconnecté ou reprise disponible : `.terminal-host-state` reste dans le flux.
- [ ] CA2 Survol de l'en-tête → tiroir visible ; le fil ne bouge pas.
- [ ] CA3 Clic sur la pastille moteur → bascule ouvert/fermé, `aria-expanded` à jour.
- [ ] CA4 `switchingTarget` vrai → tiroir ouvert.
- [ ] CA5 Non-régression : `executionTargetChange`, `refreshRunner`, `openRunnerAudit`, `killRunner` émis comme avant ; specs F-38 et en-tête (F-200) vertes ; build vert.

## Plan de test
- `terminal-tiroir-execution.spec.ts` (nouveau) : CA1–CA4 ; specs existantes adaptées si elles supposent la rangée visible (on la survole ou on l'ouvre d'abord).
- Isolation utilisateur : sans objet.

## Impacts
- Tables / endpoints : aucun. Composants : `AtelierTerminalComponent` (état du tiroir, pastille moteur cliquable), `atelier-terminal-header.component.scss`.
- Préoccupations transversales : aucune.
- Design system : aucune couleur nouvelle.

## Hors périmètre
Contenu de la rangée et de la ligne d'état, notices d'appairage et proposition de runner (restent dans le flux).
