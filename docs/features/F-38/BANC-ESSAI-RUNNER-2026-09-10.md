# Banc d'essai runner — protocole du 2026-09-10

> Première mise en service **après F-48** : le poste est désormais l'unité. Un seul appairage
> pour toute une racine. À dérouler chez un client, dans l'ordre, en notant ce qui coince.

> **Mis à jour le 2026-09-12 — le parcours a changé (F-68 → F-71).** Le protocole reste le même
> sur le fond ; ce sont les écrans qui ont bougé. Ce qu'il faut savoir avant de dérouler :
>
> - **L'onglet « Postes » n'existe plus.** La vue des postes **est** la page d'accueil de la Forge
>   (`/forge`) : on arrive sur ses **missions**, et l'on entre dans un projet pour travailler.
>   `/postes` **redirige** — un lien collé la veille s'ouvre au bon endroit (F-68).
> - **Un fil d'Ariane** « Forge › CAGIP › mon-projet » remplace l'onglet retiré, chaque niveau
>   cliquable, le niveau du client ramenant à **sa** carte (F-68).
> - **Le dossier d'un projet ne se tape plus** : le runner liste les sous-dossiers de la racine, on
>   clique, on descend, on remonte ; un dossier **déjà ouvert** est marqué et non proposé (F-71).
> - **Un projet se supprime** depuis la liste de `/atelier` ; **un poste** depuis sa carte sur
>   `/forge`, et **seulement s'il ne lui reste aucun projet** (F-69).
> - **Quatre terminaux vivants au maximum**, avec un signe de vie visible aux deux endroits et un
>   compteur permanent sur `/forge` (F-70).
> - **Les projets sans machine** — dépôt GitHub, archive importée — se rangent sous un poste
>   virtuel **« Hébergé »**, en dernier sur la page d'accueil (F-71).

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

0. ☐ Se connecter → **on arrive sur `/forge`**, la page d'accueil des missions. Vérifier qu'**aucun
   onglet « Postes »** ne subsiste dans le menu *(F-68)*
1. ☐ Créer un **poste** dans l'application, nommé du client (ex. `CAGIP`)
2. ☐ Déclarer la **racine** : le dossier qui contient les projets (ex. `~/dev`, `C:\dev`)
3. ☐ Générer le code d'appairage — **maintenant seulement** : il expire en 5 minutes
4. ☐ Télécharger le format proposé (le poste consulté est présélectionné)
5. ☐ Lancer le runner

**Ce qu'on vérifie ici** : un seul appairage suffit pour toute la racine. C'est le gain de F-48.

⚠️ **Le parcours part encore du projet, pas du poste** — c'est le défaut connu que F-72 corrigera.
Le noter s'il gêne, mais ne pas le traiter comme une surprise.

## Étape 2 — Les projets

6. ☐ Ouvrir un **premier projet** : le dialogue **n'a plus de champ de chemin** — le runner liste
   les sous-dossiers de la racine et **l'on clique**. Vérifier qu'on peut **descendre** d'un
   niveau, **remonter**, et choisir la **racine** elle-même *(F-71)*
7. ☐ Ouvrir un **second projet** → même chose, et **sa propre conversation**
8. ☐ Rouvrir l'explorateur : le dossier du premier projet doit être marqué **« déjà ouvert »** et
   **non sélectionnable** — c'est le défaut vécu, deux entités du même nom *(F-71)*
9. ☐ **Runner arrêté**, rouvrir l'explorateur → un encart doit **le dire**, avec un bouton
   « Réessayer », et **jamais une liste vide** *(F-71)*
10. ~~☐ Vérifier que le projet A ne peut pas lire le projet B *(confinement par sous-dossier)*~~
    **Point retiré** : le confinement annoncé n'existe pas pour `bash` — vérifié dans le code le
    2026-09-12 (`BashTool` fait passer le **répertoire de départ** par le `PathGuard`, jamais la
    commande elle-même). Ne pas le tester, il échouerait ; F-73 retire la promesse et rearme la
    porte de confirmation par défaut.
11. ☐ **Le fil d'Ariane** : dans un projet, il affiche « Forge › CAGIP › mon-projet » ; chaque
    niveau est cliquable, et le niveau du client ramène à **sa** carte *(F-68)*

