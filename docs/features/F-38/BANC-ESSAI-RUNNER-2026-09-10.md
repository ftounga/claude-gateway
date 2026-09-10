# Banc d'essai runner — protocole du 2026-09-10

> Première mise en service **après F-48** : le poste est désormais l'unité. Un seul appairage
> pour toute une racine. À dérouler chez un client, dans l'ordre, en notant ce qui coince.

---

## Avant de partir — 10 minutes, chez toi

| | Vérification | Pourquoi |
|---|---|---|
| ☐ | Emporter les deux paquets macOS **et** le paquet Windows sur une clé | Un poste sans accès à la gateway ne peut rien télécharger |
| ☐ | Emporter `scripts/setup-runner-macos.sh` | Le filet quand le proxy résiste |
| ☐ | Vérifier depuis chez toi : `curl -sS -o /dev/null -w "%{http_code}" https://portal.ng-itconsulting.com/api/actuator/health` → **200** | Écarte la gateway de la liste des suspects |
| ☐ | Créer le compte du client **avant** d'arriver, ou vérifier qu'il peut en créer un | La création de compte demande un e-mail vérifiable |

---

## Étape 0 — Le réseau, avant tout le reste

**C'est l'étape qui a coûté trois heures la première fois.** Elle se fait avant tout téléchargement.

```bash
curl -sS -o /dev/null -w "%{http_code}\n" --max-time 20 \
  https://portal.ng-itconsulting.com/api/runner/download/formats
```

| Résultat | Ce que ça dit | Où aller |
|---|---|---|
| **200** | La voie est libre | Étape 1 |
| **407** | Proxy à authentification | Étape 0 bis |
| **000** / « Could not resolve host » | Aucun proxy déclaré dans ce terminal, et pas de sortie directe | Étape 0 ter |
| **403 / page HTML** | Filtrage de catégorie — le domaine est bloqué, pas le réseau | Demande DSI, rien à faire sur le poste |

### Étape 0 bis — 407 : le proxy exige une authentification

```bash
curl -sS -o /dev/null -w "Kerberos : %{http_code}\n" --proxy-negotiate --proxy-user : <URL>
curl -sS -o /dev/null -w "NTLM     : %{http_code}\n" --proxy-ntlm      --proxy-user : <URL>
```

**200 sur l'un des deux** → l'authentification intégrée passe pour `curl`, mais **la JVM ne sait pas
la faire** : il faut un relais local. Voir le volet proxy ci-dessous.

**407 partout** → identifiants applicatifs exigés. Rien à faire sur le poste : c'est une demande DSI
(exception de domaine, ou compte de service). La fiche « Pour votre DSI » de l'écran est faite pour ça.

### Étape 0 ter — Trouver le proxy quand rien n'est déclaré

Le piège : **le navigateur y arrive**, parce que le proxy est configuré côté système, pas dans le shell.

```bash
# Windows
netsh winhttp show proxy
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v AutoConfigURL

# macOS
scutil --proxy

# Linux
env | grep -i proxy
```

**`AutoConfigURL` renvoie un `.pac`** → ouvre-le dans le navigateur : l'adresse est écrite en clair
sur une ligne `PROXY hôte:port`. C'est exactement ce qui nous a débloqués la première fois.

Puis, dans **ce** terminal :

```bash
export HTTPS_PROXY=http://hote:port
export HTTP_PROXY=http://hote:port
export NO_PROXY=".domaine-interne.local,localhost,127.0.0.1"
```

⚠️ **Windows sépare le `NO_PROXY` par des `;`, curl et le runner attendent des `,`.**

---

## Le volet proxy — le relais local, pas à pas

À faire **uniquement** si l'étape 0 bis a donné 200 avec `--proxy-ntlm` ou `--proxy-negotiate`.

**Pourquoi c'est nécessaire** : `java.net.http.HttpClient` n'a aucun support SSPI, et
l'authentification Basic est désactivée sur les tunnels depuis Java 8u111. Le navigateur et `curl`
s'authentifient avec la session Windows ; la JVM, jamais. Un relais local porte l'authentification
à sa place et expose un proxy **sans authentification** sur `127.0.0.1`.

### Windows — px

