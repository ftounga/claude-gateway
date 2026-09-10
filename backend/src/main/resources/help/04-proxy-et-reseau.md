# Proxy, réseau et pare-feu d'entreprise

C'est l'obstacle le plus fréquent en entreprise, et le plus déroutant : **le navigateur du poste
atteint l'application, mais le terminal ne sort pas**. C'est normal — le proxy est déclaré côté
système, et un shell ne le lit pas tout seul.

## Vérifier la sortie réseau AVANT de télécharger

Collez cette commande dans le terminal **qui lancera le runner** :

- Windows :
  ```
  curl.exe -sS -o NUL -w "%{http_code}\n" https://portal.ng-itconsulting.com/api/runner/download/formats
  ```
- macOS, Linux :
  ```
  curl -sS -o /dev/null -w "%{http_code}\n" https://portal.ng-itconsulting.com/api/runner/download/formats
  ```

Trois résultats possibles :

| Ce qui s'affiche | Ce que cela veut dire | Que faire |
|---|---|---|
| **`200`** | Ce terminal sort jusqu'à la passerelle | Continuez, tout va bien |
| **`407`** | Un proxy répond et **exige une authentification** | Voir « Le proxy demande une authentification » plus bas |
| **Rien**, ou `could not resolve host`, `failed to connect`, délai dépassé | Le proxy du poste n'est **pas déclaré dans ce terminal** | Retrouvez-le, puis déclarez-le — ci-dessous |

## Retrouver puis déclarer le proxy

| Système | Retrouver le proxy | Le déclarer dans ce terminal |
|---|---|---|
| Windows (PowerShell) | `netsh winhttp show proxy` | `$env:HTTPS_PROXY="http://hote:port"` |
| Windows (invite de commandes) | `netsh winhttp show proxy` | `set HTTPS_PROXY=http://hote:port` |
| macOS | `scutil --proxy` | `export HTTPS_PROXY=http://hote:port` |
| Linux | `env \| grep -i proxy` | `export HTTPS_PROXY=http://hote:port` |

La variable ne vaut que pour **ce** terminal : rouvrir une fenêtre, c'est repartir sans proxy.

Le runner honore `HTTPS_PROXY`, `HTTP_PROXY` et `NO_PROXY` (ainsi que leurs variantes en
minuscules), aussi bien pour l'appairage que pour la connexion permanente.

## Le proxy demande une authentification (`407`)

Si l'authentification est *intégrée* (NTLM ou Kerberos), la machine virtuelle Java **ne sait pas la
porter** : son client HTTP n'a aucun support SSPI, et l'authentification `Basic` est désactivée sur
les tunnels `CONNECT` depuis Java 8u111. Le produit ne fournit pas de relais d'authentification —
cela reviendrait à manipuler vos identifiants Windows.

Le remède est un **relais local** : un outil qui porte l'authentification intégrée et expose, sur
`127.0.0.1`, un proxy **sans** authentification (`px`, `cntlm` par exemple).

`px` est **servi par la passerelle elle-même** (`/api/runner/relay/windows`,
`/api/runner/relay/macos-aarch64`, `/api/runner/relay/linux-x64`) : sur un poste où GitHub est
bloqué par catégorie, c'est la seule adresse dont on soit sûr — sinon rien de ce produit ne
fonctionnerait. Le binaire est celui publié en amont, sous licence **MIT**, et sa notice accompagne
l'archive (`/api/runner/relay/license`). Rien à installer : décompressez, lancez. `cntlm`, sous
licence GPL, n'est pas redistribué : il reste à récupérer chez son éditeur.

Ensuite, dans le terminal qui lance le runner :

```
HTTPS_PROXY=http://127.0.0.1:3128
```

## Certificat d'entreprise (inspection TLS)

Si le proxy déchiffre le trafic, la machine virtuelle Java doit connaître le certificat de
l'entreprise. Utilisez le magasin de certificats standard :

```
java -Djavax.net.ssl.trustStore=/chemin/truststore.jks \
     -Djavax.net.ssl.trustStorePassword=... \
     -jar claude-runner.jar --gateway ... --workspace ...
```

## Le runner le vérifie lui-même

Au démarrage, **avant** l'appairage et avant toute connexion, le runner joint la passerelle. Si elle
répond — quel que soit le code HTTP — il affiche simplement :

```
Réseau    : gateway joignable
```

Sinon il s'arrête tout de suite, dit ce qui a échoué, donne la piste (DNS ? proxy ?) et rappelle les
gestes de votre système. Il ne tente pas l'appairage pour échouer plus loin.

## Ce qu'il faut demander à sa DSI

L'écran d'appairage sait produire une fiche à transmettre. En résumé, la demande porte sur :

- le domaine `portal.ng-itconsulting.com`, port **443** ;
- en **HTTPS** et en **WebSocket sécurisé (WSS)** — c'est le second point qui est souvent oublié,
  et la connexion permanente du runner en dépend ;
- du trafic **sortant uniquement** : aucun port entrant à ouvrir, aucune règle de NAT ;
- si le proxy exige une authentification intégrée, préciser qu'une machine virtuelle Java ne sait
  pas la porter, et demander soit une exception, soit l'autorisation d'un relais local.
