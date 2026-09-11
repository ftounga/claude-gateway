# Cadrage — F-61 — Consommation : par client pour chacun, par utilisateur pour l'admin

> Feature référencée dans `docs/PRODUCT_SPEC.md` (ligne F-61). Ce cadrage ne l'élargit pas :
> il tranche ce que la ligne laissait explicitement à trancher, et trace les arbitrages.

Date : 2026-09-11 — Session autonome.

---

## 1 — La vérification exigée avant d'écrire la moindre requête

La ligne F-61 pose une condition suspensive : *« `agent_*_tokens` est un compteur de **session**
d'agent (migration `040-workspaces-agent-session`), susceptible d'être remis à zéro ; si c'est le
cas, la source de vérité du relevé doit être les **tours persistés**, pas ce compteur — à vérifier
avant d'écrire la moindre requête, sous peine d'afficher des totaux qui rétrécissent. »*

### Ce que j'ai constaté

**Le soupçon est fondé, et plus net que « susceptible » : la remise à zéro est écrite, explicite et
systématique.**

1. `backend/src/main/resources/db/changelog/migrations/040-workspaces-agent-session.xml` documente
   lui-même le rôle de ces colonnes : *« Les compteurs `agent_*_tokens` / `agent_active_seconds`
   mémorisent le **DERNIER RELEVÉ** d'usage »*. Ce n'est pas un cumul de projet : c'est un
   **repère de delta**.
2. `AtelierSessionService.markSessionOpened()` les **remet à zéro à chaque ouverture de session** :
   ```java
   workspace.setAgentInputTokens(0L);
   workspace.setAgentOutputTokens(0L);
   workspace.setAgentActiveSeconds(0L);
   workspace.setAgentListCost(0L);
   ```
3. `AtelierSessionService.recordSessionUsage()` y **écrase** ensuite le cumul rendu par le
   fournisseur (`setAgentInputTokens(usage.inputTokens())`) — la colonne ne s'incrémente jamais,
   elle **suit** un compteur externe qui repart de zéro avec chaque sandbox.

**Conséquence si on l'agrégeait** : exactement le scénario redouté. Un projet ayant consommé 40 M de
tokens sur trois sessions afficherait la consommation de la **dernière** — et le lendemain, sandbox
recyclée, **zéro**. Un consultant verrait la consommation d'un client **baisser toute seule**, et
refacturerait moins qu'il n'a dépensé. La colonne n'est pas insuffisante : elle est **fausse pour
cet usage**.

### Ce que j'ai retenu — et l'ennui trouvé en chemin

La ligne F-61 supposait deux choses : que la donnée était « déjà là » par projet, et qu'il existait
des **tours persistés** vers lesquels se rabattre. La première est fausse (ci-dessus). **La seconde
l'est à moitié** :

| Source candidate | Grain | Monotone ? | Exploitable ? |
|---|---|---|---|
| `workspaces.agent_*_tokens` (040) | projet | **Non** — remis à zéro | ❌ faux pour l'agrégation |
| `usage_counters` (009, F-10) | **utilisateur × mois** | Oui, cumulatif | ✅ **mais aucune dimension projet/poste** |
| `atelier_messages.terminal_json` (041) — `AtelierTurnReport` | tour | Oui (append) | ❌ **JSON d'affichage**, jamais requêté, et il **porte la transcription du tour** |

Le « relevé de tour » existe donc bien — `AtelierTurnReport(inputTokens, outputTokens,
activeSeconds, …)` est écrit à chaque tour — mais il est rangé dans une colonne **d'affichage**, en
JSON, **aux côtés du contenu des messages et des commandes**. L'agréger voudrait dire parser du JSON
en SQL et, surtout, faire passer une requête de facturation **au travers des contenus** — ce que la
limite non négociable de la feature interdit précisément (§3).

**Décision retenue** : la source de vérité de la vue par client est un **journal de consommation par
tour**, table neuve `usage_turns`, **append-only**, écrite aux points où la consommation est
**déjà** décomptée, et qui ne porte **que des volumes** — aucun texte, aucune commande, aucun
chemin. C'est la lecture littérale de « la source de vérité doit être les tours persistés » : les
tours *sont* persistés, mais dans un format qui ne se lit pas ; F-61 les persiste **aussi** sous
forme de mesure. Pour le volet **admin**, la source reste `usage_counters` (F-10) : monotone,
référence de facturation, et elle porte déjà le grain mensuel que demande « l'évolution sur une
période choisie ».

**Contrepartie assumée et écrite à l'écran** : le journal commence le jour de sa mise en service. La
consommation antérieure **existe** (dans `usage_counters`, au grain utilisateur) mais n'est
**attribuable à aucun client** — personne n'a jamais rangé cette information. Inventer une
ventilation rétroactive serait fabriquer un chiffre de refacturation, c'est-à-dire le pire mensonge
possible ici. L'écran le dit, il ne le masque pas.

---

## 2 — Ce que F-61 fait

Deux publics, **une** agrégation, deux angles de lecture :

1. **Pour l'utilisateur** — ce qu'il consomme **par client** (par **poste**, `runner_hosts`), et par
   **projet** dessous (`workspaces`). L'usage est la **refacturation** : savoir quelle mission coûte.
