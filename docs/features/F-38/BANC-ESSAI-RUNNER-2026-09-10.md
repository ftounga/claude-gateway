# Banc d'essai runner — protocole du 2026-09-10, révisé le 2026-09-12

> **Le parcours a entièrement changé le 2026-09-12** (F-72 à F-76), après la séance de test du PO.
> On **connecte un poste**, puis on y **ajoute des projets** — et non plus l'inverse. Le
> **confinement n'existe plus** et la **porte de confirmation est réarmée par défaut**. Le poste a
> son **propre terminal**. La **gouvernance s'active par poste**, et l'on **lit les fichiers avant
> d'accepter**. Enfin, une **vue de supervision** montre les terminaux au travail.
>
> Le poste reste l'unité (F-48) : un seul appairage pour toute une racine. À dérouler chez un
> client, dans l'ordre, en notant ce qui coince.

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

## Étape 1 — Connecter un poste

**Le parcours a entièrement changé (F-72) : on part de la machine, plus du projet.** À la racine de
la Forge, « Nouveau projet » **a disparu** ; il ne reste que **« Connecter un poste »**.

1. ☐ « Connecter un poste » → le nommer du nom du **client** (ex. `CAGIP`). **Le nom n'est demandé
   qu'une fois** : plus de seconde saisie dans le dialogue d'appairage, plus deux entités du même nom
2. ☐ Vérifier l'accès réseau — l'étape 0 ci-dessus, désormais **dans l'écran** (F-45, F-55)
3. ☐ Générer le code d'appairage — **maintenant seulement** : il expire en 5 minutes
4. ☐ Télécharger le format proposé (le poste consulté est présélectionné)
5. ☐ Lancer le runner, qui **déclare lui-même sa racine**
6. ☐ **Vérifier qu'à ce stade le poste ne porte aucun projet, et que l'écran le dit** : on vient de
   brancher une machine, c'est normal. Un écran qui aurait seulement l'air vide est un défaut à noter

**Ce qu'on vérifie ici** : un seul appairage pour toute la racine (F-48), et **une seule intention,
un seul nom** (F-72). Le parcours de mise en service est le même qu'avant — proxy, `407`, paquet
autonome, commande de reprise —, seul son **mode** change ; il se déduit de l'absence de projet.

## Étape 2 — Y ajouter des projets

7. ☐ Sur la carte du poste, **« Ajouter un projet »** → l'explorateur liste les dossiers de la
   racine ; **cliquer** un dossier suffit — aucun chemin à taper, aucun nom à redonner
8. ☐ Recommencer pour un **second projet** → **sans jamais réappairer**, et avec **sa propre
   conversation**. C'est le bénéfice de F-48, enfin atteignable depuis l'écran
9. ☐ Re-cliquer le **même dossier** → l'écran doit **refuser** en **nommant** le projet déjà ouvert
   (et non faire semblant de réussir)
10. ☐ Les dossiers **non encore ouverts** apparaissent-ils sur la carte, **en retrait** ? On voit ce
    que la machine contient **sans que rien ne soit créé** ; au-delà de huit, la liste est
    **tronquée et le dit** ; le bruit (dossiers cachés, `node_modules`, `.runnerignore`) n'y figure pas
11. ☐ Un **dépôt GitHub** ou une **archive** n'a pas de machine : passer par la carte **« Hébergé »**
    (F-71), désormais **toujours affichée, même vide**, puisqu'elle porte ces gestes

> **Le confinement par sous-dossier n'existe plus** (F-73). L'ancien point « vérifier que le projet A
> ne peut pas lire le projet B » **est retiré du protocole** : il éprouvait une promesse qui n'a
> jamais été tenue pour `bash`. Ce n'est pas un défaut à noter — c'est la décision.

## Étape 3 — Le terminal du poste

12. ☐ Sur la carte d'une **machine** (jamais sur « Hébergé », qui n'en est pas une),
    **« Terminal du poste »** → un terminal **à la racine, sans projet** : c'est là qu'on fait un
    `git clone`, un VPN, un `terraform`, l'installation d'un outil — **et tout le premier jour, quand
    la racine est vide et qu'il n'y a aucun dossier à ajouter** (F-74)
13. ☐ Vérifier qu'il **n'apparaît pas comme un projet** sur la carte, qu'il se **nomme** dans la
    liste latérale, et qu'il porte le même **signe de vie** que les autres — il compte dans le
    plafond de quatre (F-70)

## Étape 4 — L'exécution, porte armée

