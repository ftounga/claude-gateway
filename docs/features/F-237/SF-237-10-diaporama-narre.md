# Mini-spec — F-237 / SF-237-10 — Le diaporama narré (« raconte-moi le chantier 3 »)

## Identifiant
`F-237 / SF-237-10` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-10-diaporama-narre`
Cadrage : §8.3, §11, §14 idée 2. Dépend de SF-237-08 (action `raconter` de l'outil `afficher`) et SF-237-09.

## Objectif
> « Raconte-moi le chantier 3 » : la galerie se lit toute seule, une pièce par phrase, faite **uniquement** de ce
> qui est déjà écrit ; la pièce suivante arrive **à la fin de la phrase** ; « pause », « suivante », « ferme » la
> pilotent. Aucun appel modèle (0 €) au-delà de la réponse rapide qui ouvre le récit.

## Comportement attendu
- Le cerveau rapide appelle `afficher {action: raconter, portee: {chantier: 3}}` (consigne v7, SF-237-08) ; l'écran
  ouvre la galerie en mode `story`, **ordonnée** : besoin initial → entrées → preuves (par étape, puis date) →
  productions → envois ; ≤ 12 pièces (≈ 1 min).
- `StoryRunner` (pur, crochets injectés) : sélectionne la pièce n, dit `storyPhrase(pièce)`, attend la **fin de la
  voix** ; pour une vidéo ou un audio, lit l'extrait (chapitre utile, ≤ 15 s) puis met en pause ; passe à la suivante.
  Le récit démarre **après** la phrase du cerveau rapide (`voice.idle()`).
- Phrase (`storyPhrase`) : description existante (§11), sinon titre + date ; rattachement « d'après la date » dit ;
  jamais de description d'image inventée.
- Voix locale pendant le récit : « suivante » coupe la voix et saute ; « pause » fige (la même pièce reprend) ;
  « reprends » relance ; « ferme » / « retour » / « remonte » arrêtent le récit et s'appliquent à la galerie.

### Cas d'erreur
- Galerie vide ou index injoignable : refus dit par l'écran (SF-237-08), aucun récit.
- Un nouveau récit arrête le précédent ; quitter / fermer coupe la voix et l'extrait en cours.
- Mode présentation : `raconter` refusé comme toute galerie.

## Critères d'acceptation
- [ ] Ordre du récit conforme ; ≤ 12 pièces.
- [ ] La pièce suivante n'arrive qu'à la fin de la phrase (test à voix factice).
- [ ] « suivante » / « pause » / « reprends » / « ferme » pilotent le récit.
- [ ] Les phrases ne contiennent que des champs de l'index (description, titre, date, étape).

## Préoccupations transversales
Aucune cochée : écran seul, aucune route, aucune lecture nouvelle, aucun appel modèle.

## Plan de test
`jarvis-piece-story.spec.ts` : ordre, phrases (avec / sans description, rattachement déduit), extrait, cadence à la
fin de phrase, pilotage (suivante, pause, reprise, arrêt).

## Hors périmètre
Avant / après, planche contact (SF-237-11) ; mesure du nombre de récits (SF-237-14).
