# Mini-spec — F-80 / SF-80-01 — Le diagnostic survit à l'échec

## Identifiant

`F-80 / SF-80-01`

## Feature parente

`F-80` — Le runner derrière un proxy qui déchiffre le TLS

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-80-01-diagnostic-survit-echec`

---

## Objectif

Quand le contrôle de vol échoue sur une `SSLException`, le runner lit la chaîne de certificats
**sans la valider** et **nomme l'émetteur**, au lieu de rendre un `PKIX path building failed` brut.

---

## Comportement attendu

### Cas nominal

1. `RunnerMain` appelle le contrôle de vol (`NetworkPreflight`).
2. Le contrôle échoue et **qualifie** son échec : une `SSLException` quelque part dans la chaîne des
   causes vaut « échec de poignée de main TLS ».
3. Dans ce cas, et dans ce cas seulement, le runner ouvre une **lecture de diagnostic** : une
   poignée de main TLS **non validante**, refermée immédiatement, qui ne transmet **aucune** donnée
   applicative — pas de requête HTTP, pas d'en-tête, pas de jeton.
4. Si la chaîne lue s'enracine dans une autorité **absente** des racines publiques livrées avec
   Java, le runner affiche, sous l'erreur :

```
        Certificat présenté par : Zscaler Inc. (CN=Zscaler Intermediate Root CA)
        Un équipement du réseau déchiffre le trafic et le re-signe. C'est le fonctionnement
        normal d'un proxy d'inspection d'entreprise.
```

5. Le code de sortie reste `5`. Le diagnostic **explique** l'échec, il ne l'annule pas.

Sur le chemin nominal (gateway joignable), la ligne `TLS :` de F-57 continue d'être affichée ; elle
emprunte désormais la **même** lecture non validante — jusqu'ici elle ouvrait une connexion
validante, qui échouait exactement là où elle aurait servi.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Échec de vol **non** TLS (DNS muet, port fermé, 407) | Aucune lecture de diagnostic — il n'y a pas de certificat à lire |
| Lecture de diagnostic impossible (connexion coupée, délai dépassé, hôte inconnu) | Silence total, message d'erreur d'origine inchangé, code `5` |
| Chaîne enracinée dans une racine **publique** (certificat expiré, nom d'hôte faux) | **Aucune** mention d'interception — faux positif interdit (D2 de F-57) |
| Gateway en clair (`http://`) | Aucune lecture : il n'y a pas de chaîne |

---

## Critères d'acceptation

- [ ] Le contrôle de vol distingue un échec TLS des autres échecs, et le dit à l'appelant.
- [ ] Sur échec TLS, le runner affiche l'émetteur nommé (organisation + CN de l'autorité).
- [ ] Sur échec non TLS, aucune lecture de diagnostic n'est tentée.
- [ ] La lecture de diagnostic **n'émet aucun octet applicatif** : le serveur qu'elle contacte ne
      reçoit qu'une poignée de main TLS, jamais une requête HTTP — vérifié par test.
- [ ] La lecture de diagnostic **ne modifie aucun réglage global** : `SSLContext.getDefault()` et la
      fabrique de sockets par défaut de `HttpsURLConnection` sont inchangés après son passage —
      vérifié par test.
- [ ] Le canal de trafic (`HttpClient` du runner) n'utilise **jamais** le contexte permissif —
      vérifié par test.
- [ ] Une chaîne publique ne produit aucune mention d'interception.
- [ ] Le code de sortie du contrôle de vol échoué reste `5`.

---

## Périmètre

### Hors scope (explicite)

- Faire **confiance** au magasin du système — c'est SF-80-02.
- Désactiver ou relâcher la vérification TLS du canal réel, sous quelque drapeau que ce soit.
- Écrire dans le magasin du poste ou dans le `cacerts` de la JDK.
- Le proxy à authentification NTLM/Kerberos (F-55, F-59).
- L'écran de mise en service (SF-80-03).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Normalisation |
|---|---|---|---|
| Délai de la lecture de diagnostic | Oui | 10 s (aligné sur le contrôle de vol) | — |
| Port sondé | Oui | port de l'URL, `443` par défaut | — |
| Nom affiché de l'émetteur | Non | `O=` du certificat présenté, puis `CN=` de l'autorité | `trim`, DN entier en repli |

