# Audit du 2026-10-06 — Terminal central, gouvernance, mode guidé, attentes, parité Claude Code

> Demande du PO le 2026-10-06 (dictée, condensée) : *« J'ai rajouté de la gouvernance, est-ce bien
> pris en compte ? Est-ce que depuis le terminal central je peux lui demander de mettre en place une
> gouvernance, de créer des skills propres à un client ? — Le mode guidé : je clique sur des options
> mais je ne sais pas comment passer à l'étape suivante ; à la fin, le bloc ne disparaît pas, et une
> porte fermée l'a empêché de faire `aws sso login`. — Les actions à faire : j'adore, comment aller
> plus loin (tracer les accès, débloquer plus vite) ? — Le recall : je veux que le terminal central
> puisse interroger l'historique de toutes les conversations, qu'il soit conscient de presque toute
> l'application. — Nouveau sujet : quand je dis go, qu'il ouvre lui-même le terminal du sujet avec la
> phrase déjà dans la saisie. — Parité Claude Code : hooks, workflows, skills, sous-agents… à notre
> sauce. »*
>
> Méthode : lecture du code `origin/main` (`de9213b0`) + requêtes **en lecture seule** sur la base de
> prod (transaction `READ ONLY`). Prod = images `staging-a7a3bcb9` (F-175 et F-176 inclus).

## 1. La gouvernance ajoutée le 06/10 — prise en compte ? **Partiellement, et par chance**

**Ce qui s'est passé** (prod, terminal « Terminal du poste », 06/10 09:26) : le PO demande
*« insère dans la gouvernance que les commentaires doivent être courts »*. L'agent répond (09:31)
*« j'ai ajouté la règle des commentaires Jira dans `GOUVERNANCE.md` »* — il a **écrit un fichier sur
le poste** et appliqué la règle **dans ce tour**, parce qu'elle était dans la conversation.

**Ce qui n'est pas garanti ensuite** :

| Fait | Preuve |
|---|---|
| `GOUVERNANCE.md` n'est **jamais injecté** dans la consigne ni dans le message. Seuls `CLAUDE.md`, `STATE.md`, `PLAN-ACTION.md` et le catalogue de skills le sont. | `AtelierChatService.java:793, 3227, 7532` ; `prompt_source_files` (7 j) ne contient aucun `GOUVERNANCE.md` ; `regles.md` du paquet `savoir-durable` ne le cite pas |
| La règle ne s'appliquera dans un autre sujet (ou après compaction) **que si l'agent pense à relire ce fichier**. | idem |
| Aucun élément de gouvernance « applicatif » n'a été créé/modifié depuis 7 jours (paquets, activations, dépôts). | `governance_*` |
| EDENRED : `savoir-durable` activé mais **jamais déposé** (`applied_at` nul) → règles non injectées. CAGIP : fichiers déposés en v2/4/6, paquet en **v17**. | `governance_host_activations`, `governance_deposited_files` |

**Depuis le terminal, l'agent ne peut créer aucun élément de gouvernance versionné** : aucun outil
de gouvernance dans le catalogue de la boucle ; le serveur MCP F-112 n'expose que `gouvernance_etat`
(lecture). Il peut écrire un `SKILL.md` avec `write_file` — annoncé au tour suivant, mais **hors
circuit** (ni version, ni portée, ni visibilité à l'écran). Les paquets applicatifs sont **globaux et
réservés à l'ADMIN** (pas de `user_id`, pas d'auteur). → **F-177**.

## 2. Le mode guidé (F-176) — trois défauts prouvés en prod

Prod : une seule ligne `subject_journeys` (`data-platform`, `GUIDE / CLOS`, plan v5, clos le 05/10 18:59).

| # | Défaut | Cause | Preuve |
|---|---|---|---|
| G1 | « J'ai cliqué, et maintenant ? » | Les gestes [Planifier] [Valider le plan] [Valider l'amendement] **changent l'état sans relancer de tour** ; l'agent avait fini son tour sur « attends son clic » ; aucun texte ne dit qu'il faut écrire. Deux règles cohabitent (carte `demander` = cocher puis [Envoyer] qui reprend le tour). | `atelier-terminal.component.ts:740-775`, `SubjectJourneyService.java:229-241, 461-485`, `JourneyGate.java:40-45`. Prod 05/10 : après **chaque** clic, le PO tape « go » / « Valide le plan v1 » dans les 10–70 s (5 fois). |
| G2 | Le bloc reste après la clôture | Le parcours est lié au **terminal** ; `close()` met `phase=CLOS` mais laisse `mode=GUIDE` ; le front affiche la bande tant que `mode==='GUIDE' && phase`. | `SubjectJourneyService.java:351-361`, `terminal-journey-strip.component.ts:381` |
| G3 | « Porte fermée » sur `aws sso login` | En Guidé, toute action ni `LECTURE` ni `NOTES` exige la phase Exécution ; en `CLOS`, tout est refusé **indéfiniment** et l'agent ne peut pas rouvrir (`setPlan`/`reopen` refusent `CLOS`). `aws sso login` → `EXTERNAL`. Le front **masque** le verrou en `CLOS`. Des lectures inconnues sont aussi classées « modification ». | `JourneyGate.java:26-35, 50-51`, `JourneyRiskClassifier.java:354-377`, strip `:408`. Prod : **11 refus `GATE_BLOCKED` après la clôture** (5 le 05/10, 6 le 06/10 sur un autre sujet : push S3 `cft-in`), dont « INCOMPLET… la porte du sujet clos m'empêche de lire les journaux de flux ». |

