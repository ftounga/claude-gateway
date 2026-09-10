# Cadrage — F-59 — Le relais servi par la gateway

## Déclencheur

F-55 conduit l'utilisateur d'un proxy inconnu jusqu'à un runner qui passe, et son remède central est
un **relais local** — `px`. Mais elle le fait **télécharger depuis GitHub**.

Or sur un poste d'entreprise, GitHub est souvent bloqué **par catégorie** (« hébergement de code »),
indépendamment du proxy à authentification. L'utilisateur se retrouve alors dans un cercle :

> il lui faut le relais **pour sortir**, et une sortie **pour obtenir le relais**.

L'assistant, lui, ne propose aucune issue : il affiche une commande `curl` vers `api.github.com` qui
répondra `403` — sans dire que ce `403`-là n'est pas le `407` d'avant, ni quoi faire ensuite.
Constaté sur le terrain le 2026-09-07.

## Le constat qui rompt le cercle

**Le domaine de la gateway est forcément autorisé chez le client** — sinon rien du produit ne
fonctionne : ni l'écran, ni l'appairage, ni le runner. Et il est joignable **même derrière un `407`**,
avec l'authentification intégrée que F-55 vient précisément de qualifier :
`curl --proxy-ntlm --proxy-user : -x http://<proxy> -L https://<gateway>/…` — démontré le 2026-09-07.

La gateway sert donc **elle-même** le relais, exactement comme elle sert déjà le `.jar` du runner
(F-38 / SF-38-03) et ses paquets autonomes (F-44). Le mécanisme est connu, éprouvé, et ses garde-fous
aussi : un build qui **échoue** plutôt que de servir une archive tronquée, un **code d'erreur
distinct** quand un format n'est pas empaqueté, et un `/formats` qui annonce ce qui existe **pour que
l'écran masque un lien mort**.

## Licence — la condition, pas une note de bas de page

| Outil | Licence | Décision |
|---|---|---|
| **`px`** (genotrance/px) | **MIT** | **Redistribué**, à la condition MIT : la notice de licence et le copyright accompagnent la copie. Elle est **dans l'archive** (l'archive amont porte déjà `LICENSE.txt` à sa racine) **et** servie à part, visible depuis l'écran. |
| **`cntlm`** | **GPL** | **Non redistribué.** Fournir le binaire imposerait de fournir les sources correspondantes, ce que ce produit n'est pas en mesure de garantir dans la durée. L'assistant continue d'y renvoyer **par lien**, comme aujourd'hui. |

La condition est tenue **par le build et par le service**, pas par une bonne intention :

1. le script d'empaquetage **vérifie** que l'archive amont contient `LICENSE.txt`, que ce fichier
   porte bien la notice MIT, et **échoue** sinon — aucune image ne peut être construite sans elle ;
2. le service ne sert **aucune** archive quand la notice n'est pas lisible : `/runner/relay/*` répond
   alors un `404` explicite. Redistribuer sans la notice serait une violation ; ne rien servir n'en
   est pas une.

## Ce que fait F-59

1. La gateway **empaquette** `px`, à une version amont **figée et citée** (`v0.11.0`), pour les
   plateformes retenues, avec sa notice.
2. Elle le **sert** : `GET /runner/relay/<plateforme>`, `GET /runner/relay/license`,
   `GET /runner/relay/formats` — publics, à côté de `/runner/download/*`, sans rien lui retirer.
3. L'assistant proxy (F-55) propose **notre domaine d'abord**, GitHub **en repli explicite**, et
   **dit pourquoi** : « si GitHub est filtré chez vous, ce lien-ci passe par le même domaine que la
   passerelle, déjà autorisé ».

## Ce que F-59 ne refait pas

F-55 n'est **pas réécrite** : le repérage de l'adresse, la qualification du `407`, le test de
l'authentification intégrée, la configuration `cntlm` (mot de passe **haché**), la vérification du
relais et la redirection du runner restent tels quels. F-59 **se greffe** sur l'étape 3 — d'où vient
le binaire — et ne touche à rien d'autre.

## Plateformes retenues

