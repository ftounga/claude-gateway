# Mini-spec — F-82 / SF-82-02 — Le coupe-circuit vit sur la carte du poste

## Identifiant

`F-82 / SF-82-02`

## Feature parente

`F-82` — Arrêter proprement, et savoir quand on n'y arrive pas

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-82-02-coupe-circuit-carte-poste`

---

## Objectif

Porter le coupe-circuit du poste sur **la carte du poste** dans `/forge`, avec une confirmation qui
**dit tout** ce qu'il fait — et ce qu'il ne fait pas.

---

## Le défaut, tel qu'il est dans le code

`RunnerKillSwitchService` (SF-38-08, porté au poste par SF-48-01) fait exactement ce qu'il promet :
révoquer tous les jetons du poste, couper la liaison, ramener **tous** ses projets en cible
`SANDBOX`. Son endpoint `POST /api/runner-hosts/{hostId}/kill` existe et est isolé par `user_id`.

Mais son bouton ne vit **que** dans l'en-tête d'un terminal
(`atelier-terminal.component.html:229` → `atelier.component.ts:killRunner()`). Vérifié : aucune
occurrence de `killHost` dans les écrans de postes.

**Un poste connecté sans aucun projet n'a pas de terminal, donc pas de bouton.** C'est exactement la
situation vécue le 2026-09-12 : la machine est branchée, et on ne peut rien en faire depuis
l'application.

Et le libellé ne dit pas ce qu'il fait. « Couper la liaison avec la machine » se comprend comme
« arrête ça ». Or :

- son effet **dépasse** la coupure : **tous** les projets du poste repassent en bac à sable ;
- il ne coupe **pas** le processus : le programme Java continue de tourner sur la machine, tente de
  se reconnecter, et se fait refuser (jeton mort). Vu du terminal du client, **rien ne s'arrête**.

---

## Comportement attendu

### Cas nominal

1. `/forge`, carte d'un poste. Le menu de dépassement (`more_vert`, celui de F-69) porte désormais
   **deux** entrées, séparées : **« Couper la liaison… »** puis **« Supprimer le poste »**.
   Jamais d'accès direct : un geste destructif ne s'atteint pas d'un clic sur la carte.
2. Clic → dialogue de confirmation `KillHostDialogComponent`. Il dit **trois** choses, dans cet
   ordre :

   **(a) Ce qui change tout de suite.** La liaison est fermée, les jetons de ce poste sont révoqués,
   et les projets du poste **repassent en bac à sable** — ils sont **nommés un par un**, pas
   comptés. Ceux qui y sont déjà sont marqués comme tels : on ne promet pas un changement qui
   n'aura pas lieu. Aucun projet → la carence est dite (« ce poste ne porte aucun projet »).

   **(b) Ce qui ne s'arrête pas.** Le runner **continue de tourner** sur la machine. Il tentera de
   se reconnecter et **sera refusé**. C'est la phrase qui manquait : le libellé laissait croire
   l'inverse.

   **(c) Comment l'arrêter vraiment**, sur la machine :
   - dans le terminal où il tourne : `Ctrl-C` ;
   - ce terminal est fermé : `pkill -f claude-runner.jar` (macOS, Linux) ou, sous Windows,
     `taskkill /F /IM java.exe` depuis le Gestionnaire des tâches.

   L'application **ne le fait pas** : elle **dit** comment le faire (hors périmètre F-82).
3. Confirmation → `POST /api/runner-hosts/{hostId}/kill`. Pendant l'aller-retour la carte se
   verrouille (même verrou que la suppression : le menu est désactivé, un spinner remplace l'icône).
4. Réponse → un `MatSnackBar` reprend ce que la gateway a **réellement** fait (jetons révoqués,
   projets ramenés), puis la vue est relue (`load(false)`) : c'est la gateway qui fait foi.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Poste « Hébergé » (virtuel, `id === null`) | L'entrée de menu **n'existe pas** ; garde dans le code en plus du gabarit | — |
| Poste d'un autre utilisateur | Refusé par la gateway, isolation `user_id` — **inchangé** | 403 / 404 |
| Poste inexistant (vue en retard) | Message d'échec, et la vue est relue | 404 |
| Liaison déjà coupée | Succès : le coupe-circuit est **idempotent** (0 jeton révoqué) — le message le dit | 200 |
| Erreur réseau / 5xx | Message d'échec, verrou relâché, vue relue | 5xx |
| Un coupe-circuit déjà en cours sur ce poste | Le second clic ne fait rien | — |

---

## Critères d'acceptation

- [ ] Une entrée **« Couper la liaison… »** existe dans le menu de dépassement de la carte de poste,
      **au-dessus** de « Supprimer le poste », séparée d'elle.
- [ ] Elle est **absente** du poste virtuel « Hébergé », gabarit **et** code.
- [ ] Elle n'est **jamais** en accès direct sur la carte : uniquement sous le menu de dépassement.
- [ ] Le dialogue **nomme** les projets du poste qui repassent en bac à sable — pas seulement leur
      nombre.
- [ ] Le dialogue marque les projets **déjà** en bac à sable, et gère le cas **zéro projet** (le cas
      vécu par le PO).
- [ ] Le dialogue dit que le processus **continue de tourner** sur la machine et **sera refusé** à
      sa prochaine tentative.
- [ ] Le dialogue dit **comment l'arrêter** sur la machine (`Ctrl-C`, puis la commande selon le
      système), et l'application ne l'arrête pas elle-même.
- [ ] Annuler ne déclenche **aucun** appel.
- [ ] Confirmer appelle `killHost(hostId)` et **uniquement** lui — aucune autre route.
- [ ] Sur succès : message reprenant le résultat de la gateway, puis relecture de la vue.
- [ ] Sur échec : message d'erreur, verrou relâché, vue relue.
- [ ] Le comportement du coupe-circuit côté gateway est **inchangé** — aucun fichier backend touché.
- [ ] L'en-tête du terminal est **inchangé** (le bouton d'origine reste).
- [ ] Aucun registre de couleur nouveau : uniquement les jetons `--cg-*` de `DESIGN_SYSTEM.md`.
- [ ] Confirmation destructive par `MatDialog`, notification par `MatSnackBar` — aucun
      `window.confirm()`.

---

## Périmètre

### Hors scope (explicite)

- **Arrêter le processus du runner depuis l'application** — ce serait une extinction à distance sur
  la machine d'un client. L'écran **dit** comment l'arrêter.
- **Changer ce que fait le coupe-circuit** : aucun fichier backend n'est touché, l'endpoint et le
  service restent tels quels. F-82 le rend atteignable et compréhensible, elle ne le redessine pas.
- Le bouton de l'en-tête du terminal : **inchangé**, et son dialogue de confirmation avec.
- Le coupe-circuit sur le poste « Hébergé » : il n'y a pas de machine à couper.
- Révoquer un jeton isolément (reste dans le dialogue de mise en service).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Normalisation |
|---|---|---|---|
| `hostId` | Oui | UUID du poste ; `null` interdit (poste virtuel) | — |
| Projets nommés | — | `HostProjectSummary.name`, tels que rendus par la gateway | aucun tronquage |
| `executionTarget` | Non | `RUNNER` \| `SANDBOX` \| absent | absent = traité comme « cible inconnue », le projet est listé sans mention |

---

## Technique

### Endpoint(s)

`POST /api/runner-hosts/{hostId}/kill` — **existant**, inchangé. Aucun endpoint créé ni modifié.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants impactés

| Fichier | Nature |
|---|---|
| `frontend/src/app/postes/kill-host-dialog/kill-host-dialog.component.{ts,html,scss}` | **nouveau** — la confirmation qui dit tout |
| `frontend/src/app/postes/kill-host-dialog/kill-host-dialog.component.spec.ts` | **nouveau** |
| `frontend/src/app/postes/postes.component.html` | l'entrée de menu, sous le menu de dépassement existant |
| `frontend/src/app/postes/postes.component.ts` | `killHost()`, `performHostKill()`, `killingHostId` |
| `frontend/src/app/postes/postes.component.spec.ts` | les cas de cette subfeature |

Aucun fichier backend, aucun fichier runner.

---

## Plan de test

### Tests unitaires — `KillHostDialogComponent`

| # | Test | Vérifie |
|---|---|---|
| 1 | `nomme_les_projets_qui_repassent_en_bac_a_sable` | Les **noms** apparaissent, pas seulement le compte |
| 2 | `marque_les_projets_deja_en_bac_a_sable` | Un projet en `SANDBOX` est marqué comme tel |
| 3 | `dit_que_le_poste_ne_porte_aucun_projet` | Le cas vécu : zéro projet, et c'est dit |
| 4 | `dit_que_le_processus_continue_de_tourner_et_sera_refuse` | Le point (b) est à l'écran |
| 5 | `dit_comment_arreter_le_runner_sur_la_machine` | `Ctrl-C` et la commande sont à l'écran |
| 6 | `annuler_rend_false_confirmer_rend_true` | Le contrat du dialogue |

### Tests unitaires — `PostesComponent`

| # | Test | Vérifie |
|---|---|---|
| 7 | `le_coupe_circuit_depuis_la_carte_d_un_poste_sans_aucun_projet` | **Le cas vécu** : poste connecté, zéro projet → `killHost` appelé |
| 8 | `annuler_n_appelle_pas_la_gateway` | Aucun appel sur annulation |
| 9 | `le_poste_heberge_n_expose_pas_le_coupe_circuit` | Garde de code : `id === null` → aucun appel |
| 10 | `un_echec_relit_la_vue_et_relache_le_verrou` | Le chemin d'erreur |
| 11 | `le_succes_dit_ce_que_la_gateway_a_fait_puis_relit` | Message et relecture |
| 12 | `un_second_clic_pendant_l_aller_retour_ne_fait_rien` | Verrou `killingHostId` |

### Tests d'intégration

Aucun : l'endpoint est existant et déjà couvert côté backend (SF-38-08 / SF-48-01). Cette
subfeature n'ajoute aucune route.

### Isolation utilisateur

Garantie côté gateway et **inchangée** : `POST /api/runner-hosts/{hostId}/kill` résout le `user_id`
depuis le JWT, et `RunnerKillSwitchService.kill(userId, hostId)` filtre dessus. L'écran n'envoie
qu'un identifiant de poste, exactement comme le bouton du terminal aujourd'hui. Aucun test nouveau
n'est requis de ce côté, et aucun code d'accès aux données n'est ajouté.

---

## Préoccupations transversales

| Préoccupation | Touchée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | Aucun nouveau chemin d'accès aux données ; l'endpoint existant résout le tenant depuis le JWT |
| Plans / limites | Non | — |
| **Navigation / routing** | **Oui** — un geste destructif de plus dans `/forge` | `postes.component.html` (menu `#hostMenu` de la carte), `postes.component.ts`. Aucune route ajoutée, aucun guard touché, aucune redirection. Les chemins de navigation existants de `/forge` sont inchangés : le geste vit **dans** le menu déjà présent |

---

## Ce qui reste ouvert

Rien. Le libellé de l'en-tête du terminal reste « Couper la liaison avec la machine » : le redresser
là-bas aussi relèverait d'un passage sur `atelier.component`, hors du périmètre annoncé de cette
subfeature.