## Étape 3 — L'exécution

12. ☐ Demander un listing → doit répondre **sans demander d'autorisation** (porte désarmée par défaut)
13. ☐ Armer la porte dans les réglages, redemander une commande → l'invite doit **apparaître tout de suite**
14. ☐ Cliquer « Autoriser » → la commande s'exécute
15. ☐ Vérifier le journal d'audit

## Étape 3 bis — Plusieurs terminaux *(F-70)*

16. ☐ Ouvrir **deux terminaux** sur deux projets, et lancer une demande dans chacun : les **deux**
    doivent avancer réellement — aucun ne doit rester sur « en cours » sans rien produire
17. ☐ Vérifier le **signe de vie** : pastille + le mot **« connecté »** dans la barre du terminal,
    et **la même** sur la carte du poste (« Terminal connecté »). Jamais la couleur seule
18. ☐ Lire le compteur permanent de `/forge` : **« Terminaux vivants : n / 4 — chaque terminal
    vivant consomme un tour en parallèle »**
19. ☐ Ouvrir un **cinquième** terminal → refus explicite, le bandeau **nomme** les quatre à fermer,
    et **l'envoi est fermé**. C'est un garde-fou de **dépense**, pas une limite technique
20. ☐ Fermer brutalement un onglet, attendre ~1 min 30 → la place doit **se libérer** d'elle-même

## Étape 3 ter — Faire le ménage *(F-69)*

21. ☐ Supprimer un projet **depuis la liste de `/atelier`** — la seule vue qui montre **tous** les
    projets, rattachés à un poste ou non
22. ☐ **Lire le dialogue avant de confirmer** : il écrit, en deux listes de même poids, ce qui part
    (conversation, historique, réglages, journal) et **ce qui ne bouge pas — le dossier sur votre
    machine en tête**. C'est le point à juger : la phrase rassure-t-elle vraiment le client ?
23. ☐ Confirmer, puis **vérifier sur la machine** que le dossier est toujours là, intact
24. ☐ Essayer de supprimer un **poste qui porte encore des projets** → **refus** nommant le poste,
    comptant les projets restants et disant où les trouver. Pas de cascade
25. ☐ Supprimer les projets, puis le poste → doit passer

## Étape 4 — Le reste

26. ☐ Le **guide d'accueil** se déclenche-t-il à la première connexion ?
27. ☐ La **vue d'ensemble** montre-t-elle le poste, son système, son interpréteur, ses projets ?
27 bis. ☐ **L'appartenance se voit-elle ?** Chaque poste porte une couleur et des initiales, reprises
    dans la liste des projets **et dans la barre du terminal**. Deux clients ouverts côte à côte
    doivent se distinguer d'un coup d'œil — c'est le point à juger à l'œil, il n'a jamais été vu
    en vrai.
27 ter. ☐ Le menu dit-il **Forge** partout, et plus jamais « Atelier » ?
27 quater. ☐ Le runner annonce-t-il au démarrage **par où il sort** (direct, proxy, relais local) et
    sous quels droits ?
27 quinquies. ☐ **Le poste « Hébergé »** *(F-71)* : importer une archive ou ouvrir un dépôt — le
    projet doit apparaître sous une carte **« Hébergé »**, placée **en dernier**, sans appairage,
    sans état de connexion, sans suppression de poste. Elle ne doit **apparaître que si elle porte
    quelque chose**. À juger à l'œil : se distingue-t-elle bien d'une vraie machine ?
28. ☐ Le **chatbot d'aide** répond-il à « comment configurer un proxy » ?
29. ☐ Activer un **paquet de gouvernance** → l'écran annonce-t-il ce qu'il va écrire, et où ?

---

## À noter pendant la séance

Pour chaque point qui coince : **ce que tu as fait**, **ce que tu attendais**, **ce que tu as vu**,
et l'**heure** — c'est elle qui permet de retrouver le tour dans les journaux de production.

Ne corrige rien sur le moment. Un défaut noté vaut mieux qu'un défaut contourné : le contournement
efface la trace de ce qui l'a causé.
