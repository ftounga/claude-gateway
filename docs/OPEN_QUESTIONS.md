# OPEN_QUESTIONS.md — claude-gateway

Questions non tranchées ayant un impact produit ou technique. À mettre à jour au fil des décisions.

> **MàJ 2026-07-01 (amendement)** — Le traitement documentaire est **entré dans le périmètre** (amendement
> `PROJECT.md`, ADR-011). Les questions RAG/pgvector **OQ-01, OQ-02, OQ-03, OQ-10** sont désormais
> **tranchées** (F-06 livrée : 1536 / pgvector exploité / IVFFlat lists=100 / workers intra-backend,
> toutes réversibles). **OQ-05 (auth)** tranchée (OAuth + email/mot de passe JWT). **OQ-06 (chiffrement
> clés BYOK)** tranchée (AWS KMS, débloque F-03).

---

## OQ-01 — Dimension d'embedding
**Statut** : **Tranchée (2026-07-01, F-06 / SF-06-01)**
**Impact** : Définit le type de `chunks.embedding` (`vector(N)`) et l'index pgvector. Un changement après ingestion impose une ré-indexation complète.
**Options** : 1536 (embeddings via API fournisseur OpenAI/Anthropic, défaut actuel du schéma) ; 384 (modèle local all-MiniLM, cible V2) ; autre selon modèle.
**Décision** : **1536** (`chunks.embedding vector(1536)`, migrations `002`/`011`). Réversible via `app.rag.embedding.dimension` (une nouvelle dimension imposerait une ré-indexation + une migration du type de colonne). **F-15 (SF-15-01, 2026-07-02) livre le fournisseur d'embeddings local (`provider=local`) en conservant la dimension 1536** (vectoriseur lexical in-process, aucune migration/ré-indexation). Le modèle local natif **384** (transformer all-MiniLM ONNX) reste un basculement futur réversible sur la même interface `EmbeddingProvider` (impliquerait `dimension=384` + migration `vector(384)` + ré-indexation).

## OQ-02 — Version Postgres RDS & activation pgvector
**Statut** : **Exploitée (2026-07-01, F-06)** — pgvector activé (`002-pgvector`) et utilisé (`011`)
**Impact** : L'instance RDS est partagée avec legalcase. Il faut confirmer la version PG et que l'extension `vector` est disponible/activable sur cette instance.
**Options** : Activer pgvector sur la base `claudegatewaydb` (extension par base) ; vérifier la version PG (≥ 15 recommandé pour HNSW).
**Décision** : Extension `vector` activée par base via `002-pgvector.xml` ; colonne + index créés en `011` (DDL isolé `dbms=postgresql`). Les tests H2 restent verts via l'abstraction `EmbeddingStore` (store no-op par défaut, colonne vectorielle non mappée par l'entité).

## OQ-03 — Index vectoriel : IVFFlat vs HNSW
**Statut** : **Tranchée (2026-07-01, F-06 / SF-06-01)**
**Impact** : Qualité/latence de la recherche sémantique.
**Options** : IVFFlat (défaut actuel, `lists=100`) ; HNSW (meilleur rappel, plus coûteux en écriture, requiert pgvector récent).
**Décision** : **IVFFlat `lists=100`** (déjà provisionné `002`, recréé en `011`). **F-07 (`/ask`) livrée** exploite cet index via la recherche plus-proches-voisins `<->` L2 (isolée `user_id`) et le **conserve**. HNSW reste une **évolution ultérieure** (à réévaluer selon le rappel/charge réels), réversible via migration d'index.

## OQ-04 — Modèles Claude disponibles sur le compte
**Statut** : **TRANCHÉE (2026-08-26, F-28 / SF-28-17)** — inventaire relevé sur le compte, et usage décidé.

**Inventaire réel** (`GET /v1/models`, 2026-08-26) : `claude-opus-5`, `claude-sonnet-5`,
`claude-fable-5`, `claude-opus-4-8`, `claude-opus-4-7`, `claude-sonnet-4-6`, `claude-opus-4-6`,
`claude-opus-4-5`, `claude-haiku-4-5`, `claude-sonnet-4-5`. Contexte 1 M et sortie 128 K sur les
générations récentes.