2. **Pour l'admin** (rôle `ADMIN`, sans exception) — **par utilisateur** : tokens **d'entrée** et
   **de sortie distingués** (leurs coûts unitaires n'ont rien à voir : 5 €/M contre 25 €/M), coût
   estimé, **part du total**, plan, et **évolution sur une période choisie**.

## 3 — La limite non négociable

**Ces écrans montrent des VOLUMES et des COÛTS, jamais des CONTENUS.** Ni message, ni commande, ni
chemin de fichier, ni nom de branche. *Un administrateur qui pourrait lire les conversations de ses
utilisateurs ferait de la gateway un outil de surveillance* — ce qu'elle n'est pas.

Traduction en contraintes vérifiables, tenues par la structure plutôt que par la discipline :

- La table `usage_turns` **n'a aucune colonne de texte libre**. Il n'y a rien à divulguer parce
  qu'il n'y a rien de rangé — seule garantie qui survive à la prochaine feature.
- Les réponses admin portent : identité du compte (e-mail, déjà exposé par `GET /admin/users`),
  plan, volumes, coût. **Aucun nom de projet, aucun nom de poste** — le nom d'une mission (« refonte
  paie Groupe X ») est une information **du client de l'utilisateur**, pas de la plateforme.
- Les noms de poste et de projet n'apparaissent que dans la vue **de l'utilisateur lui-même**, sur
  ses **propres** données, filtrées `user_id`.

## 4 — Hors périmètre (repris de `PRODUCT_SPEC.md`, non négociable)

- **Agir sur les quotas** depuis ces écrans : aucun bouton, aucune route d'écriture.
- **L'export comptable** (CSV/PDF).
- **L'édition de factures** — refacturer reste le métier de l'utilisateur.
- Ventilation par modèle : le journal comme `usage_counters` agrègent entrée/sortie sans modèle
  servi ; le coût reste une **estimation** au tarif configuré (`app.usage.report.*`), comme F-16.

---

## 5 — Découpage

| SF | Titre | Portée |
|----|-------|--------|
| SF-61-01 | Le relevé par tour, source de vérité | Backend — table `usage_turns`, écriture aux points de décompte |
| SF-61-02 | Ce que chaque client consomme | Backend — `GET /usage/by-client` |
| SF-61-03 | Ce que chaque utilisateur consomme | Backend — `GET /admin/usage` |
| SF-61-04 | L'écran « par client » | Frontend — section de `/reports` |
| SF-61-05 | L'écran admin de consommation | Frontend — section de `/admin` |

---

## 6 — Arbitrages

### A-1 — Une table neuve plutôt que la colonne existante ou le JSON d'affichage
**Décidé** : `usage_turns`, append-only, volumes seuls.
**Pourquoi** : la colonne 040 rétrécit (§1) ; le JSON 041 mélange mesure et **contenu**, et parser du
JSON en SQL pour facturer serait à la fois fragile et contraire à la limite du §3.
**Alternative écartée** : ajouter `input_tokens`/`output_tokens` en colonnes sur `atelier_messages`.
Rejetée : elle lierait le relevé de consommation au **message**, donc au contenu, et ne couvrirait
ni `/chat` ni `/ask`, qui consomment aussi.
**Réversible** : oui — `dropTable`, aucune donnée existante touchée.

### A-2 — Le poste est figé à l'écriture (instantané), pas résolu à la lecture
**Décidé** : le journal range `host_id` **tel qu'il était au moment du tour**.
**Pourquoi** : c'est une pièce de refacturation. Déplacer un projet d'un poste à l'autre ne doit pas
déplacer rétroactivement une dépense **déjà refacturée** à un client.
**Alternative écartée** : joindre `workspaces.host_id` à la lecture — plus simple, mais réécrit
l'histoire à chaque déplacement de projet.
**Réversible** : oui (colonne).

### A-3 — Ce qui n'appartient à aucun poste est montré, pas caché
**Décidé** : un seau **« Hors client »** regroupe les tours sans poste (projets en bac à sable,
`/chat`, `/ask`).
**Pourquoi** : la somme des clients doit **se réconcilier** avec le total de la période. Un écran de
refacturation dont les lignes ne font pas le total n'est pas utilisable.
**Réversible** : oui (présentation).

### A-4 — L'admin lit `usage_counters`, pas le journal
**Décidé** : la vue admin agrège `usage_counters` (F-10).
**Pourquoi** : monotone, référence de quota et de facturation, grain mensuel déjà présent, et
**aucune dimension projet** — donc rien à divulguer par construction (§3). Le journal, lui, connaît
les projets : une vue admin bâtie dessus serait un pas vers la surveillance.
**Réversible** : oui.

### A-5 — Deux PR (backend puis frontend) pour cinq subfeatures
**Décidé** : PR 1 = SF-61-01→03, PR 2 = SF-61-04→05.
**Pourquoi** : les trois subfeatures backend partagent une table neuve et un estimateur de coût
extrait ; les séparer produirait deux PR dont les tests ne compilent pas seuls. Le backend est
mergé **avant** le frontend, conformément à la séquence.
**Réversible** : oui (organisation de livraison).

### A-6 — Fenêtre de période bornée à 24 mois, exprimée en mois
**Décidé** : `from`/`to` sont des **premiers de mois** (UTC), fenêtre par défaut 12 mois, plafond dur
24 mois ; hors bornes → `400`.
**Pourquoi** : `usage_counters` a le mois pour grain — offrir un intervalle au jour laisserait croire
à une précision qui n'existe pas. Le plafond protège une console admin qui lit **tous** les comptes.
**Réversible** : oui (constante de service).
