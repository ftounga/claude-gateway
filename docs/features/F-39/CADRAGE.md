# Cadrage — F-39 · L'Atelier comme harnais

**Date** : 2026-09-06 · **Statut** : cadré, découpage à valider
**Origine** : `docs/features/F-28/AUDIT-parite-claude-code.md` + audit de l'**usage réel** (§2)

---

## 1. Pourquoi cette feature existe

### 1.1 Ce qui s'est passé, en trois temps

Aucune étape n'a été une erreur isolée ; c'est leur enchaînement qui a produit l'écart.

| Quand | Fait | Conséquence |
|---|---|---|
| **Juillet 2026** | La Phase 1 de l'Atelier n'exécute rien. Pour exécuter, la seule option existante est **Managed Agents** — il n'y a aucune machine à cibler. | Le mode « Exécution » naît comme *le seul moyen d'exécuter*, pas comme un choix. |
| **23 août, 21h49** | Demande explicite : « **En gros je veux claude code.** Peut-être sans ses skills natifs, ses commandes /toto, ni plugins **mais le comportement du terminal oui** ». Puis, à 21h56 : « je préfère garder les différents modes édition et exécution, **mais il faudrait les appeler autrement** ». | Le mot **« Terminal » est collé sur le moteur Managed Agents** existant, et habillé d'une vraie vue terminal (F-30). Aucun moteur n'est créé : un moteur est *renommé*. |
| **Fin août (F-38)** | Il faut exécuter sur la machine de l'utilisateur. Les Managed Agents en sont incapables (leur conteneur ne sort pas du périmètre du fournisseur — décision D2 de SF-38-05). L'exécution repasse sur la **boucle maison**. | Cette boucle était née **pour éditer des fichiers**, pas pour conduire un travail long. La question « est-elle à la hauteur d'un harnais ? » n'a jamais été reposée. |

