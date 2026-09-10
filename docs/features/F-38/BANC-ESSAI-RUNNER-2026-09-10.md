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

## Le volet proxy — désormais dans l'application

**Ce protocole ne déroule plus les commandes : F-55 les a mises dans l'écran.** Quand l'étape 0
renvoie un `407` ou une absence de route, l'application ouvre un **assistant proxy** par-dessus le
parcours de mise en service — qui reste ouvert dessous, pour ne pas perdre le code d'appairage.

Ce que l'assistant fait à ta place :

- **il compose les commandes avec l'adresse que tu saisis**, au lieu de te laisser substituer
  `hote:port` dans six endroits — c'est là qu'on se trompe une fois ;
- **il force le proxy avec `-x`** plutôt que de dépendre de l'environnement du terminal : le cas
  « absence de route » est précisément celui où rien n'y est déclaré ;
- **il propose le bon relais** selon le poste *et* le verdict d'authentification — jamais `cntlm`
  sur un poste en Kerberos, puisqu'il ne porte que NTLM ;
- **il fait télécharger le relais depuis la passerelle** (F-59), et non plus depuis GitHub : `px` est
  servi par **notre domaine** — forcément autorisé chez le client, sinon rien du produit ne
  fonctionnerait — avec un bouton *et* la commande `curl` correspondante, portant l'option
  d'authentification du verdict (`--proxy-ntlm` / `--proxy-negotiate`), la version servie citée et la
  licence MIT à un clic. **GitHub reste proposé, mais en repli**, nommé comme tel ; `cntlm`, sous
  GPL, n'est pas redistribué et demeure un lien vers son éditeur ;
- **il n'affiche les commandes de redirection du runner qu'après un `200` déclaré** sur le relais :
  rediriger vers un relais qui ne porte rien reproduit la panne en donnant à croire qu'elle est
  réparée ;
- **aucun mot de passe n'entre dans le navigateur** : les identifiants d'une adresse collée sont
  retirés, et `cntlm -H` hache le secret dans ton terminal.

**Ce qu'il faut encore savoir de tête** (le motif, pour l'expliquer au client) : la JVM n'a aucun
support SSPI et l'authentification Basic est désactivée sur les tunnels depuis Java 8u111. `curl`
s'authentifie avec la session Windows, la JVM jamais — d'où le relais local.

**Ce que l'assistant ne couvre pas encore**, et qui reste à faire à la main :

- Windows antérieur à 10 (1803) → pas de `tar` pour décompresser `px.zip` ;
- macOS Intel et Linux non x86_64 → le projet amont ne publie pas ces binaires ; le chemin `pip3`
  reste la voie.

**Le `NO_PROXY` à la forme Windows (`;`) est désormais accepté par le runner** (SF-55-03) : découpé
sur la seule virgule, il devenait une entrée unique ne correspondant à aucun hôte, et toutes les
exclusions tombaient en silence. Le runner le signale sans en faire une erreur — mais `curl`, lui,
attend toujours des virgules.

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
14 bis. ☐ **L'appartenance se voit-elle ?** Chaque poste porte une couleur et des initiales, reprises
    dans la liste des projets **et dans la barre du terminal**. Deux clients ouverts côte à côte
    doivent se distinguer d'un coup d'œil — c'est le point à juger à l'œil, il n'a jamais été vu
    en vrai.
14 ter. ☐ Le menu dit-il **Forge** partout, et plus jamais « Atelier » ?
14 quater. ☐ Le runner annonce-t-il au démarrage **par où il sort** (direct, proxy, relais local) et
    sous quels droits ?
15. ☐ Le **chatbot d'aide** répond-il à « comment configurer un proxy » ?
16. ☐ Activer un **paquet de gouvernance** → l'écran annonce-t-il ce qu'il va écrire, et où ?

---

## À noter pendant la séance

Pour chaque point qui coince : **ce que tu as fait**, **ce que tu attendais**, **ce que tu as vu**,
et l'**heure** — c'est elle qui permet de retrouver le tour dans les journaux de production.

Ne corrige rien sur le moment. Un défaut noté vaut mieux qu'un défaut contourné : le contournement
efface la trace de ce qui l'a causé.
