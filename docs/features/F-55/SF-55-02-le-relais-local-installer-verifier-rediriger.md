# Mini-spec — F-55 / SF-55-02 — Le relais local : installer, vérifier, rediriger

## Identifiant

`F-55 / SF-55-02`

## Feature parente

`F-55` — Assistant proxy dans l'application

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-55-02-relais-local`

---

## Objectif

> Conduire l'utilisateur, geste par geste, de « l'authentification intégrée passe pour `curl` »
> jusqu'à un runner qui passe : **installer, configurer et lancer** un relais local — `px` ou
> `cntlm`, mot de passe **haché**, jamais en clair —, le **vérifier**, et **seulement ensuite** y
> rediriger le runner.

---

## Déclencheur

SF-55-01 s'arrête au verdict : « le remède est un relais local ». Ce verdict est juste et ne suffit
pas. Le volet proxy du banc d'essai (`docs/features/F-38/BANC-ESSAI-RUNNER-2026-09-10.md`) tient
deux pages parce que chacun de ces gestes a coûté du temps :

- `winget install genotrance.px` **échoue souvent** — et pour une raison circulaire : `winget` sort
  par WinHTTP, qui n'a justement pas de proxy configuré. Il faut alors le binaire autonome,
  téléchargé **à travers** le proxy avec l'authentification intégrée ;
- sous macOS, il n'y a de binaire publié **que pour Apple Silicon** — et le navigateur ne sait pas
  distinguer les deux architectures (Safari comme Chrome annoncent `MacIntel` sur un M3) ;
- `cntlm` ne porte **que NTLM** : le conseiller à un poste en Kerberos fait tout réinstaller ;
- son mot de passe se met **haché** dans le fichier de configuration, jamais en clair — et personne
  ne devine `cntlm -H` sans qu'on le lui dise ;
- enfin, **rediriger le runner vers un relais non vérifié** produit exactement la même panne qu'au
  départ, à ceci près qu'on croit l'avoir réparée. La vérification n'est pas une précaution : c'est
  ce qui distingue les deux.

Et un piège de plus, qui n'appartient à aucune de ces étapes et les traverse toutes : **Windows
sépare son `NO_PROXY` par des `;`, quand `curl` et le runner attendent des `,`**. Recopiée telle
quelle, la liste devient **un seul nom d'hôte** : plus aucune exclusion ne s'applique, et tout le
trafic interne part dans le relais.

---

## Comportement attendu

### Étape C — Le relais local

L'assistant propose **le** relais qui convient au poste et au verdict de SF-55-01 :

| Système | Verdict | Relais proposé | Pourquoi |
|---|---|---|---|
| Windows | Kerberos ou NTLM | `px` | Binaire autonome, aucun droit administrateur, porte les deux |
| macOS | Kerberos ou NTLM | `px` (Apple Silicon) **et** `cntlm` (Intel), les deux affichés | Le navigateur ne sait pas distinguer les deux architectures — l'utilisateur, si |
| macOS / autre | Kerberos | `px` **seul** | `cntlm` ne porte que NTLM : le proposer ici serait une fausse piste |
| Autre (Linux) | NTLM | `cntlm` | Empaqueté partout ; `px` reste offert par `pip3` |

Chaque relais est donné **en ordre d'exécution**, avec ce que fait chaque commande :

- **`px` sous Windows** : `winget install genotrance.px` d'abord, puis — parce qu'il échoue
  souvent, et l'assistant **dit pourquoi** : `winget` sort par WinHTTP, qui n'a pas de proxy — le
  binaire autonome, téléchargé **à travers** le proxy avec l'option d'authentification
  correspondant au verdict (`--proxy-negotiate` en Kerberos, `--proxy-ntlm` en NTLM), puis
  `px.exe --proxy=<adresse> --port=3128`.
- **`px` sous macOS Apple Silicon / Linux** : archive publiée, ou `pip3 install --user px-proxy`,
  puis `px --proxy=<adresse> --port=3128`.
- **`cntlm`** : le fichier de configuration (`Username`, `Domain`, `Proxy`, `Listen 3128`,
  `NoProxy`), **le mot de passe haché** — `cntlm -H -d <DOMAINE> -u <identifiant>` produit les
  lignes `PassNTLMv2` à coller —, `chmod 600` sur le fichier, puis `cntlm -f`.

**Le mot de passe n'est jamais écrit en clair, et jamais saisi ici.** L'assistant l'énonce comme une
règle : `cntlm -H` se lance **sur le poste**, demande le mot de passe **dans le terminal**, et rend
un haché. Ce haché reste un secret — il ouvre le proxy —, d'où le `chmod 600`. Les seuls champs
proposés sont le **domaine** et l'**identifiant**, qui n'en sont pas.

### Étape D — Vérifier, puis rediriger

La vérification se fait dans un **second terminal**, le relais devant rester lancé dans le premier :

```
curl -sS -o /dev/null -w "via le relais : %{http_code}\n" -x http://127.0.0.1:3128 <url du contrôle>
```

**Aucune option d'authentification** dans cette commande : c'est tout l'objet du test. Un `200`
**sans** `--proxy-ntlm` prouve que le relais porte l'authentification à la place de celui qui
l'appelle — donc à la place de la JVM.

L'utilisateur déclare ce qu'il obtient :

| Déclaration | Ce que l'assistant affiche |
|---|---|
| `200` | La **redirection du runner** : `HTTPS_PROXY` et `HTTP_PROXY` vers `127.0.0.1:3128`, le `NO_PROXY` et son piège de séparateur, et le rappel que le relais doit rester ouvert tant que le runner tourne |
| Autre chose | Ce qu'il faut revoir **avant** de rediriger : le relais est-il encore lancé dans l'autre terminal, l'adresse du proxy est-elle la bonne, le domaine et l'identifiant sont-ils ceux du poste |

**Les commandes de redirection ne sont affichées qu'après un `200` déclaré** (D3) : rediriger le
runner vers un relais qui ne porte rien produit **exactement** la panne de départ — un `407` de
plus — en donnant à croire qu'on l'a réparée. La déclaration reste révisable.

Le `NO_PROXY` est donné avec sa virgule et son avertissement :

> Windows écrit ses exclusions avec des `;`. `curl` et le runner attendent des `,`. Recopiée telle
> quelle, la liste devient **un seul nom d'hôte** : plus rien n'est exclu, et tout le trafic interne
> part dans le relais.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucune adresse de proxy saisie à l'étape A | Les commandes gardent le marqueur `hote:port` — visiblement incomplètes plutôt que faussement prêtes (règle de SF-55-01, conservée) |
| Aucun domaine / identifiant saisi | Les marqueurs `DOMAINE` et `identifiant` tiennent la place dans la commande de hachage |
| Verdict `refused` (les deux tests en `407`) | **Aucun relais n'est proposé** : un relais s'authentifierait de la même façon et échouerait pareil. L'étape renvoie à la demande DSI |
| Verdict `unknown` (rien n'a été déclaré) | L'étape reste lisible et propose de faire d'abord le test de l'étape B, sans rien cacher |
| Système non reconnu | Les gestes génériques (`cntlm`, et `px` par `pip3`) sont affichés — jamais une étape vide |
| L'utilisateur déclare un échec de vérification | Les commandes de redirection **ne s'affichent pas** ; la liste de ce qu'il faut revoir s'affiche |

---

## Critères d'acceptation

- [ ] L'assistant porte **quatre** étapes : adresse, qualification, relais, vérification.
- [ ] Sous Windows, l'étape « relais » propose `px`, avec `winget` **puis** le binaire autonome, et
      **dit pourquoi** `winget` échoue souvent (il sort par WinHTTP, sans proxy).
- [ ] Le téléchargement du binaire autonome emploie l'option correspondant au verdict :
      `--proxy-negotiate` en Kerberos, `--proxy-ntlm` en NTLM.
- [ ] Sous macOS, **les deux** relais sont proposés en NTLM (Apple Silicon / Intel), et **`px`
      seul** en Kerberos.
- [ ] Sur tout autre système, `cntlm` est proposé en NTLM, `px` par `pip3` en Kerberos.
- [ ] `cntlm` n'est **jamais** proposé quand le verdict est Kerberos, et la raison est écrite.
- [ ] La configuration `cntlm` affiche `Username`, `Domain`, `Proxy`, `Listen 3128` et `NoProxy`.
- [ ] La commande de **hachage** `cntlm -H -d … -u …` est affichée, et le texte dit que le mot de
      passe se tape **dans le terminal** et ne s'écrit **jamais** dans le fichier.
- [ ] Un `chmod 600` accompagne le fichier de configuration, avec sa raison (le haché est un secret).
- [ ] **Aucun champ de mot de passe** n'existe dans l'assistant.
- [ ] Le verdict `refused` ne propose **aucun** relais et renvoie à la demande DSI.
- [ ] L'étape « vérification » affiche la commande à travers `127.0.0.1:3128`, **sans** option
      d'authentification, et dit qu'elle se lance dans un **second** terminal.
- [ ] Tant que le `200` n'est pas déclaré, **aucune** commande de redirection n'est affichée.
- [ ] Déclarer `200` affiche `HTTPS_PROXY`, `HTTP_PROXY` et `NO_PROXY` vers `127.0.0.1:3128`.
- [ ] Déclarer un échec affiche la liste de ce qu'il faut revoir, et **pas** la redirection.
- [ ] La déclaration de vérification est **révisable**.
- [ ] Le `NO_PROXY` proposé emploie des **virgules**, et l'avertissement sur les `;` de Windows est
      affiché.
- [ ] L'avertissement « le relais doit rester ouvert tant que le runner tourne » est affiché avec la
      redirection.
- [ ] L'adresse saisie à l'étape A compose **aussi** les commandes du relais.
- [ ] Aucun appel réseau n'est émis par l'assistant.

---

## Périmètre

### Hors scope (explicite)

- **Embarquer, distribuer ou installer un relais** : l'assistant affiche des commandes, il n'exécute
  rien et ne sert aucun binaire.
- **Porter NTLM / Kerberos dans le runner** : hors périmètre de F-55 entière.
- **Demander, transporter ou stocker un mot de passe** (ni en clair, ni haché).
- **Vérifier le relais depuis la page** : elle ne peut pas atteindre `127.0.0.1` du poste, et le
  prétendre serait pire que se taire.
- **Le comportement du runner face à un `NO_PROXY` à la Windows** : c'est SF-55-03.
- **Mémoriser la configuration** d'une ouverture à l'autre.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `relayDomain` | `''` | Marqueur `DOMAINE` tant que rien n'est saisi |
| `relayUser` | `''` | Marqueur `identifiant` tant que rien n'est saisi |
| `relayVerified` | `'unknown'` | Aucune commande de redirection tant que la vérification n'est pas déclarée |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / valeurs | Unicité | Normalisation |
|-------|-------------|-------------|------------------|---------|---------------|
| `relayDomain` | Non | 64 | Lettres, chiffres, `.`, `-`, `_` | Non | `trim` ; toute autre saisie retombe sur le marqueur `DOMAINE` |
| `relayUser` | Non | 64 | Lettres, chiffres, `.`, `-`, `_`, `\` | Non | `trim` ; toute autre saisie retombe sur le marqueur `identifiant` |

Notes :
- Ces deux valeurs ne sont **pas** des secrets, et ne quittent pas le navigateur.
- Une saisie hors format n'affiche **pas** d'erreur bloquante : elle est simplement ignorée au profit
  du marqueur — une commande visiblement incomplète vaut mieux qu'une commande fausse.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

### Composants Angular

- `ProxyAssistantDialogComponent` — deux étapes de plus (`relay`, `verify`), signaux `relayDomain`,
  `relayUser`, `relayVerified`, et les fonctions pures :
  - `relayOptions(platform, verdict, address)` — les relais qui conviennent, en ordre d'exécution
  - `cntlmConfig(address, domain, user)` — le fichier de configuration
  - `cntlmHashCommand(domain, user)` — la commande de hachage
  - `relayCheckCommand(platform, checkUrl)` — la vérification à travers `127.0.0.1:3128`
  - `runnerRedirectCommands(platform)` — `HTTPS_PROXY`, `HTTP_PROXY`, `NO_PROXY`
- Aucun autre composant modifié ; aucun service, aucune route.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | Aucun appel, aucun principal |
| Contexte tenant | Non | Aucune donnée côté gateway |
| Plans / limites | Non | Aucun quota |
| Navigation / routing | Non | Le même dialogue, deux étapes de plus |
| **Secrets** | **Oui** | Aucun champ de mot de passe ; le haché `cntlm` est produit **sur le poste** et n'entre jamais dans l'écran ; `chmod 600` rappelé |
| **Contrat visuel du dialogue** | **Oui** | Les quatre étapes partagent la grammaire de SF-45-05 (en-tête cliquable, une étape ouverte) ; les classes `proxy-*` de SF-55-01 sont réemployées, aucune n'est renommée |

---

## Plan de test

### Tests unitaires — frontend (fonctions pures)

- [ ] `relayOptions('windows', 'ntlm', …)` propose `px`, avec `winget` puis le binaire autonome.
- [ ] Le téléchargement du binaire emploie `--proxy-ntlm` en NTLM, `--proxy-negotiate` en Kerberos.
- [ ] `relayOptions('macos', 'ntlm', …)` propose **deux** options ; `relayOptions('macos',
      'negotiate', …)` **une seule**, `px`.
- [ ] `relayOptions(…, 'refused', …)` est **vide**.
- [ ] Aucune option `cntlm` n'apparaît quand le verdict est `negotiate`.
- [ ] `relayOptions` emploie l'adresse fournie dans `--proxy=`.
- [ ] `cntlmConfig` contient `Listen 3128`, le proxy, le domaine, l'identifiant, et **aucun**
      `Password` en clair.
- [ ] `cntlmHashCommand` emploie les marqueurs quand rien n'est saisi.
- [ ] `relayCheckCommand` passe par `-x http://127.0.0.1:3128` et **ne contient aucune** option
      `--proxy-ntlm` / `--proxy-negotiate`.
