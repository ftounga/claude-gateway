# Mini-spec — [F-110 / SF-110-07] Le filtre de secrets distingue un nom de paramètre d'une valeur

---

## Identifiant

`F-110 / SF-110-07`

## Feature parente

`F-110` — Le courriel du client — l'application m'envoie des courriels

## Statut

`ready`

## Date de création

2026-10-06

## Branche Git

`feat/SF-110-07-secret-nom-vs-valeur`

---

## Objectif

La règle « un mot de passe » du filtre de secrets des courriels ne bloque plus un **nom** de paramètre (`Token:KeyCrt`, `secret: machineKey`), un **masque** ou un **gabarit** (`********`, `${DB_PASSWORD}`). Elle continue de bloquer une **valeur** de secret (`password=Hunter2024!`).

---

## Constat (prod, 2026-10-06)

Sur le poste EdenRed, sujet `alarm4tech`, l'agent n'a pas pu envoyer `notes/messages/rotation-secrets.md` : des brouillons de **demande de rotation**, sans aucune valeur de secret. Le refus était « la pièce jointe … contient manifestement un mot de passe ».

La cause est la règle générique de `ClientMailSecrets` : `(password|pwd|mot de passe|mdp|secret|api key|token)\s*[:=]\s*\S{6,}`. Le texte `Token:KeyCrt` la déclenche, car `KeyCrt` fait exactement 6 caractères. La règle ne regarde que la **longueur** de ce qui suit les deux-points, jamais sa **forme**. Un document qui parle de secrets (rotation, audit, procédure) est donc presque toujours refusé, et la prose française aussi : `mot de passe : réinitialisé`.

---

## Comportement attendu

### Cas nominal

Pour chaque occurrence `mot-clé [:=] candidat` (et non plus seulement la première), le candidat est d'abord **débarrassé** des guillemets, apostrophes, chevrons français, accents graves et ponctuation qui l'encadrent. Il n'est un secret que si les trois conditions suivantes sont réunies :

1. il fait encore **au moins 6 caractères** ;
2. ce n'est **pas un nom** : il ne se compose pas uniquement de lettres, éventuellement séparées par `_`, `.` ou `-` (`KeyCrt`, `machineKey`, `réinitialisé`, `client_secret`) ;
3. ce n'est **pas un masque ni un gabarit** : il ne commence pas par `${`, `{{`, `<` ou `%`, et ne se compose pas uniquement de `*`, `•`, `x`/`X`, `.`, `_`, `-`.

Une seule occurrence qualifiée suffit à refuser. Le message est inchangé : il nomme la nature (« un mot de passe »), jamais la valeur.

Les règles à **forme de fournisseur** (clé privée, AWS, GitHub, Slack, Stripe, Anthropic, `sk-…`, Google, JWT, `Authorization: Bearer`) sont **inchangées**.

La règle s'applique partout où `ClientMailSecrets` est appelé : objet, corps, pièces texte et texte extrait des documents (`DocumentSecretScanner`).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `password=Hunter2024!` (chiffres, symboles) | Refusé, « un mot de passe » | — (résultat d'outil) |
| ``mdp : `P@ssw0rd` `` (valeur entre accents graves) | Refusé : l'encadrement est retiré avant l'examen | — |
| `Token:KeyCrt` puis plus loin `password=Hunter2024!` | Refusé : la 2ᵉ occurrence est examinée | — |
| `Token:KeyCrt`, `secret: machineKey` | Accepté | — |
| `password: ********`, `password=${DB_PASSWORD}`, `api_key=<votre-clé>` | Accepté | — |
| Clé de fournisseur (`AKIA…`, `ghp_…`, `sk-ant-…`) | Refusé, comme avant | — |

---

## Critères d'acceptation

- [ ] `Token:KeyCrt`, ``Token:`KeyCrt` ``, `secret: machineKey`, `mot de passe : réinitialisé`, `password: ********`, `password=${DB_PASSWORD}` et `api_key=<votre-clé>` ne sont plus détectés.
- [ ] Les 14 cas positifs existants de `ClientMailSecretsTest` restent détectés avec le même libellé.
- [ ] ``mdp : `P@ssw0rd` ``, `token = "abc123def"` et un texte où un nom précède une vraie valeur sont détectés comme « un mot de passe ».
- [ ] Une pièce jointe texte reprenant le contenu du constat (noms de paramètres, nombreuses mentions « mot de passe ») passe `ClientMailAttachments`, alors qu'une pièce contenant `password=Hunter2024!` est toujours refusée.
- [ ] Aucune autre règle de `ClientMailSecrets` n'est modifiée.

---

## Périmètre

### Hors scope (explicite)

- Les mots de passe faits **uniquement de lettres** (`correcthorsebattery`) ne sont plus détectés par la règle générique. C'est une limite acceptée : la règle vise le *manifeste* (cadrage F-110 §4), et un mot de passe purement alphabétique n'est pas distinguable d'un nom de paramètre par sa forme.
- Pas de détection par entropie, pas d'appel modèle.
- Les autres filtres de secrets (journal SF-38-30, sorties MCP F-112) ne sont pas touchés.
- Aucun réglage par client : le changement corrige la règle elle-même (un nom n'est pas un secret, quel que soit le client).

---

## Valeurs initiales

Non applicable : aucune entité créée.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| candidat après `:`/`=` | — | — | ≥ 6 caractères après retrait de l'encadrement ; pas un nom (lettres + `_.-`) ; pas un masque ni un gabarit | — | retrait des `"'`«»()[]{},;.!?` en tête et en queue |

Note : le `!` final est retiré, mais une valeur comme `Hunter2024!` reste un secret grâce à ses chiffres.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

Aucun : pas d'écran (durcissement d'une règle serveur).

### Composants modifiés

- `backend/.../mail/ClientMailSecrets.java` : la règle « un mot de passe » devient une règle à **qualification de valeur**.

### Préoccupations transversales

Aucune (ni auth, ni tenant, ni plans, ni routing).

---

## Plan de test

### Tests unitaires

- [ ] `ClientMailSecretsTest` : nouveaux cas négatifs (noms, masques, gabarits, prose française) et positifs (encadrement retiré, 2ᵉ occurrence, guillemets) ; les cas existants restent verts.
- [ ] `ClientMailAttachmentsTest` : pièce texte « brouillon de rotation » acceptée ; pièce avec `password=Hunter2024!` refusée.

### Tests d'intégration

- [ ] Suite backend `fr.claudegateway.mail` complète verte, puis suite backend complète (contexte Spring).

### Isolation utilisateur

- [x] Non applicable : fonction pure sur du texte, sans accès aux données.

---

## Dépendances

### Subfeatures bloquantes

- SF-110-02 / SF-110-05 : done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- Demande et validation du PO le 2026-10-06 (« Go, cadre la subfeature pour affiner la règle, livre et déploie »), après diagnostic du refus en prod.
- La règle est **corrigée**, pas assouplie au cas par cas : un nom de paramètre n'est un secret chez aucun client. Les valeurs à chiffres ou symboles restent bloquées.