14. ☐ Demander une commande → **l'invite doit apparaître** : la porte de confirmation est
    **réarmée par défaut** (F-73), à l'inverse du protocole précédent. Les projets créés avant
    gardent le réglage qu'ils avaient
15. ☐ **L'invite dit-elle la portée** — ce que la commande peut atteindre ? C'est ce qui remplace le
    confinement : un utilisateur informé décide, un utilisateur rassuré à tort ne décide pas
16. ☐ Cliquer « Autoriser » → la commande s'exécute
17. ☐ Désarmer la porte dans les réglages → la commande suivante passe sans invite
18. ☐ Vérifier le **journal d'audit**, puis le **coupe-circuit**
19. ☐ Au démarrage, le runner annonce-t-il sa **portée**, **par où il sort** (direct, proxy, relais
    local) et **sous quels droits** ?

## Étape 5 — Voir travailler ses terminaux

20. ☐ Ouvrir **deux ou trois terminaux** sur des clients différents, les faire travailler, puis
    **« Voir travailler »** depuis l'accueil de la Forge (`/forge/supervision`) → **une tuile par
    terminal** : ses dernières lignes, ce qu'il fait à l'instant, la **couleur du client**, un clic
    pour entrer (F-76)
21. ☐ Provoquer une **attente d'autorisation** dans l'un d'eux, puis **regarder ailleurs** : la tuile
    doit **passer en tête**, se signaler **franchement**, et l'en-tête la **compter en toutes
    lettres**. **C'est le point le plus important de la séance** — c'est exactement ce qui a échappé
    douze heures le 2026-09-08
22. ☐ Revenir sur l'**accueil de la Forge** : chaque projet actif montre-t-il ses dernières lignes
    **sous son nom**, sans qu'on ait eu besoin d'ouvrir la vue de supervision ?
23. ☐ Ouvrir un **cinquième** terminal → refus explicite (« quatre terminaux actifs au maximum »),
    les quatre à fermer étant **nommés**. Vérifier que la vue de supervision, elle, **ne prend aucune
    place** au registre : regarder ne coûte pas un flux

## Étape 6 — La gouvernance, par poste

24. ☐ Dans `/gouvernance`, **choisir un poste** — et non un projet : l'activation a changé de grain
    (F-75). Retenir un paquet, l'activer **une fois**
25. ☐ **Avant d'accepter, peut-on lire ?** Cliquer un fichier du paquet doit l'**ouvrir en lecture
    seule**. Quand le fichier **existe déjà** sur la machine, le **différentiel** doit montrer ce qui
    sera **laissé en place** : le dépôt est idempotent et n'écrase jamais
26. ☐ L'écran annonce-t-il **ce qu'il va écrire et où**, et l'activation est-elle une **vraie
    confirmation** — la liste des fichiers, leur contenu, ceux qui existent déjà ?
27. ☐ Vérifier que **tous les dossiers du poste** l'ont reçue, puis **ajouter un projet après coup**
    → hérite-t-il **sans qu'on y pense** ? C'est tout l'intérêt du bootstrap idempotent

## Étape 7 — Le reste

28. ☐ Le **guide d'accueil** se déclenche-t-il à la première connexion, et conduit-il bien à
    « Connecter un poste » (et à « Hébergé » pour un dépôt) ?
29. ☐ La **vue d'ensemble** montre-t-elle le poste, son système, son interpréteur, ses projets ?
30. ☐ **L'appartenance se voit-elle ?** Chaque poste porte une couleur et des initiales, reprises
    dans la liste des projets, **dans la barre du terminal** et **sur les tuiles de supervision**.
    Deux clients ouverts côte à côte doivent se distinguer d'un coup d'œil — c'est le point à juger
    à l'œil, il n'a jamais été vu en vrai
31. ☐ Le menu dit-il **Forge** partout, et plus jamais « Atelier » ?
32. ☐ Le **chatbot d'aide** répond-il à « comment configurer un proxy » ?
33. ☐ **Supprimer** un projet, puis le poste (F-69) : la garde refuse-t-elle tant qu'il reste des
    projets, et le **terminal du poste** part-il bien avec le poste ?

---

## À noter pendant la séance

Pour chaque point qui coince : **ce que tu as fait**, **ce que tu attendais**, **ce que tu as vu**,
et l'**heure** — c'est elle qui permet de retrouver le tour dans les journaux de production.

Ne corrige rien sur le moment. Un défaut noté vaut mieux qu'un défaut contourné : le contournement
efface la trace de ce qui l'a causé.