- [ ] `runnerRedirectCommands` pointe sur `127.0.0.1:3128` et sépare `NO_PROXY` par des virgules.
- [ ] L'URL du relais est **la même** que celle annoncée par le parcours de mise en service
      (`LOCAL_RELAY_PROXY_URL`).

### Tests unitaires — frontend (composant)

- [ ] Les quatre étapes sont présentes et une seule est ouverte à la fois.
- [ ] L'étape « relais » d'un verdict `refused` n'affiche aucun relais et renvoie à la DSI.
- [ ] Le domaine et l'identifiant saisis apparaissent dans la configuration et la commande de hachage.
- [ ] Le texte du hachage dit que le mot de passe ne s'écrit **jamais** dans le fichier.
- [ ] Aucun champ de type `password` n'existe dans le gabarit.
- [ ] Tant que la vérification n'est pas déclarée, aucune commande de redirection n'est rendue.
- [ ] Déclarer `200` rend la redirection **et** l'avertissement sur les `;` de Windows.
- [ ] Déclarer un échec rend la liste de contrôle et **pas** la redirection.
- [ ] Revenir sur la déclaration remet l'étape à son état initial.

### Isolation utilisateur

- [x] **Sans objet** : aucune donnée n'est lue ni écrite côté gateway.

---

## Dépendances

