# Mini-spec — F-39 / SF-39-04 — Reprendre le fil, ou repartir à neuf

## Identifiant

`F-39 / SF-39-04`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-06

## Branches Git

- `feat/SF-39-04-reprise-de-fil` — backend (livrée en premier)
- `feat/SF-39-04-front-reprise-de-fil` — frontend (livrée après le backend)

---

## Objectif

> À la réouverture d'un projet, le fil reprend **sans rien demander** ; l'utilisateur n'est
> sollicité que lorsque la reprise ne va pas de soi, et un « nouveau départ » reste accessible à
> tout moment.

---

## Déclencheur

Décision **D5** du cadrage F-39. Depuis SF-39-03, la mémoire du travail — la trajectoire d'outils —
vit **chez nous**, sur `atelier_messages`, et non plus dans la survie d'une sandbox chez le
fournisseur. La reprise cesse donc d'être un effet de bord de l'infrastructure : elle devient une
décision produit.

Deux manques restaient :

1. **On ne repart jamais à neuf.** L'historique est rejoué en entier, indéfiniment. Après une
   semaine sur un sujet clos, chaque nouveau message traîne des tours qui n'ont plus rien à dire —
   et ce, au tarif d'entrée du fournisseur.
2. **On ne dit jamais rien.** Rouvrir un projet touché il y a deux mois reprend le fil en silence,
   sans que l'utilisateur sache que l'agent a encore en tête ce qu'il faisait à l'époque.

---

## Comportement attendu

### Cas nominal — la reprise silencieuse

Rien ne change à l'écran. Le fil reprend, la trajectoire des cinq derniers tours est rejouée
(SF-39-03), aucune question n'est posée. C'est le cas par défaut, et il doit le rester.

### Quand la reprise ne va pas de soi

Un projet dont le dernier message date de plus de **14 jours** est *inactif*. À sa réouverture,
l'écran propose un choix explicite, **sans rien décider à la place de l'utilisateur** :

- **Reprendre le fil** — le comportement d'avant ; la question ne revient pas tant que le projet
  reste actif ;
- **Repartir à neuf** — les tours passés cessent d'être rejoués.

### « Repartir à neuf » ne détruit rien

Le nouveau départ **déplace une frontière de rejeu**, il n'efface aucun message : la conversation
reste lisible à l'écran, et seul ce qui repart chez le fournisseur change. C'est le choix
réversible ; une suppression ne le serait pas.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Projet d'un autre utilisateur | Aucune information, aucune action | 404 |
| Projet sans aucun message | Reprise sans question ; nouveau départ accepté et sans effet | 200 |
| Nouveau départ demandé deux fois | Idempotent : la frontière est simplement redéplacée | 200 |
| Frontière postérieure à tous les messages | Le tour suivant part sur un historique vide, jamais en erreur | 200 |

---

## Critères d'acceptation

### Backend

- [ ] `GET /workspaces/{id}/chat/resume` renvoie l'état de reprise : nombre de tours rejouables,
      date du dernier message, et si un choix doit être proposé.
- [ ] Un projet actif (< 14 jours) ne demande **rien** (`prompt = NONE`).
- [ ] Un projet inactif (> 14 jours) demande un choix (`prompt = IDLE`).
- [ ] Un projet sans message ne demande rien et annonce zéro tour.
- [ ] `POST /workspaces/{id}/chat/restart` pose la frontière de rejeu à l'instant courant.
- [ ] Après un nouveau départ, le tour suivant ne rejoue **aucun** message antérieur.
- [ ] Après un nouveau départ, `GET /workspaces/{id}/chat` renvoie **toujours** tous les messages :
      rien n'est détruit.
- [ ] Les deux endpoints répondent 404 sur un projet qu'on ne possède pas (isolation `user_id`).

### Frontend

- [ ] À l'ouverture d'un projet inactif, une bannière propose les deux choix, dans la charte.
- [ ] « Reprendre le fil » ferme la bannière et ne change rien d'autre.
- [ ] « Repartir à neuf » appelle l'endpoint, ferme la bannière et signale le nouveau départ.
- [ ] Un projet actif n'affiche jamais la bannière.
- [ ] Un « Nouveau départ » reste accessible depuis la barre d'outils quand le fil a des messages.

---

## Périmètre

### Hors scope (explicite)

- La reprise de session **Managed Agents** (sandbox du fournisseur) : elle a sa propre vie
  (`AtelierSessionService`), et c'est précisément ce dont D5 libère la boucle maison.
- La suppression d'une conversation : ce serait irréversible, ce n'est pas ce que D5 demande.
- La compaction automatique d'un historique trop long — lot 6 (SF-39-11).
- L'écran unique et la fusion des modes — lot 4.

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs | Normalisation |
|-------|-------------|---------|---------------|
| Seuil d'inactivité | — | **14 jours** (constante) | — |
| `prompt` | Oui | `NONE` \| `IDLE` | — |
| `chat_thread_started_at` | Non | horodatage, nullable | `null` = tout l'historique est rejoué |

---

## Technique

### Endpoint(s)

