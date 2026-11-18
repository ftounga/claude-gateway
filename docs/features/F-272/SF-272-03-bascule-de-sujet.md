# Mini-spec — [F-272 / SF-272-03] Changer de sujet à la voix, tournures réelles

## Identifiant
`F-272 / SF-272-03`

## Feature parente
`F-272` — Jarvis : corrections de la démo du 17/11

## Statut
`in-progress`

## Date de création
2026-11-18

## Branche Git
`feat/SF-272-03-bascule-de-sujet`

---

## Objectif
« Allons sur data-platform », « on va sur… », « ouvre data-platform » changent de sujet sur l'appareil, et un sujet
montré à la demande devient le sujet des tours suivants.

## Cause racine (prouvée)
`jarvis-oral.ts` `SWITCH` n'accepte que passe/bascule/va/change + préposition ; la démo (00:02:56) : « allons sur
data-platform » part au cerveau rapide, qui n'a aucun moyen de changer le sujet en vue ; la galerie suivante interroge
« Terminal du poste » (sujet resté en vue).

## Comportement attendu

### Cas nominal
1. Nouvelles tournures (sur l'appareil, sans tour ni coût) : allons, allez, passons, basculons, retourne(ons),
   reviens/revenons, direction, on va/passe/bascule/retourne/revient/se met, mets-toi, place-toi, je veux aller +
   sur/à/au/vers/dans/en ; ouvre/ouvrons/ouvrez/affiche/prends/prenons (sans préposition). « Je passe sur X. »
2. Ces tournures ne basculent que si la suite **est** un nom de sujet (au mot près, sans accents ni casse, « le
   sujet », « s'il te plaît », « Jarvis » tolérés) — sinon la phrase part au cerveau comme avant.
3. Un ordre d'écran **demandé** (`galerie`, `piece`, `comparer`, `raconter`, `planche`) dont la portée nomme un sujet
   connu (la passerelle l'y écrit, SF-272-02) fait de ce sujet le sujet en vue (sélecteur, scène, mémoire locale) :
   les tours suivants le prennent pour « sujet courant ». Trace `subject_follow`.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| « Ouvre le ticket 42 », « allons sur la prod de data-platform », « affiche l'architecture de X » | pas de bascule, la phrase part au cerveau |
| Nom de moins de 3 lettres | pas de bascule |
| Ordre d'initiative (`appui`) d'un autre sujet | le sujet en vue ne change pas |
| Sujet inconnu de l'écran | rien |

## Critères d'acceptation
- [ ] Les 8 tournures de la démo et voisines basculent (test).
- [ ] Les 5 phrases-demandes ci-dessus ne basculent pas (test).
- [ ] Les tournures existantes (passe/bascule/va/change, F-263) inchangées (tests existants).
- [ ] Un ordre demandé portant `scope.subjectId` connu rend ce sujet ; jamais en `appui` ni pour une navigation (test).

## Plan de test
### Tests unitaires
`jarvis-oral.spec.ts` (tournures), `jarvis-piece-show.spec.ts` (`askedSubjectOf`), `jarvis.component.spec.ts` inchangé.
### Tests d'intégration
Banc à vraie voix F-256 scénario `demo-1711` : « Allons sur data-platform » ⇒ « Je passe sur data-platform. » sans
cerveau ; 2 passages, boucle legacy.
### Isolation utilisateur
Non applicable (liste des sujets déjà filtrée par le serveur ; aucune lecture nouvelle).

## Tables / endpoints / composants impactés
Aucun endpoint, aucune table. `jarvis-oral.ts`, `pieces/jarvis-piece-show.ts`, `jarvis.component.ts`, banc
`frontend/scripts/banc-jarvis-voix/` (phrases d01-d04, scénario `demo-1711`, schéma d'architecture amorcé sur
data-platform).

## Préoccupations transversales
- Navigation / routing : non (pas de route ; le sujet en vue est un état de l'écran Jarvis).
- Auth, tenant, plans : non.

## Hors périmètre
Un outil « changer de sujet » pour le cerveau rapide (préfixe de cache et routage intouchés ; le sujet suit l'écran via
la portée vérifiée par la passerelle, SF-272-02).

## Dépendances
SF-272-02 pour que la portée porte le sujet trouvé (sans elle, la règle 3 ne s'applique qu'aux portées nommées par le
modèle).
