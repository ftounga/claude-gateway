# La carte du poste — transposer la gouvernance « poste / infra » dans le produit

> Cadrage du 2026-09-12, à la demande du PO : *« je veux vraiment cette gouvernance. Trouve le moyen
> de définir une gouvernance à notre niveau qui donne le même résultat. »*
>
> **Le but principal, dans ses mots** : *« à chaque projet qu'on rajoute, la connaissance de l'infra
> augmente. »* Tout ce qui suit est jugé à cette aune.

---

## 1. La pièce qui manque

Le produit sait déjà porter **le travail**. Il ne sait pas porter **le savoir**.

| Le prompt d'origine | Le produit aujourd'hui |
|---|---|
| `~/dev/repos/` — les dépôts clients clonés | **les projets d'un poste** ✅ |
| `~/dev/infra/` — la carte transversale | **rien** ❌ |
| `~/dev/infra/<sujet>/STATE.md` | le `STATE.md` déposé par F-52 ✅ |

**Un poste porte un nom, une racine, un système — et aucun endroit où la connaissance s'accumule.**
La dette de promotion existe (`PromotionDetteBloquanteControl`), mais **elle n'a nulle part où
promouvoir**. On bloque la clôture d'un tour au nom d'une carte qui n'existe pas.

C'est le défaut le plus sérieux de la gouvernance actuelle, et il explique pourquoi elle ne tient pas
sa promesse : le savoir meurt toujours avec le dossier.

## 2. La transposition — corrigée par le PO

**Correction du PO, décisive** : *« les projets dans mes clients/postes actuels sont l'équivalent de
ce que je mets dans `infra`. La preuve, la gouvernance vient du client FREE, et chez lui j'ai dû
choisir la racine `infra`. »*

L'équivalence n'est donc pas celle que j'avais écrite :

| Le prompt | Le poste |
|---|---|
| `~/dev/infra/` | **la racine du poste** |
| `~/dev/infra/<sujet>/` | **les projets** — ce sont les sujets, pas les dépôts clonés |
| `~/dev/infra/README.md`, `acces.md`, `<domaine>.md` | **les fichiers à la racine — la carte** |
| `~/dev/repos/` | un **dossier `repos/`** de la racine, où les dépôts clients se clonent |

**La carte n'est donc pas un dossier à créer. Ce sont les fichiers `.md` posés à la racine du
poste**, à côté des dossiers de projets. C'est ce que le prompt décrivait déjà, et c'est ce que le PO
fait aujourd'hui à la main chez FREE.

```
POSTE « FREE »  ← racine déclarée par le runner
├── README.md          ┐
├── acces.md           ├─ LA CARTE : le savoir transversal
├── <domaine>.md       ┘
├── repos/             ← les dépôts clients clonés (jamais de carte dedans)
├── migration-dns/     ┐
│   └── STATE.md       ├─ LES PROJETS : le travail, et la dette de promotion
└── bascule-b2b/       ┘
    └── STATE.md
```

**Ce que cette lecture change, et pourquoi elle est meilleure :**

- **rien à inventer** — la carte, ce sont des fichiers, et ils ne peuvent pas être confondus avec des
  projets, qui sont des dossiers. Le problème de découverte que je craignais n'existe pas ;
- **le terminal de poste (F-74) prend son sens plein** : il s'ouvre à la racine, **là où vit la
  carte**. C'est le terminal de la carte, et il existe déjà ;
- **l'activation par poste (F-75) était déjà au bon grain** ;
- et le but du PO est tenu **mécaniquement** : chaque projet ouvert sous ce poste promeut dans la
  **même** carte, à côté de lui. **Au troisième sujet, la carte de FREE vaut plus que les trois
  projets réunis.**

**Un constat au passage** : les postes du PO n'ont pas tous la même racine — `dev` chez l'un, `infra`
chez FREE. Rien ne l'impose aujourd'hui, et rien ne le dit. La gouvernance transposée doit donc
**s'accommoder de n'importe quelle racine** plutôt que d'exiger un chemin : la carte est « les
fichiers de la racine », quelle qu'elle soit.

## 3. Les quatre règles, transposées

| Prompt | Produit | État |
|---|---|---|
| **R1 — Un dossier, un sujet, une session** | **Déjà structurel** : un projet = un dossier = un terminal, avec sa conversation. Ce que la règle demandait à l'agent est devenu une contrainte du modèle | ✅ rien à faire |
| **R2 — Aucune trace LLM en sortie** | `CommitSansTraceLlmControl`, au point *avant commande* | ✅ livré |
| **R3 — Promotion** | `PromotionDetteBloquanteControl` compte la dette — **mais vers la carte du poste**, qui n'existait pas | 🟠 la moitié |
| **R4 — Intégrité anti-divergence** | Aucun équivalent d'`infra-doctor` | ❌ F-95 |

## 4. Les règles de clonage, transposées

Le prompt impose trois invariants sur `~/dev`. Transposés à la racine d'un poste :

1. **Un dépôt client se clone dans `repos/`**, sous la racine du poste — jamais à côté des sujets.
   C'est la transposition directe de `~/dev/repos` : un dépôt cloné qui atterrit parmi les projets
   est une erreur, parce qu'il sera pris pour un sujet et gouverné comme tel.
2. **Un projet n'est jamais un dépôt git.** Un sujet est un dossier de travail ; s'il porte un
   `.git/`, c'est qu'un dépôt s'est trompé d'endroit. Le message le dit **avec son action
   corrective**.