| Méthode | Chemin | Rôle |
|---|---|---|
| `GET` | `/workspaces/{id}/chat/resume` | État de reprise du fil |
| `POST` | `/workspaces/{id}/chat/restart` | Nouveau départ : pose la frontière de rejeu |

`AtelierResumeResponse { turns: int, lastMessageAt: OffsetDateTime|null, threadStartedAt:
OffsetDateTime|null, prompt: "NONE"|"IDLE" }`.

### Tables impactées

| Table | Changement |
|---|---|
| `workspaces` | Nouvelle colonne `chat_thread_started_at` (horodatage, nullable) |

### Migration Liquibase

- [x] `051-workspaces-chat-thread.xml` — `addColumn` nullable, réversible (`dropColumn`).

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `atelier/AtelierThreadService` | **Nouveau** — état de reprise, nouveau départ ; isolation par `requireOwned` |
| `atelier/dto/AtelierResumeResponse` | **Nouveau** |
| `atelier/Workspace` | Champ `chatThreadStartedAt` |
| `atelier/AtelierMessageRepository` | Lecture bornée par la frontière |
| `atelier/AtelierChatService` | L'historique rejoué démarre à la frontière |
| `atelier/AtelierChatController` | Les deux endpoints |

### Composants Angular

| Composant | Changement |
|---|---|
| `core/services/atelier.service.ts` | `getResume`, `restartThread` |
| `core/models/atelier.models.ts` | `AtelierResume` |
| `atelier/atelier.component.*` | Bannière de choix + action « Nouveau départ » |

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés et vérification |
|--------------|-----------|-----------------------------------|
| Auth / Principal | Non | Les deux routes passent par le même `CurrentUser` que les autres routes d'atelier |
| **Contexte tenant** | **Oui** | Nouveaux endpoints ⇒ nouveaux accès. Composants revus : `AtelierThreadService` (passe par `WorkspaceService.requireOwned(userId, id)`, donc 404 sur un projet non possédé), `AtelierMessageRepository` (nouvelle méthode filtrée `workspaceId` **et** `userId`), `AtelierChatService.runLoop` (même filtre, plus la frontière), `AtelierChatController` (deux routes, `CurrentUser` obligatoire). Un test d'intégration prouve le 404 croisé sur chacune. |
| **Plans / limites** | **Oui** | Un nouveau départ **réduit** les tokens d'entrée d'un tour, donc le quota décompté (F-10) et le budget de session (F-36) : aucun gate contourné, aucun quota crédité. `QuotaService` n'est pas touché. |
| **Navigation / routing** | **Oui** | Aucune route Angular ajoutée ni modifiée : la bannière vit dans l'écran Atelier existant (`/atelier`), sous le même garde d'accès Gold. Chemins vérifiés : entrée depuis la liste de projets, retour depuis l'explorateur (SF-30-10), rechargement direct de l'URL. |

---

## Plan de test

### Tests unitaires

- [ ] `AtelierThreadServiceTest` — projet actif ⇒ `prompt = NONE`, tours comptés.
- [ ] `AtelierThreadServiceTest` — dernier message vieux de 15 jours ⇒ `prompt = IDLE`.
- [ ] `AtelierThreadServiceTest` — projet sans message ⇒ `NONE`, zéro tour.
- [ ] `AtelierThreadServiceTest` — le nouveau départ pose la frontière et est idempotent.
- [ ] `AtelierThreadServiceTest` — projet non possédé ⇒ exception d'accès, aucune écriture.
- [ ] `AtelierChatServiceMemoryTest` — l'historique rejoué démarre à la frontière.

### Tests d'intégration

- [ ] `AtelierChatApiIntegrationTest` — `GET .../chat/resume` sur projet actif et projet inactif.
- [ ] `AtelierChatApiIntegrationTest` — `POST .../chat/restart` puis un message : l'historique
      exposé garde tout, le fournisseur ne reçoit que le nouveau message.
- [ ] `AtelierChatApiIntegrationTest` — 404 croisé sur les deux routes.

### Frontend

- [ ] `atelier.component.spec.ts` — bannière affichée si `prompt = IDLE`, absente sinon.
- [ ] `atelier.component.spec.ts` — « Repartir à neuf » appelle le service et ferme la bannière.

### Isolation workspace

- [x] Applicable — testée sur chacune des deux nouvelles routes.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-03` — done (sans mémoire persistée, la reprise n'a rien à reprendre).

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

**D1 — Un nouveau départ déplace une frontière, il ne supprime rien.** C'est le choix réversible :
l'utilisateur retrouve sa conversation à l'écran, et peut demander autre chose au tour suivant. Une
suppression serait irréversible pour un gain nul.

**D2 — Le seuil d'inactivité est une constante (14 jours), pas un réglage.** Un réglage de plus
demanderait un huitième paramètre à `AtelierProperties`, donc une modification de tous les points
de construction, pour une valeur que personne n'a demandé à ajuster. La constante est nommée et
documentée ; elle deviendra un réglage le jour où quelqu'un voudra la changer.

**D3 — Seul l'inactivité déclenche la question.** Le cadrage évoquait aussi « session du
fournisseur expirée » : cette notion appartient au chemin Managed Agents, et la boucle maison vient
justement de s'en affranchir. L'inventer ici serait recréer la dépendance que D5 supprime.
