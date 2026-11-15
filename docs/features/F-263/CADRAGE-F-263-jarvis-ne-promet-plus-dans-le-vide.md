# Cadrage F-263 — Jarvis ne promet plus dans le vide

**Origine** : même audit que F-262 (séance du PO du 2026-11-15, 09:29-09:40, prod `staging-5c16b4d7`).

## Constat (causes prouvées)

1. **(2) « Je cherche les schémas de data-platform » puis rien** : 2e appel de la boucle du cerveau rapide = deux outils sans texte, aucun ordre d'écran émis ; la boucle ne relance que sur une lecture nouvelle ou quand rien n'a été dit (`freshRead || needPhrase`) → le tour finit sur l'annonce.
2. **(3b) « Bascule vers CAGIP sur data-platform »** : `subjectSwitch` (écran) exige que tout ce qui suit la préposition soit le nom d'un sujet ; « CAGIP sur data-platform » ne l'est pas → la phrase part au cerveau, qui dit « je bascule » sans le pouvoir ; le sujet de l'écran reste « Terminal du poste » (journal d'écoute : 38 tours, `subject=8210f678…`) → deux galeries `NOT_FOUND`.
3. **(3b) « Voici » avant l'écran** : le refus de l'écran (`show_refused`) ne dit qu'une phrase neutre (« Je ne la trouve pas. ») ou rien (`NOTHING`).
4. **(3c) Photos d'agenor** : pièces sur le poste CAGIP éteint (`HOST_OFFLINE`), Jarvis dit « Voilà ».

## Décision

- **Cerveau** : un tour qui finit sur des outils sans texte ni rien montré relance **une** fois (les refus sont dans les résultats d'outil) ; sinon une phrase honnête (« Je n'ai rien pu vous afficher »). Chaque refus d'affichage est journalisé (action, motif).
- **Écran** : « bascule / passe / va / change sur|vers|dans … » change de sujet quand **un seul** nom de sujet figure, mot pour mot, dans la suite de la phrase (« CAGIP sur data-platform » → data-platform) ; jamais au hasard.
- **Écran** : un refus d'affichage après une réponse dite produit une **correction** (« Pardon, je ne les trouve pas dans data-platform. ») ; une galerie dont toutes les pièces sont sur un poste éteint le dit (« Elles sont sur le poste CAGIP, éteint : je ne peux pas les ouvrir. »).
- Aucune consigne modifiée.

## Découpage

- **SF-263-01** — relance d'une annonce sans écran, aveu d'échec, refus journalisés (≈ 0,5 j). Backend.
- **SF-263-02** — bascule de sujet, correction d'un refus, pièces sur poste éteint (≈ 1 j). Frontend.

## Hors périmètre

- Un ordre « changer de sujet » émis par le cerveau (nouvel outil/consigne) : l'écran suffit pour les formes mesurées.
- Retenir le « Voici » jusqu'à la réponse de l'écran (changerait la voix au premier fragment).