```bash
winget install genotrance.px          # souvent bloqué : WinHTTP n'a pas de proxy configuré
# sinon, binaire autonome :
curl -sS --proxy-ntlm --proxy-user : -L \
  https://api.github.com/repos/genotrance/px/releases/latest | grep browser_download_url
curl -sS --proxy-ntlm --proxy-user : -L -o px.zip "<url windows-amd64>"
unzip px.zip -d px && ./px/px.exe --proxy=hote:port --port=3128
```

### macOS — px (Apple Silicon) ou cntlm (Intel)

```bash
# Apple Silicon : binaire publié
curl -sS --proxy-ntlm --proxy-user : -L -o px.tar.gz "<url mac-arm64>"
tar -xzf px.tar.gz && ./px --proxy=hote:port --port=3128

# Intel : pas de binaire publié
pip3 install --user px-proxy && px --proxy=hote:port --port=3128
# ou, si Homebrew existe :
brew install cntlm
```

### cntlm — l'alternative universelle

`/etc/cntlm.conf` (ou `cntlm.ini` sous Windows) :

```
Username    <identifiant>
Domain      <DOMAINE>
Proxy       hote:port
Listen      3128
NoProxy     localhost, 127.0.0.*
```

Mot de passe **haché**, jamais en clair : `cntlm -H -d DOMAINE -u identifiant` produit les lignes
`PassNTLMv2` à coller dans le fichier. Puis `cntlm -f` pour le lancer au premier plan.

### Vérifier le relais, puis y rediriger le runner

```bash
# dans un SECOND terminal — le relais doit rester lancé
curl -sS -o /dev/null -w "via le relais : %{http_code}\n" \
  -x http://127.0.0.1:3128 https://portal.ng-itconsulting.com/api/actuator/health
```

**200 sans `--proxy-ntlm`** = le relais porte l'authentification. Alors seulement :

```bash
export HTTPS_PROXY=http://127.0.0.1:3128
export HTTP_PROXY=http://127.0.0.1:3128
```

⚠️ Le relais doit **rester ouvert** tant que le runner tourne. Ferme-le, la connexion tombe.

---

## Étape 1 — Le poste

1. ☐ Créer un **poste** dans l'application, nommé du client (ex. `CAGIP`)
2. ☐ Déclarer la **racine** : le dossier qui contient les projets (ex. `~/dev`, `C:\dev`)
3. ☐ Générer le code d'appairage — **maintenant seulement** : il expire en 5 minutes
4. ☐ Télécharger le format proposé (le poste consulté est présélectionné)
5. ☐ Lancer le runner

**Ce qu'on vérifie ici** : un seul appairage suffit pour toute la racine. C'est le gain de F-48.

## Étape 2 — Les projets

6. ☐ Ouvrir un **premier projet** = un dossier sous la racine → aucun code, aucune installation
7. ☐ Ouvrir un **second projet** → même chose, et **sa propre conversation**
8. ☐ Vérifier que le projet A ne peut pas lire le projet B *(confinement par sous-dossier)*

## Étape 3 — L'exécution

9. ☐ Demander un listing → doit répondre **sans demander d'autorisation** (porte désarmée par défaut)
10. ☐ Armer la porte dans les réglages, redemander une commande → l'invite doit **apparaître tout de suite**
11. ☐ Cliquer « Autoriser » → la commande s'exécute
12. ☐ Vérifier le journal d'audit

## Étape 4 — Le reste

13. ☐ Le **guide d'accueil** se déclenche-t-il à la première connexion ?
14. ☐ La **vue d'ensemble** montre-t-elle le poste, son système, son interpréteur, ses projets ?
15. ☐ Le **chatbot d'aide** répond-il à « comment configurer un proxy » ?
16. ☐ Activer un **paquet de gouvernance** → l'écran annonce-t-il ce qu'il va écrire, et où ?

---

## À noter pendant la séance

Pour chaque point qui coince : **ce que tu as fait**, **ce que tu attendais**, **ce que tu as vu**,
et l'**heure** — c'est elle qui permet de retrouver le tour dans les journaux de production.

Ne corrige rien sur le moment. Un défaut noté vaut mieux qu'un défaut contourné : le contournement
efface la trace de ce qui l'a causé.
