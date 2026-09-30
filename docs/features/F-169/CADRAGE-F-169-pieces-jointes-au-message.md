# Cadrage F-169 — Pièces jointes attachées au message

> Cadrage produit le 2026-09-30. Source de vérité produit : `docs/PROJECT.md`.
> Feature référencée dans `docs/PRODUCT_SPEC.md`.

## Problème observé

Quand le PO ajoute des fichiers au terminal de l'Atelier (glisser / coller / trombone), les
**« notices » de dépôt** (F-115 / SF-115-02) sont rendues **HORS de la zone défilante**
(`.terminal-scrollback`) et **JAMAIS purgées** :

- elles s'empilent dans une **bande fixe au-dessus de la saisie** ;
- elles **ne défilent pas** et **masquent le flux** au fil des dépôts ;
- l'état est **flou** : « on dirait que c'est parti mais rien » — rien ne dit que les fichiers
  **partiront avec la prochaine demande**, ni qu'on peut en retirer un.

Le dépôt backend est déjà **« chemin uniquement »** : le fichier est déposé dans le workspace et
l'agent le lit par `read_file`. **Ce comportement backend n'est pas en cause** et n'est pas changé
par cette feature.

## Objectif produit

Rendre les fichiers ajoutés **lisibles, gérables et non intrusifs** : ils s'affichent comme des
**puces compactes DANS le composer**, purgées à l'envoi, avec un libellé clair (« joint au
message »). Puis, dans un second temps, **relier explicitement** les fichiers au message envoyé et
les **montrer dans la bulle** du message.

Gateway-First / Provider-First respectés : aucune capacité IA réimplémentée. Isolation `user_id`
inchangée (le dépôt existant porte déjà le contexte workspace).

## Découpage (3 subfeatures)

| SF | Titre | Portée | Périmètre technique |
|----|-------|--------|---------------------|
| **SF-169-01** | Puces de pièces jointes dans le composer (frontend seul) | Sortir les dépôts de la bande fixe hors-scrollback → **puces compactes, supprimables, DANS le composer**, **purgées à l'envoi**, libellé « joint au message ». Supprime l'ancien rendu empilé jamais purgé. | Frontend uniquement : `atelier-terminal.component.*`, `atelier.component.ts`. **Aucun** changement backend, modèle de message ou table. |
| **SF-169-02** | Lien fichier ↔ message (backend + contrat d'envoi) | Attacher au message envoyé la liste des chemins de fichiers joints (`message_id` / association), pour que la relation soit persistée et non plus implicite. | Backend : migration Liquibase (`message_id` ou table de liaison), contrat d'envoi, service, isolation `user_id`. |
| **SF-169-03** | Rendu des pièces jointes dans la bulle du message | Afficher, dans la bulle de la demande, les fichiers effectivement envoyés avec elle (nom, taille), en lecture. | Frontend : rendu du fil (`terminal-prompt-line` / bulle de demande). |

**SF-169-01 est frontend seul et n'anticipe aucun des deux autres** : elle ne crée pas de
`message_id`, ne change pas le contrat d'envoi, ne persiste aucun lien, ne rend rien dans la bulle.

## Décision par défaut (flaguée)

- **Terminaux en lecture seule (mosaïque)** : ils **ne reçoivent pas** `depositNotices`
  (`mosaique.component.html` ne le lie pas) et n'ont **pas de composer**. Déplacer les puces dans le
  composer ne retire donc **aucune fonctionnalité existante** de la mosaïque. Un terminal en lecture
  seule ne peut pas composer / envoyer de message : une puce « joint au message » n'y a pas de sens.
  **Décision** : les puces vivent dans le composer (`!readOnly`) ; pas de bande de dépôt autonome en
  lecture seule.

## Hors périmètre de la feature (rappel)

- V3 (F-17 / F-18) et multi-LLM runtime : hors scope (ADR-011).
- Aucune réimplémentation d'analyse de fichier (Provider-First : l'agent lit par `read_file`).
