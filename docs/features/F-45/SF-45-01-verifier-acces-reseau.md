# Mini-spec — F-45 / SF-45-01 — Étape « Vérifier l'accès réseau »

## Identifiant

`F-45 / SF-45-01`

## Feature parente

`F-45` — Mise en service guidée du runner sur poste d'entreprise

## Statut

`ready`

## Date de création

2026-09-08

## Branche Git

`feat/SF-45-01-verifier-acces-reseau`

---

## Objectif

> Que l'écran d'appairage fasse **vérifier la sortie réseau du poste avant tout téléchargement**, avec
> la commande du système consulté et l'arbre de lecture de son résultat.

---

## Déclencheur

Chez le client du 2026-09-07, les quatre obstacles se sont tous découverts **après** le
téléchargement et le lancement. L'écran promet « aucun port à ouvrir » — vrai pour l'**entrant**, et
muet sur le **sortant**, qui est ce qui bloque réellement. Une commande de dix secondes, collée
**avant** de télécharger 39 Mo, répond à la seule question qui compte : *ce terminal sort-il jusqu'à
cette gateway ?*

---

## Comportement attendu

### Cas nominal

Le dialogue « Connecter une machine » ouvre sur une étape **1 — Vérifier l'accès réseau**, avant la
génération du code et avant le téléchargement. Elle contient :

1. une **commande** à coller dans le terminal qui lancera le runner, adaptée au système d'où la page
   est consultée, copiable en un clic ;
2. l'**arbre de lecture** de ce qu'elle affiche, à **trois branches**.

Commande affichée (l'URL est celle de la gateway courante, préfixe `/api` compris) :

| Poste consulté | Commande |
|---|---|
| Windows | `curl.exe -sS -o NUL -w "%{http_code}\n" https://<gateway>/api/runner/download/formats` |
| macOS, Linux, autre | `curl -sS -o /dev/null -w "%{http_code}\n" https://<gateway>/api/runner/download/formats` |

### Arbre de lecture — trois branches

| Ce qui s'affiche | Lecture | Geste |
|---|---|---|
| **`200`** | Ce terminal sort jusqu'à la gateway. | Poursuivez à l'étape 2. |
| **`407`** | Un proxy d'entreprise répond et **exige une authentification**. Si elle est *intégrée* (NTLM/Kerberos), la JVM du runner **ne sait pas la porter** : `java.net.http.HttpClient` n'a aucun support SSPI, et `Basic` est désactivé sur les tunnels `CONNECT` depuis Java 8u111. | **Relais local** : un outil qui porte l'authentification intégrée et expose un proxy **sans authentification** sur `127.0.0.1` (`px`, `cntlm`), puis `HTTPS_PROXY=http://127.0.0.1:3128` dans le terminal qui lance le runner. |
| **Aucun code** (`could not resolve host`, `failed to connect`, délai dépassé) | Le proxy du poste **n'est pas déclaré dans ce terminal** — il l'est côté système, ce que le shell ne lit pas. | Retrouver le proxy avec la commande du système, puis le déclarer dans **ce** terminal. |

Les gestes de la troisième branche sont ceux du système consulté :

| Poste | Retrouver | Déclarer |
|---|---|---|
| Windows | `netsh winhttp show proxy` | `$env:HTTPS_PROXY="http://hote:port"` (PowerShell) / `set HTTPS_PROXY=http://hote:port` (invite) |
| macOS | `scutil --proxy` | `export HTTPS_PROXY=http://hote:port` |
| Linux / autre | `env \| grep -i proxy` | `export HTTPS_PROXY=http://hote:port` |

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le système consulté n'est ni Windows ni macOS | Les gestes **génériques** (variables d'environnement) sont affichés — **jamais** une étape vide |
| `navigator` indisponible (rendu serveur, test) | La détection retombe sur `other` ; la commande `curl` générique est affichée |
| L'origine de la page est illisible | L'étape reste affichée avec l'URL vide plutôt que masquée : le texte de diagnostic garde sa valeur |
| L'utilisateur n'a pas `curl` | Hors périmètre — `curl` est présent sur Windows 10+ (`curl.exe`), macOS et toute distribution Linux courante ; la mention `curl.exe` évite le piège de l'alias PowerShell |
| Le presse-papiers est indisponible | Message « sélectionnez le texte manuellement » (comportement existant du dialogue) |

