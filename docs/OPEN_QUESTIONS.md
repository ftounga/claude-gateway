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

**Statut** : **Ouverte — question de planification adressée au product owner (2026-09-06).**
Ne bloque pas F-38, **Terminée** dans `docs/PRODUCT_SPEC.md`.

**Le contexte**

F-38 (runner local) est livrée — 14 subfeatures, relais inter-pods compris — et **déployée en
production** depuis le 2026-08-30 (image `staging-b907947`). Tout ce qui pouvait être vérifié sans
machine tierce l'est par la suite de tests : handshake, registre, confinement, exclusions,
garde-fous, audit, relais, purge à la suppression de compte.

**Ce qui reste, et pourquoi ce n'est pas un ticket de dev**

Le parcours **bout en bout sur une vraie machine** — appairage réel, WSS sortant, bascule
long-polling derrière un proxy qui coupe l'`Upgrade`, `Ctrl-C` — n'est pas automatisable au coût
raisonnable : il demande une **machine tierce hors cluster**, un **réseau d'entreprise réellement
contraint** (un proxy simulé prouve le code, pas le terrain) et un **opérateur**. Construire le banc
d'essai correspondant (VM éphémère + proxy + pilotage navigateur) coûterait plus que la feature, pour
un parcours joué une fois à la mise en service. Il a donc été **sorti du périmètre de dev et parqué**
sous forme de protocole exécutable : `docs/features/F-38/SMOKE-manuel-bout-en-bout.md` (9 scénarios,
prérequis, grille de compte rendu).

**Ce qui est demandé au PO**

1. **Une date** et **un opérateur**.
2. **Une machine** hors cluster avec Java 21 et un projet réel.
3. **Un accès réseau contraint** pour le scénario S5 (proxy cassant l'`Upgrade`) — à défaut, le
   scénario est joué en proxy simulé et **noté comme partiel**.
4. **Un créneau de scale à 2 replicas** pour le scénario S6 (relais inter-pods).
5. **Un compte de test jetable** (le scénario S9 le supprime).

**Ce qui se passe ensuite**

Tout OK → une ligne d'historique dans `PRODUCT_SPEC.md`, rien d'autre. Un KO → une **subfeature
correctif ciblée** (`SF-38-15`…), pas une réouverture de F-38 en bloc ; un KO sur **S5** (repli de
transport) ou **S6** (deux pods) est **bloquant pour la promesse produit** et passe devant le backlog.

**Risque assumé en attendant** : un défaut d'intégration réseau ou de parcours réel resterait
invisible jusqu'au premier utilisateur du mode `RUNNER`. C'est le prix du parcage — il est accepté
parce que le mode `RUNNER` n'est pas le premier pas d'un utilisateur (F-39, D6) et que les chemins
sensibles sont couverts par des tests.
