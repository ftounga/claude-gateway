# Mini-spec — F-239 / SF-239-08 — Converser comme une personne

## Identifiant
`F-239 / SF-239-08` · Feature parente `F-239` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-239-08-converser`

## Objectif
> « Je dois avoir l'impression de parler avec une vraie personne » : salutation rendue, au plus une petite question
> en retour, transitions naturelles, réponses courtes, et « Pardon, Monsieur, je n'ai pas saisi la fin » au lieu de
> citer une transcription tronquée.

## Comportement attendu
### Cas nominal
- **Consigne du cerveau rapide v5** (fixe, versionnée ; personnalité D18 F-235 inchangée) : section « Converser comme
  une personne » — salutation rendue, une courte question bienveillante seulement à la **première salutation du
  jour** ; au plus une question par réponse, aucune question de politesse si une a déjà été posée ; « commençons » ⇒
  « Très bien, Monsieur, par quoi souhaitez-vous commencer ? » (ou enchaîne sur le sujet nommé) ; petite
  conversation sobre ; remerciement / au revoir brefs ; « Tu es là ? » ⇒ « Toujours, Monsieur. » ; phrase coupée ou
  incohérente ⇒ demander de répéter, **jamais** citer la transcription ; après une coupure, enchaîner sur la nouvelle
  parole ; « fais-moi le point » ⇒ répondu depuis le contexte ; longues énumérations résumées ; formules interdites
  étendues (« Je reste prêt », « Que puis-je faire pour vous ? »).
- **État de la conversation du jour, dans le message** (volatil, jamais dans la consigne) :
  `## Conversation du jour` — `Première salutation du jour : oui|non`, `Question de politesse déjà posée : oui|non`
  (une des trois dernières réponses de la mémoire de session contient une question).
- Garde d'invitation étendue (« Je reste prêt… », « Que puis-je faire pour vous »).

## Critères d'acceptation
- [ ] Pré-banc (`tools/jarvis-stt-bench/conversation.mjs`, jeu SF-239-07, Sonnet 5.5) ≥ 90 % de scénarios réussis,
      contre 82,4 % avec la consigne v4.
- [ ] État du jour présent dans le dernier message, absent de la consigne ; consigne identique d'un appel à l'autre.

## Mesure (pré-banc `tools/jarvis-stt-bench/conversation.mjs`, 34 scénarios / 43 tours, Sonnet 5.5, juge Opus 5.5)
| Consigne | Scénarios réussis | Délégations | Échecs |
|---|---|---|---|
| v4 (actuelle) | 28/34 — 82,4 % | 7/43 | invitation « je reste prêt », point délégué, transcription citée, trop long |
| v5, 1er jet | 27/34 — 79,4 % | 5/43 | phrase de transition d'exemple répétée mot pour mot (robotique), point trop long, décision non déléguée |
| **v5 réglée** | **32/34 — 94,1 %** | 6/43 | point en 4 phrases ; un fait jugé non ancré (contestable) |
Exemples v5 : « Bonjour Jarvis. » ⇒ « Bonjour, Monsieur. La semaine commence du bon pied ? » (5/5) ; « Commençons. » ⇒
« Très bien, Monsieur — je suggère de partir sur la recette du portail d'Orion… » (5/5) ; « Allons chez le client et
donnez- » ⇒ « Pardon, Monsieur, je n'ai pas saisi la fin. » (5/5). Le pré-banc n'applique pas les garde-fous de la
passerelle ; la porte qui fait foi est le banc `CONVERSATION` après déploiement.

## Hors périmètre
Mémoire du jour au-delà de 30 min / redémarrage (SF-239-09).

## Technique
`JarvisFastBrainPrompt` (v5), `JarvisFastContextBuilder.dayState`, `JarvisFastBrainService` (état du jour),
`JarvisFastGuards.INVITATIONS`, `JarvisConversationBench` (même état). Aucune table.

## Plan de test
Service : état du jour (première salutation, question déjà posée) dans le dernier message seulement ; garde
d'invitation étendue ; pré-banc mesuré (rapport dans la PR).