**Décision d'usage (Atelier)** : **`claude-opus-5`**, effort **`xhigh`**, envoyés en **surcharge de
session**. Trois raisons : Opus 5 est au **même tarif** qu'Opus 4.8 pour des capacités supérieures ;
`xhigh` était déjà le réglage effectif (défaut de la plateforme, que rien n'envoyait) ; et la surcharge
de session rend les deux modifiables **sans re-provisionner l'agent**, ce que l'ancienne propriété
`model` ne permettait pas — `ensureBootstrapped` ne comparait jamais la configuration voulue à celle
déjà en base.

**Reste ouvert, à traiter à part** : le catalogue proposé aux utilisateurs du **chat** (`ANTHROPIC_MODELS`)
ignore encore `opus-5`. Et baisser l'effort est désormais possible — cela se décidera sur les mesures
de coût produites par F-36, pas sur une intuition.
**Impact** : Valeurs par défaut du proxy (`model`) et affichage des modèles sélectionnables côté UI.
**Options** : À lister depuis le compte Anthropic (ex. Sonnet/Haiku/Opus courants).
**Décision** : À définir.

## OQ-05 — Fournisseurs OAuth & modèle de session
**Statut** : Tranchée (2026-07-01)
**Impact** : F-01 (auth), configuration Spring Security, redirections, JWT.
**Décision** : **Les deux modes** — OAuth2/OIDC (Google) **et** compte email/mot de passe (inscription, reset, vérification email), authentification par **JWT**. Microsoft/autres providers → V2.

## OQ-06 — Stockage & chiffrement des clés BYOK
**Statut** : Tranchée (2026-07-01) — **implémentée et livrée** en F-03 (SF-03-01→04, PR #46/#48/#49/#50)
**Impact** : F-03, conformité. Où et comment chiffrer la clé utilisateur.
**Options** : Chiffrement applicatif via AWS KMS ; Vault. Rotation, suppression sur demande.
**Décision (2026-07-01)** : **AWS KMS envelope encryption**. Clé customer-managed dédiée (rotation activée, alias `alias/claude-gateway-staging-byok`), rôle IRSA backend autorisé `GenerateDataKey/Encrypt/Decrypt` sur cette seule clé (moindre privilège). La clé API BYOK est chiffrée côté application via une data key KMS ; jamais stockée ni loggée en clair, jamais exposée au frontend. Alias injecté par `APP_BYOK_KMS_KEY_ID`. Débloque F-03.

## OQ-07 — Réglages Stripe (TVA/taxes, produits, price IDs)
**Statut** : Contournée en V1 (F-09 livrée) — TVA/Stripe Tax reste à trancher
**Impact** : F-09, facturation conforme (TVA UE), mapping plans → price IDs.
**Options** : Stripe Tax activé ; price IDs par plan (Hosted/BYOK × Solo/Pro/Daily) staging + prod.
**Décision (2026-07-01, F-09)** : Les **price IDs** sont **externalisés en configuration d'environnement**
(`app.billing.stripe.prices.{SOLO,PRO,DAILY}`, `STRIPE_PRICE_*`), jamais en dur — le catalogue de code
ne porte aucun montant. Les montants réels vivent dans Stripe (réversibles sans redéploiement).
**Stripe Tax reste désactivé en V1** (option de configuration à activer ultérieurement) : point encore ouvert.

## OQ-08 — Facturation de l'overage
**Statut** : Partiellement tranchée (2026-07-01) — **V1 = blocage à la limite** ; variante monétisée reste ouverte
**Impact** : F-10, monétisation au-delà du quota.
**Options** : Prix par token (ex. 0,002 €/token) ; par tranche ; blocage à la limite.
**Décision** : **V1 = blocage à la limite** (option non monétaire, réversible) — F-10/SF-10-01 : à quota atteint, `POST /chat` renvoie `402 quota_exceeded` sans appeler le fournisseur. La **variante monétisée** (facturation au token / à la tranche au-delà du quota) reste **ouverte** et relève d'une évolution ultérieure (touche à la facturation → décision explicite requise avant implémentation).

## OQ-09 — Domaine staging vs production
**Statut** : Ouvert
**Impact** : DNS, ingress, certificats. Le déploiement de validation utilise `portal.ng-itconsulting.com`.
**Options** : Garder `portal.ng-itconsulting.com` en prod et introduire `staging.portal.ng-itconsulting.com` pour le staging ; ou domaine `.fr` dédié comme legalcase.
**Décision** : À définir (staging actuel exposé directement sur `portal.ng-itconsulting.com`).

## OQ-10 — Worker(s) : intégré vs séparé
**Statut** : **Tranchée (2026-07-01, F-05 + F-06, réversible)**
**Impact** : Architecture de déploiement (pods), scaling de l'ingestion.
**Options** : Traitement asynchrone intra-backend (scheduler/threadpool) en V1 ; workers dédiés (pods séparés + file) en V2.
**Décision** : **Workers intra-backend `@Scheduled`** retenus — `OcrPollingWorker` (F-05) et `IngestionWorker` (F-06 / SF-06-02), désactivables par config. Choix **réversible** : les abstractions (`OcrProvider`, `EmbeddingProvider`/`EmbeddingStore`) + le pilotage par état en base (`documents.status`) permettent d'extraire des workers dédiés + file (SQS/…) en V2 sans réécrire le domaine. À réévaluer selon la charge d'ingestion réelle.

## OQ-11 — Credential du serveur MCP GitHub : PAT fine-grained ou jeton OAuth ?
**Statut** : **CLOSE (2026-08-25) — un PAT fine-grained EST accepté.** SF-31-05 est **livrée** (PR #159 backend, #161 écran, #162 destruction du vault à la suppression de compte) ; D2 est resté sur le PAT chiffré livré en SF-31-01, aucune bascule sur GitHub App / OAuth n'a été nécessaire. La vérification d'**intégration** qui restait — « le vault `static_bearer` transmet-il bien le jeton au serveur ? » — a été faite en développant la subfeature.
**Impact** : Détermine si D2 (authentification GitHub) peut rester sur le **PAT chiffré** livré en SF-31-01, ou doit basculer sur une **GitHub App / OAuth** — un chantier à part entière (enregistrement d'app, callback, rafraîchissement de jetons, gestion d'installation). Conditionne l'entrée effective du MCP dans le produit tracée par **ADR-015**.
**Contexte** : le **montage du dépôt** (clone, `git pull`, `git push`) s'authentifie avec le PAT via le proxy git du fournisseur — acquis et livré (SF-31-02/04). La **création de pull request** passe en revanche par le serveur **MCP GitHub**, qui s'authentifie par un **vault de credentials** ; la documentation avertit que les serveurs MCP hébergés attendent typiquement des **jetons bearer OAuth**, pas les clés d'API natives du service.
**Options** : (a) vérifier empiriquement qu'un PAT est accepté comme `static_bearer` du serveur MCP GitHub (une requête de test, un dépôt de test, un PAT réel) ; (b) basculer d'emblée D2 sur GitHub App / OAuth ; (c) s'en tenir à SF-31-04 et laisser l'utilisateur ouvrir sa PR depuis le lien de comparaison.
**Vérification empirique (2026-08-25)** — option (a) exécutée avec un PAT réel :

| Test | Résultat |
|------|----------|
| `GET https://api.github.com/user` | **200** — jeton valide |
| `POST https://api.githubcopilot.com/mcp/` (`initialize`), PAT en `Authorization: Bearer` | **200** — poignée de main MCP réussie |
| `tools/list` | **200** — **44 outils**, dont `create_pull_request`, `create_branch`, `push_files` |

Le serveur MCP GitHub **accepte donc un PAT fine-grained en jeton bearer**. L'avertissement de la
documentation (« les serveurs MCP hébergés attendent typiquement des jetons OAuth ») vaut pour d'autres
services — l'exemple cité était Notion — mais **pas** pour GitHub.

**Reste à vérifier au moment d'implémenter** (et non plus avant de s'engager) : que le vault
`static_bearer` du fournisseur transmette bien ce jeton au serveur. Le maillon incertain — le serveur
accepte-t-il un PAT ? — est levé ; il ne reste qu'une vérification d'intégration, qui se fera
naturellement en développant SF-31-05.

**Décision** : **(a) confirmée — on garde le PAT.** **Repli en place** : (c) — SF-31-04 renvoie `https://github.com/{owner}/{repo}/compare/{base}...{branche}?expand=1`, donc le gain principal de F-31 (plus d'export/réimport manuel) est acquis sans SF-31-05. Détail : `docs/features/F-31/CADRAGE.md` § *Risque MCP*.
## OQ-12 — HTTPS sur l'apex nu `ng-itconsulting.com`

**Statut** : **Ouverte — reportée volontairement (2026-08-25)**. Correctif documenté ci-dessous, à
appliquer si le besoin se matérialise.

**Diagnostic exact (mesuré le 2026-08-25)**

| Adresse | Résultat |
|---------|----------|
| `https://www.ng-itconsulting.com` | ✅ 200 — site servi par le cluster |
| `http://ng-itconsulting.com` | ✅ **301** vers `https://www…` (redirection OVH) |
| `https://ng-itconsulting.com` | ❌ **connexion refusée — port 443 fermé** |

Ce n'est **pas** un problème de certificat : le serveur de redirection OVH (`213.186.33.5`) n'écoute
pas du tout en HTTPS. Vérifié dans l'interface OVH : **aucune option SSL n'existe** dans le parcours
de création d'une redirection de domaine (étapes 1 à 5) — OVH ne fait pas de HTTPS sur ses
redirections DNS.

**Impact réel — limité**

Un seul cas casse : un lien écrit explicitement en `https://ng-itconsulting.com` (signature de mail,
QR code, annuaire, carte de visite). La saisie au clavier fonctionne : le navigateur tente HTTPS,
échoue, retombe en HTTP et suit le 301.

**Ce qui rendrait le correctif nécessaire**

1. Diffusion de `https://ng-itconsulting.com` sur un support figé (imprimé, QR code, annuaire).
2. Durcissement du HTTPS-First des navigateurs supprimant le repli automatique vers HTTP — trajectoire
   annoncée, sans échéance ferme.

**Correctif, si le besoin se matérialise**

Cause de fond : un apex ne peut pas porter de CNAME, les IP du NLB AWS sont dynamiques, et OVH
n'aplatit pas les CNAME. Le NLB est **partagé** avec `legalcase.fr` — lui attacher des Elastic IP
imposerait de le recréer, donc une coupure sur deux produits pour un confort d'URL : **écarté**.

Reste la migration de zone DNS, en deux volets :

*Volet éditeur (OVH → Route 53 ou Cloudflare)*
1. Exporter la zone complète depuis OVH (bouton **Export as CSV** de la page Redirection).
2. Recréer **tous** les enregistrements chez le nouvel hébergeur. ⚠️ **Le risque de l'opération est
   là** : `MX 1 smtp.google.com` (messagerie Google Workspace), le SPF
   (`google + mx.ovh.com + spf.brevo.com`), les DKIM Brevo (`brevo1/brevo2._domainkey`) et le
   `google-site-verification`. Un MX oublié coupe les mails sans alerte immédiate.
3. Créer l'apex : enregistrement **alias A** vers le NLB (Route 53, natif) ou **CNAME aplati**
   (Cloudflare).
4. Basculer les serveurs de noms chez OVH.

*Volet dépôt (à faire côté cluster, avant la bascule)*
5. Ajouter `ng-itconsulting.com` à `k8s/base/ingress/corporate-ingress.yaml` et au certificat
   cert-manager, pour que l'apex soit servi dès que le DNS pointe.

**Recommandation** : **Route 53** plutôt que Cloudflare — l'infrastructure est déjà sur AWS et pilotée
par Terraform, l'alias vers un NLB est natif, et cela n'ajoute aucun intermédiaire devant le trafic.
Coût : ~0,50 $/mois par zone.

**Décision** : laissée en l'état le 2026-08-25. La règle de contournement, sans coût ni risque :
**toujours écrire `www.ng-itconsulting.com`** dans tout ce qui est diffusé. Confirme et précise
l'arbitrage « non prioritaire » de F-29 / SF-29-04.

## OQ-13 — Quand joue-t-on le smoke manuel de F-38 (runner), et sur quelle machine ?

**Statut** : **Ouverte — question de planification adressée au product owner (2026-09-06),
relancée le 2026-09-08, complétée le soir même (SF-44-03, macOS).** Ne bloque pas F-38, **Terminée** dans `docs/PRODUCT_SPEC.md`.
**Mise à jour du 2026-09-06 (soir)** : le protocole a été **remis au niveau du runner livré**.
**Mise à jour du 2026-09-08** : il l'a été **une seconde fois** — six subfeatures de plus
(SF-38-22 → SF-38-27) **et une feature entière** (**F-44**, le paquet autonome Windows) sont passées
entre-temps ; deux d'entre elles auraient produit un **KO faux**, et F-44 laissait un scénario
manquant. Voir « Ce que la relance du 2026-09-08 change ». La question posée au PO, elle,
est **inchangée depuis le 2026-09-06** — elle ne se répond ni par du code ni par de la documentation,
seulement par **une date et un opérateur**.

> **Ce que cette entrée demande, en une phrase** : un créneau de **110 minutes**, un **opérateur**,
> et une **machine Windows** hors cluster — après un déploiement embarquant la migration **063** et
> **F-44**. Tout le reste est prêt et à jour.

**Le contexte**

F-38 (runner local) est livrée — **27 subfeatures** au 2026-09-08, relais inter-pods compris — et
**déployée en production** depuis le 2026-08-30 (image `staging-b907947`, antérieure à SF-38-15→27).
Tout ce qui pouvait être vérifié sans machine tierce l'est par la suite de tests : handshake,
registre, confinement, exclusions, garde-fous, audit, relais, purge à la suppression de compte.

**Le délai lui-même produit de la valeur, et coûte** : depuis que la question est posée, six
subfeatures de plus sont nées d'un **vrai poste client** (SF-38-22 → SF-38-27, les 2026-09-07 et
2026-09-08) — prérequis Java, chemin Windows, panne réseau lisible, contrôle de vol, console
exacte, interpréteur élu. **Aucune** n'était visible en intégration continue ; **toutes** l'auraient
été par ce smoke. C'est l'argument le plus court en faveur du créneau : le protocole ne coûte pas
110 minutes, il les **économise** sur le prochain premier lancement.

**Ce qui reste, et pourquoi ce n'est pas un ticket de dev**

Le parcours **bout en bout sur une vraie machine** — appairage réel, WSS sortant, bascule
long-polling derrière un proxy qui coupe l'`Upgrade`, `Ctrl-C` — n'est pas automatisable au coût
raisonnable : il demande une **machine tierce hors cluster**, un **réseau d'entreprise réellement
contraint** (un proxy simulé prouve le code, pas le terrain) et un **opérateur**. Construire le banc
d'essai correspondant (VM éphémère + proxy + pilotage navigateur) coûterait plus que la feature, pour
un parcours joué une fois à la mise en service. Il a donc été **sorti du périmètre de dev et parqué**
sous forme de protocole exécutable : `docs/features/F-38/SMOKE-manuel-bout-en-bout.md` (**17
scénarios** depuis la remise à niveau du 2026-09-08, prérequis, grille de compte rendu).

**Ce qui a été fait depuis (2026-09-06, soir) — et qui ne referme pas la question**

Le protocole avait été écrit **le matin**, avant que le second passage du banc d'essai ne livre
SF-38-15→21. Joué tel quel, il aurait produit **au moins un KO faux** : son point S4.3 exigeait
qu'**aucun** réglage ne desserre la porte de confirmation, alors que **SF-38-20 a précisément amendé
cette décision**. Le protocole est donc à jour : S1 (commande d'appairage avec son `/api`, `bash` par
défaut), S2 (explorateur qui lit la machine, filtre du bruit de construction, troncature annoncée),
S4 (les deux gestes d'autorisation groupée — et ce qui, lui, ne se desserre jamais : le journal
d'audit et le coupe-circuit), **S10** (droits déclarés du runner) et **S11** (projet « sur ma
machine ») ajoutés, prérequis d'image relevé à la **migration 053**. C'est de la mise à niveau
documentaire : **le parcours réel n'a toujours pas été joué**.

**Ce que la relance du 2026-09-08 change (et qui ne referme toujours pas la question)**

Le même défaut qu'en septembre s'est reproduit, pour la même raison de fond : **un protocole ne
compile pas**, donc rien ne le prévient quand le produit bouge sous lui. Corrigé une seconde fois :

1. **Prérequis d'image relevé** : migration **063** (`workspaces.runner_shell`), et non plus 053.
   Trois signes vérifiables sont listés en P1, un par lot livré.
2. **Un KO faux évité** — le plus coûteux du lot : **SF-38-25 pose un contrôle de vol réseau *avant*
   l'appairage**. Derrière un proxy qui bloque **tout** le sortant, le runner s'arrête désormais
   avant d'avoir rien tenté. Un opérateur jouant **S5** (repli de transport) sans le savoir lirait
   cet arrêt comme « le repli est cassé » — un KO **bloquant** au sens du protocole — et ouvrirait
   une subfeature correctif contre un comportement **voulu**. Une note en tête de S5 le dit.
3. **Un angle mort de couverture** : SF-38-22, 23, 26 et 27 ne se manifestent **que sous Windows**
   (JVM trop ancienne, `C:\Users\…` avalé par Git Bash, ponctuation rendue `?` en cp850, absence de
   `ls`/`grep` dans `cmd.exe`). Joué sur Linux ou macOS, le protocole n'en verrait **aucun** et
   rendrait un « tout OK » trompeur. D'où le prérequis **P3bis**, et une demande de plus au PO
   (point 7 ci-dessous).
4. **Une feature entière absente du protocole** : **F-44** (livrée le 2026-09-07) donne au poste
   Windows un **paquet autonome embarquant sa propre JVM**, que l'écran propose désormais **en
   premier** — la commande devient `claude-runner.cmd …`, sans `java -jar`. Le protocole ne
   connaissait que le jar. Conséquence directe : **S12** (prérequis Java) est **sans objet** sur ce
   format — l'y jouer produirait un KO contre une feature qui fait exactement son travail —, et le
   scénario que **seule une vraie machine** peut jouer (un Windows verrouillé exécute-t-il le paquet
   sans droits admin, JVM système absente ?) n'existait nulle part.
5. **Cinq scénarios ajoutés** — S12 (prérequis Java nommé, *format jar seulement*), S13 (chemin
   Windows entre guillemets), S14 (contrôle de vol et pannes qui ne se racontent plus par `null`),
   S15 (interpréteur élu, déclaré, et consigne système accordée), **S16** (le paquet autonome F-44).
   Numérotation stable : ajoutés **à la fin**, joués **au début** (l'ordre recommandé du §3 le dit).
6. **Numéro du prochain correctif** : `SF-38-28` — les numéros **15 à 27** sont consommés.
7. **Complément du même jour (soir) — macOS** : **SF-44-03** fait entrer le Mac dans le périmètre de
   F-44. L'hypothèse qui fondait son exclusion — « un développeur macOS a déjà un JDK » — a été
   démentie par un poste Mac d'entreprise verrouillé chez le **même client** (ni droits
   administrateur, ni Homebrew, ni JDK). La gateway sert désormais **quatre** formats et l'écran
   **présélectionne celui du poste qui consulte**. Un **S17** est ajouté, jumeau de S16 côté Mac —
   avec ce que macOS a en propre : le bit exécutable du lanceur (raison d'être du `tar.gz`), la
   quarantaine Gatekeeper levée par le lanceur, et les **deux architectures**. **P4bis** est corrigé,
   et la note de S16 cesse d'affirmer ce qui vient d'être démenti.

**Ce qui est demandé au PO**

1. **Une date** et **un opérateur**.
2. **Une machine** hors cluster avec Java 21 et un projet réel (de préférence avec ses dépendances
   installées — le scénario S2 en a besoin).
3. **Un accès réseau contraint** pour le scénario S5 (proxy cassant l'`Upgrade`) — à défaut, le
   scénario est joué en proxy simulé et **noté comme partiel**.
4. **Un créneau de scale à 2 replicas** pour le scénario S6 (relais inter-pods).
5. **Un compte de test jetable** (le scénario S9 le supprime).
6. **Prérequis d'image (relevé le 2026-09-08)** : le smoke doit être joué **après un déploiement
   embarquant SF-38-15→27**, c'est-à-dire au moins la migration **063** (`workspaces.runner_shell`)
   — le seuil annoncé le 2026-09-06 était 053. L'image de production du 2026-08-30 est antérieure
   aux deux : jouer le protocole dessus donnerait des KO sur S10 à S15 qui ne diraient rien du
   produit.
7bis. **Un Mac, pour un second passage court (nouveau, 2026-09-08 soir)** : **S17** n'existe que
   là, et il porte la même promesse invérifiable en intégration continue que S16 — un poste
   verrouillé, sans droits administrateur ni JVM système, exécute-t-il le paquet ? Compter **20
   minutes** en plus du passage Windows, sur le poste Mac de n'importe qui — l'architecture (Apple
   Silicon ou Intel) se note au compte rendu. Ce n'est **pas** un passage complet : le reste du
   protocole a déjà été joué sous Windows.
7. **Une machine Windows (nouveau, 2026-09-08)** : quatre des six derniers correctifs ne se
   manifestent que là, et **F-44** n'existe que là. Un passage sur Linux ou macOS reste utile, mais
   **S13, S15 et S16 y sont notés « non joué »** — pas « OK » ; sur un Mac, c'est **S17** qui se
   joue à la place de S16. Poste avec **Git pour Windows** de
   préférence (S15 attend l'élection de Git Bash) et, pour S12, un moyen de pointer temporairement un
   **JDK antérieur à 21**.
8. **Les deux formats de téléchargement (nouveau, 2026-09-08)** : le **paquet autonome** pour S16
   (ou S17 sur un Mac), le **jar** pour S12. Le format retenu se note dans le compte rendu — il change la commande
   d'appairage et rend S12 sans objet.

**Proposition par défaut, à confirmer ou corriger d'un mot** — faute d'opérateur, elle n'est pas
appliquée, mais elle transforme une question ouverte en un oui/non :

| Point | Proposition |
|---|---|
| Quand | Le **premier créneau calme après le prochain déploiement de production** (celui qui embarque SF-38-15→**27** et **F-44**, migration 063). Compter **90 min** — **110** avec les cinq scénarios ajoutés le 2026-09-08 et le double téléchargement. |
| Qui | Le **PO lui-même** : les deux passages du banc d'essai ont montré que c'est son regard qui trouve les défauts d'usage (quatre subfeatures et deux correctifs le 2026-09-06), et le **premier lancement client** en a produit six de plus les 2026-09-07/08. |
| Où | **Un poste Windows** avec un projet réel et ses dépendances — c'est le poste type d'un client, et la seule machine où S13 et S15 existent. À défaut, sa machine de développement habituelle, en notant S13 et S15 « non joué ». |
| S12 | Un **JDK antérieur à 21** pointé le temps d'un lancement (`JAVA_HOME`), plutôt qu'une machine dédiée. Format **jar** obligatoire pour ce point. |
| S16 | Joué **en premier**, avec le **paquet autonome** (~39 Mo) : c'est le format qu'un client Windows recevra. Bascule sur le **jar** ensuite, pour S12. |
| S17 | **Un second passage court, ~20 min, sur un Mac**, séparé du passage Windows et postérieur à lui : décompresser le `tar.gz`, vérifier que le lanceur est resté exécutable, lancer sans droits administrateur, constater que Gatekeeper ne bloque pas. L'architecture (Apple Silicon / Intel) se note. Le reste du protocole n'est pas rejoué. |
| S5 | **Proxy simulé** (`HTTPS_PROXY` vers un mandataire qui refuse l'`Upgrade`), noté **partiel**, plutôt que d'attendre indéfiniment un vrai réseau d'entreprise. Le rejouer chez le premier client qui en a un. |
| S6 | **2 replicas pendant le créneau**, retour à 1 juste après. |
| S9 | Compte jetable créé pour l'occasion, **joué en dernier**. |

**Ce qui se passe ensuite**

Tout OK → une ligne d'historique dans `PRODUCT_SPEC.md`, rien d'autre. Un KO → une **subfeature
correctif ciblée** (**`SF-38-28`…** — les numéros **15 à 27** sont consommés depuis le 2026-09-08), pas
une réouverture de F-38 en bloc ; sont **bloquants pour la promesse produit** et passent devant le
backlog : **S5** (repli de transport), **S6** (deux pods), **S4.5** (une commande autorisée en groupe
absente du journal d'audit), **S11.4** (un chemin absolu remonté à la gateway) et, depuis le
2026-09-08, **S14.5** (le contrôle de vol de SF-38-25 qui refuse de démarrer alors que la gateway
répond : une porte posée avant tout le reste, dont le faux positif n'abîme pas le produit mais
l'empêche de partir).

**Risque assumé en attendant** : un défaut d'intégration réseau ou de parcours réel resterait
invisible jusqu'au premier utilisateur du mode `RUNNER`. C'est le prix du parcage — il est accepté
parce que le mode `RUNNER` n'est pas le premier pas d'un utilisateur (F-39, D6) et que les chemins
sensibles sont couverts par des tests.

---

## OQ-14 — En cible `RUNNER`, la porte de confirmation doit-elle rester **activée par défaut** ?

**Statut** : **TRANCHÉE le 2026-09-10 par le product owner — la porte n'est plus armée par
défaut.** Implémentée par **F-47 / SF-47-04**, enregistrée en **ADR-018**.

**La décision.** `agent_ask_before_bash` vaut **`false`** à la création d'un projet, et la bascule de
cible d'exécution ne l'arme plus (la décision D7 de SF-38-08 est retirée : elle rendait le nouveau
défaut inopérant dès la première bascule et réarmait la porte dans le dos de l'utilisateur). Le
réglage reste **activable par projet** ; le **journal d'audit** et le **coupe-circuit** sont
inchangés et restent non désactivables. Les projets **existants ne sont pas modifiés** : chacun garde
le réglage qu'il porte.

**Pourquoi maintenant, et pas le 2026-09-08.** On ne desserre pas une garde pour compenser un défaut
d'affichage. F-47 a d'abord rendu l'invite impossible à manquer — peinte à l'instant où elle arrive
(SF-47-01), son temps restant affiché et son expiration dite pour ce qu'elle est (SF-47-02), la
peinture enfin prouvée par un test qui l'aurait vue manquer (SF-47-03). Le coût de la porte est
devenu **visible** ; c'est ce qui rendait la question posable.

<details>
<summary>Historique de la question, avant qu'elle soit tranchée</summary>

**Statut au 2026-09-09** : ouverte — question de sécurité adressée au product owner (cadrage F-47,
2026-09-08). Ne bloquait pas F-47, **Terminée** dans `docs/PRODUCT_SPEC.md` : les deux subfeatures
livrées avaient rendu l'invite impossible à manquer, elles n'avaient **rien changé** au réglage par
défaut — délibérément.

**La question, en une phrase**

Sur une machine que l'utilisateur a lui-même connectée, avec son propre appairage, dans un workspace
qu'il a lui-même désigné, la première commande doit-elle encore attendre un clic ?

**Ce qui est déjà tranché autour d'elle**

- **SF-38-08 / D7** : la porte n'était pas désactivable en cible `RUNNER`. Raisonnement juste, non
  confronté à l'usage.
- **SF-38-20** : amendement — deux gestes, « Tout autoriser pour ce **message** » (la première
  commande demande toujours) et « Ne plus demander sur ce **projet** » (`agent_ask_before_bash`,
  réglage persistant). **La porte est donc déjà désactivable par projet.** Ce qui reste en question
  est sa **valeur de départ**, celle que subit l'utilisateur qui n'a encore rien réglé.
- **SF-38-19** : l'**exécution**, elle, est activée par défaut — le mode runner existe pour exécuter.
  L'asymétrie entre les deux défauts est précisément ce que la question interroge.
- **F-33 / SF-33-03** : l'invite vit **dans le flux**, pas en modale. Décision inchangée par F-47.

**Ce que F-47 a changé, et ce qu'elle n'a pas changé**

F-47 a corrigé le vrai défaut du 2026-09-08 : l'invite était émise et reçue, mais **jamais peinte**
(aucun cycle de rendu après sa pose, le flux se taisant juste derrière). Elle est désormais peinte à
l'instant où elle arrive, rappelée tant qu'elle attend, son temps restant s'affiche, et l'expiration
est dite pour ce qu'elle est. **Le coût de la porte est donc devenu visible** — deux minutes qui
s'écoulaient en silence sont maintenant deux minutes qu'on voit courir. C'est ce qui rend la
question posable sans la trancher : on ne desserre pas une garde pour compenser un défaut d'affichage.

**Ce que chaque réponse coûte**

- **Garder activée** : un clic au premier usage sur chaque projet, sur une machine où l'utilisateur
  est déjà chez lui. Le coût est faible depuis SF-38-20, mais il tombe **au pire moment** — le tout
  premier contact, celui qui décide de l'adoption.
- **Désactiver par défaut** : l'agent exécute sans demander sur une machine réelle, dès la première
  commande. Le journal d'audit et le coupe-circuit restent, eux, **non désactivables** — mais ils
  constatent, ils n'empêchent pas.

**Pourquoi elle n'était pas tranchée par un agent**

C'est un arbitrage sécurité / adoption sur du code exécuté sur la machine d'un tiers. Il revenait au
product owner, explicitement, et se documente en ADR le jour où il est rendu — c'est **ADR-018**.

</details>

---

## OQ-15 — `DESIGN_SYSTEM.md` se contredit sur sa propre palette

**Statut** : ouverte — relevée le 2026-09-10 par la passe visuelle **F-56 / SF-56-01**. **Ne bloque
rien** : les écrans sont conformes, c'est le document qui l'est à moitié.

**La contradiction, en trois lignes**

| Endroit | Ce qui est écrit |
|---|---|
| Titre de `docs/DESIGN_SYSTEM.md` | « charte répliquée de legalcase : navy `#1A3A5C` / or `#C9973A` / fond `#F5F6FA` » |
| Table §2 « Palette de couleurs » | Primary `#0B1020`, Accent `#E07B39` — la palette **antérieure**, celle de la refonte F-27 |
| `frontend/src/styles.scss` (jetons `--cg-*`) | `--cg-primary: #1A3A5C`, `--cg-accent: #C9973A` — le **titre** |

Les jetons suivent le titre, et les écrans emploient les jetons : **rien n'est faux à l'écran**. Ce
qui est faux, c'est la table §2 — et c'est elle qu'un relecteur ouvre pour vérifier une couleur.
La table §5 « Badges et statuts » a le même décalage : ses hex sont ceux de l'ancienne charte,
recopiés tels quels dans `.badge--*`, où ils vivent encore.

**Pourquoi F-56 ne l'a pas corrigée**

`docs/PRODUCT_SPEC.md` place explicitement **la charte elle-même hors du périmètre de F-56** : elle
fait autorité, la passe visuelle corrige les écrans qui s'en écartent, jamais l'inverse. Une passe
qui se met à réécrire sa propre référence n'a plus de référence.

**La question posée**

Quelle valeur fait foi — le titre (legalcase `#1A3A5C` / `#C9973A`, ce que le produit affiche
aujourd'hui) ou la table §2 (`#0B1020` / `#E07B39`, ce que F-27 avait posé) ? La réponse la plus
probable est *le titre*, auquel cas le travail est d'**aligner la table §2 sur les jetons**, sans
toucher une ligne de code. Mais c'est une décision de charte, et elle appartient au product owner.

**Effet secondaire à traiter en même temps**

`project-governance/checklists/review-checklist.md` §Design System exige « Inter, **Merriweather**,
JetBrains Mono ». Merriweather n'apparaît **nulle part** dans `DESIGN_SYSTEM.md`, qui impose Space
Grotesk pour les titres. La checklist de review a gardé une police d'un projet antérieur.

---

## OQ-16 — Huit points tarifaires qu'aucune source du dépôt ne tranche

**Statut** : ouverte — relevée le 2026-09-11 par **F-64 / SF-64-01**, qui a établi
`docs/TARIFS.md` comme grille unique. **Ne bloque rien** : le produit facture aujourd'hui, ces
points concernent ce qu'on *dit* de la facturation et ce qu'on *veut* qu'elle devienne.
**Ils appartiennent tous au PO** — F-64 s'est interdit de les trancher : un chiffre inventé dans
une grille tarifaire est pire que son absence.

**Ce que F-64 a pu établir** : les quatre plans vendables, leurs montants affichés (mensuels et
annuels), leurs quotas, les deux recharges et leur contenu en jetons, l'option Atelier et l'essai —
tous repris de la configuration réellement servie en production. **Ce qui suit est ce qui manquait.**

| # | Point | Ce qu'on sait | Ce qui manque |
|---|---|---|---|
| 1 | **Prix de la recharge `STANDARD` (1 M jetons)** | Le pack est vendable (price ID d'environnement) et crédite 1 M jetons | **Aucun montant**, nulle part dans le dépôt. Le code n'expose ni prix ni price ID (`TopUpPackResponse`) : l'écran de rachat n'affiche rien avant la page Stripe |
| 2 | **Prix de la recharge `DAY` (200 k jetons)** | **4,99 €**, relevé dans une note de livraison (`PRODUCT_SPEC.md`, F-09 / SF-09-04), produit Stripe « Claude Proxy — Recharge 200 k » | Ce montant n'est **pas configuré** : à reconfirmer au tableau de bord Stripe |
| 3 | **Durée de l'essai : 5 appliqués contre 14 annoncés** | `app.billing.trial-days` = **5** ; la page d'accueil et `marketing.md` annoncent **14 jours, sans carte** | Dans quel sens aligner ? Les deux corrections sont des décisions commerciales **de sens opposé** |
| 4 | **Concordance montants affichés ↔ prix Stripe** | Les montants d'affichage sont **cosmétiques** ; le débit est porté par le price ID, que la gateway relaie sans vérifier (OQ-07) | Le dépôt ne peut pas vérifier la concordance. Quatre plans, trois prix annuels, l'option Atelier, deux recharges : seul le tableau de bord Stripe fait foi |
| 5 | **BYOK sans offre annuelle** | `yearly-prices` et `yearly-display-prices` n'ont **pas d'entrée `BYOK`** : le plan n'est proposé qu'au mois | Absence **subie ou voulue** ? Les trois autres plans ont leur annuel |
| 6 | **`markup` de décompte à `1.0`** | Neutre : le décompte n'applique aucun multiplicateur, la marge étant portée par l'allocation de chaque plan (`app.atelier.agent.cost.markup`) | Le porter à `2.0` **doublerait la vitesse de consommation du quota de chaque client** — levier de marge réel, à actionner sciemment, jamais par inadvertance |
| 7 | **Valeur d'un token de quota à `9,00 $/M`** *(ajouté le 2026-09-11 par F-63)* | `app.atelier.agent.cost.quota-token-cost-per-million-tokens`, **inchangé** : c'est l'ancien `cost-per-million-tokens`, renommé et redocumenté. Il ne dit plus un « coût blended » — approximation sans objet depuis que l'entrée et la sortie sont distinguées — mais **ce que vaut un token de quota**, c'est-à-dire le coût fournisseur qu'il représente. Conséquence directe : le coût fournisseur maximal d'un quota vaut `quota × cette valeur`, **quel que soit le style d'usage** | C'est le **second levier de marge**, à côté du `markup`. L'abaisser à `5,00` reviendrait à poser « un token de quota = un token d'entrée » — formulation littérale de `STRATEGIE-TARIFAIRE.md` §2 — et **multiplierait par 1,8 la vitesse de consommation** de tout utilisateur de la Forge, soit une coupe de 44 % de son quota effectif. F-63 s'est **interdit** de le faire : les ratios entre natures de tokens sont des tarifs fournisseur, l'échelle est une décision commerciale |
| 8 | **Montant du supplément par poste, jetons qu'il apporte, et paliers de dégressivité** *(ajouté le 2026-09-11 par F-65)* | Le **mécanisme** est livré et **inerte** : `app.seat.included-seats` = 1 (l'abonnement couvre un poste), `tokens-per-extra-seat` = **0**, `quota-tiers` **vide**, `price-id` **vide** — donc aucun jeton apporté, aucun supplément facturé, quota rigoureusement identique à celui d'avant F-65. La proratisation d'un poste ouvert en cours de mois est tranchée (prorata temporis à la journée, `proration: DAILY`), la réouverture dans le mois aussi (un mois-poste se paie une fois) | Trois valeurs, toutes commerciales : **(a)** le montant mensuel du supplément — il n'existe que chez Stripe, sous un price ID que le PO créera et renseignera dans `app.seat.price-id` ; **(b)** les jetons qu'il apporte (`tokens-per-extra-seat`) — le calibrage **recommandé** par `STRATEGIE-TARIFAIRE.md` §6 est « le même nombre de tokens par euro que le plan de base », mais le chiffre appartient au PO ; **(c)** les paliers de dégressivité (`quota-tiers` ici, price à paliers chez Stripe) — un consultant à six missions n'acceptera pas six fois le supplément plein, et **les deux grilles doivent être tenues alignées par le PO** : le dépôt ne peut pas le vérifier (même régime que le point 4) |

**Pourquoi cette question existe plutôt qu'une décision par défaut.** Le régime d'autonomie du
projet autorise à trancher un gate produit *réversible* et à le tracer. Un prix ne l'est pas : il
est **publié**, il est **opposable**, et c'est précisément une grille publiée à la légère qui a
produit le litige que F-64 vient de refermer.

**Effet du non-traitement** : aucun sur le code. Sur le commerce, deux effets nets — le point 3 est
une **promesse publique tenue à 36 %** (5 jours sur 14 annoncés), et les points 1-2 rendent les
recharges **invendables autrement qu'à l'aveugle**, le client ne découvrant leur prix qu'à la page
de paiement.

**Ne pas confondre avec** : **F-63** — livrée le 2026-09-11, elle change le *décompte* du quota et
**aucun tarif** (`docs/TARIFS.md` §8.1 en décrit la règle) — et **F-65**, qui ajoutera un supplément
par poste dont le montant appartient aussi au PO (`TARIFS.md` §8.2 — **livrée le 2026-09-11**, et son
tableau reste vide de tout montant : voir le point 8 ci-dessus). F-63 n'a décidé
aucun montant : elle a livré le mécanisme et laissé toutes les valeurs en configuration, avec leurs
défauts d'avant. Elle a en revanche **révélé** le point 7 ci-dessus — un levier qui existait déjà,
mais que son ancien nom (« coût blended ») présentait comme un constat technique plutôt que comme un
réglage commercial.
