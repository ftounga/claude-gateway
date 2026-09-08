# Cadrage — F-46 — Reprendre le runner sans le réinstaller

## Déclencheur

Le runner échange son code d'appairage contre un **jeton stocké**
(`<workspace>/.claude-runner/token.json`, SF-38-03) : il n'y a donc **pas** de nouvel appairage à
chaque lancement. Mais deux arguments restent **exigés** (`RunnerConfig.resolve`) :

- `--gateway` — l'adresse de la passerelle ;
- `--workspace` — le chemin **absolu** de la racine du projet.

Or le jeton ne mémorise **ni l'un ni l'autre**. Après un redémarrage du poste, l'utilisateur doit
donc reconstituer une commande complète — souvent en retournant dans l'application pour la recopier,
alors que la machine est **déjà appairée** et que rien n'a changé.

Constaté sur les deux premières mises en service réelles (2026-09-07 et 2026-09-08) : le geste
quotidien du client n'est pas « appairer », c'est « **reprendre** ».

## Ce que fait F-46

Faire de la reprise **un geste** :

- lancer le runner **sans aucun argument** depuis le projet ;
- ou **double-cliquer** le lanceur du paquet autonome (Windows `.cmd`, macOS `.command`).

## Découpage

| SF | Objet | Portée |
|----|-------|--------|
| **SF-46-01** | Le runner **mémorise** passerelle + racine à l'appairage réussi et **redémarre sans argument** ; refus explicite si rien n'est mémorisé ou si le jeton a expiré | Runner (Java) |
| **SF-46-02** | L'écran donne la **commande de reprise** à côté de la commande d'installation ; les lanceurs des paquets deviennent **double-cliquables sans argument** | Frontend + scripts de paquet |

## Cohérence de périmètre (vérifiée avant dev)

| Point | Verdict |
|-------|---------|
| Feature référencée dans `docs/PRODUCT_SPEC.md` | Oui — ligne F-46, 2 SF cadrées le 2026-09-08 |
| Périmètre V1 « gateway pure » (`docs/PROJECT.md`) | Oui — aucun OCR/RAG/pgvector/Textract, aucune capacité IA ajoutée |
| Gateway-First | Oui — F-46 ne touche ni orchestration ni moteur : c'est de la **reprise de configuration locale** |
| Provider Independence (`AIProvider`) | Sans objet — aucun appel fournisseur |
| Isolation `user_id` | Sans objet — **aucun nouvel accès aux données** : rien n'est lu ni écrit en base, la mémoire de reprise est un fichier **local au poste** |
| Nouvelles tables / migration Liquibase | **Aucune**, sur les 2 SF |
| Nouvel endpoint backend | **Aucun** |
| V3 / multi-LLM runtime | Non concerné |

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés |
|---|---|---|
| Auth / Principal | **Non** — le jeton runner et son échange (SF-38-01) ne changent pas ; F-46 ne crée aucun chemin d'authentification nouveau, il **relit** ce qui est déjà stocké | — |
| Contexte tenant | **Non** — le workspace lié reste celui porté par le jeton, côté gateway | — |
| Plans / limites | Non | — |
| Navigation / routing | Non — SF-46-02 ajoute du contenu **dans** le dialogue d'appairage existant, aucune route | — |

## Hors périmètre (F-46 entière)

- **Le démarrage automatique à l'ouverture de session** (`launchd`, tâche planifiée, service
  Windows) : c'est un **service installé**, avec ses droits et sa désinstallation — pas une reprise.
- **Le partage d'un jeton entre machines** : le jeton est lié à un poste appairé ; le recopier
  ailleurs contournerait l'appairage.
- **Toute reprise qui contournerait l'expiration du jeton** : un jeton expiré reste expiré, et le
  runner le **dit** au lieu de redemander silencieusement un code.
- **Mémoriser le proxy** : les variables `HTTPS_PROXY`/`HTTP_PROXY` appartiennent au poste et à sa
  DSI (F-45) ; les figer dans un fichier produit serait décider de la configuration réseau d'un
  poste d'entreprise.