**Le mot « terminal » a désigné deux choses** : un *comportement* (on voit les commandes, on voit les
retours, ça continue jusqu'à ce que ce soit fini) côté demandeur, et un *moteur* côté implémentation.
C'est ce dédoublement qui a tenu deux semaines sans être vu.

### 1.2 L'usage réel, mesuré

Les 6 sessions Claude Code de ce projet, 21 août → 5 septembre — transcrits, pas estimations :

| Mesure | Valeur | Ce que la boucle maison en fait |
|---|---|---|
| Appels d'outils | 1 714, dont **1 630 `bash` (95 %)** | `bash` n'existe qu'en cible `RUNNER` |
| Outils par demande | médiane 6, moyenne **13,8**, max **125** | plafond porté à 30 (SF-28-19) |
| Demandes > 12 outils | **31 %** | étaient coupées en chemin |
| Durée d'une session | **2 à 5 jours** | aucune notion de session |
| Contexte max | **900 519 tokens** | historique rejoué en texte seul |

C'est le cahier des charges. Pas une inspiration : une mesure.

---

## 2. La cible

> **Dans l'Atelier, il n'y a qu'un terminal.** Le moteur qui l'anime est un détail d'implémentation
> que l'utilisateur n'a jamais à choisir.

| Situation du projet | Moteur retenu | Visible par l'utilisateur |
|---|---|---|
| Runner connecté | Boucle maison → outils relayés vers sa machine | « connecté à *poste-dev* » |
| Pas de runner | Managed Agents → bac à sable hébergé | « bac à sable hébergé » |

Le mot « Assistant » et le mot « Terminal » **disparaissent de l'interface** en tant que modes.

---

## 3. Décisions validées (2026-09-06)

**D1 — Un seul écran, moteur transparent.** Supprime le malentendu à sa racine : le nom cesse de
désigner un moteur.

**D2 — Ambition « au-delà de la parité » sur la boucle maison.** Mémoire, cache, compaction, retry,
puis liste de tâches et sous-agents. La boucle maison devient un harnais, pas un exécuteur d'outils.

**D3 — Le cache de prompt d'abord.** Sans `cache_control`, le coût d'un tour croît en **N²** :
chaque itération renvoie consigne système (jusqu'à 40 000 caractères), historique et outils au tarif
plein. Estimation sur 30 itérations : **~6,75 $ le message sans cache, ~1,27 $ avec**. C'est le
levier de rentabilité — pas les plafonds. Un plafond de dépense **par message**, affiché comme
l'est déjà le budget de session (F-36), viendra ensuite.

**D4 — Outillage `bash`-first.** Retrait de `list_files` et `search_files`, que `bash` fait mieux
(`ls`, `grep` avec expressions régulières et filtres). Conservation de `read_file` / `write_file`,
et ajout d'une lecture **numérotée et paginée** plus d'une **édition ciblée** — pas parce que
l'édition intégrale bloque (l'usage réel édite par `sed` et heredoc), mais parce qu'un prompt plus
court est un préfixe plus stable, donc mieux caché.

**D5 — La reprise est conservée par défaut, et proposée quand elle ne va pas de soi.** À la
réouverture d'un projet, le fil reprend là où il s'était arrêté, sans rien demander. L'utilisateur
n'est sollicité que lorsque la reprise n'est pas évidente — session du fournisseur expirée, projet
inactif depuis longtemps — et le choix est alors explicite : *reprendre le fil* ou *repartir à
neuf*. Un « nouveau départ » reste accessible à tout moment.
*Bénéfice de structure* : une fois la trajectoire d'outils tenue par nous (lot 2), la reprise cesse
de dépendre de la survie d'une sandbox chez le fournisseur.

**D6 — Le runner est le chemin *recommandé*, jamais le premier pas.** Ni « principal » ni « option
avancée » :
- **Premier projet** : bac à sable, zéro installation, valeur immédiate. Exiger un `.jar` avant
  d'avoir rien montré tuerait la conversion.
- **Au moment où le bac à sable devient la limite** — l'utilisateur veut ses vraies variables
  d'environnement, sa base, son cluster, ou dépasse les 300 fichiers montés — l'interface propose le
  runner **là, dans le contexte du besoin**, pas à l'inscription.
- **Projets Git** : le runner est proposé dès l'ouverture, le clone local ayant tout son sens.

Effet de marge assumé : le bac à sable nous coûte 0,08 $/h, le runner ne coûte rien — c'est la
machine du client. Pousser le runner améliore la marge, mais seulement une fois l'utilisateur acquis.

**D7 — Le chat sur fichiers sans exécution quitte l'Atelier.** Il reste pertinent dans le menu
**Chat** (F-02), où il est à sa place. Dans l'Atelier, il n'y a que le terminal.
*Conséquence à traiter* : la cible `SANDBOX` de la boucle maison (`executeToolOnStorage`) n'a plus
d'usage produit. Son retrait est une subfeature de nettoyage à part, à décider — pas un effet de bord.

---

## 4. Acquis visuels — à préserver intégralement

**Non négociable.** Ces treize subfeatures de F-30 sont le résultat d'itérations explicites sur
l'apparence et le comportement du terminal. La refonte de l'écran (lot 4) les **reprend**, elle ne
les redécouvre pas. Toute régression ici est bloquante.

| Acquis | Origine | Ce qui doit survivre |
|---|---|---|
| Sortie des commandes relayée | SF-30-01 | La sortie, pas seulement la commande |
| Rendu terminal : commande **puis** sortie | SF-30-02 | « on voit l'en-tête des commandes qu'il lance et les retours » (23/08) |
| Terminal **immersif plein écran** | SF-30-07 | « la fenêtre passe en mode terminal », pas des blocs sombres dans un fil de chat (24/08) |
| Markdown **mis en forme** | SF-30-12 | Plus de `**`, `##` ni backticks bruts (29/08) |
| **Ligne vivante** pendant le tour | SF-30-13 | Ce que l'agent fait à l'instant, étapes derrière lui, consommation, durée — « un joli spinner, couplé à autre chose » (29/08) |
| Coût du tour affiché | SF-30-05 | Tokens et durée en fin de tour |
| Transcription conservée | SF-30-02 / SF-30-09 | Survit au rechargement de page |
| Mise en valeur **Gold** | SF-30-03 | Badge + accent orange de la charte |
| Réinitialiser l'environnement | SF-30-06 | Repartir d'un espace neuf |
| Aller de l'explorateur au terminal | SF-30-10 | Retour au projet qu'on quittait |
| Un tour ne lit que ses propres events | SF-30-11 | Pas de rejeu du tour précédent |
| Diagnostic d'erreur fournisseur | SF-30-08 | Crédit épuisé ≠ panne d'exécution |
| Session persistante | SF-30-04 | L'état survit d'un message à l'autre |

S'y ajoutent les acquis F-38 côté runner : porte de confirmation, journal d'audit, coupe-circuit,
interruption `Ctrl-C`, sélecteur de branche (F-31).

---

## 5. Découpage prévisionnel

L'ordre n'est pas négociable sur les trois premiers lots : le cache conditionne l'économie de tout
le reste, et la mémoire conditionne l'utilité.

| Lot | Subfeatures | Contenu |
|---|---|---|
| **1 · Cache** | SF-39-01 → 02 | `cache_control` sur consigne système, outils et préfixe d'historique · chargement **paresseux des skills** (descriptions seules, corps à la demande) pour rendre le préfixe stable |
| **2 · Mémoire** | SF-39-03 → 04 | Rejeu de la trajectoire (`tool_use` / `tool_result`), borné · reprise de fil indépendante de la sandbox (D5) |
| **3 · Outillage** | SF-39-05 → 06 | Retrait `list_files` / `search_files` · lecture numérotée et paginée · édition ciblée |
| **4 · Écran unique** ✅ | SF-39-07 → 09 | Fusion des deux modes · sélection transparente du moteur · **report intégral des acquis §4** — livré le 2026-09-06 (PR #226, #227, #228) ; le §4 est désormais **exécutable** (`frontend/src/app/atelier/terminal/acquis-f30.spec.ts`, un test par acquis) |
| **5 · Raisonnement** ✅ | SF-39-10 | `thinking` adaptatif, `effort`, `claude-opus-5` sur la boucle maison — livré le 2026-09-06 (PR #230) ; `effort` **reste à `high`** après le lot 6 : la boucle appelle toujours en **non-streamé**, et c'est le passage au flux — non le timeout — qui débloquerait `xhigh` |
| **6 · Tenue longue** ✅ | SF-39-11 → 12 | Délai HTTP câblé · réessai `429`/`529` avec `Retry-After`, gigue et budget d'attente borné · **édition de contexte** (`clear_tool_uses`) plutôt que compaction — livré le 2026-09-06 (PR #232, #233) ; le choix est tracé en **D-L6-7** : l'édition est sans état, quand la compaction imposerait de persister ses blocs, or la boucle reconstruit `messages` depuis l'historique à chaque message |
| **7 · Outillage d'agent** | SF-39-13 → 14 | Liste de tâches visible · sous-agents |
| **8 · Coût visible** | SF-39-15 | Plafond de dépense par message, affiché |
| **9 · Nettoyage** | SF-39-16 | Retrait de la cible `SANDBOX` de la boucle maison (D7), si décidé |

---

## 6. Hors périmètre

- Commandes `/`, plugins, marketplace — écartés explicitement dès le 23 août.
- Hooks.
- F-17 (espaces d'équipe) et F-18 (on-prem), qui restent V3.
- Le chat sur fichiers, qui **reste** dans le menu Chat sans modification (D7).

---

## 7. Risques

| Risque | Portée | Traitement |
|---|---|---|
| Régression visuelle sur les acquis F-30 | Élevée — c'est l'écran le plus itéré du produit | ~~Checklist de review~~ → **traité** : `acquis-f30.spec.ts` (SF-39-09, D-L4-8) porte un test par acquis. Une checklist protège une PR ; un test protège six mois de PR |
| Le cache ne prend pas (préfixe instable) | Le gain de coût s'évapore en silence | Vérifier `usage.cache_read_input_tokens` non nul dans un test d'intégration, pas seulement en production |
| Deux moteurs à maintenir malgré l'écran unique | Dette durable | Assumé : les Managed Agents restent le seul moyen d'exécuter sans installation |
| Sous-agents dans la boucle maison (lot 7) | Complexité forte, dépense multipliée | À re-cadrer à son tour ; le budget de session (F-36) doit le borner avant tout dev |
