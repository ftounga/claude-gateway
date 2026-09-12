# Mini-spec — F-80 / SF-80-02 — Le runner fait confiance au magasin du système, et le dit

## Identifiant

`F-80 / SF-80-02`

## Feature parente

`F-80` — Le runner derrière un proxy qui déchiffre le TLS

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-80-02-confiance-magasin-systeme`

---

## Objectif

Le runner **additionne** le `cacerts` de la JDK et le magasin de certificats du système
d'exploitation, **sans rien demander**, et **annonce** au démarrage ce à quoi il fait confiance —
en nommant la racine d'entreprise détectée.

---

## Comportement attendu

### Cas nominal

1. Au démarrage, avant toute connexion de trafic, le runner construit son truststore :
   le `cacerts` de la JDK **plus** le magasin du système.

   | Système | Source lue |
   |---|---|
   | Linux / WSL | `/etc/ssl/certs/ca-certificates.crt`, puis les variantes RHEL (`/etc/pki/tls/certs/ca-bundle.crt`, `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem`, `/etc/ssl/ca-bundle.pem`) |
   | macOS | `KeychainStore-ROOT`, puis `KeychainStore` |
   | Windows | `Windows-ROOT` |

2. **Additionner, jamais remplacer** : une racine publique retirée du magasin d'un poste ne cesse
   pas d'être reconnue, puisque le `cacerts` de la JDK reste intégralement chargé.
3. Le `HttpClient` du runner — donc aussi le WebSocket, qui en dérive — porte ce truststore.
4. La déclaration de démarrage (`StartupDisclosure`, F-57) gagne une ligne :

```
Confiance : magasin de la JDK + magasin du système (racine d'entreprise détectée : Zscaler Inc.)
```

5. La racine nommée est **celle que la gateway présente réellement**, lue par la sonde non validante
   de SF-80-01 — jamais une racine moissonnée dans le magasin du poste.
6. `--no-system-trust` (ou `CLAUDE_RUNNER_NO_SYSTEM_TRUST=true`) rétablit la confiance stricte :

```
Confiance : magasin de la JDK seul (--no-system-trust)
```

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Magasin système **absent** (conteneur minimal) | **Repli silencieux** sur le `cacerts` de la JDK. Aucune erreur, aucune ligne. |
| Magasin système **illisible** (droits, fichier corrompu) | Idem : repli silencieux, aucune erreur. |
| Magasin système lu mais n'ajoutant **aucune** racine | Aucune ligne — il n'y a rien à dire. |
| `cacerts` de la JDK illisible | Repli sur le contexte par défaut de la JVM ; le runner ne se prive jamais de démarrer. |
| Chaîne de la gateway **publique** | La ligne `Confiance` apparaît **sans** mention de racine d'entreprise — faux positif interdit (D2 de F-57). |
| Gateway injoignable au démarrage | Aucune mention de racine ; la ligne `Confiance` reste, elle décrit une configuration, pas un réseau. |

---

## Critères d'acceptation

- [ ] Une racine **absente** du `cacerts` mais **présente** dans le magasin système est acceptée :
      le runner démarre.
- [ ] Une racine absente des **deux** est refusée : le runner échoue, et nomme l'émetteur (SF-80-01).
- [ ] Les racines du `cacerts` restent **toutes** reconnues, même si le magasin du poste en a retiré.
- [ ] Une chaîne publique ordinaire ne produit **aucune** mention de racine d'entreprise.
- [ ] Magasin système absent ou illisible → repli silencieux, **jamais** une erreur, aucune ligne.
- [ ] `--no-system-trust` rétablit la confiance stricte, et le dit.
- [ ] La ligne `Confiance` nomme la racine d'entreprise **détectée sur notre propre connexion**.
- [ ] Le truststore construit est celui du `HttpClient` du runner (donc du WebSocket).
- [ ] Les paquets `jlink` embarquent le module qui rend le magasin du système lisible
      (`jdk.crypto.mscapi` sous Windows) — vérifié par test sur le script d'empaquetage.

---

## Périmètre

### Hors scope (explicite)

- **Désactiver la vérification TLS**, sous quelque drapeau que ce soit.
- **Écrire** dans le magasin du poste, ou modifier le `cacerts` de la JDK. Le runner **lit**.
- Détecter *quel* éditeur intercepte au-delà de ce que le certificat déclare lui-même.
- Le proxy à authentification NTLM/Kerberos (F-55, F-59).
- L'écran de mise en service (SF-80-03).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Défaut | Normalisation |
|---|---|---|---|---|
| `--no-system-trust` | Non | drapeau seul, ou `true`/`1`/`yes`/`oui` | absent (confiance système **active**) | minuscules, `trim` |
| `CLAUDE_RUNNER_NO_SYSTEM_TRUST` | Non | idem | absent | idem |
| Délai de l'observation TLS de démarrage | Oui | 5 s | — | — |
| Type du magasin construit en mémoire | Oui | `PKCS12` | — | — |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants impactés

| Composant | Modification |
|---|---|
| `runner/.../TrustStores.java` | **Nouveau** — additionne `cacerts` et magasin système, repli silencieux |
| `runner/.../RunnerConfig.java` | `--no-system-trust` / `CLAUDE_RUNNER_NO_SYSTEM_TRUST`, accesseur `systemTrust()` |
| `runner/.../StartupDisclosure.java` | Ligne `Confiance :`, omise quand il n'y a rien à dire |
| `runner/.../RunnerMain.java` | Truststore résolu avant la déclaration ; observation TLS unique, réutilisée trois fois |
| `runner/.../TlsProbe.java` | `observe()` exposée pour la réutilisation ; messages dérivés d'une observation |
| `runner/.../TlsChainReader.java` | Délai paramétrable (5 s au démarrage) |
| `runner/package-windows.sh` | Module `jdk.crypto.mscapi` (sans lui, `Windows-ROOT` n'existe pas dans l'image) |
| `runner/package-macos.sh` | Commentaire : `KeychainStore` vit dans `java.base` sur macOS, rien à ajouter |

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `TrustStores` — le magasin construit contient **toutes** les racines du `cacerts`
- [ ] `TrustStores` — une racine présente seulement dans la source système est ajoutée et comptée
- [ ] `TrustStores` — une racine présente dans les deux n'est comptée qu'une fois
- [ ] `TrustStores` — source système absente → repli silencieux, `systemStoreUsed = false`, aucune levée
- [ ] `TrustStores` — source système illisible (fichier de bruit) → repli silencieux
- [ ] `TrustStores` — confiance stricte demandée → aucune lecture du magasin système
- [ ] `RunnerConfig` — `--no-system-trust`, `--no-system-trust=false`, variable d'environnement
- [ ] `RunnerConfig` — par défaut, la confiance système est **active** (OQ-17)
- [ ] `StartupDisclosure` — ligne `Confiance` avec racine nommée
- [ ] `StartupDisclosure` — ligne `Confiance` **sans** racine quand rien n'est détecté
- [ ] `StartupDisclosure` — **aucune** ligne quand le magasin système n'est pas utilisable
- [ ] `StartupDisclosure` — `--no-system-trust` : la ligne le dit

### Tests d'intégration

- [ ] Un `SSLContext` bâti sur une racine de test **accepte** un serveur signé par elle, et
      **refuse** un serveur signé par une autre — la confiance additionnée reste une confiance
- [ ] Le `HttpClient` du runner porte le contexte de `TrustStores`, pas celui par défaut,
      quand la confiance système est active
- [ ] `package-windows.sh` déclare `jdk.crypto.mscapi` dans ses modules `jlink`

### Isolation utilisateur

- [x] Non applicable — client local, aucun accès aux données de la gateway.

---

## Dépendances

### Subfeatures bloquantes

- `SF-80-01` — **done** (fournit la lecture non validante réutilisée par l'observation de démarrage)

### Questions ouvertes impactées

- [x] `OQ-17` — **tranchée par le PO le 2026-09-12** : confiance au magasin du système
      **automatique et annoncée**, avec `--no-system-trust` pour la confiance stricte. Cette
      subfeature implémente exactement cette décision.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |
| **Canal TLS du runner** | **Oui** | `RunnerMain.buildHttpClient` (appairage HTTP, long-polling, WebSocket via `httpClient.newWebSocketBuilder()`), `PairingClient`, `HttpPollingClient`, `FrameSender`, `RunnerConnection` — tous dérivent du **même** `HttpClient`, donc du même truststore. Aucun autre point ne construit de client HTTP. `TlsChainReader` reste à l'écart : il ne transporte rien (SF-80-01). |

---

## Notes et décisions

- **D1 — Additionner, jamais remplacer.** Charger le magasin du système *à la place* du `cacerts`
  ferait qu'un poste ayant retiré une racine publique cesserait de reconnaître les serveurs qui en
  dépendent. Les deux sources sont fusionnées, le `cacerts` en premier.
- **D2 — La racine nommée vient de NOTRE connexion, jamais du magasin du poste.** `StartupDisclosure`
  écrit noir sur blanc que tout ce qu'il affiche vient de la configuration du runner, « jamais d'une
  inspection du poste » (F-57). Et le magasin racine de Windows contient des centaines de racines
  absentes du `cacerts` qui ne sont pas des racines d'entreprise : en nommer une serait le faux
  positif que D2 interdit. La seule détection certaine est celle de SF-80-01 — la racine qui signe
  réellement la chaîne présentée.
- **D3 — Une seule poignée de main au démarrage, réutilisée trois fois.** Nommer la racine dans la
  ligne `Confiance` suppose de l'avoir observée avant la déclaration. L'observation est donc faite
  une fois, avant le bloc de transparence, avec un délai court (5 s), et sert ensuite à la ligne
  `TLS :` du chemin nominal comme au diagnostic d'échec. Aucun octet applicatif n'y transite
  (garde-fou de SF-80-01).
- **D4 — Le repli est silencieux, pas discret.** Un conteneur minimal sans `/etc/ssl/certs` est un
  cas normal, pas une anomalie : il ne produit ni erreur, ni avertissement, ni ligne. Ce qui serait
  anormal, c'est qu'un runner refuse de démarrer parce qu'un fichier facultatif manque.
- **D5 — Les paquets d'abord.** Les trois paquets embarquent leur propre runtime : sans
  `jdk.crypto.mscapi`, `KeyStore.getInstance("Windows-ROOT")` lève dans l'image Windows et la
  subfeature ne servirait qu'aux lancements par `.jar` — c'est-à-dire à personne sur un poste
  d'entreprise. Sur macOS, le fournisseur `Apple` (`KeychainStore`) vit dans `java.base` : rien à
  ajouter, et le repli silencieux couvre le cas contraire. Le paquet « relais » (`px`) ne contient
  aucune JVM : il n'est pas concerné.
- **D6 — Ce n'est pas un relâchement.** C'est exactement la confiance que le navigateur et `curl`
  accordent déjà sur ce poste. Un runner qui refuse ce que le système accepte n'est pas plus sûr, il
  est seulement inutilisable.
