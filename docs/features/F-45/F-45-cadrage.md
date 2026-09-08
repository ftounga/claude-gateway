# Cadrage — F-45 — Mise en service guidée du runner sur poste d'entreprise

## Déclencheur

Première mise en service réelle, le 2026-09-07, chez un client bancaire : **plus de trois heures**
entre le premier lancement et la première commande utile. Quatre obstacles, dans cet ordre :

1. **Java 8** sur le poste, JVM imposée par la DSI, pas de droits administrateur → traité par F-44
   (paquet autonome) et SF-38-22 (message lisible).
2. **Chemin Windows avalé par Git Bash** → traité par SF-38-23 (guillemets dans la commande).
3. **Proxy introuvable** — configuré côté système, absent du shell → traité par SF-38-25
   (contrôle de vol réseau + gestes par système).
4. **`407`** — le proxy exige une **authentification intégrée** (NTLM/Kerberos) que la JVM **ne sait
   pas porter** : `java.net.http.HttpClient` n'a aucun support SSPI, et l'authentification `Basic`
   est désactivée sur les tunnels `CONNECT` depuis Java 8u111. **Non traité à ce jour.**

Le point commun des quatre : **tous se découvrent après avoir téléchargé et lancé**. L'écran
d'appairage promet « aucun port à ouvrir » — vrai et rassurant — mais ne dit **rien du sortant**,
qui est précisément ce qui bloque.

## Ce que fait F-45

Déplacer le diagnostic **avant** l'installation, et mettre dans l'écran ce que le client doit
savoir — y compris ce qu'il doit **demander à sa DSI**.

## Limite assumée

Le navigateur **ne peut pas** lire la configuration proxy du poste (bac à sable web). L'écran ne
détectera **jamais** le proxy tout seul : il **demande**, **guide** et **interprète**.

## Découpage

| SF | Objet | Portée |
|----|-------|--------|
| **SF-45-01** | Étape « Vérifier l'accès réseau » **avant** le téléchargement : commande adaptée au système + arbre de lecture à trois branches (`200` / `407` / échec) | Frontend |
| **SF-45-02** | Cohérence Windows de l'écran : le format choisi pilote le chemin d'exemple et la commande ; état « en attente de la machine / machine connectée » | Frontend |
| **SF-45-03** | Fiche « Pour votre DSI » générée par l'écran (domaine, 443, HTTPS **et** WSS, sortant uniquement, aucun port entrant, mention NTLM/Kerberos non porté par la JVM) | Frontend |
| **SF-45-04** | Le contrôle de vol du runner **reconnaît le `407`** et nomme le remède | Runner |
| **SF-45-05** | Le parcours guidé de mise en service : une étape dépliée à la fois, les autres repliées sur leur en-tête avec ce qu'elles ont produit, et une **conclusion** quand la machine répond ; le relevé d'état nomme l'interpréteur élu | Frontend + Backend (champ additif `shell`) |

## Cohérence de périmètre (vérifiée avant dev)

| Point | Verdict |
|-------|---------|
| Feature référencée dans `docs/PRODUCT_SPEC.md` | Oui — ligne F-45, **5 SF** : 4 cadrées le 2026-09-08 au matin, SF-45-05 ajoutée à la réouverture du soir |
| Périmètre V1 « gateway pure » (`docs/PROJECT.md`) | Oui — aucun OCR/RAG/pgvector/Textract, aucune capacité IA |
| Gateway-First | Oui — l'écran **explique** et le runner **décrit** ; rien n'est exécuté à la place du poste |
| Provider Independence (`AIProvider`) | Sans objet — aucun appel fournisseur dans F-45 |
| Isolation `user_id` | Sans objet côté nouvelles données ; SF-45-02 consomme `GET /api/workspaces/{id}/runner/status`, **déjà** filtré par `user_id` (SF-38-02) |
| Nouvelles tables / migration Liquibase | **Aucune**, sur les 5 SF |
| Nouvel endpoint backend | **Aucun créé** — SF-45-05 ajoute un champ **additif** `shell` au relevé d'état existant `GET /api/workspaces/{id}/runner/status` (SF-38-02) ; le reste de F-45 est écran + runner |
| V3 / multi-LLM runtime | Non concerné |

## Hors périmètre (F-45 entière)

- **Configurer le poste à la place de l'utilisateur** (écrire des variables d'environnement, modifier
  le registre, installer un relais) : ce serait exécuter la configuration réseau d'un poste
  d'entreprise, avec les décisions de sécurité que cela suppose.
- **Embarquer un relais d'authentification dans le produit** : porter NTLM/Kerberos reviendrait à
  manipuler les identifiants Windows de l'utilisateur. Le remède est nommé, pas fourni.
- **Le support d'un proxy à identifiants applicatifs** (login/mot de passe dédiés au runner) :
  exception DSI, hors produit.
- **Détecter le proxy depuis le navigateur** : impossible (bac à sable), et le prétendre serait pire
  que se taire.
