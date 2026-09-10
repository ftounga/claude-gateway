# Cadrage — F-51, le catalogue de gouvernance

> Découpage de la feature F-51 de `docs/PRODUCT_SPEC.md`. Cadrage d'ensemble :
> `docs/features/CADRAGE-postes-et-gouvernance.md` (rang 4 de la séquence, arbitrages du 2026-09-10).
> Dépend de F-50 (les points de contrôle de la boucle), livrée le 2026-09-10.

---

## 1. Le constat

F-50 a posé deux crochets dans la boucle — après chaque écriture de fichier, et en fin de tour — et
n'y a **rien branché**. C'était voulu : *« F-51 décidera quels contrôles s'activent pour qui »*. À
l'inverse, la consigne système sait déjà porter les conventions d'un projet (F-34, `CLAUDE.md`) et la
boucle sait déjà annoncer les skills qu'elle y trouve (`SKILL_PREFIXES` = `.claude/skills/`,
`skills/`). Tout est en place, sauf **ce qui décide**.

Or une gouvernance n'est pas un bloc qu'on impose : c'est un ensemble d'**options** que l'on compose.
Un consultant n'a pas les mêmes règles qu'une équipe interne, et personne ne doit subir celles d'un
autre. F-51 pose donc l'objet qui manque — le **paquet** — et les deux étages qui le distribuent.

## 2. Les deux étages

| Étage | Qui | Ce qu'il fait |
|---|---|---|
| **Le catalogue publié** | l'**admin** (`ntounga@gmail.com`, promu par `SuperAdminBootstrap`) | Rédige des paquets et les **publie**. Un paquet non publié n'existe pour personne |
| **Le catalogue personnel** | chaque utilisateur | Retient les paquets qui lui parlent, les **active par projet**, et peut en marquer certains **appliqués par défaut** |

**Rien n'est partagé entre comptes.** Un paquet publié est un contenu produit, comme un plan
tarifaire : il est lisible par tous, il n'appartient au dossier de personne. Tout le reste — la
sélection, les activations — porte `user_id` et n'est jamais lu sans lui. Le partage d'un catalogue
entre comptes est **F-17, V3, hors périmètre**.

## 3. Ce qu'un paquet apporte

Quatre choses, et rien d'autre :

| Apport | Où il atterrit | Quand |
|---|---|---|
| **Des règles** | ajoutées à la consigne système du projet, sous leur propre titre | à chaque tour, tant que le paquet est actif |
| **Des contrôles** | branchés sur les crochets de F-50 | à chaque écriture / fin de tour |
| **Des gabarits** | **déposés** comme fichiers dans le projet | à l'activation |
| **Des skills** | **déposés** sous `.claude/skills/` — le produit les lit déjà | à l'activation |

Gabarits et skills sont **le même mécanisme** : des fichiers. Ils ne diffèrent que par l'étiquette
qui sert à l'annonce (« ce paquet dépose 2 skills et 1 gabarit »).

## 4. Le point dur : un paquet écrit sur la machine de l'utilisateur

C'est l'exigence explicite de la feature, et elle gouverne toute la conception :

> **L'écran annonce ce qu'un paquet va écrire, et où, avant l'activation.**

Trois conséquences, non négociables :

1. **L'annonce précède l'écriture.** Un endpoint d'aperçu rend la liste exacte des chemins, avec pour
   chacun ce qui va se passer : créé, ou **laissé tel quel** parce qu'il existe déjà.
2. **L'application est idempotente et ne détruit rien.** Un fichier déjà présent n'est **jamais**
   écrasé, jamais fusionné, jamais renommé. Créer ce qui manque, et rien d'autre.
3. **La désactivation ne supprime rien.** Un fichier déposé appartient au projet dès qu'il y est ; le
   retirer serait une suppression de fichier utilisateur déclenchée par un décochage.

## 5. Ce que F-51 n'ouvre pas — la limite héritée de F-50

**Un contrôle reste un composant du serveur.** Un paquet ne porte pas de code : il porte des
**identifiants** de contrôles déjà présents dans le produit, et la publication **refuse** un
identifiant inconnu. Rien ne charge de script, ne lance de processus, ni n'évalue une source reçue.

C'est la limite posée par F-50 (« il ne permettra pas d'en apporter de nouveaux »), et elle est
structurante : elle écarte d'emblée la classe de failles qu'un catalogue de crochets « ouvert »
introduirait — un paquet publié s'exécuterait sinon sur la machine de chaque utilisateur qui le
retient.

Les règles, elles, sont du **texte** ajouté à une consigne système. Elles n'ont aucun pouvoir au-delà
de ce que le modèle veut bien en faire — c'est précisément pourquoi les contrôles existent.

## 6. Arbitrages de découpage

| # | Sujet | Décision | Motif | Réversible |
|---|---|---|---|---|
| D1 | Où vit l'activation | Sur le **projet** (`workspace`) | Le cadrage dit « activés par projet ». Un nouveau poste embarque la sélection par défaut **par les projets qu'on y range** : le poste ne porte pas de fichiers, le projet oui | oui |
| D2 | Le dépôt quand la machine est absente | L'activation est **enregistrée**, le dépôt reste **en attente** et rejouable | Un projet en cible runner vit sur une machine qui peut être éteinte. Refuser l'activation exigerait que la machine soit allumée pour composer son catalogue ; les **règles et les contrôles**, eux, s'appliquent immédiatement — ils n'ont pas besoin du disque | oui |
| D3 | Fichier déjà présent | **Laissé tel quel**, et l'annonce le dit | Exigence d'idempotence de la feature. Écraser le `STATE.md` d'un utilisateur parce qu'il a coché un paquet serait une perte de données | non — c'est la promesse |
| D4 | Désactivation | Retire règles et contrôles, **laisse les fichiers** | Voir §4.3 | oui |
| D5 | Version d'un paquet | Un entier incrémenté à chaque modification, mémorisé sur l'activation | Permet de dire « ce projet applique la v2, le paquet est en v3 » sans imposer de migration automatique — que personne n'a demandée | oui |
| D6 | Un paquet dépublié | Disparaît du catalogue ; les activations existantes **continuent** | Couper les règles d'un projet parce que l'admin range son catalogue serait un effet à distance | oui |

## 7. Découpage

| SF | Titre | Contenu | Migration |
|---|---|---|---|
| **SF-51-01** | Le catalogue publié par l'admin | Tables `governance_packages` / `governance_package_files`, le registre des contrôles disponibles, les endpoints d'administration, et la lecture du catalogue publié | `065` |
| **SF-51-02** | Mon catalogue, et l'activation par projet | Tables `governance_selections` / `governance_activations`, la sélection personnelle, le drapeau « appliqué par défaut », l'activation d'un projet | `066` |
| **SF-51-03** | L'annonce, puis le dépôt idempotent | L'aperçu « ce qui va être écrit et où », le dépôt qui crée ce qui manque sans jamais écraser — en stockage comme sur la machine — et l'embarquement de la sélection par défaut sur un projet neuf | — |
| **SF-51-04** | Ce qu'un paquet actif change | Les règles ajoutées à la consigne système, et les contrôles branchés sur les crochets de F-50 | — |
| **SF-51-05** | L'écran du catalogue | Parcourir, retenir, marquer par défaut, activer sur un projet — avec l'annonce **avant** l'écriture | — |
| **SF-51-06** | Publier un paquet (admin) | La section d'administration qui rédige, modifie et publie | — |

**Backend d'abord** (01 → 04), écrans ensuite (05, 06). F-52 apportera le **contenu** : le premier
paquet, et les premiers contrôles.
