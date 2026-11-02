# Mini-spec — [F-236 / SF-236-13] Ambiance et alertes (A1-A5, A4/N12 ADMIN, N13 rouge réservé, L-veille)

## Identifiant
SF-236-13

## Feature parente
F-236 — Jarvis en 3D (cadrage §9.2 A1-A5, §11 N12 / N13, §8.2 L-veille, §3.1 H4 / H15)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-13-ambiance-alertes`

---

## Objectif

Rendre en 3D l'ambiance hors tour déjà relevée par F-234 (poste muet, terminaux vivants, cloche, coût, veille qui
sonne), avec un rouge réservé aux seules alertes.

## Comportement attendu

### Cas nominal

1. **A1 Poste muet** (`ambient.hostDown`) : le client vire au **rouge**, halo lent qui **grésille**, son lien L-poste
   grésille aussi ; panneau « Poste muet ».
2. **A2 Terminaux vivants** (`ambient.liveSubjects`) : les lunes **respirent** (4 s) ; une impulsion part de la
   première vers son client.
3. **A3 Cloche** (`ambient.calling`) : le sujet qui appelle prend la teinte d'**attente** et lance une **onde** (une
   par appel) ; panneau « Un sujet vous appelle ».
4. **A4 / N12 Coût** (`ambient.cost`) : compteur holographique en **€** en fin de tour, **5 s** — **administrateur
   seulement** (double garde : le serveur ne livre le montant qu'à l'administrateur, l'écran vérifie le rôle).
5. **A5 La veille sonne** (`ambient.alert`) : la caméra **cadre le client**, **onde client → cœur**, **cadran relié au
   client (L-veille)**, panneau « La veille sonne » **devant tout** — en **rouge** (alerte).
6. **Au repos seulement** (aucun tour, aucune scène) **sauf A5**, qui se montre toujours (D14).
7. **N13** : le rouge (`alert`) n'est employé **que** par A1 et A5 — aucune scène, aucun « Montrer », aucune autre
   ambiance (test sur le plan, `planTones`).
8. Le plan d'ambiance s'ajoute au plan de scène : ses teintes priment (le rouge d'une alerte sur « Montrer »), ses
   panneaux passent après ceux de la scène (pool borné, « devant tout » d'abord), ses effets cèdent à ceux de la
   scène dans chaque famille.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Non-administrateur, ou coût de plus de 5 s | Aucun coût affiché. |
| Nœud de l'alerte non posé (client inconnu) | Panneau autour du cœur, aucun cadrage, aucun rouge sur l'univers. |
| Tour en cours ou scène affichée | Seule A5 ; le reste attend le repos. |
| Aucune ambiance | Aucun plan d'ambiance. |
| Mode sobre / 2D | Aucun plan d'ambiance 3D (la constellation 2D F-234 garde la sienne). |

---

## Critères d'acceptation

- [x] CA1 — Ambiance au repos seulement, sauf A5 (tests).
- [x] CA2 — A4 **jamais** pour un non-administrateur, 5 s seulement (tests).
- [x] CA3 — **Rouge réservé aux alertes** : aucune scène (toutes celles du banc), aucun filtre « Montrer », aucune
  ambiance hors A1 / A5 ne l'emploie ; A1 / A5 l'emploient et seule l'ambiance le porte (tests sur le plan).
- [x] CA4 — Captures téléphone + PC de A1-A5, non vides, sans erreur console.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Coût | 5 s, administrateur seulement, 24 c. |
| Respiration A2 | 4 s ; grésillement A1 / A5 : bruit déterministe sans allocation. |

## Plan de test

- Unitaires : `scenes3d/jarvis-ambiance-3d.spec.ts` (A1-A5, repos, A4 ADMIN, N13 sur toutes les scènes et filtres).
- Composant : suites `jarvis.component.spec.ts` / `jarvis-stage-3d.component.spec.ts` vertes (rôle lu sans casser
  l'écran monté sans service d'authentification).
- Isolation utilisateur : sans objet (aucune lecture nouvelle : l'ambiance est déjà relevée par F-234 ; le coût est
  filtré par le serveur).
- Visuel : `ambiance-poste-muet`, `ambiance-vivants`, `ambiance-cloche`, `ambiance-cout`, `ambiance-veille`.

## Périmètre

### Impacté
`scenes3d/jarvis-ambiance-3d.ts` (nouveau), fixture ; `jarvis-stage-director.ts` (`ambient`, `admin`, `planTones`),
`jarvis-stage-3d.component.ts` (5 s du coût, onde par appel) ; `engine/jarvis-universe-3d.ts` (teintes, respiration,
grésillement), `engine/jarvis-engine.ts` (panneaux, effets, cadrage A5) ; `jarvis.component.ts` (ambiance et rôle
relayés) ; banc. Captures `scene-etape` / `scene-message` (téléphone) refaites : prises pendant une recompilation du
serveur de développement, elles montraient son message d'erreur.

### Hors scope (explicite)
- Le radar des alertes et l'horloge des veilles (SF-236-25), le mode présentation (SF-236-23 : A4 masqué).
- Aucun endpoint, aucune table, aucun appel IA, aucune consigne touchée.

## Préoccupations transversales

| Préoccupation | Composants impactés et vérification |
|---|---|
| Auth / Principal (lecture du rôle seulement) | `JarvisComponent` lit `AuthService.isAdmin` (affichage) ; aucun endpoint, aucune règle d'accès modifiés ; le coût reste filtré par `AtelierChatController.labelOf(result, admin)`. |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Le rôle est lu par `inject(AuthService)` protégé (repli « non administrateur »). | L'écran est aussi monté sans service d'authentification (tests) ; le serveur garde la vraie garde. | Rendre `AuthService` obligatoire (casse les montages existants). | Oui. |
| A2 | « Au repos » = aucun tour **et** aucune scène affichée. | Une scène prend le devant (D14) ; l'ambiance ne la parasite pas. | Tour seulement. | Oui. |
| A3 | A5 en rouge (alerte), A3 en teinte d'attente (cyan). | Grammaire §23 amendée : orange = normal, cyan = attente, rouge = alerte. | A3 orange (cadrage d'origine). | Oui. |
| A4 | Teintes d'ambiance prioritaires sur celles de « Montrer ». | Une alerte doit rester visible. | Priorité à « Montrer ». | Oui. |
