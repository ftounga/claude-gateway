# Cadrage — F-74 — Un terminal au niveau du poste

> Les décisions produit sont **déjà prises** et portées par la ligne F-74 de `docs/PRODUCT_SPEC.md`.
> Ce cadrage ne les rouvre pas : il tranche le **point de conception** laissé ouvert par le PO —
> *comment représente-t-on un terminal qui n'a pas de projet ?* — et il découpe.

**Date** : 2026-09-12 · **Feature parente** : `F-74` (`docs/PRODUCT_SPEC.md`)

---

## Le trou de parcours, dit simplement

Premier jour chez un client. Le poste est connecté, le runner tourne, la racine est **vide**.
Pour ouvrir un projet il faut un dossier ; pour avoir un dossier il faut cloner ; pour cloner il
faut un terminal ; et un terminal, aujourd'hui, appartient à un projet. **La boucle est fermée** :
on sort du produit, on clone à la main dans un terminal système, puis on revient.

Et le premier jour n'est qu'un cas particulier. `git`, un VPN, `terraform`, `aws cli`, l'installation
d'un outil : beaucoup de gestes n'appartiennent à **aucun** projet. Les faire depuis le terminal
d'un projet marche — depuis F-73 rien ne les en empêche — mais c'est un rangement faux : la
conversation qui explique comment on a monté le VPN finit dans le fil d'un projet qui n'a rien à
voir, et disparaît le jour où ce projet est supprimé.

## Ce que le PO a tranché (repris tel quel, non rouvert)

| # | Décision |
|---|----------|
| P1 | Un terminal **rattaché au poste**, à la racine — **un terminal comme les autres** : sa conversation, son historique, ses réglages. |
| P2 | **Rien de nouveau à apprendre** : même écran, même barre, mêmes gestes que le terminal d'un projet. |
| P3 | Il **compte dans le plafond de quatre terminaux vivants** (F-70). |
| P4 | Il **suit la porte de confirmation du poste** — même défaut armé, même réglage (F-73 / SF-73-02). |
| P5 | **Aucun accès nouveau** : le confinement ayant disparu (F-73 / SF-73-01), il n'ouvre rien qu'un terminal de projet n'ouvrait déjà. |
| P6 | **Hors périmètre** : un terminal **sans poste**. Il faut une machine appairée. |

## Le point de conception, et sa décision

> *« Le modèle lie une conversation à un workspace ; un terminal de poste n'a pas de projet.
> Choisis la voie la moins invasive et explique pourquoi — sans casser ce que F-61 compte par
> projet ni ce que F-65 facture par poste. »*

### D1 — Le terminal du poste **est un workspace**, marqué comme tel

Une ligne `workspaces` ordinaire : `host_id` = le poste, `project_path` = `""` (la racine),
`source = LOCAL`, `execution_target = RUNNER`, et **une colonne booléenne de plus**,
`host_terminal`, qui dit que cette ligne n'est **pas un projet**.

**Pourquoi c'est la voie la moins invasive.** Tout ce qui fait un terminal pend déjà à
`workspace_id` : la conversation (`atelier_messages`), le fil et sa frontière de rejeu, la session
d'agent, la porte de confirmation, le journal du runner, le registre des terminaux vivants (F-70),
le relevé de consommation par tour (F-61), l'héritage de gouvernance (F-75). Réutiliser la ligne,
c'est obtenir **P1, P2, P3 et P4 sans écrire une ligne de code de plus** — un terminal comme les
autres, parce que c'est littéralement le même objet.

**Alternatives écartées** :

| Écartée | Pourquoi |
|---|---|
| Une **table dédiée** `host_terminals` avec sa propre conversation | Il faudrait un second chemin pour les messages, le fil, la session, l'audit, les terminaux vivants, l'usage, la gouvernance. « Un terminal comme les autres » deviendrait le seul terminal **pas** comme les autres. Le plus invasif de tous. |
| **Aucune colonne** : reconnu à `project_path = ""` | Ambigu. F-72 autorise explicitement d'ouvrir un **projet** sur la racine (`path` vide → le projet prend le nom du poste). Les deux seraient indiscernables. |
| Une colonne `kind` (énumération `PROJECT` / `HOST_TERMINAL`) | Même coût d'écriture, mais oblige à relire **toutes** les requêtes existantes pour y ajouter `kind = 'PROJECT'`. Un booléen à `false` par défaut laisse chaque ligne existante et chaque lecture existante **justes par construction**. |
| Un **poste implicite** ou un workspace au `project_path` **vide et sans poste** | Un terminal sans poste est **hors périmètre** (P6) : sans machine appairée, il n'y a ni runner ni racine où exécuter quoi que ce soit. |

### D2 — Créé **à la demande**, et idempotent

`POST /runner-hosts/{hostId}/terminal` **retrouve ou crée**, puis rend le détail. Pas de
rétro-remplissage de migration (les postes existants obtiennent le leur le jour où on le demande),
pas de ligne pour un poste dont personne n'ouvrira le terminal, et **rappuyer ne crée pas un
second**.

### D3 — Ce n'est **pas** un projet, et trois lectures en dépendent