---

## Technique

### Endpoint(s)

Aucun. Le runner est un client autonome.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants impactés

| Composant | Modification |
|---|---|
| `runner/.../TlsChainReader.java` | **Nouveau** — poignée de main non validante, chaîne lue, socket refermée |
| `runner/.../TlsProbe.java` | Lecture de diagnostic au lieu de la connexion validante ; `explain()` pour le chemin d'échec |
| `runner/.../TlsInspection.java` | Nom affichable de l'émetteur (`O=` + `CN=`) et message du chemin d'échec |
| `runner/.../NetworkPreflight.java` | `verify()` rend un verdict qualifié (`tlsFailure`) ; `check()` conservé |
| `runner/.../RunnerMain.java` | Sonde appelée **avant** le `return 5`, sur échec TLS seulement |

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `NetworkPreflight` — une `SSLHandshakeException` produit un verdict `tlsFailure = true`
- [ ] `NetworkPreflight` — un DNS muet, un port fermé et un `407` produisent `tlsFailure = false`
- [ ] `TlsInspection` — l'émetteur est nommé « organisation (CN=autorité) »
- [ ] `TlsInspection` — sans `O=`, seul le `CN` de l'autorité est cité
- [ ] `TlsProbe.explain` — chaîne interceptée → message nommant l'émetteur
- [ ] `TlsProbe.explain` — chaîne publique → silence
- [ ] `TlsProbe.explain` — lecture qui lève → silence
- [ ] `RunnerMain` — échec TLS : l'émetteur est nommé et le code reste `5`
- [ ] `RunnerMain` — échec non TLS : aucune ligne d'interception

### Tests d'intégration (garde-fou)

- [ ] `TlsChainReader` contacté sur un serveur en clair qui **enregistre tout** : les octets reçus
      commencent par un enregistrement de poignée de main TLS (`0x16`) et ne contiennent ni
      `GET `, ni `HTTP/1.1`, ni en-tête — **aucun octet de trafic**
- [ ] `SSLContext.getDefault()` et `HttpsURLConnection.getDefaultSSLSocketFactory()` sont identiques
      avant et après l'usage de la lecture de diagnostic
- [ ] Le `HttpClient` construit par `RunnerMain` porte le contexte SSL **par défaut** de la JVM

### Isolation utilisateur

- [x] Non applicable — le runner est un client local, aucun accès aux données de la gateway.

---

## Dépendances

### Subfeatures bloquantes

Aucune.

### Questions ouvertes impactées

- [x] `OQ-17` — tranchée par le PO le 2026-09-12. **Sans effet sur SF-80-01** : cette subfeature
      ne change aucune confiance, elle affiche.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |

---

## Notes et décisions

- **D1 — La sonde passe avant le `return 5`.** Le commentaire d'origine (« sonder une gateway
  injoignable n'apprendrait rien ») est vrai d'un DNS muet et **faux** d'un échec TLS : un échec TLS
  prouve que la connexion a abouti et que le serveur a présenté un certificat. C'est donc le seul
  échec qui déclenche la sonde.
- **D2 — Une seule lecture, non validante, pour les deux chemins.** La lecture validante d'origine
  échouait exactement là où elle sert. La garder pour le chemin nominal aurait recréé le même trou
  dès SF-80-02 (le canal réel réussirait via le magasin du système, la sonde échouerait via le
  `cacerts` seul). La lecture est donc non validante partout — elle ne décide de rien et ne
  transporte rien.
- **D3 — Le garde-fou est structurel, pas déclaratif.** Le `TrustManager` permissif vit dans un
  `SSLContext` local, jamais posé en défaut de la JVM ; la socket est refermée aussitôt après la
  poignée de main, sans qu'un seul octet applicatif soit écrit. Les deux propriétés sont tenues par
  des tests, pas par un commentaire.
- **D4 — Faux positif interdit.** Le verdict reste celui de `TlsInspection` : une chaîne enracinée
  dans une racine publique ne produit rien, même quand la poignée de main a échoué pour une autre
  raison (certificat expiré, nom d'hôte faux).
