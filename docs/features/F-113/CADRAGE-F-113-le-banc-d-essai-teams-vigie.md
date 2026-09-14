# F-113 — Le banc d'essai Teams et Vigie : tout tester, sans dépendre d'un client

> Cadrage du 2026-09-13, à la demande du PO. **Cadrage seul : la livraison attend le go du PO.**
> S'appuie sur **F-112** (le serveur MCP), qui en est le moyen d'exécution.

## 1. Le besoin

> « On doit véritablement tester franchement toute la partie Teams et Vigie. Donc tu devras mettre le
> banc d'essai pour qu'il permette de littéralement tout tester dessus. »

Aujourd'hui, tout test Teams passe **par le poste d'un client** (CAGIP) : ses réglages Microsoft
(téléchargement de transcription bloqué), son proxy, sa disponibilité, ses données réelles. On ne peut
ni tout tester (pas de réunion enregistrée à la demande, pas de fichier à modifier sans risque), ni
tester souvent, ni tester sans le PO au clavier.

**Le banc d'essai répond à trois questions :**
1. **Où** tester tout, sans risque pour un client → un **environnement Microsoft 365 à nous**, avec des
   données de test connues.
2. **Avec quoi** → une **machine de test** qui porte le runner et Teams web, disponible à toute heure.
3. **Comment** → un **catalogue de scénarios** que l'IA déroule par le serveur MCP (F-112), avec les
   résultats attendus, et un **rapport**.

Le banc **ne remplace pas** le test sur poste client : il le rend court. Ce qui passe sur le banc n'a
plus qu'à être confirmé chez le client (proxy, politique du tenant, Netskope).

## 2. L'environnement Microsoft 365 de test

- **Un tenant Microsoft 365 dédié**, séparé de tout client, sous un domaine à nous
  (`test.ng-itconsulting.com` ou le domaine `onmicrosoft.com` du tenant).
- **Licences** : Teams avec **enregistrement et transcription des réunions** (Microsoft 365 Business
  Standard ou équivalent), pour **3 comptes** : le consultant testé, un collègue, un manager.
- **Double authentification** : active pour le compte humain d'administration, **désactivée par
  stratégie d'accès conditionnel** pour les trois comptes de test limités à la machine de test, afin
  que le banc tourne sans intervention. Aucune donnée réelle dans ce tenant.
