# Cadrage F-205 — Registre des besoins : signaler un bug ou une évolution depuis le terminal

**Demande PO (2026-10-15)**, livraison autonome (vague). Constat vérifié sur `origin/main` 6e16f2c8 et en prod. Justesse avant coût ; charte `DESIGN_SYSTEM.md` (aucune couleur nouvelle) ; préfixe système stable (rien de volatil).

## Besoin
Depuis un terminal, je décris un bug ou un besoin **de l'application elle-même** (comme avec Claude Code), avec captures, photos, vidéos. L'agent le reconnaît et l'enregistre dans l'application. Plus tard, je demande à Claude Code de « récupérer les besoins en attente, les cadrer et voir si on peut les livrer ».

## Existant
Rien de tel (aucun outil, aucune table, aucune route). Patron à copier : **F-177 `gouvernance_proposer`** (outil serveur, carte dans le fil, [Appliquer]/[Refuser], table à statut). Écran admin à onglets (`admin.component.ts`), garde `AdminService.assertAdmin()`. Pièces : sur un poste les octets restent sur la machine (`.atelier/entrees/`) ⇒ il faut les **copier** dans le stockage de la gateway (S3 `WorkspaceStorage`) ; vidéos bornées (`max-runner-bytes` 100 Mo).

## Décisions
- Tables `app_requests` (id, user_id, workspace_id, message_id, kind BUG|EVOLUTION, title, description = mots de l'utilisateur + reformulation, observed_context, status PROPOSE|EN_ATTENTE|CADRE|REJETE|LIVRE, feature_ref, timestamps) et `app_request_attachments` (request_id, name, content_type, size, storage_key). Isolation `user_id`.
- Outil agent `signaler_besoin` (kind, titre, description, pièces = fichiers déposés du tour) déclaré sur toutes les cibles sauf Teams ; doctrine stable : « l'utilisateur décrit un défaut ou un manque **de l'application** (pas de son projet) ⇒ propose de l'enregistrer » ; carte [Enregistrer]/[Non] ; au clic : EN_ATTENTE + copie des pièces vers S3 (préfixe `besoins/{id}/`).
- Écran : onglet **Besoins** dans l'administration (liste, filtre statut, aperçu des pièces, changement de statut, référence de feature).
- Lecture pour Claude Code : `GET /api/admin/app-requests?status=EN_ATTENTE` (JSON), `GET …/{id}/attachments/{n}`, `PATCH` statut/feature_ref ; + documentation d'usage dans `docs/ops/` (requête `q-prod-ro.sh` et route admin).
- Réservé à l'ADMIN (le PO) à ce stade.

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-205-01 | Backend : tables + migration, outil `signaler_besoin` + doctrine, carte de confirmation, copie des pièces vers S3, routes admin, tests (isolation, pièces poste/hébergé) | 🟠 |
| SF-205-02 | Front : carte dans le fil + onglet admin Besoins (liste, détail, pièces, statut) | 🟠 |
| SF-205-03 | Doc `docs/ops/besoins-en-attente.md` : comment Claude Code récupère et marque les besoins | 🟢 |