---

## Critères d'acceptation

- [ ] L'étape « Vérifier l'accès réseau » apparaît **avant** l'étape de téléchargement du runner.
- [ ] La commande affichée cible la **gateway courante**, préfixe `/api` inclus.
- [ ] Sur un poste Windows, la commande écrit **`curl.exe`** (et non `curl`) et redirige vers `NUL`.
- [ ] Sur macOS, Linux et tout autre système, la commande écrit `curl` et redirige vers `/dev/null`.
- [ ] Les **trois** branches de lecture sont présentes : `200`, `407`, absence de code.
- [ ] La branche `407` nomme l'**authentification intégrée** et le remède **relais local**, et dit que
      la JVM ne porte ni NTLM ni Kerberos.
- [ ] La branche « aucun code » affiche la commande de **découverte** du proxy du système consulté.
- [ ] Un système non reconnu reçoit les gestes génériques, jamais une branche vide.
- [ ] La commande est **copiable** en un clic, comme les autres commandes du dialogue.
- [ ] L'écran dit que le **navigateur ne peut pas lire** la configuration proxy du poste — la limite
      est assumée à l'écran, pas cachée.
- [ ] Aucun appel réseau n'est ajouté au chargement du dialogue : l'étape est **du texte**, le poste
      exécute la commande lui-même.

---

## Périmètre

### Hors scope (explicite)

- **Exécuter la vérification depuis le navigateur** : une requête `fetch` depuis la page ne prouve
  rien sur le **terminal**, qui est l'endroit où le runner tournera — c'est justement le paradoxe qui
  a coûté l'aller-retour chez le client.
- **Détecter le proxy** : impossible depuis un navigateur, et le prétendre serait pire que se taire.
- **Configurer le poste** (écrire des variables, installer un relais).
- **Le message `407` côté runner** : c'est SF-45-04.
- **Le chemin d'exemple Windows et l'état de connexion** : c'est SF-45-02.
- **La fiche DSI** : c'est SF-45-03.

---

## Valeurs initiales

Sans objet — aucune entité créée, aucune donnée persistée.

---

## Contraintes de validation

Sans objet — l'étape n'accepte aucune saisie ; elle **affiche** du texte constant dérivé de
l'origine de la page et du système consulté.

---

## Technique

### Endpoint(s)

**Aucun.** L'étape est entièrement côté écran. L'URL citée dans la commande pointe vers
`GET /api/runner/download/formats`, endpoint **public existant** (F-44 / SF-44-02) — le même que le
contrôle de vol du runner (SF-38-25), pour que la commande collée éprouve exactement le chemin que
le runner empruntera.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

### Composants Angular

- `RunnerPairingDialogComponent` — nouvelle étape, nouveaux `computed` :
  `networkCheckCommand()`, `proxyDiscoveryCommand()`, `proxyExportCommand()`.
- `runner-pairing-dialog.component.html` — l'étape et son arbre de lecture ; renumérotation des
  étapes existantes.
- `runner-pairing-dialog.component.scss` — styles de l'arbre, jetons `--cg-*` uniquement.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | Aucun appel authentifié ajouté |
| Contexte tenant | Non | Aucun accès données |
| Plans / limites | Non | — |
| Navigation / routing | Non | Le dialogue s'ouvre depuis les mêmes points qu'avant |
| **Numérotation des étapes du dialogue** | **Oui** | Les étapes 1→3 deviennent 2→4. Composants à vérifier : `runner-pairing-dialog.component.html` (seul porteur des numéros), et ses tests, qui raisonnent sur les `computed` et non sur les numéros. Aucun autre écran ne référence « l'étape 2 » du dialogue (vérifié par `grep`). |

---

## Plan de test

### Tests unitaires

