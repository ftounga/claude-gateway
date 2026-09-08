# Mini-spec — F-45 / SF-45-04 — Le contrôle de vol reconnaît le `407`

## Identifiant

`F-45 / SF-45-04`

## Feature parente

`F-45` — Mise en service guidée du runner sur poste d'entreprise

## Statut

`done` — PR #294, mergée le 2026-09-08

## Date de création

2026-09-08

## Branche Git

`feat/SF-45-04-le-runner-reconnait-le-407`

---

## Objectif

> Que le contrôle de vol du runner **reconnaisse un `407`** et **nomme le remède**, au lieu de le
> compter comme une gateway joignable ou de le décrire comme une panne de transport quelconque.

---

## Déclencheur

Quatrième et dernier obstacle du 2026-09-07. Une fois le proxy enfin déclaré dans le terminal, le
runner ne passait toujours pas : le proxy exigeait une authentification **intégrée** (NTLM), que la
JVM ne sait pas porter — `java.net.http.HttpClient` n'a aucun support SSPI, et l'authentification
`Basic` est désactivée sur les tunnels `CONNECT` depuis Java 8u111.

Le contrôle de vol (SF-38-25) laissait passer ce cas par construction : sa décision D1 dit que
**toute** réponse HTTP vaut « joignable ». C'était juste tant que la réponse venait de la gateway ;
un `407` vient du **proxy**, qui n'a rien transmis.

---

## Comportement attendu

### Cas nominal (inchangé)

Toute réponse de la gateway — `200`, `404`, `500` — vaut « joignable ». D1 de SF-38-25 est conservée
pour tout ce qui vient du serveur.

### Le `407`

Le contrôle **échoue** et le runner s'arrête avant l'appairage, avec un message qui :

1. dit que c'est le **proxy** qui refuse, pas la gateway ;
2. dit que le runner **ne pourra pas** porter une authentification intégrée, **quelle que soit sa
   version** — la cause est dans la JVM (aucun SSPI ; `Basic` désactivé sur les tunnels depuis
   8u111) ;
3. donne les **deux** issues : faire exclure ce domaine de l'authentification proxy, ou passer par un
   **relais local** (`px`, `cntlm`) qui porte l'authentification et expose un proxy sans
   authentification ;
4. donne la commande de déclaration du relais **pour le système courant** ;
5. renvoie à la fiche « Pour votre DSI » de l'écran d'appairage (SF-45-03).

Le `407` est reconnu sous ses **deux** formes observables :

| Forme | D'où elle vient |
|---|---|
| Réponse HTTP de statut `407` | Le proxy répond directement à la requête (cible en clair, ou proxy qui court-circuite) |
| `IOException` dont la chaîne de messages contient `407` ou « proxy authentication » | Tunnel `CONNECT` refusé : la JVM lève `IOException("Tunnel failed, got: 407")` sans jamais produire de `HttpResponse` |

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Gateway répond `401` (authentification applicative) | **Joignable** — c'est la gateway qui parle, pas le proxy ; le contrôle ne juge pas la santé |
| Gateway répond `403`, `404`, `500` | **Joignable**, comme avant |
| `IOException` sans mention de `407` | Message de panne réseau existant (SF-38-24/25), inchangé |
| Système d'exploitation non reconnu | Le remède est donné avec les gestes **génériques**, jamais un message vide |
| Interruption du thread | Comportement inchangé : ce n'est pas un verdict réseau |

---

## Critères d'acceptation

- [ ] Une réponse `407` fait **échouer** le contrôle de vol.
- [ ] Une réponse `200`, `401`, `404` ou `500` continue de valoir « joignable ».
- [ ] Une `IOException` dont la chaîne contient `407` produit le **même** message que la réponse `407`.
- [ ] Le message nomme le **proxy** comme auteur du refus, pas la gateway.
- [ ] Le message dit que la JVM ne porte **ni NTLM ni Kerberos**, et pourquoi (`SSPI`, `8u111`).
- [ ] Le message propose les **deux** issues (exclusion DSI, relais local).
- [ ] Le message donne la commande de déclaration du relais **du système courant**.
- [ ] Un système non reconnu reçoit une commande générique, jamais un message vide.
- [ ] Le message renvoie à la fiche DSI de l'écran d'appairage.
- [ ] Le runner s'arrête **avant** l'appairage, avec un code de sortie non nul (inchangé : `5`).
- [ ] Aucun identifiant, aucun mot de passe, aucun jeton n'apparaît dans le message.

---

## Périmètre

### Hors scope (explicite)

- **Porter l'authentification NTLM/Kerberos** : cela reviendrait à manipuler les identifiants Windows
  de l'utilisateur depuis le produit. C'est le « relais d'authentification embarqué » que la feature
  exclut explicitement.
- **Un proxy à identifiants applicatifs** (login/mot de passe dédiés au runner, `Basic`) : exception
  DSI, hors produit — et de toute façon refusée par la JVM sur les tunnels.
- **Lancer ou installer le relais local** : le runner le **nomme**, il ne l'installe pas.
- **Détecter la marque du proxy.**

---