`listByHost` continue de rendre **les projets** — le terminal du poste en est exclu. Sans quoi :

- la carte du poste (F-49 / F-72) gagnerait un projet fantôme ;
- le contrôle de doublon de `openOnHost` (F-72) **interdirait** d'ouvrir un vrai projet sur la
  racine, puisque le terminal l'occupe déjà ;
- la garde de suppression de F-69 (« refusé tant qu'il reste des projets ») deviendrait
  **impossible à satisfaire** : le poste porterait à jamais un « projet » qu'aucun écran ne montre.

Symétriquement, **supprimer le poste emporte son terminal** : il n'a aucun sens sans sa machine.

### D4 — Ce que ça ne casse pas

| Feature | Ce qu'elle compte | Effet de F-74 |
|---|---|---|
| **F-61** — consommation par client, puis par projet | Agrège `usage_turns` par `(host_id, workspace_id)` | Le terminal du poste **est** un workspace sous ce poste : sa consommation tombe sous le **bon client**, et apparaît comme **une ligne nommée** parmi les projets. Rien n'est compté deux fois, rien n'est perdu. Aucun code à changer. |
| **F-65** — facturation par poste | Compte des **mois-postes** (`host_seat_months`), déclenchés par la création, la clôture et la réouverture d'un **poste** | **Aucun poste n'est créé.** Ouvrir un terminal de poste ne crée, ne clôt ni ne rouvre aucune mission : le registre ne bouge pas. Aucun code à changer. |
| **F-70** — plafond de quatre terminaux vivants | Une place par `(user_id, session_id)`, portant un `workspace_id` | Le terminal du poste porte un `workspace_id` comme un autre : **il compte, sans un mot de code** (P3). |
| **F-73** — porte de confirmation | Défaut `agent_ask_before_bash = true`, réglable par terminal | Créé par le même chemin que tout projet local : **armée par défaut** (P4). |
| **F-75** — gouvernance activée par poste | Héritée à la création d'un workspace, depuis le poste | Le terminal du poste **hérite de la gouvernance de son poste**, ce qui est exactement ce qu'on veut. Aucun code à changer. |
| **F-69** — suppression d'un poste vide | Refusée tant qu'il reste des **projets** | Voir D3 : le terminal n'est pas un projet, il ne bloque pas — il part avec le poste. |

### D5 — Son nom : « Terminal du poste »

Fixe, écrit par la gateway. Il apparaît tel quel dans le relevé de F-61, où « une ligne de plus
sous EDENRED » doit se lire sans explication. Le nom du **client** est déjà porté par le poste
au-dessus ; le répéter ici ne dirait rien de neuf.

### D6 — Il n'entre pas dans les gestes de projet

Dans la liste latérale de l'Atelier, il se montre (c'est par là qu'on y revient) avec l'icône
`terminal` et **sans** le menu « Supprimer le projet » : ce n'est pas un projet, et le supprimer
comme tel serait un geste dont l'effet — une recréation silencieuse au clic suivant — ne se
comprendrait pas. Il se supprime avec son poste, et c'est tout.

## Découpage

| SF | Titre | Côté | Dépend de |
|----|-------|------|-----------|
| SF-74-01 | Le terminal du poste dans le modèle, et son endpoint | backend | — |
| SF-74-02 | « Terminal du poste » sur la carte, et le terminal qui se nomme | frontend | SF-74-01 |

Ordre de livraison : **backend d'abord**, puis frontend.

## Ce qui existe déjà et qu'on ne réécrit pas

| Brique | D'où elle vient | Ce que F-74 en fait |
|---|---|---|
| L'écran de terminal (`/atelier/:id`), sa barre, ses réglages | F-30, F-33, F-38 | **réutilisé tel quel** — c'est tout l'intérêt de D1 |
| Registre des terminaux vivants et plafond de quatre | F-70 | inchangé : il compte des `workspace_id` |
| Porte de confirmation armée par défaut | F-73 / SF-73-02 | inchangée : même chemin de création |
| Carte de poste, fil d'Ariane, suppression, signe de vie | F-49, F-68, F-69, F-70 | la carte gagne **un bouton**, rien d'autre |
| Héritage de gouvernance par poste | F-75 / SF-75-01 | s'applique sans une ligne de code |

## Registres de couleur — ce qu'on ne touche pas

Trois registres cohabitent déjà et F-74 **n'en ajoute pas un quatrième** :

| Registre | Porte quoi | Où |
|---|---|---|
| Identité du poste (§9, F-49 / SF-49-03) | **quelle machine** | filet de carte + `app-host-badge` |
| Statut (§5) / mission (§10, F-60) | **où en est la mission** | `app-mission-badge`, pastilles `badge--*` |
| Signe de vie (§11, F-70) | **un onglet vit** | `app-live-badge`, `currentColor` |

Le terminal du poste emprunte **les trois tels quels** : il vit sur la carte du poste (donc sous son
filet d'identité), il n'a pas d'état de mission propre (celui du poste vaut pour lui), et son signe
de vie est la **même** pastille `app-live-badge` que celle d'un projet.