- [ ] Poste Windows : la commande contient `curl.exe` et `NUL`.
- [ ] Poste macOS : la commande contient `curl ` et `/dev/null`, jamais `curl.exe`.
- [ ] Poste inconnu (`other`) : la commande générique est produite, non vide.
- [ ] La commande cible l'origine de la page **suivie de `/api/runner/download/formats`**.
- [ ] Windows : la commande de découverte du proxy est `netsh winhttp show proxy`.
- [ ] macOS : la commande de découverte est `scutil --proxy`.
- [ ] Autre : la commande de découverte est `env | grep -i proxy` — jamais vide.
- [ ] Windows : la commande de déclaration mentionne `$env:HTTPS_PROXY` ; ailleurs, `export`.
- [ ] Le gabarit rend les trois branches (`200`, `407`, échec) et le mot « relais ».
- [ ] Le gabarit rend l'étape **avant** l'étape « Récupérer le runner » (ordre du DOM).
- [ ] Copier la commande passe par le presse-papiers et le confirme.

### Tests d'intégration

Sans objet — aucune route ajoutée ni modifiée. La couverture d'intégration existante de
`GET /api/runner/download/formats` (SF-44-02) reste valable et n'est pas touchée.

### Isolation workspace

- [x] **Non applicable** — l'étape n'accède à aucune donnée.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-06` (écran d'appairage) — **done**
- `SF-44-02` / `SF-44-03` (formats servis, détection du poste consulté) — **done** ; la détection
  `RUNNER_HOST_PLATFORM` est **réutilisée** telle quelle.

### Questions ouvertes impactées

- Aucune. `docs/OPEN_QUESTIONS.md` : OQ-13 (smoke sur poste verrouillé) reste ouverte et n'est ni
  tranchée ni aggravée ici.

---

## Notes et décisions

**D1 — L'étape est en position 1, avant la génération du code.** La consigne produit dit « avant le
téléchargement » ; la placer avant le **code** est strictement meilleur, pour une raison mesurable :
le code d'appairage a un TTL de 5 minutes (`app.runner.pairing-code-ttl`). Diagnostiquer un proxy
prend plus longtemps que cela. Générer d'abord, c'est garantir un code expiré au moment de s'en
servir — et un second aller-retour. *Alternative écartée* : position 2, littérale mais qui brûle le
compte à rebours. **Réversible** : un déplacement de bloc dans le gabarit.

**D2 — `curl`, et non une requête du navigateur.** Une vérification faite par la page prouverait que
**le navigateur** sort, ce qui est déjà vrai — l'utilisateur lit la page. Or le poste du client
d'où vient cette feature avait exactement ce profil : navigateur d'accord, terminal muet. Seule une
commande exécutée **dans le terminal qui lancera le runner** répond à la question posée.
*Alternative écartée* : un bouton « Tester » dans l'écran, rassurant et faux.

**D3 — `curl.exe` sur Windows, jamais `curl`.** Dans PowerShell, `curl` est un **alias** de
`Invoke-WebRequest`, dont les options n'ont rien à voir : la commande échouerait sur une erreur de
paramètre que personne ne relierait au réseau. `curl.exe` désigne le vrai binaire, présent depuis
Windows 10 1803.

**D4 — `curl` plutôt que `Invoke-WebRequest`, même sur Windows.** `Invoke-WebRequest` emprunte le
proxy **système**. Il réussirait donc là où le runner échoue, et masquerait la panne. `curl` lit
`HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY` — exactement ce que lit le runner (`ProxyResolver.fromEnv`).
La commande doit éprouver le chemin du runner, pas un autre.

**D5 — La même URL que le contrôle de vol du runner.** `/runner/download/formats` est publique, sans
authentification, et c'est celle que `NetworkPreflight` interroge (SF-38-25). Deux adresses
différentes autoriseraient un verdict vert à l'écran et rouge au lancement.

**D6 — Un arbre affiché en entier, pas un questionnaire.** Les trois branches sont visibles
simultanément, sans demander à l'utilisateur de saisir ce qu'il a obtenu. Il lit son résultat, il
descend à la ligne correspondante. *Alternative écartée* : un sélecteur « qu'avez-vous obtenu ? »,
qui ajoute un clic et un état pour ne rien apprendre de plus.

**D7 — La limite est écrite à l'écran.** « Le navigateur ne peut pas lire la configuration proxy de
votre poste » est dit noir sur blanc. Une page qui semble vérifier sans vérifier vaut moins qu'une
page qui explique pourquoi elle demande.
