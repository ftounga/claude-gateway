# Cadrage — F-54 — Chatbot d'aide produit

> Cadrage de la feature. Arrête le périmètre, les sources documentaires et le découpage.
> Subordonné à `docs/PROJECT.md` et à `docs/features/CADRAGE-postes-et-gouvernance.md`.

## Déclencheur

Deux jours de mise en service chez un client (2026-09-07 / 09-08) ont produit une dizaine de
questions : JVM en Java 8, chemin Windows avalé par Git Bash, proxy absent du shell, `407` d'un
proxy à authentification intégrée, quel fichier télécharger, où coller le code d'appairage.
**Toutes les réponses étaient déjà écrites** — dans `runner/README.md`, dans les mini-specs F-38,
F-44, F-45. Personne ne les a trouvées : il n'existe **aucun endroit où demander** dans le produit.

## Ce que fait F-54

Poser une **bulle d'aide** dans l'application : une question en langue naturelle, une réponse
**fondée sur la documentation produit**, jamais inventée. Quand la documentation ne répond pas, le
chatbot le dit et n'improvise pas.

## Ce que F-54 ne fait pas

| Hors périmètre | Pourquoi |
|---|---|
| Répondre sur le **contenu des projets** de l'utilisateur | C'est l'Atelier (F-38) et le Q&A documentaire (F-07). L'aide ne voit aucune donnée utilisateur |
| **Vectorisation / pgvector / embeddings** | La documentation d'usage tient dans une consigne système. Un index vectoriel serait une machinerie sans bénéfice |
| Ouvrir un **canal de support humain** | Ni ticket, ni messagerie, ni notification : F-54 répond, elle ne relaie pas |
| Mémoriser l'**historique** des questions | Chaque question est indépendante. Rien n'est persisté, aucune table |
| Répondre **hors produit** (questions générales, code, droit…) | La consigne borne le sujet ; hors sujet, le chatbot redirige |

## Sources documentaires — le choix, et pourquoi il est *curaté*

La base de connaissance est un jeu de fichiers `.md` **versionnés dans le dépôt** et chargés dans la
consigne système au démarrage. Ils sont **écrits pour l'utilisateur**, à partir de la documentation
d'ingénierie existante — ils n'en sont pas la copie :

| Fichier d'aide | Répond à | Distillé de |
|---|---|---|
| `00-consignes-de-lecture.md` | Ce que la documentation couvre, et ce qu'elle ne couvre pas | — (écrit pour F-54) |
| `01-decouvrir-le-produit.md` | À quoi sert le produit, quel écran fait quoi | `docs/PROJECT.md`, `docs/PRODUCT_SPEC.md` |
| `02-telecharger-le-runner.md` | Quel fichier prendre, quelle taille, quel prérequis | F-44 (SF-44-01/02/03), `RunnerDownloadController` |
| `03-java-et-prerequis.md` | « JNI error », `UnsupportedClassVersionError`, Java 8 vs 21 | SF-38-22, F-44 |
| `04-proxy-et-reseau.md` | Proxy absent du shell, `407`, DNS, truststore, fiche DSI | SF-38-25, SF-45-01, SF-45-03, SF-45-04, `runner/README.md` |
| `05-appairer-une-machine.md` | Code d'appairage, jeton, reprise sans argument, révocation | SF-38-03, F-46, `runner/README.md` |
| `06-lancer-et-executer.md` | Lancer le runner, `--allow-bash`, porte de confirmation, droits | SF-38-07/18/19/23, `runner/README.md` |
| `07-fichiers-et-confinement.md` | Outils fichiers, `.runnerignore`, ce qui ne sort jamais du poste | SF-38-04, SF-38-10 |
| `08-compte-plans-et-quotas.md` | Connexion, plans, quota, facturation, clé personnelle | F-01, F-10, F-21, F-41 |
| `09-messages-et-depannage.md` | Les messages d'erreur réels et leur remède | F-38, F-45, codes de sortie du runner |

**Pourquoi ne pas charger `docs/*.md` tel quel** — arbitrage tranché ici :
`PROJECT.md`, `ADR.md`, `OPEN_QUESTIONS.md`, `PRODUCT_SPEC.md`, `DEPLOYMENT.md` contiennent la
feuille de route, les décisions non tranchées, les coûts, les noms de cluster et de namespace. Les
verser dans une consigne système exposée à tout compte connecté reviendrait à **publier l'interne du
projet** — une consigne ne protège pas un contenu, elle l'oriente. Les fichiers d'aide sont donc une
**réécriture destinée à l'utilisateur**, sans référence SF-XX, sans feuille de route, sans
infrastructure. Second motif, pratique : l'image du backend ne copie que `backend/src` et `runner/`
(cf. `backend/Dockerfile`) — un chargeur qui lirait `docs/` à l'exécution trouverait un dossier
absent en production. Décision réversible : ajouter un document, c'est ajouter un `.md`.

## Découpage

| SF | Objet | Portée |
|----|-------|--------|
| **SF-54-01** | `POST /api/help/chat`, chargeur de documentation statique, service borné sur modèle rapide, garde-fou de débit | Backend |
| **SF-54-02** | Bulle fixe + panneau, suggestions, compteur de caractères, états, réservée aux comptes connectés | Frontend |

## Cohérence de périmètre (vérifiée avant dev)

| Point | Verdict |
|---|---|
| Feature référencée dans `docs/PRODUCT_SPEC.md` | Oui — ligne F-54, 2 SF (commit `b7d4cc6`) |
| Périmètre (`docs/PROJECT.md` + ADR-011) | Oui — aucune vectorisation, aucun pgvector, aucun OCR |
| Gateway-First | Oui — la Gateway relaie une question au fournisseur avec une consigne ; elle n'implémente aucun moteur |
| Provider Independence | Oui — appel via `AIProvider` / `ModelCatalog`, jamais Anthropic en direct |
| Isolation `user_id` | La documentation est **la même pour tous** : aucune donnée utilisateur n'est lue ni écrite. Le garde-fou de débit est **indexé par `user_id`** |
| Nouvelles tables / migration Liquibase | **Aucune** sur les 2 SF |
| V3 (F-17/F-18) / multi-LLM runtime | Non concerné |
