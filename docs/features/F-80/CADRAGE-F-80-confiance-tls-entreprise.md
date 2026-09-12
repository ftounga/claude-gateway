# F-80 — Le runner derrière un proxy qui déchiffre le TLS

> Cadrage du 2026-09-12, écrit pendant la séance de test du PO, sur un poste Windows/WSL
> sous **Zscaler**. Tous les éléments ci-dessous ont été **observés**, aucun n'est supposé.

## 1. Ce qui s'est passé

Le PO lance le runner dans WSL. Le contrôle de vol échoue :

```
ERREUR  La gateway n'est pas joignable (https://portal.ng-itconsulting.com/api) :
        SSLHandshakeException: (certificate_unknown) PKIX path building failed
Certificat non reconnu par Java — un proxy interceptant le TLS ?
Le truststore d'entreprise se déclare par -Djavax.net.ssl.trustStore=<fichier>.
```

Il vérifie, et obtient **200**. Le message d'erreur paraît donc faux — alors qu'il est exact :

| Commande | Magasin de confiance utilisé | Résultat |
|---|---|---|
| `curl.exe …` (binaire **Windows**) | magasin de certificats **Windows** | **200** |
| `curl …` (binaire **Linux**, dans WSL) | `/etc/ssl/certs` | **200** |
| le **runner** (JVM dans WSL) | `cacerts` **de la JDK** | **échec PKIX** |

La chaîne présentée, lue depuis WSL :

```
subject = CN=portal.ng-itconsulting.com, O=Zscaler Inc.
issuer  = CN=Zscaler Intermediate Root CA (zscaler.net) (t)
```

**Zscaler déchiffre et re-signe le trafic.** La racine Zscaler est déjà installée sur le poste —
Windows lui fait confiance, `/etc/ssl/certs` aussi. **Seule la JVM ne la connaît pas**, parce
qu'elle n'a jamais lu le magasin du système : depuis toujours, la JVM ne fait confiance qu'à son
propre `cacerts`.

Il n'y a donc **rien à demander à la DSI**. Tout est sur la machine ; c'est le runner qui regarde
au mauvais endroit.

## 2. Les trois défauts, par ordre de coût

### D1 — Le diagnostic qui existe est structurellement inatteignable

F-57 a livré `TlsProbe` + `TlsInspection` : de quoi lire la chaîne, reconnaître une racine non
publique et **nommer l'intercepteur**. C'est exactement l'information qui manquait au PO.

Or, dans `RunnerMain` :

```java
String unreachable = new NetworkPreflight(...).check(config.gatewayBaseUrl());
if (unreachable != null) {
    console.error(unreachable);
    return 5;                         // ← on sort ICI
}
console.info("Réseau    : gateway joignable");
TlsProbe.forRuntime(proxyResolver).inspect(...).ifPresent(console::info);   // ← jamais atteint
```

La sonde est placée **après** le point de sortie, avec ce commentaire : *« sonder une gateway
injoignable n'apprendrait rien »*. C'est vrai d'un DNS muet ou d'un port fermé. **C'est faux d'un
échec TLS** — un échec TLS signifie que la connexion a abouti et que le serveur a présenté un
certificat : c'est précisément le cas où la chaîne est lisible et où elle explique tout.

Et quand bien même la sonde serait atteinte, `readChain` ouvre une connexion **validante** : elle
échouerait de la même façon. Le diagnostic est donc doublement inopérant dans le seul cas qui le
justifie.

### D2 — Le message nomme le remède mais pas le moyen

« *Le truststore d'entreprise se déclare par `-Djavax.net.ssl.trustStore=<fichier>`* » est exact et
inutilisable : le fichier **n'existe pas**. Personne ne sait qu'il faut extraire une racine du
magasin système, la convertir et la ranger dans une copie de `cacerts`. Le PO a eu besoin de dix
lignes de `openssl` et `keytool` pour fabriquer ce que le système possédait déjà.

### D3 — Le test réseau de l'écran ne teste pas le chemin du runner

F-45 / SF-45-01 fait vérifier l'accès **avec `curl`**, et F-45 / SF-45-03 en fait une fiche pour la
DSI. Sur tout poste sous inspection TLS — Zscaler, Netskope, Palo Alto, un antivirus qui inspecte —
**`curl` répond 200 et le runner échoue**. L'étape censée écarter le réseau de la liste des
suspects le déclare donc innocent à tort, et envoie chercher le défaut ailleurs. C'est le défaut le
plus coûteux des trois : il ne fait pas perdre une manipulation, il fait perdre une piste.

## 3. Ce que F-80 livre

### SF-80-01 — Le diagnostic survit à l'échec

Quand le contrôle de vol échoue sur une `SSLException`, le runner sonde la chaîne **sans la
valider** — une lecture de diagnostic, jamais un canal de trafic — et dit ce qu'il voit :

