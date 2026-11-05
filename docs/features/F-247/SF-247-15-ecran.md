# Mini-spec — F-247 / SF-247-15 L'écran « Ce que je sais » (lot 1 : réglages et faits)

## Identifiant
`F-247 / SF-247-15`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.1 réglage par client, D3, D11)

## Statut
`en cours` — **lot 1 livré le 2026-11-05** (réglages, sources, familles, effacement, faits). **Reste** : la rétention
réglable à l'écran, les conversations capturées dans la vue et la dépense en € (avec SF-247-12/13/14), le banc headless.
**Branche :** `feat/SF-247-15-ecran` · **Dépend de :** SF-247-02

## Objectif
Le registre étant activé d'office pour chaque client (D3), le PO voit ce que Jarvis sait de chaque client et peut le
couper (couper efface), choisir les sources et les familles, effacer tout ou un fait.

## Comportement attendu

### Cas nominal
1. API (`JarvisStateController`, sous le `user_id` du principal) :
   - `GET /api/jarvis/state/clients` : un réglage par client du compte (ligne absente = tout activé) + nombre de faits ;
   - `PUT /api/jarvis/state/clients/{hostId}` : patch (`enabled`, `disabledFamilies`, `extractFromTurns`,
     `notesEnabled`, `releveEnabled`, `capturesEnabled`, `factRetentionDays`, `captureRetentionDays`) ;
     `enabled: false` **purge** ;
   - `GET /api/jarvis/state/clients/{hostId}/facts[?famille=]` : 20 faits récents (datés, source) ;
   - `DELETE /api/jarvis/state/clients/{hostId}/facts` (« Effacer tout ») ; `DELETE /api/jarvis/state/facts/{id}`.
2. Écran : bouton « Ce que je sais » dans les réglages de `/jarvis` (pas de nouvelle route), volet en verre (patron
   « Mes agendas ») : par client, interrupteur « Activé » (couper ⇒ confirmation `MatDialog` « Couper et effacer »),
   sources (Tours, Relevé, Captures, Constats), familles (bascule d'un clic), « Voir » les faits (clé, valeur, date,
   source, effacer un fait), « Effacer tout » (confirmation). **Jamais en présentation.**

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Client d'un autre compte | refus | 404 |
| Rétention hors 1-365, famille inconnue | refus | 400 |
| Fait d'un autre compte | refus | 404 |
| Échec réseau | message `MatSnackBar`, état d'avant rétabli | — |
| Couper refusé à la confirmation | rien n'est envoyé | — |

## Critères d'acceptation
- [x] Activé d'office ; couper efface ; isolation à deux comptes ; bornes.
- [x] Volet : client, faits, couper confirmé, famille, effacer tout.
- [x] Masqué en présentation ; design system (verre §23, Inter/Merriweather/JetBrains Mono, `MatDialog`, `MatSnackBar`, multiples de 4 px).

## Plan de test
- Intégration : `JarvisStateApiIntegrationTest` (2).
- Frontend : `jarvis-state-panel.component.spec.ts` (4) ; suite complète.

## Impact
- Backend : `jarvis/state/JarvisStateController` (nouveau). Frontend : `jarvis/jarvis-state.ts`, `jarvis/jarvis-state-panel.component.ts` (nouveaux), `jarvis.component.{ts,html}`.
- Préoccupations transversales : Contexte tenant — chaque route vérifie `RunnerHostRepository.findByIdAndUserId` ; effacement d'un fait par `findByIdAndUserId`. Navigation : aucune route ajoutée.

## Hors périmètre (lot 2)
Rétention réglable à l'écran, conversations capturées, dépense en € (captures, constats), banc headless 1366×768 / 360×740.
