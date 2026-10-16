# Mini-spec — [F-199 / SF-199-01] Client courant : mesure en prod + service + lien Forge et fil d'Ariane qui le suivent

## Identifiant
`F-199 / SF-199-01` — Feature parente `F-199` — Statut `in-progress` — Créée 2026-10-16
Branche : `feat/SF-199-01-client-courant` — dépend de F-198 (Terminée, #1182/#1184)

## Objectif
Ouvrir le terminal d'un client fait de ce client le **client courant** de toute l'application : le lien « Forge » de la barre, le fil d'Ariane du terminal et l'accueil de la Forge (`/forge`) l'ouvrent, au lieu du poste par défaut.

## Mesure en prod (2026-10-16, lecture seule, `q-prod-ro.sh`)
| Constat | Valeur |
|---|---|
| Terminaux (`workspaces`) | 27 : 26 rattachés à un poste, 1 sans poste (Hébergé, `SANDBOX`) |
| Postes (`runner_hosts`) | 4 : CAGIP (Forge+Vigie, 6 projets + terminal du poste + terminal Teams), EDENRED (7 + 1), FREE (7 + 1 + Teams), Richemont (0 + 1) |
| Terminaux Teams | 2, **chacun rattaché à son poste** (CAGIP, FREE) |
| Portée du Fil (`PosteTurnsService`) | un seul poste (`findByUserIdAndHostId`) + défense en profondeur ; sans poste ⇒ les seuls terminaux sans poste (1 en prod) |

**Conclusion** : la bande ne montre jamais la tâche d'un **autre** client (elle mélange au plus projets, terminal du poste et terminal Teams **du même** poste). L'incohérence constatée par le PO vient d'ailleurs : sur `/atelier/<id>` l'adresse ne porte aucun client, donc le lien « Forge » mène à `/forge` ⇒ `defaultHostRef` (premier « À regarder », sinon premier en ligne) — souvent un **autre** client que celui du terminal ouvert. Même effet avec le fil d'Ariane tant que le détail du projet n'est pas relu (`activeHostId` vaut `null` juste après `selectWorkspace`, le lien retombe sur `/forge`).

## Comportement nominal
1. **Backend (additif)** : `GET /api/workspaces` porte `hostId` sur chaque résumé — **seulement** si le poste appartient à l'appelant (même règle que `hostName` : poste résolu dans `runnerHostService.list(userId)`), sinon `null`.
2. **`CurrentClientService`** (`core/services/current-client.service.ts`, racine, en mémoire) : `ref` = identifiant du poste, ou `heberge` ; `set(ref)`, `clear()`.
3. **Atelier** : à l'ouverture d'un terminal, le client courant devient son poste — `hostId` du résumé (immédiat), sinon le détail/l'état runner ; un terminal sans poste (résumé avec `hostId: null`) ⇒ `heberge`. Au changement de terminal, l'ancien client n'est jamais conservé : il est remplacé dès que le nouveau est connu, effacé sinon.
4. **Fil d'Ariane** : `activeHostId` lit d'abord le `hostId` du résumé ⇒ le niveau « client » pointe sur `/forge/<id>` (ou `/vigie/<id>` pour un terminal Teams) **dès l'ouverture**.
5. **Lien « Forge » / « Vigie » de la barre** (`spaceLinks`) : le client de l'adresse prime (inchangé) ; à défaut, le client courant. `heberge` n'est jamais porté vers la Vigie.
6. **Forge** : `/forge/<ref>` connu ⇒ devient le client courant. `/forge` sans référence ⇒ le client courant s'il est dans la flotte, sinon `defaultHostRef` (inchangé).

## Cas d'erreur
- Backend antérieur (pas de `hostId` dans le résumé) ⇒ repli sur détail/état runner (comportement d'avant).
- Client courant supprimé / inconnu de la flotte ⇒ `/forge` retombe sur `defaultHostRef`, jamais d'erreur.
- Rechargement de page ⇒ service vide ⇒ comportement d'avant jusqu'à l'ouverture d'un terminal ou d'un client.

## Critères d'acceptation
- [ ] CA1 `GET /api/workspaces` : `hostId` présent pour un projet rattaché à un poste de l'appelant, absent/`null` pour un projet sans poste.
- [ ] CA2 Ouvrir `/atelier/<projet EDENRED>` ⇒ lien « Forge » de la barre = `/forge/<EDENRED>` (et non le poste par défaut).
- [ ] CA3 Passer de A (CAGIP) à B (EDENRED) ⇒ client courant = EDENRED, fil d'Ariane `/forge/<EDENRED>` sans attendre le détail.
- [ ] CA4 `/forge` sans référence, client courant connu ⇒ ce client ouvert ; inconnu ⇒ `defaultHostRef`.
- [ ] CA5 `/forge/<X>` ⇒ client courant = X.
- [ ] CA6 Terminal sans poste ⇒ client courant `heberge` ; lien Vigie inchangé (`/vigie`).
- [ ] CA7 Non-régression : specs `space-links`, `shell`, `postes`, `atelier` verts.

## Plan de test
- Backend : `AtelierApiIntegrationTest` — `hostId` porté (CA1) et absent sans poste.
- Unitaires : `space-links.spec.ts` (repli client courant, `heberge` hors Vigie), `current-client.service.spec.ts`, `atelier.component.spec.ts` (CA2/CA3/CA6), `postes.component.spec.ts` (CA4/CA5).
- Isolation utilisateur : `hostId` n'est servi que pour un poste résolu dans la liste **de l'appelant** ; aucun nouvel endpoint ; le service front ne contient qu'un identifiant déjà servi à cet utilisateur.
- Non-régression : `mvn -pl backend test` + `npm run build && npm test`.

## Impacts
- Tables : aucune. Endpoint : `GET /api/workspaces` (champ additif `hostId`).
- Composants : `WorkspaceSummaryResponse`, `AtelierController#list` ; front `CurrentClientService` (nouveau), `space-links.ts`, `ShellComponent`, `AtelierComponent` (`activeHostId`, client courant), `PostesComponent` (`selectedRef`).
- Préoccupations transversales :
  - [x] **Contexte tenant / client** — composants qui résolvent le client affiché : `space-links.ts#openClientRef/spaceLinks` (barre), `AtelierComponent#activeHostId/activeHostName` (en-tête + fil d'Ariane), `AtelierTerminalComponent#crumbs`, `PostesComponent#selectedRef` (Forge), Vigie (non modifiée : suit son adresse). Chacun vérifié par test.
  - [x] **Navigation / routing** — aucune route ajoutée ; seules les destinations des liens Forge/Vigie et de `/forge` sans référence changent. Chemins vérifiés : `/forge`, `/forge/<id>`, `/forge/heberge`, `/forge/voir`, `/vigie`, `/vigie/<id>`, `/atelier/<id>`.
  - [ ] Auth / Principal — non touché. [ ] Plans / limites — non touché.
- Design system : rien de visuel.

## Hors périmètre
- Persistance du client courant au-delà de l'onglet (rechargement).
- Le clic du Fil (SF-199-02).
- La Vigie ne pousse pas son client dans le service (elle porte déjà le client dans son adresse).
