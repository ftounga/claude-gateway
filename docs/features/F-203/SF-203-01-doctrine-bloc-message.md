# Mini-spec — F-203 / SF-203-01 Doctrine stable « bloc message » (Atelier)

## Identifiant
`F-203 / SF-203-01`

## Feature parente
`F-203` — Mails et messages Teams : un encart « Copier » qui garde la mise en forme (cadrage `CADRAGE-F-203-messages-a-copier.md`)

## Statut
`in-progress` — 2026-10-16

## Branche Git
`feat/SF-203-01-doctrine-bloc-message`

---

## Objectif
L'agent de l'Atelier rend tout mail ou message à envoyer dans un bloc balisé ```` ```mail ```` / ```` ```message ```` que le terminal pourra présenter dans un encart copiable.

## Comportement attendu

### Cas nominal
- Nouvelle doctrine `MESSAGE_BLOCK_DOCTRINE` (littéral **stable**, constante Java) ajoutée au préfixe système, juste après `NEXT_STEPS_DOCTRINE` :
  - tout mail, message Teams/Slack, commentaire Jira ou réponse à envoyer **tel quel** par l'utilisateur est rendu dans un bloc ```` ```mail ```` (courriel) ou ```` ```message ```` (messagerie, ticket) ;
  - **un bloc par message** ; l'objet facultatif en **première ligne** `Objet : …` (mail) ;
  - corps en Markdown simple (gras, italique, listes, liens) — pas de titres `#`, pas de tableau, pas de bloc de code imbriqué ;
  - l'explication reste **hors** du bloc ; le bloc ne contient que ce qui part ;
  - ne pas utiliser ce bloc pour un texte qui n'est pas à envoyer.
- Injection sur toutes les cibles (bac à sable, poste, sujet) **sauf** le terminal Teams (sa réponse est relayée dans Teams, où une balise s'afficherait brute) — condition stable par workspace : cache de prompt (F-134) préservé.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Terminal Teams | doctrine absente (préfixe inchangé pour ce terminal) |
| Deux constructions successives du prompt | bloc de doctrine identique à l'octet (aucun élément volatil) |

## Contraintes de validation
- Aucune donnée saisie ; littéral constant, < 1 500 caractères (préfixe sous `SYSTEM_MAX_CHARS`).

## Critères d'acceptation
- [ ] La doctrine est présente **une seule fois** sur un projet bac à sable et sur un projet poste.
- [ ] Elle contient les balises ```` ```mail ```` et ```` ```message ````, « un bloc par message », la ligne `Objet :`.
- [ ] Elle est placée après « Propose la suite ».
- [ ] Elle est absente sur un terminal Teams.
- [ ] Le bloc est stable à l'octet entre deux constructions et égal à la constante.

## Plan de test
- Unitaires `AtelierChatServiceSystemPromptTest` : présence/unicité (sandbox + runner), placement, absence Teams, stabilité octet.
- Intégration : sans objet (aucun endpoint) — la suite backend complète (`mvn -pl backend test`) garantit le démarrage du contexte.
- Isolation utilisateur : sans objet (aucun accès données).

## Impacts
- Composant : `AtelierChatService` (constante + `append` dans la construction du système).
- Tables / endpoints : aucun.

## Préoccupations transversales
- Aucune (auth, tenant, limites, routage non touchés).

## Hors périmètre
- Le rendu en encart dans le terminal (SF-203-02).
- La réécriture des anciens messages (pas de rétro-découpage).
- L'envoi effectif du message (l'utilisateur copie et colle).
