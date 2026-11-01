# Mini-spec — F-235 / SF-235-03 — Les garde-fous (D9, D11)

## Identifiant
`F-235 / SF-235-03` · Feature parente `F-235` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-235-03-garde-fous`

## Objectif
> Ne jamais faire dire au cerveau rapide un fait inventé, une formule creuse ou une phrase répétée : contrôles
> déterministes, sans appel modèle, appliqués phrase par phrase **avant** que la phrase parte vers l'écran.

## Comportement attendu
### Cas nominal
`JarvisFastGuards` (pur, sans état) + `JarvisFastBrainService` :
- **Phrase par phrase** : les fragments du modèle sont retenus jusqu'à la fin d'une phrase (`. ! ? …`) ; la phrase
  complète passe les contrôles puis seulement part en événement `text` (aucune latence ajoutée : la synthèse attend
  de toute façon une phrase complète, `OralStream`).
- **Ancrage (D9a)** : tout nombre (≥ 2 chiffres, montant, pourcentage, heure), toute clé de ticket (`ABC-123`), tout
  nom propre absent **à la fois** du contexte D5 et de la parole ⇒ la phrase n'est pas dite, le reste n'est plus
  écouté, l'échange devient une **délégation d'office** (`reason=grounding`, annonce « Je vérifie, Monsieur. »).
- **Texte qui imite un appel d'outil (D9c)** (`deleguer(`, `"demande":`, balise `<tool…>`, JSON en tête) ⇒ délégation
  d'office (`reason=tool_text`), jamais dit.
- **Délégation forcée (D9d)** : la parole est (à la politesse près) « vérifie », « demande à Opus », « réfléchis-y »,
  « c'est faux »… (liste fermée, classement local) ⇒ **aucun appel modèle** ; délégation d'office de la **parole
  précédente** de la session (mémoire), sinon de la parole elle-même (`reason=forced`).
- **Formules creuses (D11d)** : une phrase qui n'est **que** « Je m'en occupe », « Je t'écoute », « Je vous écoute »,
  « Bien reçu », « D'accord, je regarde », « Très bien », « Je regarde. », « Une seconde. », « Laissez-moi vérifier »
  (± « Monsieur ») est **retirée** (comptée) ; dans une phrase porteuse de contenu, elle est conservée.
- **Anti-répétition (D11c)** : une phrase identique (normalisée) à une phrase des **5 dernières réponses** de la
  session, ou déjà dite dans la même réponse, est retirée (comptée).
- **Réponse vide après retrait** ⇒ délégation d'office (`reason=empty`).
- **Annonce de délégation (D11b)** : creuse ou répétée ⇒ remplacée par une annonce qui nomme l'objet
  (« Je regarde : <label>. ») ; sans label, « Je vérifie, Monsieur. ».
- **« Monsieur » au plus une fois par réponse (D11e)** : les occurrences suivantes sont retirées de la phrase.
- **Compteurs** portés par le résultat (`Guards` : `groundingTrips`, `fillersRemoved`, `repeatsRemoved`,
  `forcedDelegation`, `toolText`) et par l'événement `done` (consommés par la mesure SF-235-07).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Fait non ancré dans la 2ᵉ phrase | 1ʳᵉ phrase dite, 2ᵉ jamais dite, délégation d'office |
| Garde en exception | phrase traitée comme non ancrée (délégation) — jamais d'invention |
| Délégation forcée sans parole précédente | la parole elle-même est déléguée |

## Critères d'acceptation
- [x] Nombre / clé de ticket / nom propre inventé ⇒ délégation ; présent dans le contexte ou la parole ⇒ dit.
- [x] Formule seule retirée ; formule dans une phrase pleine conservée.
- [x] Jamais deux remplissages identiques consécutifs ; phrase des 5 dernières réponses retirée.
- [x] « vérifie » / « demande à Opus » / « c'est faux » ⇒ délégation sans appel modèle, de la question précédente.
- [x] Texte d'appel d'outil ⇒ délégation, jamais émis vers l'écran.
- [x] Compteurs exposés dans `done`.

## Plan de test
- Unitaires `JarvisFastGuardsTest` : ancrage (nombre, heure, montant, clé, nom propre ; présents ⇒ ok), formules,
  répétition, délégation forcée (liste fermée, politesse), texte d'outil, découpage en phrases.
- Unitaires `JarvisFastBrainServiceTest` : phrase non ancrée ⇒ délégation et non émise ; formule retirée ; répétition
  d'une réponse précédente retirée ; « vérifie » ⇒ pas d'appel, délégation de la question précédente ; annonce creuse
  remplacée ; « Monsieur » au plus une fois.
- Pas de nouvel endpoint (le contrat `done` gagne des champs facultatifs).

## Tables / endpoints / composants
Aucune table. `POST /api/jarvis/converse` : `done` gagne `guards`. `jarvis/fastbrain/JarvisFastGuards` (nouveau),
`JarvisFastBrainService`, `JarvisFastBrainController`.

## Préoccupations transversales
Aucune (pas d'auth, de tenant, de plan ni de route modifiés) ; la mémoire lue reste clefée par `user_id`.

## Hors périmètre
Filtre « aucune imitation » et note « ton sobre » (SF-235-09) ; persistance des compteurs (SF-235-07).

## Arbitrages (par défaut, réversibles)
- Mots toujours permis (jours, « Monsieur », « Opus », « Jira »…) : liste fermée pour ne pas déléguer sur un mot de
  début de phrase intérieure ; un nom propre en tout début de phrase n'est pas contrôlé (on ne distingue pas
  « Edenred » de « Demain » sans modèle) — risque résiduel couvert par le banc (SF-235-08).
- « Monsieur » surnuméraire retiré plutôt que la phrase entière.