3. **Aucune note personnelle non versionnée à la racine d'un dépôt client** — un `.md` non suivi dans
   `repos/<dépôt>/` doit vivre dans la carte, pas chez le client. **C'est la règle qui protège le
   plus** : elle évite de livrer ses propres notes dans un dépôt qu'on ne possède pas.

## 5. Le filet sémantique : rétablir le juge indépendant

**C'est l'écart le plus important entre le prompt et ce qui a été livré.**

Le prompt décrit `infra-audit` : un **second appel LLM**, qui reçoit la carte d'un côté et les notes
de sujets de l'autre, et répond à une question unique — *« qu'est-ce qui est cité là et absent
d'ici ? »* Avec un bloc `===VERDICT===` dont **seul le contenu est lu**, et un repli qui **alerte
plutôt que de laisser passer** quand le marqueur manque.

Ce qui a été livré (`JugeFinDeTourControl`) **ne fait aucun appel** : il lit un **marqueur que le
modèle pose lui-même**. C'est de l'**auto-déclaration**. Le motif était Provider-First — mais un
modèle qui oublie de promouvoir oubliera aussi de le déclarer.

**F-94 rétablit le juge indépendant**, avec les trois détails du prompt qui comptent :

- **le bloc de verdict** : le juge peut raisonner librement, seul ce qui suit le marqueur est lu ;
- **le repli qui alerte** : marqueur absent → on analyse tout, donc on signale. *Le filet doit
  échouer bruyamment* — c'est la règle de la journée, et le prompt l'avait déjà ;
- **best-effort, jamais une autorité** : le résultat est une **liste à vérifier**, et F-50 rend la
  main après un nombre fixe de refus. Un juge ne prend jamais le message d'un utilisateur en otage.

**Et il ne coûte que quand il peut servir** : le prompt ne déclenche l'audit que si la session a
touché un sujet (marqueur `.infra-dirty`). Transposé : **le juge ne tourne que si un tour a écrit
dans un projet du poste.**

## 6. Ce qu'on ne transpose pas, et pourquoi

- **Les cinq scripts** (`infra-doctor`, `infra-audit`…). F-50 a posé qu'un paquet porte des
  **identifiants** de contrôles présents dans le produit, jamais du code — un catalogue ouvert ferait
  s'exécuter du code publié sur la machine de chaque utilisateur. Les contrôles restent des
  composants du serveur ; ils *lisent* la machine par le runner.
- **`~/.claude/settings.json` et les hooks** : le produit a ses propres points de contrôle (F-50).
- **`~/poste/config-poste.md`, `~/methodo/`, le site de méthodologie, la méthode d'inspection** :
  ils décrivent l'outillage et la méthode *de l'auteur*, pas ceux d'un client. Hors périmètre, comme
  le cadrage d'origine l'avait déjà tranché.
- **La mémoire par répertoire** : c'est une capacité de Claude Code, pas du produit.

## 7. Découpage

| | | |
|---|---|---|
| **F-92** | **La carte du poste** | Les fichiers de carte **à la racine du poste** — `README.md`, `acces.md`, un fichier par domaine —, structurés mais vides, créés à l'activation comme le reste du paquet. Ils sont **lus et rendus** sur la carte du poste : on voit ce que la machine sait, sans ouvrir un terminal. Le terminal de poste devient le terminal de la carte. **Aucune convention de chemin imposée** : la carte, ce sont les fichiers de la racine, quelle qu'elle soit. |
| **F-93** | **La promotion a une destination** | La dette de promotion pointe vers la carte du poste ; le `STATE.md` d'un projet dit **où** un élément a été promu ; et l'écran montre **ce que la carte a gagné**. |
| **F-94** | **Le juge indépendant** | Le vrai filet sémantique : un second appel qui compare la carte et les notes, le bloc de verdict, le repli qui alerte, et le déclenchement seulement si un tour a écrit. |
| **F-95** | **L'intégrité du poste** | L'équivalent d'`infra-doctor`, réduit à ce qui a du sens ici : carte présente et structurée, `STATE.md` par projet, dette et clôture, carte jamais un dépôt git, notes perso non versionnées chez un client, liens morts dans la carte. **Chaque message porte son action corrective** — il est lu par un modèle qui doit corriger. |

**Dans cet ordre.** F-92 est la fondation : sans destination, ni la promotion, ni le juge, ni le
doctor n'ont de sens.

## 8. Ce que ça vaut, au-delà de la gouvernance

**Un argument commercial que le PO n'a pas encore nommé.** Après six mois chez un client, la carte de
ce poste contient ses VPN, ses bastions, ses domaines, ses contacts, ses pièges — datés, avec leurs
sources. **Personne d'autre ne l'a**, pas même le client.

Et c'est aussi ce qui rend une mission reprenable : un consultant qui part laisse une carte, pas un
historique de conversations.

## 9. Plan de test minimal

- Un poste neuf reçoit une carte **structurée et vide** — les en-têtes prêts à recevoir des faits.
- Le dossier de carte **n'apparaît jamais** comme projet candidat.
- Un dépôt git cloné dans la carte → erreur **nommant l'action corrective**.
- Un `.md` non suivi à la racine d'un projet → erreur qui dit de le déplacer dans la carte.
- Une dette ouverte bloque la clôture ; une dette promue **dit où**.
- Le juge : une carte vide + un projet citant un serveur nommé → l'élément est **listé** ; la même
  carte le contenant → **rien**. Et un juge bavard qui conclut « AUCUN » hors du bloc de verdict
  **ne doit pas** être pris pour un silence.
- Le juge **ne tourne pas** si aucun tour n'a écrit dans un projet du poste.
