# Mini-spec — F-239 / SF-239-05 — Comprendre ce qui a été voulu (relecture `compris:`)

## Identifiant
`F-239 / SF-239-05` · Feature parente `F-239` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-239-05-compris`

## Objectif
> Comme la dictée de Claude Code, Jarvis relit ce qu'il a entendu à la lumière du contexte et en tire le texte
> voulu (noms propres, mots mal entendus, ponctuation), sans appel ni latence en plus, et sans jamais inventer de
> demande.

## Comportement attendu
### Cas nominal
- **Consigne du cerveau rapide v4** (fixe, versionnée) : la réponse commence **toujours** par une ligne
  `compris: <la parole relue>` — la parole corrigée seulement des noms propres et mots mal entendus d'après le
  contexte (univers, derniers échanges), ponctuée, sans rien ajouter ni retirer ; puis la réponse, ou l'appel à
  `deleguer`. Rien de volatil dans la consigne : le contexte est dans le message (inchangé).
- **Passerelle** : la première ligne `compris:` n'est **jamais dite** ; elle est lue, passée à la **garde
  anti-invention** (`JarvisUnderstanding`) puis envoyée à l'écran (événement `understood`) avant la première phrase.
  Garde : relecture refusée (⇒ parole brute) si elle ajoute un verbe d'action absent de la parole (liste fermée :
  envoyer, lancer, supprimer, créer, publier, pousser, valider, fermer, relancer, effacer, déployer, fusionner,
  annuler, payer…), si la distance d'édition par mot dépasse **40 %** de la parole, ou si elle est vide.
- **Le texte relu** est celui de la trace du fil (message USER de l'échange rapide) et celui que reçoit Opus quand
  l'écran ouvre la délégation (au lieu de la parole brute) ; la parole brute ne reste que dans la mesure (longueur).
- **Écran** : le texte affiché sous l'orbe passe du provisoire (flux) au retranscrit (SF-239-04) puis au **compris**.
- Cerveau rapide éteint, repli, ou confirmation directe à Opus : la parole retranscrite part telle quelle (passe
  dédiée reportée : la retranscription avec vocabulaire suffit à 0,3-1,1 % au banc).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Pas de ligne `compris:` | Parole retranscrite gardée, rien de dit en moins |
| Relecture qui invente une action / trop éloignée | Refusée (compteur `understandingRejected`), parole gardée |
| Ligne `compris:` seule (aucune réponse) | Délégation d'office comme une réponse vide (inchangé) |

## Critères d'acceptation
- [ ] « Est-ce que le client Cagibi est connecté ? » + univers CAGIP ⇒ `compris: Est-ce que le client CAGIP est connecté ?`
      affiché, jamais dit ; trace USER = texte relu.
- [ ] « Regarde le ticket » relu en « Envoie le ticket » ⇒ refusé (verbe d'action ajouté).
- [ ] Délégation ouverte par l'écran avec le texte relu.
- [ ] Consigne v4 identique d'un appel à l'autre (préfixe stable).

## Mesure (banc `tools/jarvis-stt-bench/relecture.mjs`, 2026-11-02, 72 énoncés propre + dégradé, Sonnet 5.5)
| Transcription | WER avant | WER après relecture | Relectures refusées par la garde |
|---|---|---|---|
| Scribe v2 batch + vocabulaire (SF-239-04, chemin retenu) | 0,9 % | **0,8 %** | 1 |
| Scribe v2 batch sans vocabulaire | 6,6 % | **3,1 %** | 2 |
Premier passage : la relecture retouchait la grammaire (« Allons » ⇒ « Allez ») et dégradait les bonnes transcriptions
(0,9 % ⇒ 1,1 %) ; la consigne a été resserrée (« ne touche ni à la grammaire, ni au style, ni à la personne du
verbe »). Coût : ≈ 15-25 jetons de sortie de plus par échange (≈ 0,2-0,3 s avant la première phrase, ≈ 0,0003 $).

## Hors périmètre
Passe dédiée sans cerveau rapide ; banc « sens compris » (SF-239-07, juge).

## Technique
`JarvisFastBrainPrompt` (v4), `JarvisUnderstanding` (garde), `JarvisFastBrainService` (`SentenceGate` lit la ligne,
`Outcome.understood`), `JarvisFastBrainController` (événement `understood`), `JarvisFastExchangeWriter` (texte relu),
front `jarvis-fast-brain.ts` (`onUnderstood`), `jarvis.component.ts` (affichage, délégation). Aucune table.

## Plan de test
Unitaires (garde, lecture de la ligne, flux), service (relecture acceptée / refusée, jamais dite, trace), front
(affichage, délégation avec le texte relu).
