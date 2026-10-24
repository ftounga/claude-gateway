# Mini-spec — [F-205 / SF-205-04] Compléter un besoin déjà signalé

## Identifiant
`F-205 / SF-205-04` — Feature parente `F-205` (Registre des besoins) — Statut `done` — Créée 2026-10-24
Branche : `feat/SF-205-04-completer-un-besoin` — dépend de SF-205-01/02 (mergées, en prod).

## Objectif
Depuis un terminal, l'agent peut **compléter ou modifier** un besoin déjà enregistré : une carte « Compléter le besoin « … » ? » range le complément (les mots du PO, une reformulation à jour, des pièces), rattaché au besoin d'origine, sans rien effacer.

## Constat (prod, 2026-10-24)
Le PO a enregistré un besoin (« Panneau visuel des chantiers guidés… »), puis l'a enrichi dans le même terminal. L'agent n'a posé aucune carte : l'outil `signaler_besoin` ne sait que **créer**. Le complément n'est nulle part en base.

## Comportement nominal
- `signaler_besoin` gagne un paramètre facultatif **`complete`** : l'identifiant du besoin à compléter, **ou son titre** (ou un fragment non ambigu).
- Avec `complete` :
  - `description` est obligatoire (les mots du PO pour le complément, tels quels) ; `type` et `titre` sont facultatifs et **hérités** du besoin d'origine ; `reformulation` = la compréhension **à jour, d'ensemble** ; `contexte`, `pieces` comme d'habitude ;
  - une ligne `app_requests` est créée en `PROPOSE`, avec `amends_request_id` = le besoin d'origine ; la carte s'intitule « Compléter le besoin · <titre> » et propose **[Ajouter au besoin]** / **[Non]**.
- **[Ajouter au besoin]** : le complément passe au statut **`JOINT`**, ses pièces sont copiées (même mécanisme) ; le besoin d'origine voit sa date de mise à jour avancer, et **revient `EN_ATTENTE`** s'il était `CADRE` ou `LIVRE` (sa `featureRef` est conservée) — un complément doit être relu.
- **[Non]** : le complément passe `REJETE`, le besoin d'origine ne bouge pas.
- **Lecture** (API admin, onglet Besoins) : un besoin rend ses **compléments joints** (`complements[]` : id, mots, reformulation, contexte, date, pièces), du plus ancien au plus récent. Les compléments ne figurent pas comme besoins à part dans la liste. Une pièce de complément se lit par l'id du complément (`/admin/app-requests/{idComplement}/attachments/{n}`).
- **Doctrine** (littéral stable) : pour compléter ou corriger un besoin déjà signalé, rappeler `signaler_besoin` avec `complete` ; **poser la carte d'emblée**, sans demander la permission de la poser (la carte EST la demande).

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| `complete` ne correspond à aucun besoin de l'utilisateur | erreur rendue à l'agent, avec la liste des 5 besoins récents (titre + id) pour réessayer |
| `complete` correspond à plusieurs besoins | erreur avec les candidats (titre + id) |
| Besoin d'origine `REJETE` ou encore `PROPOSE` | erreur : « rejeté : signale un nouveau besoin » / « pas encore enregistré : fais d'abord cliquer [Enregistrer] » |
| `complete` désigne un complément | on remonte à son besoin d'origine |
| Besoin d'un autre utilisateur | introuvable (isolation `user_id`) |
| PATCH admin sur un complément | 409 : agir sur le besoin d'origine |

## Critères d'acceptation
- [ ] CA1 `complete` = id ⇒ complément `PROPOSE` rattaché, type/titre hérités ; carte avec `amendsRequestId`.
- [ ] CA2 `complete` = fragment de titre unique ⇒ résolu ; ambigu ou inconnu ⇒ erreur listant les candidats.
- [ ] CA3 [Ajouter] ⇒ complément `JOINT`, pièces en copie, besoin d'origine `updatedAt` avancé ; `CADRE`/`LIVRE` ⇒ `EN_ATTENTE`.
- [ ] CA4 Détail et liste admin : `complements[]` ordonnés ; les compléments absents de la liste à plat.
- [ ] CA5 Isolation : un `complete` visant le besoin d'un autre utilisateur échoue (introuvable).
- [ ] CA6 Carte écran : libellés « Compléter le besoin » / [Ajouter au besoin] ; statut `JOINT` affiché « Ajouté au besoin ». Onglet Besoins : compléments sous le besoin.
- [ ] CA7 Non-régression : création simple inchangée ; suites back et front vertes.

## Plan de test
- `AppRequestServiceTest` : CA1, CA2 (id, titre, ambigu, inconnu, complément→origine), CA3, CA5, erreurs de statut d'origine, PATCH complément 409.
- Test d'intégration du contrôleur admin : `complements[]` dans le détail et la liste (CA4).
- Front : `besoin-card.component.spec.ts` (libellés, statut JOINT), `admin-besoins.component.spec.ts` (compléments affichés).
- Isolation utilisateur : CA5.

## Impacts
- Migration `165` : colonne `app_requests.amends_request_id` (uuid, null) + index ; rollback = retrait de la colonne.
- Back : `AppRequest`, `AppRequestRepository`, `AppRequestService`, `AppRequestView`, `AppRequestBlock`, `AtelierChatService` (schéma de l'outil, doctrine, message de retour).
- Front : `atelier.models.ts`, `app-request.service.ts` (libellé `JOINT`), `besoin-card.component.ts`, `admin-besoins.component.ts`.
- Préoccupations transversales : aucune (pas d'auth, pas de tenant nouveau — filtre `user_id` conservé —, pas de plan, pas de route front).
- Changement du littéral de doctrine : une invalidation unique du cache de préfixe (admin seulement).

## Hors périmètre
Éditer en place le texte d'origine (on n'efface jamais les mots du PO) ; fusionner deux besoins ; relance automatique des copies en échec.