| Plateforme | Publiée en amont | Servie | Poids |
|---|---|---|---|
| Windows amd64 | oui | **oui** | ~21 Mo |
| macOS arm64 (Apple Silicon) | oui | **oui** | ~20 Mo |
| Linux glibc x86_64 | oui | **oui** | ~26 Mo |
| macOS x64 (Intel) | **non** | non | — |
| Linux musl, Linux aarch64 | oui | non | — |

**macOS Intel n'est pas servi parce qu'il n'existe pas en amont** : le projet ne publie pas de binaire
`mac-x64`. En fabriquer un reviendrait à maintenir une version de `px` autre que celle publiée —
explicitement hors périmètre (`PRODUCT_SPEC.md`, F-59). L'assistant y garde donc le chemin `pip3`,
inchangé. Même raisonnement pour musl et Linux aarch64 : peu de postes de travail concernés, et le
chemin `pip3` couvre déjà le cas.

Coût d'image retenu : **~67 Mo**, à comparer aux ~120 Mo déjà ajoutés par les trois paquets du
runner. La ligne est tenue : trois archives, aucune redondance, aucune reconstruction.

## Découpage

| SF | Objet | Portée |
|----|-------|--------|
| **SF-59-01** | Empaqueter `px` (version figée, notice vérifiée) et le **servir** : `/runner/relay/<plateforme>`, `/runner/relay/license`, `/runner/relay/formats` | Build + Backend |
| **SF-59-02** | L'assistant proxy propose **notre domaine d'abord**, GitHub en repli explicite, et rend la **notice MIT** visible | Frontend |

## Cohérence de périmètre (vérifiée avant dev)

| Point | Verdict |
|-------|---------|
| Feature référencée dans `docs/PRODUCT_SPEC.md` | Oui — ligne F-59, créée le 2026-09-10 (commit `d967d98`) |
| Gateway-First | Respecté : la gateway **distribue un client**, elle n'exécute rien de plus. Même nature que `/runner/download`. |
| Provider Independence | Sans objet : aucun appel fournisseur. |
| Isolation `user_id` | Sans objet : aucune donnée utilisateur. Endpoints **publics** comme `/runner/download` — l'archive `px` est un binaire tiers public, elle ne porte ni jeton ni secret, et l'exiger authentifiée empêcherait précisément le poste bloqué de s'en sortir. |
| Nouvelle table | Aucune. Aucune migration Liquibase. |
| Traitement lourd synchrone | Aucun : le téléchargement amont a lieu **au build de l'image**, jamais à la requête. |
| Question ouverte impactée | Aucune. |

## Arbitrages du cadrage

| # | Question | Décision | Alternative écartée | Réversible |
|---|---|---|---|---|
| 1 | Archive amont **verbatim** ou ré-empaquetée ? | **Verbatim.** La notice y est déjà, la somme SHA-256 publiée reste vérifiable, et l'utilisateur reçoit exactement ce que le projet publie. | Ré-archiver pour y ajouter nos fichiers : casse la vérification amont, et le `.tar.gz` macOS perdrait ses bits exécutables au moindre faux pas. | Oui |
| 2 | Notice **dans** l'archive seulement, ou aussi servie à part ? | **Les deux.** Dans l'archive (condition MIT), et à part pour que l'écran puisse l'ouvrir **avant** de télécharger 21 Mo. | La notice seule dans l'archive : l'écran ne pourrait pas la montrer. | Oui |
| 3 | Endpoints **publics** ou authentifiés ? | **Publics**, comme `/runner/download/*`. | Authentifiés : l'archive est un binaire tiers public, et l'utilisateur qui en a besoin est justement celui dont le poste ne sort pas. | Oui |
| 4 | Préfixe `/runner/relay` malgré `app.runner.relay.*` (relais **interne** entre pods, SF-38-12) | **`/runner/relay/*` côté HTTP**, configuration sous **`app.runner.proxy-relay.*`** pour ne pas confondre deux choses qui n'ont rien à voir. | Renommer la configuration existante : touche un secret de production, hors sujet ici. | Oui |
