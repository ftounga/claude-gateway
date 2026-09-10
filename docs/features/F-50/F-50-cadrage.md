# Cadrage — F-50, les points de contrôle de la boucle

> Découpage de la feature F-50 de `docs/PRODUCT_SPEC.md`. Cadrage d'ensemble :
> `docs/features/CADRAGE-postes-et-gouvernance.md` (rang 3 de la séquence, arbitrages du 2026-09-10).

---

## 1. Le constat

La boucle maison — `AtelierChatService.runLoop` — n'a **aucun point d'accroche**. Elle appelle le
fournisseur, exécute les outils demandés, rend les résultats, recommence, puis s'arrête sur le
premier tour sans appel d'outil. Entre ces étapes, **rien** ne peut s'exécuter, et **rien** ne peut
interrompre le modèle pour lui imposer une correction.

C'est ce qui manque pour qu'une règle cesse d'être une consigne. Aujourd'hui, tout ce qu'on veut
faire respecter à l'agent passe par la consigne système : on le lui **demande**. Un modèle oublie,
contourne, ou n'a simplement pas lu la ligne qui comptait. Le niveau au-dessus — le **verrou
déterministe**, celui qui ne se négocie pas — n'a nulle part où se brancher.

Le produit possède pourtant déjà exactement le geste voulu, à un seul endroit : la **porte de
confirmation** (`RunnerConfirmationGate`, F-38 / SF-38-08). Quand une commande n'est pas autorisée,
le résultat d'outil rendu au modèle est une **erreur** dont le texte porte le motif — et le modèle
repart de là. F-50 généralise ce geste à deux moments qui n'en avaient pas.

## 2. Les deux crochets

| Crochet | Quand | Ce que « bloquer » veut dire |
|---|---|---|
| **Après écriture** | Après chaque `write_file` / `edit_file` **abouti** | Le `tool_result` devient une **erreur** portant l'action corrective. Le fichier est écrit ; le modèle est prévenu qu'il doit y revenir |
| **Fin de tour** | Quand le modèle rend sa réponse finale (aucun appel d'outil) | Le tour **ne se termine pas** : le message correctif est déposé comme message utilisateur et la boucle repart |

Ce sont les deux points de la demande, et rien de plus. `bash` n'a pas de crochet en F-50 : il a déjà
sa porte (SF-38-08) et son journal (SF-38-08 / D11).

## 3. Ce qui est écrit dans le message

Le principe repris du cadrage, et qui gouverne toute la rédaction : **un message d'erreur porte son
action corrective**, parce qu'il est lu par un modèle qui doit corriger, pas par un humain qui doit
comprendre. « Le fichier ne respecte pas la convention » ne sert à rien. « Ajoute l'en-tête de
licence en tête de `src/Foo.java`, puis reprends » se corrige.

## 4. Ce que F-50 ne fait pas

- **Aucun contrôle branché.** F-50 livre le mécanisme et **zéro** contrôle actif : le comportement de
  la boucle est strictement inchangé tant que rien n'est enregistré. C'est F-51 (catalogue) qui
  décidera ce qui s'y branche, et F-52 qui apportera le premier contenu.
- **Rien de configurable par l'utilisateur** : ni table, ni endpoint, ni écran. La feature est
  entièrement interne à la boucle.
- **Aucune exécution de code fourni par un tiers.** Un contrôle est un composant du serveur,
  enregistré au démarrage. Rien ne charge de script, ne lance de processus, ni n'évalue de source
  reçue d'un utilisateur. C'est la limite explicite du périmètre F-50, et elle est structurante :
  elle interdit d'emblée la classe de failles qu'un système de crochets « ouvert » ouvrirait.

## 5. Découpage

| SF | Titre | Contenu |
|---|---|---|
| **SF-50-01** | Le crochet d'écriture | Le contrat (`AtelierCheckpoint`, contexte, verdict), le registre qui les exécute, et le branchement après chaque écriture de fichier |
| **SF-50-02** | Le crochet de fin de tour | Le second point d'accroche : la boucle repart au lieu de s'arrêter, bornée, avec le message correctif déposé côté utilisateur |

**Backend uniquement, deux SF, aucune migration.** F-50 n'a pas d'écran : ce qu'un blocage produit
est un résultat d'outil en erreur et un tour qui continue — deux choses que l'écran rend **déjà**
(transcription SF-39-17, étapes SF-28-05). Aucune SF frontend n'est donc requise, et ce n'est pas un
oubli : la règle CLAUDE.md (« subfeature backend mergée sans subfeature frontend planifiée si la
feature a une UI ») ne s'applique pas, la feature n'ayant pas d'UI propre.