**Contournement immédiat** : passer `data-platform` en **Libre** via la puce de l'en-tête.
→ **Rouverture F-176 (SF-176-07 → 10)**.

## 3. Les attentes (F-175) — mesures prod

37 attentes (16 À faire, 4 Demandé, 13 Fait, 4 Annulé), 5 terminaux, 1 poste.
- **Accès = 51 %** des attentes (19) et **12 des 20 ouvertes**, durée moyenne 4,3 j ; validations et transmissions se ferment le jour même.
- **Une personne porte 11 des 20 attentes ouvertes** (noms saisis en texte libre, variantes « NOM Prénom » / « Prénom NOM »).
- Champs vides : `subject_id` 0/37, `channel` 1/37, `requested_at` 4/37 ; `review_pending` encore vrai pour 12 (reprise jamais validée) ; 9 sans embedding.
- Doublons restants : le dédoublonnage ignore les **Fait** (compte forge CAPFM fait ↔ à faire, distance 0,118) ; un même accès éclaté en 4 lignes.
- Abonnements push F-153 : **0** ; Radar : 0 engagement, 0 personne.
→ **F-180**.

## 4. Le terminal central — ce qu'il voit

| Donnée | Lisible au poste aujourd'hui ? |
|---|---|
| Son propre fil | Oui (`recall`) |
| **Fils des sujets** | **Non** — `recall` filtré `workspace_id` ; seules les résolutions F-148 (par poste) passent d'office |
| Fichiers des sujets (STATE, PLAN, code) | Oui, par `bash` à la racine — **c'est ainsi qu'il a « retrouvé » tickets et commentaires** (traces écrites, pas conversations) |
| Attentes F-175 du poste | Oui (jointes au tour) |
| Carte F-173/174 | Oui |
| Bilans de session F-155 | Non (écran admin) |
| Radar, Teams, réunions | Non (terminal Teams uniquement) |
| Coûts, pages publiées | Non (outils MCP F-112 `compte_consommation`, `page_lire` non branchés sur la boucle) |

Le serveur MCP F-112 n'est branché que pour une IA externe ; ses outils de lecture sont réutilisables
via un adaptateur. → **F-178**.

## 5. Passation vers un nouveau sujet

`create_subject` crée le workspace mais ne rend pas son `id` ; la passation SF-141-06 propose une phrase
à coller. **Le pré-remplissage non envoyé existe déjà** (`RADAR_DRAFT_STATE`, `atelier.component.ts:814-826`)
et la route est unique (`atelier/:id`). SF-141-06 avait explicitement reporté l'ouverture automatique.
→ **F-179**.

## 6. Parité Claude Code — ce qui manque vraiment

Déjà livré (ne pas reproposer) : skills à chargement paresseux (≤ 15), `explore`/`task` (worktree),
plan mode, todo, `demander`, `recall`, compaction, commandes slash (catalogue fermé), permissions par
préfixe, bash en fond, recherche web, MCP serveur, notifications push.

| Capacité Claude Code | Chez nous | Feature |
|---|---|---|
| Hooks (PreToolUse/PostToolUse/Stop/SessionStart…) | 3 points fermés, code serveur, juger/bloquer seulement | **F-181** |
| Permissions par motif (jokers, chemins) | Préfixe de commande seulement | **F-181** |
| Skill invoqué par `/nom`, créé par l'utilisateur | Non (catalogue slash fermé, pas d'outil `skill`) | **F-177** |
| Sous-agents personnalisés (`.claude/agents`) | Non (`explore`/`task` génériques) | **F-182** |
| Routines programmées (`/loop`, `/schedule`) | Non côté utilisateur | **F-183** |
| Checkpoints / rewind | Non | réserve |
| Monitor (réveil sur fin de commande) | Partiel (`bash_output` à relancer) | réserve |
| Workflows multi-agents orchestrés | Non | réserve, après F-181/F-182 |
| Client MCP (consommer des serveurs externes) | Non | **décision PO** (périmètre) |

## 7. Divers (sécurité)

Deux pods de requête laissés dans le namespace de prod (`pgq-11960`, `pgq-30712`, statut Completed,
10 et 19 jours) portent le mot de passe de la base **en clair dans leur spec**. À supprimer, et
rotation du mot de passe à envisager.

## 8. Ordre recommandé

1. **F-176 rouverture** — bug bloquant en prod (porte fermée) + ergonomie.
2. **F-179** — petit, gain quotidien.
3. **F-177** — SF-177-01 est un gain immédiat (la règle écrite s'applique partout).
4. **F-178** — le poste devient central.
5. **F-180** — les accès et les interlocuteurs.
6. **F-181** — crochets et permissions par motif.
7. **F-182**, **F-183**.