- **Données de test semées et connues** (un jeu de référence versionné dans le dépôt, décrit en
  clair) :
  - équipes et canaux, conversations privées et de groupe, mentions, promesses écrites (« je te
    l'envoie jeudi »), un fil qui clôt un sujet (« on peut fermer ») ;
  - **réunions enregistrées avec transcription**, dont une au **téléchargement bloqué** par
    l'organisateur et une **sans transcription** ;
  - fichiers dans une équipe, un canal et un OneDrive, dont un document à modifier ;
  - un calendrier sur quatre semaines.
- **Réinitialisation** : un script remet le tenant dans l'état de référence avant chaque passage
  (sauf les réunions enregistrées, créées une fois et conservées).
- **Coût** : licences Microsoft 365 pour 3 comptes — **montant À CONFIRMER PAR LE PO** (ordre de
  grandeur public : une douzaine d'euros par compte et par mois pour une offre avec enregistrement et
  transcription).

## 3. La machine de test

- **Une machine virtuelle dans le compte AWS existant** (hors du cluster de production), Linux avec
  bureau virtuel, **Chrome** lancé avec le port de débogage et **Teams web connecté** au compte de test,
  et le **runner** appairé à un poste « BANC » d'un compte de test de l'application.
- **Allumée à la demande** (démarrage et arrêt par l'IA via F-112 ou par une commande), éteinte le reste
  du temps : on ne paie que les heures d'essai.
- **Deuxième poste simulé** pour la Vigie à plusieurs clients : un second profil Chrome et un second
  runner sur la même machine, poste « BANC-2 ».
- **Réunions jouées** : pour générer une réunion enregistrée neuve (synchro du soir, rattachement des
  sujets), deux comptes de test rejoignent une réunion depuis deux profils Chrome, un fichier audio
  joue le dialogue du jeu de référence par un micro virtuel ; Teams enregistre et transcrit.
- **Coût** : machine virtuelle à la demande — **À CONFIRMER PAR LE PO** (quelques euros par jour d'essai).
- **Sécurité** : la machine n'a accès qu'au tenant de test et à la gateway ; ses secrets (mots de passe
  des comptes de test, jeton du runner) vivent dans AWS Secrets Manager.

## 4. Le catalogue de scénarios

Chaque scénario : **préconditions**, **étapes** (appels MCP), **résultat attendu** vérifiable
(valeurs du jeu de référence), **nettoyage**. Le catalogue couvre **toutes** les capacités livrées :

| Domaine | Scénarios (extrait) |
|---|---|
| Liaison Teams (F-87, SF-89-05, SF-89-08) | liaison établie ; navigateur absent ; session Microsoft expirée ; « rien servi » contre « reçu mais pas reconnu » ; diagnostic chiffré |
| Lecture (F-88, SF-89-06) | trouver une réunion ; lire un fil sur une fenêtre ; mentions ; recherche ; transcription par le réseau, puis par l'écran ; **téléchargement bloqué signalé et transcription jamais recopiée** |
| Terminal Teams (F-89, SF-84-06) | compte rendu avec cartes ; précision pendant un tour ; retour sur l'écran pendant un tour ; bandeau « option non active » sur un compte sans droit |
| Captures et enregistrements (F-90, F-91, SF-108-05) | enregistrement Teams téléchargé puis moments et captures ; capture locale avec trace et transcription locale |
| Microsoft 365 (F-108) | lister, lire, créer un dossier, déposer, renommer, déplacer, supprimer, remplacer une version ; **chaque écriture reste en attente d'autorisation** et n'aboutit qu'une fois accordée par l'utilisateur de test |
| Vigie (F-106, F-107) | activer un client ; vérification guidée (toutes cases, puis chaque case vide avec son remède) ; compte Vigie sans Forge ; supplément par espace |
| Synchro du soir (F-100) | première synchro 30 jours ; incrémentale ; rattrapage après poste éteint ; annulation ; réserve épuisée ; couverture |
| Lecture des échanges (F-101) | sujets attendus du jeu de référence ; engagements dans les deux sens ; relance due ; mise en relation ; signal de clôture ; **taux de rattachement mesuré** |
| Radar à l'écran (F-102, F-103, F-104) | résumé du matin ; page sujet avec preuves ; réponse au manager ; donner une nouvelle ; courriel collé daté ; séparer, fusionner, alias ; clore ; lien sujet ↔ projet ; relance préparée |
| Pages et courriel (F-109, F-110) | publier une page du compte rendu ; lien partagé révoqué ; courriel à l'adresse vérifiée ; résumé du matin par courriel |
| Runner (F-111) | mise à jour d'un clic ; attente du calme ; retour automatique après une version piégée |

Le catalogue vit dans le dépôt (`docs/features/F-113/scenarios/`), en fichiers lisibles, **un par
scénario**, et chaque feature future qui touche Teams ou la Vigie **ajoute ses scénarios** dans sa
définition de terminé.

## 5. L'exécution et le rapport

- **Par l'IA, via F-112** : un prompt MCP « Banc d'essai Teams et Vigie » (tout, ou un domaine) démarre
  la machine, réinitialise le tenant, déroule les scénarios, et **accorde les autorisations en tant
  qu'utilisateur de test dans l'application du banc** — **seul cas** où une autorisation est donnée
  automatiquement, **limité au compte de test du banc** et refusé par la gateway sur tout autre compte.
- **Rapport** publié comme page (F-109) : par scénario, OK / KO / partiel, écart constaté, journal du
  tour, captures de l'écran Teams de la machine au moment de l'écart ; tendance d'un passage à l'autre
  (dont le taux de rattachement du Radar).
- **Trois rythmes** : à la demande ; **avant chaque déploiement** touchant Teams ou la Vigie (le
  déploiement attend un banc vert, ou une décision explicite du PO) ; **chaque semaine**, pour voir
  quand Microsoft change Teams avant qu'un client ne le voie.

## 6. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-113-01 | Le tenant de test et son jeu de référence | Création du tenant, comptes, accès conditionnel, jeu de référence versionné, script de réinitialisation, réunions enregistrées de référence |
| SF-113-02 | La machine de test | Machine virtuelle à la demande, Chrome et Teams web, deux runners et deux postes, secrets, démarrage et arrêt pilotés |
| SF-113-03 | Les réunions jouées | Deux profils, micro virtuel, dialogue du jeu de référence, enregistrement et transcription générés |
| SF-113-04 | Le catalogue de scénarios | Format, scénarios de tous les domaines du §4, résultats attendus liés au jeu de référence |
| SF-113-05 | L'exécution par l'IA et le rapport | Prompt MCP, autorisation automatique limitée au compte du banc, rapport en page, tendance, déclenchement avant déploiement et chaque semaine |

**Ordre** : F-112 d'abord (SF-112-01 à 05 au minimum) ; puis 01 → 02 → (03 ∥ 04) → 05.

## 7. Préoccupations transversales

- **Sécurité : oui.** L'autorisation automatique n'existe **que** pour le compte de test du banc,
  vérifiée côté gateway (liste fermée de comptes, drapeau posé par l'administrateur), avec test de refus
  sur tout autre compte. Secrets du banc dans AWS Secrets Manager. Tenant sans donnée réelle.
- **Plans / limites : oui** — compte de test du banc avec un quota dédié, suivi à part.
- **Infrastructure : oui** — machine virtuelle hors cluster de production ; aucune modification du
  cluster.
- **Auth / tenant** : compte de test isolé comme tout utilisateur.

## 8. Décisions qui reviennent au PO

| Décision | Recommandation |
|---|---|
| Souscrire un tenant Microsoft 365 de test (3 comptes, enregistrement et transcription) | Oui — sans lui, les réunions, transcriptions et fichiers ne se testent que chez un client |
| Montant des licences et de la machine virtuelle | À confirmer par le PO |
| Autoriser l'autorisation automatique limitée au compte du banc | Oui — sans elle, les écritures Microsoft 365 et les commandes ne se testent pas sans quelqu'un au clavier |
| Déploiement conditionné à un banc vert pour Teams et la Vigie | Oui, avec dérogation explicite possible |

## 9. Hors périmètre

- Reproduire le proxy ou Netskope d'un client (restent vérifiés sur poste client).
- Tester Outlook (retiré, F-105).
- Un tenant par client.