```
ERREUR  La gateway n'est pas joignable : le certificat présenté n'est pas signé par une
        autorité que Java connaît.
        Certificat présenté par : Zscaler Inc. (CN=Zscaler Intermediate Root CA)
        Un équipement du réseau déchiffre le trafic et le re-signe. C'est le
        fonctionnement normal d'un proxy d'inspection d'entreprise.
```

**Garde-fou non négociable** : cette lecture non validante sert **au seul diagnostic**. Aucun octet
de trafic ne passe par elle, et elle ne relâche jamais la vérification du canal réel. Le principe
de F-57 tient : le runner *affiche*, il ne *contourne* pas.

### SF-80-02 — Le runner sait faire confiance au magasin du système

Le runner construit son truststore en **additionnant** le `cacerts` de la JDK et le magasin du
système d'exploitation :

| Système | Source |
|---|---|
| Linux / WSL | `/etc/ssl/certs/ca-certificates.crt` (et les variantes RHEL) |
| macOS | `KeychainStore` |
| Windows | `Windows-ROOT` |

Ce n'est **pas** un relâchement : c'est exactement la confiance que le navigateur et `curl`
accordent déjà sur ce poste. Un runner qui refuse ce que le système accepte n'est pas plus sûr, il
est seulement inutilisable.

**Et il le dit.** La ligne de transparence du démarrage (F-57) gagne une mention :

```
Confiance : magasin de la JDK + magasin du système (racine d'entreprise détectée : Zscaler Inc.)
```

**Question ouverte, à trancher par le PO — OQ-17** : automatique, ou sur drapeau explicite ?
- *Automatique* : personne n'est bloqué, et le comportement rejoint celui du navigateur. Mais le
  runner fait alors confiance à un équipement qui déchiffre, **sans que l'utilisateur ait rien
  demandé** — même si le système le fait déjà dans son dos.
- *Sur drapeau* (`--trust-system`) : le geste est conscient. Mais chaque poste d'entreprise devra
  le poser, et le premier lancement échouera toujours.

*Recommandation* : **automatique et annoncé**, la mention au démarrage portant l'information. Le
drapeau inverse (`--no-system-trust`) reste disponible pour qui veut la confiance stricte.
**À CONFIRMER PAR LE PO.**

### SF-80-03 — L'écran teste le chemin du runner, pas celui de curl

Le test d'accès réseau de la mise en service ne peut plus répondre « la voie est libre » sur la foi
d'un `curl`. Deux ajouts :

1. La commande proposée porte une **mise en garde nommée** : sous Windows, `curl.exe` valide avec
   le magasin **Windows**, et le runner avec celui de la **JVM** — un 200 ici ne garantit pas le
   runner.
2. Le **contrôle de vol du runner** (`--check`, SF-38-25) devient le test de référence, et l'écran
   le propose : c'est le seul qui emprunte exactement le chemin du runner.

La fiche « Pour votre DSI » (SF-45-03) gagne une ligne : **inspection TLS** — si le poste déchiffre
le trafic, la racine d'inspection doit être connue du runner (ce que SF-80-02 règle seul).

## 4. Hors périmètre

- **Désactiver la vérification TLS**, sous quelque drapeau que ce soit. Un produit qui exécute des
  commandes sur la machine d'un client ne négocie pas là-dessus.
- Installer une autorité dans le magasin du système, ou modifier le `cacerts` de la JDK : le runner
  **lit**, il n'écrit pas dans la configuration du poste.
- Détecter *quel* éditeur intercepte au-delà de ce que le certificat déclare lui-même.
- Le proxy à authentification NTLM/Kerberos : c'est F-55 et F-59, un problème distinct.

## 5. Impact transversal

| Préoccupation | Composants touchés |
|---|---|
| Aucune (ni auth, ni tenant, ni plans, ni routing) | — |
| Runner | `NetworkPreflight`, `RunnerMain`, `TlsProbe`, `TlsInspection`, `Failures`, `StartupDisclosure`, `RunnerConfig` |
| Gateway / écran | `runner-pairing-dialog` (étape « Vérifier l'accès réseau »), fiche DSI (SF-45-03) |
| Paquets | les trois paquets `jlink` embarquent leur propre `cacerts` : SF-80-02 doit valoir pour eux aussi |

## 6. Plan de test minimal

- Chaîne présentée par une racine **absente** du `cacerts` mais **présente** dans le magasin
  système → le runner démarre, et l'annonce.
- Chaîne présentée par une racine absente des **deux** → le runner **refuse**, et nomme l'émetteur.
- Chaîne publique ordinaire → **aucune mention** ajoutée au démarrage (faux positif interdit, D2 de
  F-57).
- Magasin système **absent ou illisible** (conteneur minimal) → repli silencieux sur le `cacerts`
  de la JDK, jamais une erreur.
- Sonde de diagnostic : elle ne doit **jamais** transporter de trafic — vérifié par test.
