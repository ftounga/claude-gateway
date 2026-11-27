# SF-259-02 — Le voile discret actif par défaut, partout

**Feature parente** : F-259 — Le voile discret
**Type** : évolution (décision PO du 2026-11-27)
**Estimation** : < 1 h

## Objectif
Le voile discret est actif d'office sur tout appareil, sans passer par les Paramètres.

## Constat
Le voile est livré depuis le 15/11, mais il était désactivé par défaut. Le PO ne l'a donc jamais vu à l'œuvre et a cru
qu'il manquait. Sa décision : « active le voile par défaut partout ».

## Comportement nominal
- Un appareil sans réglage a le voile actif, avec un délai de 2 min. Le double Échap le pose, l'inactivité aussi, et
  un clic l'enlève.
- La clé de stockage passe à `cg.veil.v2`. Un réglage enregistré avant cette version (`cg.veil`), datant du temps où le
  voile était éteint par défaut, est ignoré une fois. Le voile est ainsi actif partout.
- Le choix explicite de l'utilisateur reste respecté : s'il décoche, le voile reste éteint sur cet appareil.
- Un réglage sans champ `enabled` vaut « actif ».

## Cas d'erreur
- Si le stockage local est inaccessible (navigation privée, accès refusé), le voile reste **éteint**, car on ne
  pourrait pas l'y désactiver.

## Critères d'acceptation
- [x] Défaut = `{ enabled: true, delayMin: 2 }` (test).
- [x] Un `enabled: false` explicite est respecté ; un réglage sans `enabled` vaut actif (test).
- [x] L'écran Paramètres montre la case cochée et le délai réglable ; décocher écrit `enabled: false` (test).
- [x] Stockage inaccessible : voile éteint, sans erreur (test existant).

## Plan de test
- Unitaires : `discreet-veil.service.spec.ts`, `discreet-veil-settings.component.spec.ts`, `layout/discreet-veil`,
  `layout/shell`, `jarvis.component.spec.ts`.
- Isolation utilisateur : non applicable. Le réglage est local à l'appareil, sans aucun appel serveur.

## Impact
- Frontend : `core/services/discreet-veil.service.ts` uniquement, plus les tests.
- Backend, tables, endpoints : aucun.
- Préoccupation transversale : aucune. La navigation n'est pas touchée.

## Hors périmètre
- Le réglage côté compte (serveur) : il reste par appareil, comme le prévoyait le cadrage F-259.
