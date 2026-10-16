# Mini-spec — F-180 / SF-180-06 — Échéances et rappels

## Identifiant
`F-180 / SF-180-06` — feature parente `F-180` *Les accès et les interlocuteurs* (décision **D6**).
Dépend de SF-180-04 (registre) — mergée. Branche : `feat/SF-180-06-echeances-et-rappels`. Statut :
`in-progress`. Date : 2026-10-16.

## Objectif
Qu'une relance due, une échéance atteinte ou un accès qui expire **se voient** : notification push (F-153 /
F-185) et ligne d'un **point du matin** dans la bande des attentes — avec une invitation à activer le push
quand l'appareil n'est pas abonné.

## Comportement attendu

### Cas nominal
1. **Échéance facultative** : `terminal_actions.due_at` (date, migration `162`). Saisie à l'ajout et à
   l'édition (`dueAt`), proposable par l'agent (`record_blocker` / `update_blocker` : `due_date`
   `AAAA-MM-JJ`). Réponses : `dueAt`, `dueState` ∈ {`NONE`, `LATER`, `TODAY`, `OVERDUE`}. La liste jointe au
   tour dit « · échéance 20/10 » ou « · ÉCHÉANCE DÉPASSÉE (20/10) ».
2. **Point du matin** — `GET /api/workspaces/{id}/actions/morning` (portée poste du terminal possédé) →
   `{ followUps: [...], dueToday: [...], overdue: [...], accesExpiring: [...] }` : relances dues (F-175),
   échéances du jour et dépassées (attentes ouvertes), accès du registre qui expirent dans 7 jours ou ont
   expiré depuis 7 jours au plus.
3. **Bande** : quand le point du matin n'est pas vide, une ligne « Point du matin : 2 relances dues ·
   1 échéance dépassée · VPN expire dans 4 j » au-dessus de la bande des attentes, [Voir] (ouvre le
   panneau) et [×] (masquée **pour la journée** sur ce terminal, mémoire locale du navigateur). Si l'appareil
   n'est pas abonné au push et que le navigateur le permet : [Activer les notifications] (F-153).
4. **Notification** : le récapitulatif quotidien de 9 h (F-185 / SF-185-07, **une** notification par compte
   et par jour, verrou existant) part aussi pour une échéance atteinte ou un accès qui expire dans 7 jours ;
   événement `ATTENTES_TO_FOLLOW_UP` s'il n'y a que des relances, sinon `ATTENTES_POINT_DU_MATIN`
   (« Votre point du matin sur les attentes » — corps neutre, aucun nom de sujet ni d'accès ne quitte
   l'application). Chaque émission porte le `user_id` de la ligne.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `dueAt` illisible (API) | 400 |
| `due_date` illisible (outil) | résultat d'outil en erreur, le tour continue |
| Terminal d'un autre compte | 404 |
| Push non configuré / non supporté | pas d'invitation ; la bande reste |
| Échec de l'envoi pour un compte | les autres comptes sont prévenus (inchangé) |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `dueAt` / `due_date` | date ISO, facultative ; `""` à l'édition = effacée |
| Fenêtre d'expiration | 7 jours avant, 7 jours après |

## Critères d'acceptation
- [ ] Une échéance se saisit, s'affiche (« échéance dépassée »), voyage avec le tour.
- [ ] Le point du matin liste relances dues, échéances, accès qui expirent ; la bande l'annonce, [×] la masque pour la journée.
- [ ] Sans abonnement push, la bande invite à activer les notifications.
- [ ] Le récapitulatif de 9 h part pour une échéance ou un accès qui expire, une fois par jour, au seul compte concerné.
- [ ] Bob ne lit pas le point du matin d'Alice (404).

## Plan de test
- **Unitaires** : `TerminalActionMorningTest` (état d'échéance, point du matin, fenêtres, ligne du tour,
  outil `due_date`), `AttentesDigestServiceTest`/intégration (échéance, accès, une fois par jour, choix de
  l'événement, isolation par compte).
- **Intégration** : `TerminalActionMorningApiIntegrationTest` — échéance saisie, point du matin, 400, isolation.
- **Front** : bande du point du matin (résumé, voir, masquer pour la journée, invitation push), formulaire
  d'échéance, pastille d'échéance.

## Impacts
- **Table** `terminal_actions.due_at` (migration `162`, réversible). Aucune nouvelle table.
- **Endpoints** : `GET /api/workspaces/{id}/actions/morning` ; `dueAt` sur création / édition / réponses.
- **Composants** : `TerminalActionMorningService` (nouveau), `TerminalActionService`, `TerminalActionController`,
  `TerminalActionResponse`, `TerminalActionTurnNote`, outils, `AttentesDigestService`, `PushEvent` ;
  front : modèles, service, `terminal-morning-band` (nouveau), terminal, panneau.

### Préoccupations transversales
- **Contexte tenant** : oui — `TerminalActionMorningService` (poste du terminal possédé, `user_id`),
  `AttentesDigestService` (lecture transverse, **chaque émission** sous le `user_id` de la ligne).
- **Notifications** : nouvel événement `ATTENTES_POINT_DU_MATIN` — le catalogue des préférences (F-185)
  le liste d'office ; corps neutre (D1 de F-185).

## Hors périmètre
Envoi automatique de relances ; rappel par courriel ; calendrier des jours fériés.

## Arbitrages (réversibles)
- Une seule notification par jour et par compte (verrou F-185 réutilisé), l'événement dit le cas dominant.
- Masquage du point du matin **par terminal et par jour**, en mémoire locale (préférence d'affichage).
- Fenêtre d'expiration ±7 jours.
