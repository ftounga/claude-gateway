# Mini-spec — F-196 / SF-196-03 Le séparateur « ✓ Chantier N résolu » dans le fil (frontend)

## Identifiant
`F-196 / SF-196-03` — Feature parente : F-196 « Cap au service du chantier » (décision PO 4).
Statut : `ready` — 2026-10-12 — Branche `feat/SF-196-03-separateur-resolution`. Dépend de SF-196-01 (#1175, consigne « après clôture ») et SF-196-02 (#1176).

## Objectif
À la clôture d'un chantier, le fil porte un séparateur visible et rejouable « ✓ Chantier N résolu — ce qui suit va au-delà du besoin de départ », à l'endroit exact de la clôture.

## Comportement attendu

### Cas nominal
1. **Le message de clôture devient la trace** : le geste [Clore le sujet] (flux F-176 / SF-176-09 inchangé : enregistrement du geste puis tour de reprise) envoie désormais le message visible « ✓ Chantier N résolu — ce qui suit va au-delà du besoin de départ » (N = numéro du chantier courant vu au clic ; sans numéro connu : « ✓ Chantier résolu — … »). Ce message est **persisté** comme tout message du fil (`atelier_messages`) : il réapparaît à l'historique, au bon endroit, sans nouvelle table ni route.
2. **Rendu en séparateur** : un message utilisateur dont la première ligne est une marque de résolution (« ✓ Chantier [N] résolu … », ou l'ancienne « ✓ Chantier clos. » d'avant F-196) est rendu comme un **séparateur** pleine largeur — filet, coche, texte — au lieu d'une bulle « Votre question ». `role="separator"` + `aria-label` égal au texte (AA : encre du terminal héritée, aucune couleur nouvelle ; peau Teams comprise).
3. **Brouillon joint** : si un brouillon est parti avec la clôture (« marque\n\nbrouillon »), le séparateur est suivi de la bulle « Votre question » ne contenant que le brouillon.
4. **Après le séparateur** : le sujet est revenu en Libre (F-176) ; Cap n'affiche plus d'étiquette « Hors plan » (SF-196-01/02) et la consigne stable (SF-196-01) autorise les approfondissements et la proposition d'un nouveau chantier.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Tour en cours / lecture seule / plafond au moment du clic | pas de tour de reprise (règle SF-176-09 inchangée) ⇒ pas de séparateur ; la clôture reste visible dans la bande et la liste des chantiers clos |
| Message utilisateur contenant « ✓ Chantier » ailleurs qu'en tête | bulle normale |
| Chantier sans numéro connu | « ✓ Chantier résolu — … » |

## Contraintes de validation
Marque reconnue : `^✓ Chantier( \d+)? (résolu|clos)\b` sur la première ligne. Texte figé : « ✓ Chantier N résolu — ce qui suit va au-delà du besoin de départ ».

## Critères d'acceptation
- [ ] Le geste [Clore] avec chantier 2 envoie « ✓ Chantier 2 résolu — ce qui suit va au-delà du besoin de départ ».
- [ ] Un message utilisateur portant cette marque est rendu en séparateur (`role="separator"`), pas en bulle.
- [ ] Une marque suivie d'un brouillon : séparateur + bulle avec le brouillon seul.
- [ ] « ✓ Chantier clos. » de l'historique antérieur est aussi rendu en séparateur.
- [ ] Un message ordinaire reste une bulle.

## Plan de test
- `terminal-journey-resume.spec.ts` : message de clôture avec / sans numéro.
- `terminal-resolution.spec.ts` (nouveau) : fonction de reconnaissance (marque seule, marque + brouillon, ancienne marque, faux positif) ; rendu dans le terminal (séparateur, bulle du brouillon, bulle ordinaire).
- Isolation : aucune requête nouvelle ; le message passe par l'envoi existant (contrôle `requireOwned` inchangé).

## Impacts
- Tables : aucune. Endpoints : aucun.
- Composants : `journey.models.ts` (`journeyResumeMessage`, `chantierResolution`), `AtelierTerminalComponent` (rendu), `atelier-terminal.component.scss`.
- Préoccupations transversales : Auth ☐ ; Tenant ☐ ; Plans/limites ☐ ; Navigation ☐.

## Hors périmètre
Modifier le flux de clôture F-176 (enregistrement, archivage) ; un marqueur côté serveur (la trace est le message de reprise déjà persisté) ; interdire les approfondissements.
