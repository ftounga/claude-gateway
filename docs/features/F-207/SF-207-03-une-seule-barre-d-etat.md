# Mini-spec — [F-207 / SF-207-03] Une seule barre d'état en bas du terminal

## Identifiant
`F-207 / SF-207-03` — Feature parente `F-207` — Statut `ready` — Créée 2026-10-21
Branche : `feat/SF-207-03-barre-d-etat` — indépendante.

## Objectif
Au-dessus de la saisie, les bandeaux empilés (plan reporté, parcours guidé, point du matin, attentes) et la bande « Mode » laissent place à une seule barre d'état fine, qui s'ouvre vers le haut au survol ; le fil des tâches reste affiché.

## Comportement nominal
- **Barre d'état** (≈ 30 px), juste au-dessus de la saisie, avec des **puces** :
  - **Chantier** — « Chantier N · <phase> » (ou « Guidé · <phase> ») si le sujet est guidé, + « à vous » si un geste est attendu ;
  - **Attentes** — résumé court (« 3 · 1 à relancer ») si le tableau des attentes en a ;
  - **Point du matin** — si le bandeau du matin a quelque chose aujourd'hui ;
  - **Plan** — « Plan · 2/5 » si un plan reporté est au repos.
  Aucune puce → pas de barre.
- **Ouvrir** : survol de la barre (ou focus d'une puce, ou clic) → un **tiroir** monte en surimpression au-dessus de la saisie et montre les composants d'origine, inchangés (`app-terminal-journey-strip`, `app-terminal-morning-band`, `app-terminal-attentes-band`, plan reporté). Fermeture 350 ms après la sortie de la souris, ou par Échap.
- **Les décisions ne se cachent pas** : si le parcours attend un geste (proposition de passer en guidé, diagnostic à confirmer, plan ou amendement à valider, clôture proposée), le tiroir est **ouvert d'office** et reste ouvert tant que le geste est attendu ; la puce Chantier porte « à vous ».
- **Retirer une puce** : un menu ⋯ en bout de barre liste les puces avec une case à cocher (« Afficher : Chantier, Attentes, Point du matin, Plan »). Une puce retirée n'apparaît plus dans la barre ni dans le tiroir (préférence par appareil, `localStorage` `cg.dock.hidden`, try/catch). Exception : un geste attendu par le parcours s'affiche quand même.
- **Mode du tour** : le sélecteur « Réponse/Plan | Agir » quitte sa ligne et passe dans la ligne de saisie, en version compacte (icônes `forum` / `bolt`, libellé en infobulle et `aria-label`). « Approuver & exécuter » / « Passer à l'exécution » s'y affichent aussi, en clair, quand ils s'appliquent.
- **Restent affichés en permanence** : le fil des tâches (`app-terminal-fil` : tâches, sous-agents, sous-tâches), la Plume, le menu des slash-commandes, la carte des chantiers ouverte à la demande.
- Sous 820 px : les mêmes puces, le tiroir s'ouvre au tap.

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Toutes les puces retirées | pas de barre ; un geste attendu affiche quand même le parcours |
| `localStorage` inaccessible | toutes les puces affichées, aucune erreur |
| Le geste est fait pendant que le tiroir est ouvert d'office | le tiroir redevient « au survol » |
| Lecture seule | ni barre ni mode (inchangé) |

## Critères d'acceptation
- [ ] CA1 Avec parcours guidé + attentes + point du matin, aucune décision attendue : un seul bandeau de ≤ 32 px au-dessus de la saisie ; les composants d'origine ne sont pas affichés tant qu'on ne survole pas.
- [ ] CA2 Survol → tiroir en surimpression avec les composants d'origine ; la hauteur du fil ne change pas.
- [ ] CA3 Plan à valider (ou proposition, diagnostic, clôture) → tiroir ouvert sans survol, puce « à vous ».
- [ ] CA4 Retirer « Attentes » → la puce et le bandeau disparaissent, préférence relue au rechargement ; un geste attendu passe outre.
- [ ] CA5 Le sélecteur de mode est dans la ligne de saisie ; il émet `modeChange` ; « Approuver & exécuter » émet `switchToAct`.
- [ ] CA6 Le fil des tâches reste affiché hors tiroir.
- [ ] CA7 Non-régression : specs bandeaux (`terminal-bandeaux-empiles`, `terminal-journey*`, `terminal-morning-band`, `terminal-actions*`), mode (F-120) et lecture seule vertes ; build vert.

## Plan de test
- `terminal-barre-etat.spec.ts` (nouveau) : puces selon l'état (CA1), ouverture et fermeture (CA2), ouverture d'office par décision (CA3), retrait et `localStorage` absent (CA4), mode dans la saisie (CA5), fil hors tiroir (CA6).
- Mise à jour des specs existantes qui cherchent `.terminal-mode` ou la position des bandeaux.
- Isolation utilisateur : sans objet.

## Impacts
- Tables / endpoints : aucun. Composants : `AtelierTerminalComponent` (gabarit bas, état du tiroir, préférences), nouvelle feuille `atelier-terminal-dock.component.scss`.
- Préoccupations transversales : aucune.
- Design system : couleurs existantes (`--cg-orange-2` pour « à vous », encre du terminal).

## Hors périmètre
Contenu des composants de bandeau, panneau des attentes, Cap/« Et maintenant ? » (dans le fil).
