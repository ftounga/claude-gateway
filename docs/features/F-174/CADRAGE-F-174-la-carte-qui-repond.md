# Cadrage — F-174 La carte qui répond

> Cadrage PO le 2026-10-04 (« livre et déploie les 2 en autonome », F-173 et F-174).
> Source de vérité produit : `docs/PROJECT.md`. Subordonné à `CLAUDE.md`.
> Livrée **avant** F-173, dont elle fournit l'index.

## 1. Le constat (mesuré en prod le 2026-10-04)

La carte du poste (F-92 et suivantes) est devenue la mémoire d'infrastructure la plus riche de
l'application. Le raisonnement de l'agent n'en voit pourtant presque rien.

| Mesure | Valeur |
|---|---|
| Carte du poste Corporate Center (CA-GIP), table `host_map_files` | **≈ 5 100 faits, ≈ 480 Ko** sur 6 fichiers (`plateformes.md` 113 Ko, `exploitation.md` 110 Ko, `acces.md` 90 Ko…) |
| Injecté à chaque tour (F-136 + F-137) | Titres de sections (12 max par fichier) + **12 faits / 3 000 caractères au plus**, choisis par mots-clés (`HostFactLookup`) |
| Appels d'outils de l'agent sur la carte en 30 jours (`runner_audit`) | **550 `bash`** (grep/sed sur les fichiers de carte) + 48 `read_file` + 72 `edit_file`, sur 11 171 appels au total |
| Lectures de la carte par la gateway en 30 jours (`governance_map_read`) | 3 589 lectures, **197 Mo** transférés depuis le poste |

Contenu réel de la carte : 18 comptes AWS (domaine × environnement), clusters EKS, 13 dépôts,
2 forges, registres, domaines DNS joignables ou non, proxy Netskope, qui accorde quel accès,
pièges, jetons qui expirent (« ce jeton périme le 2026-10-09 »), listes « ce qui reste à
cartographier ». Les fichiers accumulent aussi des corrections successives (deux sections « cause
réelle » différentes sur le même sujet).

**Conséquence** : l'agent doit fouiller la carte en `bash` pour retrouver un fait. Quand il ne le
fait pas, il suppose. C'est l'une des causes du défaut connu « il suppose au lieu de vérifier ».

## 2. Objectif

Faire de la carte une mémoire **interrogeable** : l'agent reçoit les faits pertinents (identifiants
exacts, sens de la question, pièges, échéances) et dispose d'un outil précis pour la questionner,
au lieu de relire ou de fouiller 480 Ko de texte.

Règle absolue « justesse avant coût » : **rien n'est retiré** de ce que l'agent reçoit aujourd'hui.
On ajoute de la précision, et chaque gain est mesuré.

