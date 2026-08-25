# Mini-spec — [F-33 / SF-03] Invite d'autorisation dans la vue terminal (frontend)

---

## Identifiant

`F-33 / SF-03`

## Feature parente

`F-33` — Validation d'action avant exécution

## Statut

`in progress` — 2026-08-25

## Date de création

2026-08-25

## Branche Git

`feat/SF-33-03-invite-autorisation`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Afficher, dans le flux du terminal, la commande que l'agent veut lancer, avec **Autoriser** /
**Refuser** (motif facultatif) — et donner à l'utilisateur le bouton qui **active** cette validation
pour le projet.

---

## Contexte

SF-33-01 pose l'option et la politique, SF-33-02 relaie la demande dans le flux SSE et expose
l'endpoint de réponse. Sans écran, la fonctionnalité existe mais reste inatteignable : personne ne peut
l'activer, et une demande relayée n'aurait aucun interlocuteur — toutes les commandes finiraient
refusées par expiration du délai.

**Contrats importés tels quels** de `SF-33-01-option-validation-bash.md` (`PUT /agent/confirmation`,
`askBeforeBash` sur le détail du projet) et `SF-33-02-demande-confirmation-relais.md`
(`POST /agent/confirm`, événements SSE `confirm_request` / `confirm_resolved`).

---

## Comportement attendu

### Cas nominal

1. Dans la barre du terminal, un bouton **Valider les commandes** montre l'état courant du projet
   (`askBeforeBash`) et le bascule.
2. Bascule pendant qu'une sandbox est ouverte (`appliesToCurrentSession: false`) : un message dit que
   le réglage prendra effet à la prochaine sandbox, et rappelle « Réinitialiser ».
3. Pendant un run, l'événement `confirm_request` affiche l'invite **en bas du flux**, à l'endroit où
   défile la sortie : la commande en monospace, et deux actions.
4. **Autoriser** → `POST /agent/confirm` `allow` → l'invite passe en attente puis disparaît sur
   `confirm_resolved` ; l'exécution reprend, la sortie s'affiche comme d'habitude.
5. **Refuser** → un champ de motif (facultatif) puis `deny` → l'agent reçoit le motif et poursuit
   autrement.
6. `confirm_resolved` avec `timeout` retire l'invite et signale que la commande a été **refusée faute
   de réponse** — sans quoi l'utilisateur croirait sa décision encore attendue.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `409 no_active_session` sur une réponse | Message « L'exécution n'attend plus de réponse. » et invite retirée |
| `502 provider_error` (demande déjà tranchée / fournisseur) | Message « Votre réponse n'a pas pu être transmise. » ; l'invite est retirée (elle n'est plus à trancher) |
| `404` sur la bascule ou la réponse | « Projet introuvable. » |
| Toute autre erreur | Message générique, l'écran reste utilisable |
| Fin du run (`done` / `error`) avec une invite encore affichée | L'invite est retirée : plus rien ne l'attend |
| Deux demandes successives dans le même run | La seconde remplace la première une fois celle-ci tranchée (une invite à la fois) |

---

## Critères d'acceptation

- [ ] Le bouton de bascule reflète `askBeforeBash` du projet et appelle `PUT /agent/confirmation`
- [ ] Une bascule non applicable à la sandbox en cours est **dite** (message explicite)
- [ ] `confirm_request` affiche la commande et les deux actions ; `confirm_resolved` retire l'invite
- [ ] **Autoriser** envoie `allow` ; **Refuser** envoie `deny` avec le motif saisi (ou sans)
- [ ] Le bouton reste inerte pendant l'envoi de la réponse (pas de double envoi)
- [ ] Une résolution `timeout` retire l'invite **et** le dit
- [ ] `done` / `error` retirent toute invite en attente
- [ ] Design system : palette et police de la vue terminal, `MatSnackBar` pour les messages, aucun
      `window.confirm` / `alert` / `prompt`, espacements multiples de 4 px
- [ ] Un projet sans l'option se comporte **exactement comme avant** (aucune invite, aucun appel)
- [ ] `npm run build` et `npm test` verts

---

## Périmètre

### Hors scope

- Mémorisation d'un choix (« toujours autoriser ce type de commande »)
- Reprise d'une invite après rechargement de page (la demande expire alors en refus — SF-33-02)
- Réglage du délai depuis l'écran (configuration serveur)
- Affichage de l'option ailleurs que dans la vue terminal (pas d'écran de réglages projet)

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Motif de refus | facultatif, **500** caractères max (borne du backend), champ libre |
| Invite simultanée | **une seule** à l'écran ; une nouvelle demande remplace une invite déjà tranchée |
| Envoi en cours | actions désactivées jusqu'à la réponse HTTP (pas de double décision) |
| Couleurs / polices | `DESIGN_SYSTEM.md` uniquement (variables `--cg-*`, monospace du terminal) |
| Messages | `MatSnackBar` (aucune boîte native) |

