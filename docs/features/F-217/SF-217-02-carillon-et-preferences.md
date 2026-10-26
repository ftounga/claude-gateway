# Mini-spec — F-217 / SF-217-02 Le carillon de l'application et ses préférences

## Identifiant
`F-217 / SF-217-02`

## Feature parente
`F-217` — Les notifications qui sonnent (cadrage `CADRAGE-F-217-les-notifications-qui-sonnent.md`, décisions D5, D8, D9)

## Statut
`done`

## Date de création
2026-10-26

## Branche Git
`feat/SF-217-02-carillon-et-preferences`

> **Ajustement du découpage** (reprise du 2026-10-26) : le service du carillon (D5) et ses préférences (D8–D9) forment un seul lot, car le carillon absorbe le son de fin de tâche de F-191 (`FinishSoundService`) dont le réglage vivait dans les préférences : les séparer laisserait un réglage orphelin entre deux merges. Les **branchements** (terminal, cloche, onglet qui clignote, fermeture des notifications périmées : D5 branchements, D6, D7) passent en SF-217-03. Le cadrage est mis à jour.

---

## Objectif
Doter l'application d'un carillon Web Audio à deux sons, réglable par appareil (activé par défaut, volume, catégories), avec un bouton « Tester le son » qui joue le carillon et envoie une notification système de test, et un guide du son par plateforme.

## Comportement attendu

### Cas nominal
1. **`NotificationSoundService`** (racine) :
   - deux sons synthétisés en Web Audio (aucun fichier, aucune dépendance, aucun CDN) : `action` (trois notes montantes, triangle) et `info` (deux notes douces, sinus — l'ancien son de fin de tâche) ;
   - `actionRequired(key)` : joue `action` et programme **un** rejeu après 30 s ; `settle(key)` / `settleAll()` l'annulent ; une clef déjà en attente ne reprogramme rien ;
   - `info()` : joue `info` ;
   - anti-rafale : deux sons à moins de 2 s → le second se tait (le rejeu à 30 s n'y est pas soumis) ;
   - **déblocage au premier geste** (`pointerdown`, `keydown`, `touchstart`, ou `navigator.userActivation.hasBeenActive`) : avant, silence sans erreur ;
   - réglages de l'appareil (`localStorage` `cg.notif.sound`) : `{ enabled: true, volume: 0.7, action: true, info: true }` par défaut (décision PO) ; valeur illisible → défaut ; volume borné à [0, 1].
2. **`FinishSoundService` (F-191) retiré** : la bande du Fil joue `sound.info()` ; son réglage « son de fin de tâche » disparaît des préférences (absorbé par la catégorie « information »).
3. **`PushActivationService.sendTest()`** : `POST /api/push/test` → `{ configured, devices }`, `null` si l'appel échoue.
4. **Composant `app-notification-sound`** dans Paramètres › Notifications : case « Jouer un son », curseur de volume (aperçu au relâché), deux catégories (Action requise / Information), bouton **« Tester le son »** (carillon `action` + notification système de test + message `MatSnackBar` qui dit ce qui s'est passé), guide repliable par plateforme (Mac, Windows, Android, iPhone/iPad — PWA installée, iOS 16.4+, Réglages › Notifications › Sons — et le mode Concentration). Effet immédiat, pas de bouton « Enregistrer ».

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Aucun geste encore sur la page | silence, aucune erreur |
| `AudioContext` absent ou bloqué | silence, aucune erreur |
| `localStorage` indisponible / valeur corrompue | réglages par défaut, réglage tenu pour la page seulement |
| `POST /api/push/test` en échec | message « La notification système de test n'a pas pu partir. » |
| Push non configuré / aucun appareil | message explicite (non configuré / activez les notifications) |

## Critères d'acceptation
- [ ] CA1 — Par défaut (rien de stocké) : son activé, volume 70 %, deux catégories actives.
- [ ] CA2 — Avant tout geste, aucun son n'est joué ; après un `pointerdown`, `play` émet des oscillateurs.
- [ ] CA3 — `actionRequired(k)` rejoue une seule fois après 30 s ; `settle(k)` avant 30 s annule le rejeu ; un second `actionRequired(k)` en attente ne rejoue pas deux fois.
- [ ] CA4 — Catégorie coupée, interrupteur coupé ou volume 0 → silence ; anti-rafale de 2 s.
- [ ] CA5 — Valeur stockée corrompue → défaut ; volume hors bornes ramené dans [0, 1].
- [ ] CA6 — La bande du Fil joue le son `info` à la fin d'une tâche d'un autre sujet ; `FinishSoundService` n'existe plus.
- [ ] CA7 — « Tester le son » joue le carillon et appelle `POST /api/push/test` ; le message reflète les 4 issues (échec, non configuré, 0 appareil, n appareils).
- [ ] CA8 — Le guide couvre Mac, Windows, Android, iPhone/iPad et le mode Concentration.
- [ ] CA9 — Couleurs, polices et espacements conformes à `DESIGN_SYSTEM.md` (variables `--cg-*`).

## Plan de test
- **Unitaires** (`notification-sound.service.spec.ts`) : CA1–CA5 (faux `AudioContext`, `jasmine.clock`).
- **Unitaires** (`notification-sound.component.spec.ts`) : CA7, CA8, réglages qui suivent les cases.
- **Unitaires** (`terminal-fil.component.spec.ts`) : CA6 (le Fil appelle `info()`).
- **Unitaires** (`notification-preferences.component.spec.ts`) : le réglage du son F-191 a disparu, le reste inchangé.
- **Intégration / isolation** : l'endpoint `POST /api/push/test` et son isolation sont couverts par SF-217-01 ; aucun accès de données nouveau côté front.

## Périmètre — hors scope
Branchements sur le terminal (accord, question, plan, fin de tour), la cloche, l'onglet qui clignote et la fermeture des notifications périmées (SF-217-03) ; réglages synchronisés entre appareils ; son personnalisé des notifications système (impossible).

## Valeurs initiales
`{ enabled: true, volume: 0.7, action: true, info: true }` (stockage local de l'appareil).

## Contraintes de validation
Volume : nombre dans [0, 1] (curseur 0–100 %, pas de 5). Aucun champ soumis au serveur.

## Technique
Endpoint consommé : `POST /api/push/test` (SF-217-01). Fichiers : `core/services/notification-sound.service.ts` (+spec, nouveau), `core/services/finish-sound.service.ts` (+spec, supprimé), `core/services/push-activation.service.ts`, `settings/notification-sound/` (nouveau), `settings/notification-preferences/`, `settings/notifications-settings/`, `atelier/terminal/terminal-fil.component.ts` (+spec). Tables : aucune. Migration : aucune.

## Préoccupations transversales
Auth, tenant, plans, navigation : non concernés (réglage local de l'appareil ; l'endpoint de test est authentifié et isolé en SF-217-01).
