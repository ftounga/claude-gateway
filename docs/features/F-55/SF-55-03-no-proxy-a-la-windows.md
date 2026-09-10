# Mini-spec — F-55 / SF-55-03 — Le runner accepte un `NO_PROXY` à la Windows

## Identifiant

`F-55 / SF-55-03`

## Feature parente

`F-55` — Assistant proxy dans l'application

## Statut

`done` — PR #340, mergée le 2026-09-10

## Date de création

2026-09-10

## Branche Git

`feat/SF-55-03-no-proxy-windows`

---

## Objectif

> Que le runner **comprenne** une liste d'exclusions séparée par des `;` — la forme que Windows
> écrit — au lieu d'en faire un seul nom d'hôte, et qu'il **dise** au démarrage qu'il l'a acceptée
> et quelle forme il attend.

---

## Déclencheur

C'est le piège que le volet proxy du banc d'essai signale, et le seul qui ne se voie pas :

```
NO_PROXY=.corp.local;localhost;127.0.0.1
```

Le résolveur du runner découpe sur la **virgule** (`ProxyResolver.fromEnv`, SF-38-03). Cette valeur
produit donc **une seule** entrée — `.corp.local;localhost;127.0.0.1` — qui ne correspond à aucun
hôte réel. Résultat : **aucune exclusion ne s'applique**, et tout le trafic interne part dans le
proxy (ou dans le relais local, ce qui est pire : il le fera suivre au proxy d'entreprise).

Rien ne le signale. Le runner démarre, se connecte, fonctionne — et le jour où un projet doit
joindre un dépôt Git interne ou un artefactoire, cela échoue pour une raison sans rapport apparent.

Windows sépare ses listes d'exclusion par des `;` : c'est la forme qu'affichent `netsh` et le
registre, celle qu'on recopie, et celle que l'utilisateur n'a aucune raison de suspecter — d'autant
que `;` n'est **jamais** un caractère valide dans un nom d'hôte, donc la valeur n'est ambiguë pour
personne sauf pour un découpage naïf.

SF-55-02 **avertit** à l'écran, au moment où l'on compose la liste. Cette subfeature fait la moitié
qui manque : **le runner accepte les deux formes**, et le dit.

---

## Comportement attendu

### Cas nominal

`ProxyResolver.fromEnv` découpe `NO_PROXY` sur `,` **et** sur `;`. Les entrées sont ensuite traitées
exactement comme avant (trim, minuscules, entrées vides ignorées, `*`, suffixes en `.`).

| `NO_PROXY` | Exclusions retenues |
|---|---|
| `.corp.local,localhost` | `.corp.local`, `localhost` — inchangé |
| `.corp.local;localhost;127.0.0.1` | `.corp.local`, `localhost`, `127.0.0.1` |
| `.corp.local; localhost , 127.0.0.1` | les trois, quelle que soit la ponctuation |
| `*` | tout est exclu — inchangé |
| absent ou vide | aucune exclusion — inchangé |

### Ce que le runner en dit

Quand — et **seulement** quand — un `;` a servi de séparateur, le runner écrit **une** ligne au
démarrage, à côté de sa déclaration de route :

```
Exclusions: NO_PROXY est écrit à la forme Windows (« ; ») — le runner l'accepte.
            curl et la plupart des outils attendent des virgules : « a,b,c ».
```

Elle n'est **pas** une erreur : rien n'est cassé, la valeur est comprise. Elle existe parce que le
même `NO_PROXY` sera lu par `curl` dans le terminal d'à côté, et que **là**, il ne marchera pas —
c'est ce décalage qui coûte une heure de diagnostic.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `NO_PROXY` absent, vide ou uniquement des séparateurs | Aucune exclusion, **aucune ligne** affichée |
| `NO_PROXY` déjà écrit avec des virgules | Comportement **strictement inchangé**, aucune ligne affichée |
| Mélange de `;` et de `,` | Les deux séparent ; la ligne est affichée (un `;` a bien servi) |
| Un `;` en fin de valeur (`a,b;`) | Entrée vide ignorée ; la ligne est affichée |
| `NO_PROXY` valant `*` | Tout est exclu, comme avant |

---

## Critères d'acceptation

- [ ] `NO_PROXY=.corp.local;localhost` produit **deux** exclusions, et l'hôte `localhost` est bien
      exclu du proxy.
- [ ] Un `NO_PROXY` séparé par des virgules se comporte **exactement** comme avant.
- [ ] Un mélange `a;b,c` produit **trois** exclusions.
- [ ] Les espaces autour des séparateurs sont ignorés.
- [ ] Les entrées vides (`a;;b`, `a;`) sont ignorées.
- [ ] `*` continue de tout exclure.
- [ ] Le runner expose le fait qu'un `;` a servi de séparateur.
- [ ] La ligne de démarrage n'est écrite **que** dans ce cas, et elle nomme la forme attendue
      ailleurs (les virgules).
- [ ] Cette ligne n'est **pas** une erreur : le runner démarre normalement.
- [ ] Aucun autre comportement du résolveur n'est modifié (`select`, `route`, `hasProxy`).

---

## Périmètre

### Hors scope (explicite)

- **Corriger `HTTPS_PROXY` / `HTTP_PROXY`** : ils ne portent qu'une adresse, jamais une liste — il
  n'y a pas de séparateur à interpréter.
- **Réécrire la variable d'environnement du poste** : le runner lit, il ne configure pas.
- **Interpréter les jokers de Windows** (`*.corp.local`) : le résolveur traite déjà les suffixes en
  `.`, et ajouter une syntaxe de motif serait une autre subfeature, avec ses propres pièges.
- **Avertir à l'écran** : c'est SF-55-02, déjà livrée.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `noProxyWindowsForm` | `false` | Passe à vrai si, et seulement si, un `;` a servi de séparateur |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / valeurs | Unicité | Normalisation |
|-------|-------------|-------------|------------------|---------|---------------|
| `NO_PROXY` (environnement) | Non | — | Liste séparée par `,` **ou** `;` | Non | `trim`, minuscules, entrées vides ignorées (inchangé depuis SF-38-03) |

---

## Technique

### Endpoint(s)

Aucun. C'est le runner.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

### Composants runner

- `ProxyResolver` — découpage sur `[,;]`, et un accesseur `noProxyWindowsForm()`.
- `ProxyResolver.noProxyNotice()` — la ligne à afficher, ou `null` : fonction sans I/O, testable
  telle quelle.
- `RunnerMain` — écrit cette ligne, quand elle existe, juste après la déclaration de démarrage.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | Aucun |
| Contexte tenant | Non | Aucun accès aux données |
| Plans / limites | Non | Aucun quota |
| Navigation / routing | Non | Aucune route |
| **Route sortante du runner** | **Oui** | `ProxyResolver.select` décide par où sortent **toutes** les connexions du runner (appairage HTTP et WSS). Le changement porte **uniquement** sur le découpage de la liste d'exclusions : `select`, `route()` et `hasProxy()` ne sont pas touchés, et leurs tests existants restent verts sans modification. Un `NO_PROXY` en virgules produit exactement les mêmes exclusions qu'avant. |

---

## Plan de test

### Tests unitaires — runner

- [ ] `.corp.local;localhost` exclut les deux hôtes.
- [ ] `.corp.local,localhost` se comporte comme avant (non-régression).
- [ ] `a;b,c` produit trois exclusions.
- [ ] Les espaces autour des séparateurs sont ignorés.
- [ ] `a;;b` et `a;` ignorent les entrées vides.
- [ ] `*` exclut tout.
- [ ] Un hôte **hors** liste passe toujours par le proxy (le découpage n'élargit pas l'exclusion).
- [ ] `noProxyWindowsForm()` est vrai avec un `;`, faux avec des virgules, faux sans `NO_PROXY`.
- [ ] `noProxyNotice()` renvoie `null` sans `;`, et une ligne nommant les virgules avec.

### Isolation utilisateur

- [x] **Sans objet** : le runner n'accède à aucune donnée de la gateway ici.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-03` (résolveur de proxy du runner) — **done**
- `SF-55-02` (l'avertissement à l'écran) — **done** (PR #339)

---

## Décisions (arbitrages tracés)

| # | Décision | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| **D1** | Le runner **accepte** le `;` au lieu de seulement l'avertir | `;` n'est jamais un caractère valide dans un nom d'hôte : l'accepter ne crée aucune ambiguïté, et refuser ferait échouer silencieusement des exclusions parfaitement lisibles | Rester strict sur la virgule et se contenter du message | Oui |
| **D2** | Une ligne au démarrage, **seulement** quand un `;` a servi | Le même `NO_PROXY` sera lu par `curl` dans le terminal d'à côté, où il ne marchera pas : c'est ce décalage qui coûte du temps. L'écrire toujours en ferait un bruit qu'on n'attribue plus à rien | Ne rien dire ; ou l'écrire à chaque démarrage | Oui |
| **D3** | La ligne est une **information**, pas une erreur ni un avertissement d'échec | Rien n'est cassé côté runner : la valeur est comprise. Un rouge enverrait chercher une panne qui n'existe pas — le défaut même que F-45 corrigeait ailleurs | La journaliser en erreur | Oui |
| **D4** | La ligne vit dans `ProxyResolver`, en fonction sans I/O | Elle se teste alors ligne à ligne, et le résolveur reste le seul endroit qui sait comment `NO_PROXY` a été lu | Composer le message dans `RunnerMain` | Oui |
