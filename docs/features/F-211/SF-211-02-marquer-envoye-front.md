# Mini-spec — [F-211 / SF-211-02] Message envoyé d'un clic — écran

## Identifiant
SF-211-02

## Feature parente
F-211 — Message envoyé d'un clic (cadrage : `CADRAGE-F-211-message-envoye-d-un-clic.md`)

## Statut
En cours

## Date de création
2026-10-24

## Branche Git
`feat/SF-211-02-marquer-envoye-front`

---

## Objectif
Sur chaque encart mail/message d'une réponse d'agent du terminal de l'Atelier (la Forge), un bouton « Marquer envoyé » qui note l'envoi d'un clic, affiche « ✓ Envoyé · 24/10 17:42 » avec « Annuler », et retrouve cet état au rechargement.

## Comportement attendu

### Cas nominal
1. Chaque encart ```` ```mail ```` / ```` ```message ```` d'une réponse **persistée** (identifiant de message réel) porte, à côté de « Copier », un bouton « Marquer envoyé ».
2. Au clic : `POST /api/workspaces/{id}/messages/{messageId}/sends/{index}` (index = rang du bloc mail/message dans **tout** le message, calculé par le parseur partagé, même si l'encart est rendu dans « L'essentiel », « Le détail » ou une carte par question) ; le bouton est désactivé pendant l'appel ; au retour l'encart affiche « ✓ Envoyé · jj/MM HH:mm » et un lien « Annuler ».
3. « Annuler » : `DELETE …` ; l'encart revient à « Marquer envoyé ».
4. Si l'envoi a fait bouger une attente (réponse avec `attenteKey`), le compteur d'attentes du terminal est relu.
5. À l'ouverture du terminal (changement de `projectId`), `GET /api/workspaces/{id}/messages/sends` recharge les états : un rechargement de page retrouve « ✓ Envoyé ».
6. Parseur : seul le **premier jeton** de la balise donne le type (```` ```mail attente=x ```` reste un mail, la balise ne s'affiche jamais) ; `attente=` est lu dans `CopyBlock.attente`. En tête du bloc, « À : … » est présenté à part (ligne « À ») comme l'objet, et n'est pas copié avec le corps.
7. Aucun tour n'est déclenché, aucun message n'est ajouté au fil.

### Cas d'erreur
- Échec réseau / 4xx au marquage ou à l'annulation : message court (snackbar) « Impossible de marquer ce message comme envoyé. » / « Impossible d'annuler. », état inchangé.
- Flux en direct (pas encore d'identifiant) ou message d'identifiant non persistant : pas de bouton.
- Terminal en lecture seule : l'état « ✓ Envoyé » s'affiche, sans bouton ni « Annuler ».
- Encart introuvable dans le message complet (texte transformé) : pas de bouton.

## Critères d'acceptation
- [ ] CA1 : un encart d'une réponse persistée montre « Marquer envoyé » ; le clic appelle POST avec le bon index et affiche « ✓ Envoyé · … » + « Annuler ».
- [ ] CA2 : « Annuler » appelle DELETE et rétablit le bouton.
- [ ] CA3 : l'état revient au chargement du terminal (GET).
- [ ] CA4 : le 2e encart d'un message envoie l'index 1, y compris rendu dans « Le détail ».
- [ ] CA5 : ```` ```mail attente=x ```` est un encart E-mail, sans balise visible ; « À : Zahi » présenté à part, absent du corps copié.
- [ ] CA6 : pas de bouton dans le flux en direct ni en lecture seule.
- [ ] CA7 : charte respectée (jetons `--cg-*` existants seulement, `--cg-success` pour « Envoyé »).

## Périmètre

### Hors scope (explicite)
- Le chat classique (`chat/`, hors Atelier) : pas de terminal ni d'attentes, l'encart y reste copiable seulement.
- Backend (SF-211-01, livré).

## Valeurs initiales
Aucune.

## Contraintes de validation
Aucune saisie : l'index est calculé, jamais saisi.

## Technique

### Endpoint(s)
Consommés : `GET /api/workspaces/{id}/messages/sends`, `POST|DELETE /api/workspaces/{id}/messages/{messageId}/sends/{index}`.

### Tables impactées
Aucune.

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
- `shared/message-segments.ts` (+ `copy-block.model.ts`) : premier jeton, `attente`, `splitHeaders`, `messageBlockIndex`.
- `chat/copy-block` : entrées `sendable`, `sentAt`, `sendBusy`, `sendReadOnly` ; sorties `markSent`, `cancelSent` ; ligne « À ».
- `core/services/message-sends.service.ts` (nouveau) + modèle.
- `atelier/terminal/atelier-terminal.component.*` : contexte `message` du gabarit `#agentText`, état des envois, chargement, gestes.

## Plan de test

### Tests unitaires
- `message-segments.spec.ts` : balise avec attributs, `attente`, `splitHeaders` (deux ordres), `messageBlockIndex`.
- `copy-block.component.spec.ts` : bouton, état envoyé, Annuler, lecture seule, ligne « À ».
- `message-sends.service.spec.ts` : URLs et verbes.
- `terminal-message-sent.spec.ts` : POST au clic avec l'index, état, DELETE, GET au chargement, index 1 dans le détail, pas de bouton en direct / lecture seule.

### Tests d'intégration
Côté backend (SF-211-01). Ici : `ng test` complet + `npm run build`.

### Isolation workspace
Garantie serveur (SF-211-01) ; l'écran n'envoie que l'identifiant du terminal ouvert.

## Préoccupations transversales
Aucune (pas d'auth, de tenant, de plan, ni de route Angular modifiés).

## Dépendances

### Subfeatures bloquantes
SF-211-01 (mergée, PR #1249).

### Questions ouvertes impactées
Aucune.

## Notes et décisions
- L'état des envois est tenu par le terminal (signal), rechargé à chaque changement de terminal ; pas de champ ajouté à l'historique.
