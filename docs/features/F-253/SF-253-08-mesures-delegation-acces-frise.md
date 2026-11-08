# Mini-spec — F-253 / SF-253-08 Mesures justes, délégation complète, accès en 2D, frise surlignée

## Identifiant
`F-253 / SF-253-08`

## Feature parente
`F-253` — Jarvis montre vraiment (cadrage §3 ligne 8 ; audit #7, #12, #25, #37, #40, #43)

## Statut
`done` · **Estimation :** 1 j · **Branche :** `feat/SF-253-08-mesures-delegation-frise` · **Dépend de :** SF-253-01

## Objectif
Que la mesure du rendu parte même quand l'onglet se ferme, qu'une délégation s'affiche et se compte comme un tour
direct, que « Ce que je sais » s'ouvre en 2D, et que la frise d'agenda surligne le rendez-vous au moment où il est dit.

## Comportement attendu
1. **Mesure du rendu** : envoyée une seule fois par ouverture, à la première occasion parmi `pagehide`,
   `visibilitychange=hidden`, le rechargement de mise à jour automatique et la sortie de l'écran. Quand la page se
   ferme, l'envoi passe par `fetch(..., {keepalive:true})` avec le jeton (patron de `JarvisTraceSink`).
2. **Délégation** : même relais d'écran qu'un tour direct (`onPlan`, `onAttente`, `onQuestions`, `onRecalled`,
   `onCompaction`, `onPage`, `onVideo`, `onHandoff`, factorisés) ; à sa fin, `jarvis.scenes(takeStats())`.
3. **2D** : bouton « Ce que je sais » au pied de page `.jv__wake`, à côté de « Fil du jour », jamais en présentation.
4. **Frise** : au moment où une phrase commence à être jouée, les rendez-vous qu'elle nomme (titre ou mot distinctif du
   titre, heure de début dite « 14 h 30 ») sont surlignés, sur la frise et dans la liste, avec des tons existants ; la
   phrase suivante remplace le surlignage, la fin de la parole l'efface. Aucun appel réseau.

### Cas d'erreur
- Pas de scène 3D montée (`stage3dRef` absent ou rapport nul) : rien n'est envoyé, le drapeau reste baissé.
- `fetch` indisponible ou refusé : ignoré (best-effort, jamais d'erreur à l'écran).
- Un mot présent dans deux rendez-vous : il n'allume rien ; le titre complet ou l'heure restent reconnus.
- Aucune phrase en cours, ou présentation : aucun surlignage (la frise n'existe pas en présentation).

## Critères d'acceptation
- [x] `pagehide` puis `visibilitychange=hidden` puis sortie : exactement un envoi, `keepalive` sur la fermeture.
- [x] Une délégation terminée envoie `jarvis.scenes` ; ses événements plan, page, vidéo atteignent la scène.
- [x] « Ce que je sais » présent et fonctionnel en 2D, absent en présentation.
- [x] Une phrase citant un rendez-vous par titre ou heure le surligne ; un mot ambigu ne surligne rien.

## Plan de test
Unitaires : `jarvis.component.spec.ts` (mesure unique, délégation), `jarvis-agenda.component.spec.ts` (rapprochement,
classe surlignée), `jarvis.service` (keepalive). Intégration : banc `frontend/scripts/banc-jarvis-montre/` (bouton 2D,
envoi à `pagehide`). Isolation utilisateur : sans objet (aucune lecture nouvelle ; `/api/jarvis/usage` reste authentifié
par le jeton du compte).

## Impact
`jarvis.component.ts/.html`, `core/services/jarvis.service.ts`, `jarvis-agenda.component.ts`, `jarvis-calendar.ts`, specs,
banc. Aucune table, aucun endpoint. Préoccupations transversales : aucune (ni auth, ni tenant, ni limites, ni routage).

## Hors périmètre
Pancarte rendez-vous hors lecture d'agenda (audit #43 : aucune source ; le surlignage de la frise couvre le besoin) ;
frise en dehors d'une lecture d'agenda ; heures dites en toutes lettres.
