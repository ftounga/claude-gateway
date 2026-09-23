# Cadrage F-149 — Déléguer l'audit lourd, politique de modèle, et réparer l'activation

> Né de l'analyse d'un tour réel : le 2026-09-23, une **revue du repo « ossature » de Daoud** sur le
> sujet **agenor** (CAGIP) a coûté **9,52 € en un seul tour** (Opus 5, **4,32 M tokens d'entrée**, 22
> outils). Le raisonnement était **bon** (il a trouvé un défaut grave : `dev/config.tfbackend` pointant
> encore sur l'état Terraform de `data-ingestion` → un `apply` l'écraserait). Mais le **coût n'était pas
> optimal**, pour des raisons identifiables — d'où ce cadrage.
>
> **Règle cadre :** aucun changement d'infra, aucun composant cluster ; ne pas toucher la discipline
> « lire avant d'agir » (F-119) ; préserver le cache de prompt (F-134).

## Constat 1 — l'audit lourd s'est fait en boucle principale, pas en sous-agent
`explore` (sous-boucle lecture-seule, F-39 ; parallèle SF-39-21) **n'a pas `bash`** — son jeu d'outils
est `read_file / list_files / search_files / grep / glob` (`AtelierChatService.READ_ONLY_TOOLS`). Or
l'agent audite un repo avec **bash** (usage réel à 95 % bash). Il **ne pouvait donc pas déléguer** :
il a lu 19 fichiers **en boucle principale**, chacun **restant dans le contexte** et **renvoyé à
chacun des ~22 allers-retours** → 4,32 M tokens d'entrée, dont **0,83 M réécrits en cache** (la part
chère). Une délégation aurait gardé ces lectures **hors** du contexte principal (seule la conclusion
remonte).

## Constat 2 — modèle unique (Opus 5) pour tout
Le tour tourne sur **Opus 5** (le plus cher). Une revue de repo « routine » n'a pas toujours besoin
d'Opus ; **Sonnet** coûte ~5× moins pour ce type de lecture/synthèse. `AIProvider` (Provider
Independence) permet déjà de router — il manque la **politique**.

## Constat 3 (DÉFAUT confirmé) — une activation n'injecte plus rien
L'injection des règles d'un paquet est conditionnée à **`applied_at != null`** (`deposited()`,
`GovernanceActivationService.java:271`, introduit par F-135). **Vérifié :** aucun code actuel n'écrit
`applied_at` (grep exhaustif de `backend/src/main/java` : seulement des lectures ; `GovernanceDepositService`
écrit des *fichiers*, pas ce champ). En prod : **une seule** activation a `applied_at` rempli (un host
CAGIP, posé le 13/09 par du code **antérieur**, F-51) ; les autres à `null`. **C'est une régression** :
F-135 a posé la barrière `applied_at` sans conserver le code qui pose `applied_at` après un dépôt réussi.
- **Impact profils (F-138) :** un profil **n'a aucun fichier** → `applied_at` jamais posé → **il
  n'injecte jamais**. Activer un profil aujourd'hui = **aucun effet** (faux test).
- **Impact carte (F-136/F-137) :** sur tout poste activé après la régression, `savoir-durable` **n'injecte
  pas** non plus. Explique en partie « la carte sert à peine » (audit 21/09).

## Découpage — 3 subfeatures

**SF-149-01 — Réparer l'application d'une activation (prérequis, DÉFAUT)** *(priorité 1)*
- Poser `applied_at` (et le statut `APPLIED`) quand un dépôt aboutit, **et** traiter le cas d'un paquet
  **sans fichier** (un profil) comme **appliqué immédiatement** (rien à déposer = rien qui manque).
- Où : `GovernanceDepositService.deposit` (dépôt fichiers → marquer l'activation) et le chemin d'activation
  d'un paquet sans fichier (`GovernanceActivationService.activate` / la voie F-135 `remember`).
- **Falsifier** (mémoire 1re cause ≠ seule cause) : confirmer les DEUX cas — paquet avec fichiers (dépôt
  réussi → `applied_at`) et paquet sans fichier (profil → `applied_at` posé sans dépôt). Vérifier qu'on
  ne régresse pas F-135 (une activation dont le dépôt échoue **reste** non appliquée).
- Tests : profil activé → `deposited()` vrai → règle injectée ; savoir-durable déposé → `applied_at` posé ;
  dépôt échoué → toujours `null` ; isolation `user_id`+`host_id`. **Non-régression** de `activeOn`/F-135.
- Effet : les profils **et** la carte injectent réellement. **Sans cette SF, F-138 et une partie de
  F-136/137 sont inertes.**

**SF-149-02 — Déléguer l'audit/lecture lourde de dépôt (lever n°1)** *(étend F-39/SF-39-22)*
- Objectif : qu'une **revue/audit de repo** se fasse en **sous-agent** dont les lectures ne polluent pas
  le contexte principal. Deux voies à trancher dans la mini-spec :
  - (a) **Doctrine** : pour lire/auditer un dépôt, **préférer `read_file`/`grep`/`glob`** (que `explore`
    possède) à `bash cat/find/grep`, afin de pouvoir **déléguer à `explore`**. Prompt only, cache stable.
  - (b) **`explore` avec bash en lecture seule** (un bash restreint aux commandes de lecture) — **DRAPEAU
    risque** : garantir « lecture seule » sur bash est difficile (c'est justement pourquoi `explore` n'a
    pas bash) ; à ne faire que si (a) ne suffit pas, et avec bornes strictes. Par défaut : **(a)**.
- Effet : un audit de repo comme celui de Daoud remonterait la **conclusion** sans traîner 19 fichiers ×
  22 tours → coût divisé (le cache écrit — la part chère — s'effondre).
- Tests : doctrine présente (catalogue/prompt), cache stable.

**SF-149-03 — Politique de modèle (lever n°2)** *(net-neuf, décision)*
- Router le modèle selon la phase/tâche : **Opus** pour le raisonnement dur (décision, correction,
  synthèse d'architecture), **Sonnet** pour l'exploration/la revue de routine. Réutilise `AIProvider`
  (Provider Independence) ; réglable, avec repli sûr sur Opus.
- **Décision produit** (à valider) : quels déclencheurs (la sous-boucle `explore` en Sonnet d'office ?
  un modèle par phase ? un choix par poste ?). Mesurer l'effet coût/qualité avant de généraliser.
- Effet : les tours d'exploration/revue coûtent ~5× moins sans dégrader le raisonnement dur.
- Tests : routage appliqué, repli sur Opus, aucune régression de la boucle.

## Séquencement
1. **SF-149-01** d'abord — sans elle, activer un profil ne teste rien (et la carte n'injecte pas).
2. **SF-149-02** — le gros levier de coût sur les audits de repo.
3. **SF-149-03** — la politique de modèle, mesurée.

## Hors périmètre
- Donner des droits d'écriture au sous-agent ; moteur de raisonnement maison ; refonte Agent SDK ;
  embeddings (réserve F-148) ; tout composant cluster.

## Préoccupations transversales
- **Gouvernance/activation** (SF-149-01) : lister les appels à `activeOn`/`deposited` et vérifier la
  non-régression F-135 (dépôt échoué ≠ appliqué).
- **Cache de prompt** (SF-149-02) : doctrine = littéral stable dans le préfixe.
- **Plans/limites & Provider Independence** (SF-149-03) : le routage passe par `AIProvider`, jamais un
  couplage direct à un modèle.