## Valeurs initiales

Sans objet.

---

## Contraintes de validation

Sans objet — aucune entrée utilisateur. La seule entrée est la réponse (ou l'exception) du réseau.

---

## Technique

| Classe | Changement |
|--------|-----------|
| `runner/NetworkPreflight` | Lit le **statut** de la réponse ; `407` → message dédié. Reconnaît aussi la signature `407` dans la chaîne d'exceptions. |
| `runner/OperatingSystem` | Nouvelle méthode `declareProxy(String)` — la commande de déclaration d'un proxy donné, sur le système courant. Jamais vide. |
| `runner/Failures` | Nouvelle piste : une exception portant la signature `407` renvoie la mention « authentification proxy intégrée non portée », pour les chemins hors contrôle de vol. |

### Endpoint(s)

Aucun. Le runner est un client.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | Le contrôle de vol n'envoie aucun jeton — il n'en a pas encore |
| Contexte tenant | Non | Aucun accès données |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |
| **Chemin de démarrage** | **Oui** | Le contrôle de vol devient **bloquant sur un cas de plus**. Composants à vérifier : `RunnerMain` (démarrage avec appairage, démarrage avec jeton déjà stocké, repli long-polling) — le point de sortie est le même qu'en SF-38-25 (code `5`), et aucun code non-`407` ne change de verdict. Non-régression couverte par `NetworkPreflightTest` (200/404/500 restent joignables) et `RunnerStartupLinesTest`. |
| **Message d'erreur partagé** | **Oui** | `Failures.hint` est utilisé par le contrôle de vol **et** par l'appairage. L'ajout est purement additif : une exception sans signature `407` conserve exactement la piste qu'elle avait. Couvert par `FailuresTest`. |

---

## Plan de test

### Tests unitaires

- [ ] Un serveur répondant `407` fait échouer le contrôle.
- [ ] Le message nomme le proxy, `NTLM`, `Kerberos`, `SSPI` et `8u111`.
- [ ] Le message contient les deux issues et la commande de déclaration du relais.
- [ ] `200`, `401`, `404` et `500` restent joignables (non-régression de D1).
- [ ] Une `IOException` portant `Tunnel failed, got: 407` produit le message `407`.
- [ ] Une `IOException` sans `407` conserve le message de panne réseau existant.
- [ ] `OperatingSystem.declareProxy` produit la bonne syntaxe pour Windows, macOS, Linux et `OTHER`,
      jamais une chaîne vide.
- [ ] `Failures.hint` reconnaît la signature `407` et conserve toutes ses pistes existantes.
- [ ] Le message ne contient aucun identifiant ni mot de passe.

### Tests d'intégration

Sans objet — le runner n'expose aucun endpoint. Les tests du contrôle de vol montent un vrai
`HttpServer` local, ce qui est le plus proche d'un test d'intégration pour ce composant.

### Isolation workspace

- [x] **Non applicable** — aucun accès aux données.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-24` (`Failures`) — **done**
- `SF-38-25` (`NetworkPreflight`, `OperatingSystem`) — **done**
- `SF-45-03` (fiche DSI) — **done** ; le message y renvoie

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

**D1 — Le `407` est l'exception à « toute réponse vaut joignable ».** D1 de SF-38-25 disait vrai de
ce qui vient **du serveur** : un `404` prouve qu'on lui a parlé. Un `407` ne vient pas du serveur, il
vient du **proxy**, qui n'a rien transmis. Compter cette réponse comme un succès, c'est laisser le
runner échouer trois lignes plus loin, à l'appairage — exactement ce que le contrôle de vol existe
pour éviter. La décision est donc **amendée**, pas contredite : toute réponse **de la gateway** vaut
joignable. *Alternative écartée* : laisser passer et améliorer le message d'appairage, qui remet la
cause réseau au milieu d'une opération métier.

**D2 — Deux formes reconnues, parce que la JVM en produit deux.** Sur une cible en HTTPS, le proxy
refuse le tunnel `CONNECT` et la JVM lève une `IOException` — il n'y a **jamais** de `HttpResponse` à
inspecter. Ne traiter que le statut ne couvrirait donc pas le cas réel rencontré. Ne traiter que
l'exception raterait le proxy qui répond directement. Les deux mènent au même message.

**D3 — Le message dit « aucune version du runner ne corrigera cela ».** Sans cette phrase, le premier
réflexe est de chercher une mise à jour, puis d'ouvrir un ticket au produit. La cause est dans la
JVM ; l'écrire fait gagner cet aller-retour.

**D4 — Deux issues, pas une exigence.** « Faites ouvrir sans authentification » se refuse dans
beaucoup d'entreprises. Le relais local ne demande rien à personne et fonctionne sous le compte de
l'utilisateur. Proposer les deux, c'est laisser une porte ouverte quand la première se ferme.

**D5 — `Failures` gagne la piste, `NetworkPreflight` garde le message long.** Une ligne suffit là où
l'exception remonte au milieu d'autre chose ; le contrôle de vol, lui, est le seul endroit où
quelqu'un lit tranquillement un écran de diagnostic.