### Subfeatures bloquantes

- `SF-55-01` (l'assistant, l'adresse et le verdict) — **done** (PR #336)

---

## Décisions (arbitrages tracés)

| # | Décision | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| **D1** | Le relais proposé dépend **du verdict**, pas seulement du système | `cntlm` ne porte que NTLM : le proposer à un poste en Kerberos fait installer, configurer et échouer — puis tout recommencer | Proposer les deux partout et laisser choisir | Oui |
| **D2** | Sous macOS, **les deux** architectures sont montrées | Le navigateur ne sait pas distinguer Apple Silicon d'Intel (déjà constaté en F-44 / SF-44-03) ; deviner enverrait la moitié des Mac sur un binaire qui n'existe pas | Présélectionner Apple Silicon en silence | Oui |
| **D3** | La **redirection n'apparaît qu'après** un `200` déclaré à la vérification | Rediriger vers un relais qui ne porte rien reproduit la panne de départ en donnant à croire qu'elle est réparée — et le diagnostic recommence de zéro | Tout afficher d'emblée | Oui |
| **D4** | **Aucun champ de mot de passe**, et le haché est produit sur le poste | Un mot de passe de domaine n'a aucune raison d'entrer dans un navigateur, et le haché en tient lieu pour ce proxy | Composer la ligne `PassNTLMv2` depuis l'écran | Non — règle de sécurité |
| **D5** | Le `chmod 600` est affiché **avec** la configuration, pas en note de bas de page | Le haché ouvre le proxy : un fichier lisible par tous est un secret partagé qui ne se voit pas | Le mentionner en passant | Non — règle de sécurité |
| **D6** | Le piège du séparateur `NO_PROXY` est écrit **à côté** de la commande, pas ailleurs | C'est au moment de composer la liste qu'on recopie celle de Windows ; l'avertissement lu dix minutes plus tôt ne sert à rien | Une note générale en tête d'assistant | Oui |
| **D7** | `winget` est proposé **d'abord**, avec sa raison d'échec | Il marche sur les postes moins verrouillés, et son échec est déroutant : il sort par WinHTTP, qui est précisément ce qui n'a pas de proxy | Aller directement au binaire autonome | Oui |
