# Mini-spec — F-191 / SF-191-03 Le bandeau de fin et le son de fin

## Identifiant
`F-191 / SF-191-03` — Feature parente : F-191 « Le fil des tâches ». Statut `ready` — 2026-10-11 — Branche `feat/SF-191-03-fin-et-son`.
Contrat API importé de SF-191-01 (`GET /api/workspaces/{id}/fil`) ; s'appuie sur la bande SF-191-02 (mergée).

## Objectif
Prévenir, depuis le terminal où l'on travaille, qu'une tâche d'un **autre** sujet du poste vient de finir — par un bandeau discret et, si on l'a choisi, un son court.

## Comportement attendu

### Cas nominal
- La bande (`app-terminal-fil`) compare chaque lecture à la précédente. Une tâche **finie** (`done`, `failed`, `interrupted`) qui n'était pas encore connue comme finie, et qui **n'est pas** celle du terminal courant (`here = false`), déclenche :
  - un **bandeau** au-dessus de la bande : « ✓ <sujet> · « <demande> » est terminé · <durée> · **Ouvrir** » (« s'est arrêté » pour un échec, « a été interrompu » pour une interruption), bouton ✕ pour le fermer ; il disparaît seul après 30 s ; une nouvelle fin le remplace ;
  - le **son de fin**, s'il est activé.
- **La première lecture n'annonce rien** (ouvrir un terminal ne doit pas sonner pour des tâches finies avant).
- « Ouvrir » ⇒ `/atelier/<id>` et ferme le bandeau.
- **Son de fin** : réglage « Son de fin de tâche » dans Paramètres > Notifications (bloc F-185 « Ce qui vous prévient »), **coupé par défaut**, propre à **cet appareil** (mémorisé dans le navigateur), effet immédiat. Son court (≈ 0,3 s, deux notes) généré par Web Audio — aucun fichier tiers. Bouton « Écouter » pour l'essayer.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Web Audio indisponible / bloqué par le navigateur | silence, aucun message, le bandeau s'affiche quand même |
| Stockage du navigateur indisponible (navigation privée) | son coupé (défaut), le réglage ne tient que pour la page |
| Tâche finie du terminal courant | ni bandeau ni son (on la voit déjà) |

## Critères d'acceptation
- [ ] Une tâche d'un autre sujet qui passe de « en cours » à « terminée » entre deux lectures affiche le bandeau avec sujet, demande, durée et « Ouvrir ».
- [ ] Une tâche finie apparue entre deux lectures (jamais vue en cours) est annoncée aussi ; la première lecture n'annonce rien.
- [ ] La fin de la tâche du terminal courant n'annonce rien.
- [ ] Le son ne joue que si le réglage est activé ; il est coupé par défaut.
- [ ] Le réglage figure dans Paramètres > Notifications, avec « Écouter ».
- [ ] Couleurs de la charte (vert `--cg-success` pour la fin, or pour l'action), aucune fenêtre `alert`.

## Plan de test
- `FinishSoundService` : coupé par défaut ; activation mémorisée ; `play()` sans effet si coupé ; stockage qui lève ⇒ coupé, sans erreur ; Web Audio absent ⇒ silence.
- `TerminalFilComponent` : transition en cours → terminé annonce + son ; première lecture muette ; tâche « ici » muette ; « Ouvrir » navigue ; ✕ ferme ; auto-fermeture 30 s ; son non joué si coupé.
- `NotificationPreferencesComponent` : case « Son de fin de tâche » décochée par défaut, la cocher active le service, « Écouter » joue.
- Isolation : côté serveur (SF-191-01) ; rien de neuf côté compte (réglage local à l'appareil).

## Impacts
- Composants : `TerminalFilComponent` (bandeau), nouveau `FinishSoundService`, `NotificationPreferencesComponent` (bloc « Son de fin »).
- Endpoints : aucun nouveau. Tables : **aucune** (pas de migration).
- Préoccupations transversales : Navigation ☑ — « Ouvrir » navigue vers `/atelier/:id` (route existante, déjà utilisée par la bande SF-191-02) ; aucune route ni garde modifiée. Auth ☐ ; Tenant ☐ ; Plans ☐.

## Hors périmètre
Notification push de fin de tâche (déjà portée par F-185 « réponse prête ») ; son pour le terminal courant ; choix du son ; volume.

## Arbitrages (décidés par défaut, réversibles)
- **Réglage du son propre à l'appareil** (stockage navigateur) et non au compte : un son est un comportement de l'appareil, comme son volume ; on peut le vouloir au bureau et pas sur le téléphone. Évite aussi une migration de `notification_preferences`. Réversible : une colonne pourra le porter plus tard.
- **Le bandeau vit dans la bande** (même lecture, aucune requête de plus).
- **Toute fin est annoncée** (succès, échec, interruption), avec un libellé qui la distingue.