## 3. Décisions (prises par défaut, réversibles, tracées)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Le texte Markdown reste la référence.** L'index est une lecture dérivée, reconstructible à tout moment. Rien n'est jamais réécrit sur le poste sans geste de l'utilisateur. | Pas de second dépôt de vérité ; cohérent avec D5 de F-166 (pas d'auto-push). |
| D2 | **Extraction en deux couches.** (a) Déterministe : identifiants exacts par motifs (compte AWS à 12 chiffres, ARN, nom d'hôte et domaine, URL, dépôt `groupe/projet`, IP/CIDR, date « constaté le »). (b) Sémantique : Claude, via l'interface `AIProvider`, en sortie structurée, extrait entités, relations, état et pièges. | Provider-First (comprendre un texte = capacité de Claude) ; la couche (a) garantit l'exactitude des identifiants. |
| D3 | **Extraction incrémentale par section** : empreinte par section (titre `##`), seule une section modifiée est ré-extraite. Asynchrone, déclenchée par le rafraîchissement existant de `host_map_files` (`HostMapStore`, digest). | La carte change après presque chaque tour : tout ré-extraire coûterait ≈ 120 k tokens à chaque fois. |
| D4 | Modèle d'extraction `APP_MAP_INDEX_MODEL`, défaut **`claude-sonnet-5-5`**. Coupe-circuit `APP_MAP_INDEX_ENABLED` (défaut `true`). | Extraction = lecture fidèle, pas de raisonnement long ; Sonnet suffit, Opus reste possible par config. |
| D5 | **Recherche hybride** pour les faits du tour : identifiant exact > entité nommée > similarité sémantique (embeddings pgvector, même fournisseur que F-162, ADR-011) > lexical actuel en dernier recours. Repli intégral sur `HostFactLookup` si l'index est absent ou en panne. | Ne jamais faire moins bien qu'aujourd'hui. |
| D6 | Bornes du bloc de faits relevées : **20 faits / 6 000 caractères** (config). Les faits restent **dans le message du tour**, jamais dans le bloc système. | Plus de justesse ; préfixe stable préservé (F-171). |
| D7 | **Pièges et échéances en priorité** : un piège rattaché à une entité touchée par la question passe avant les autres faits ; une échéance à ≤ 14 jours sur une entité touchée est toujours citée. | Les pièges sont exactement ce que l'agent ignore quand il suppose. |
| D8 | **Outil serveur `carte_chercher`** (requête libre, type d'entité, identifiant) exécuté par la gateway sur l'index et la copie `host_map_files`, sans aller-retour poste. Rend des faits sourcés (fichier, section, date). La doctrine invite à l'utiliser avant de fouiller en `bash`. `read_file` / `bash` restent permis. | Remplace les 550 fouilles `bash` par une réponse précise ; aucune capacité retirée. |
| D9 | **Consolidation proposée, jamais appliquée seule** : détection de doublons et de contradictions par section, rendue comme propositions consultables (API), appliquées uniquement via un tour de la Forge déclenché par l'utilisateur. | D1 ; l'écran F-173 les affiche. |
| D10 | Isolation : toutes les nouvelles tables portent `user_id` + `host_id` ; tout accès filtre `user_id`. Purge à la suppression du compte (`AccountService`). | Règle multi-tenant. |

## 4. Découpage

| SF | Titre | Contenu | Dépend de |
|---|---|---|---|
| SF-174-01 | La mesure de départ | Journal par tour : faits injectés (nombre, caractères, sources), fouilles de la carte par l'agent (`bash`/`read_file` sur un chemin de carte), appels `carte_chercher` (plus tard). Requête de référence documentée. | — |
| SF-174-02 | L'index de la carte | Tables `host_map_entities` (type, libellé, identifiants, attributs dont domaine/environnement/état joignable, fichier, section, date constatée, empreinte de section) et `host_map_relations` (de, vers, nature). Extraction D2 + D3, file asynchrone, rétro-remplissage des cartes existantes. | 01 |
| SF-174-03 | Le fait juste, retrouvé à coup sûr | Recherche hybride D5 + D6, embeddings par fait/section (pgvector), repli intégral sur `HostFactLookup`. | 02 |
| SF-174-04 | Les pièges et les échéances d'abord | Priorité D7 dans le bloc de faits du tour. | 03 |
| SF-174-05 | L'outil `carte_chercher` | D8 : définition d'outil stable, exécution serveur, doctrine mise à jour, comptage dans le journal 01. | 03 |
| SF-174-06 | La carte se tient | D9 : détection doublons/contradictions/faits périmés par section, propositions exposées en API (consommées par F-173). | 02 |
| SF-174-07 | La mesure d'après | Comparaison 01 avant/après sur 7 jours : fouilles `bash` de la carte, faits injectés pertinents, relances du PO ; seuils de retour arrière (coupe-circuit D4). | 01→06 |

## 5. Hors périmètre

- Découverte automatique d'infrastructure par le runner (sondage kubectl, réseau…) : autre feature.
- Écriture automatique de la carte sur le poste (D1).
- L'écran : F-173.
- Le rappel de l'historique de conversation (F-162) reste inchangé.

## 6. Risques

- **Coût d'extraction** : rétro-remplissage ≈ 120 k tokens en entrée pour la carte CA-GIP, puis
  quelques sections par tour. Suivi dans le journal ; coupe-circuit D4.
- **Extraction fausse** : D2(a) garantit les identifiants ; chaque entité garde fichier + section
  pour revenir au texte ; l'agent reste invité à revérifier (doctrine « la carte est un pointeur »).
- **Préfixe de cache** : la définition de `carte_chercher` est fixe ; aucun contenu variable dans
  le bloc système.