---

## Technique

### Contrat API (importé — figé par SF-33-01 et SF-33-02)

| Méthode | Chemin | Corps | Réponse |
|---------|--------|-------|---------|
| `PUT` | `/api/workspaces/{id}/agent/confirmation` | `{"enabled": true}` | `200 {enabled, appliesToCurrentSession}` |
| `POST` | `/api/workspaces/{id}/agent/confirm` | `{"toolUseId","decision":"allow\|deny","reason?"}` | `204` |

Flux SSE `POST /api/workspaces/{id}/agent/stream` :

```
event:confirm_request   data:{"toolUseId":"sevt_1","tool":"bash","detail":"rm -rf build"}
event:confirm_resolved  data:{"toolUseId":"sevt_1","decision":"allow|deny|timeout"}
```

`GET /api/workspaces/{id}` porte `askBeforeBash` (booléen).

### Tables impactées / Migration

**Aucune** (frontend seul).

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `core/models/atelier.models.ts` | `askBeforeBash`, types de confirmation, 2 handlers de flux |
| `core/services/atelier.service.ts` | `setAskBeforeBash`, `confirmToolUse`, routage des 2 événements |
| `atelier/atelier.types.ts` | `AtelierPendingConfirmation` (invite courante) |
| `atelier/atelier.component.ts` | État de l'invite, décisions, bascule de l'option, nettoyage sur fin de run |
| `atelier/terminal/atelier-terminal.component.ts/.html/.scss` | Invite + bouton de bascule |
| `atelier/terminal/atelier-terminal.component.spec.ts` | Tests du rendu de l'invite |
| `atelier/atelier.component.spec.ts` | Tests de l'orchestration |
| `core/services/atelier.service.spec.ts` | Tests du service (mock HTTP) |

---

## Plan de test

### Tests unitaires (frontend, backend mocké)

- [ ] Service : `setAskBeforeBash` appelle `PUT` avec `{enabled}`
- [ ] Service : `confirmToolUse` appelle `POST` avec `toolUseId` / `decision` / `reason`
- [ ] Service : un événement `confirm_request` route vers `onConfirmRequest` avec ses champs
- [ ] Service : un événement `confirm_resolved` route vers `onConfirmResolved`
- [ ] Composant : `confirm_request` pose l'invite, `confirm_resolved` la retire
- [ ] Composant : **Autoriser** envoie `allow` ; **Refuser** envoie `deny` + motif
- [ ] Composant : `409` / `502` retirent l'invite avec un message lisible
- [ ] Composant : `done` retire une invite restée en attente
- [ ] Composant : la bascule appelle le service et signale « prend effet à la prochaine sandbox »
- [ ] Terminal : l'invite affiche la commande et les deux actions ; absente sans demande

### Tests d'intégration

- [ ] `npm run build` (compilation stricte Angular) vert
- [ ] `npm test` vert

### Isolation utilisateur

- [x] **Applicable** — aucune ressource n'est adressée sans l'identifiant du projet ; le backend
  applique `requireOwned` sur les deux endpoints. Le frontend n'envoie **jamais** d'identifiant
  d'utilisateur : le JWT porte l'identité (`authInterceptor`).

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement d'authentification ; les appels passent par l'`authInterceptor` existant. |
| Contexte tenant | **Non** | Aucun identifiant d'utilisateur manipulé côté écran ; le tenant est résolu côté backend. |
| Plans / limites | **Non** | Aucun quota affiché ni consommé par la réponse à une demande. |
| Navigation / routing | **Non** | Aucune route ajoutée ni guard modifié : l'invite vit dans la vue terminal déjà routée (`/atelier`). |

---

## Dépendances

- **F-33 SF-33-01** (option + `askBeforeBash`) — mergée.
- **F-33 SF-33-02** (événements SSE + endpoint de réponse) — mergée.

---

## Notes et décisions

- **L'invite vit dans le flux, pas dans une boîte de dialogue** : elle apparaît là où l'utilisateur
  regarde déjà défiler la sortie, et le contexte (les commandes précédentes) reste lisible derrière.
  Une modale masquerait précisément ce qui permet de juger.
- **Le motif est facultatif** : exiger une justification pour refuser ferait hésiter, alors que le
  refus doit être le geste le plus facile.
- **Une invite à la fois** : l'API peut poser plusieurs demandes en parallèle, mais les traiter à
  l'écran en même temps rendrait l'arbitrage confus. La suivante prend la place une fois la précédente
  tranchée — et si elle expire, le refus automatique du backend fait son office.
- **Le bouton de bascule vit dans la barre du terminal** (arbitrage) : c'est le seul écran où l'agent
  exécute, donc le seul endroit où ce réglage a du sens. Créer un écran de réglages projet pour une
  seule option coûterait plus qu'il ne rapporte. **Réversible** : déplacer le bouton ne changerait ni
  le contrat ni le stockage.
