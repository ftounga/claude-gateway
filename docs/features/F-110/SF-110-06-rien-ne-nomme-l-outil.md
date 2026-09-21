# Mini-spec — F-110 / SF-110-06 — Rien de ce qui part chez le client ne nomme l'outil

## Identifiant
`F-110 / SF-110-06` — feature parente `F-110`

## Objectif
Qu'aucun courriel ni aucune page reçue par un client ne laisse deviner **quel outil** les a produits.

## Le défaut
> *« Dans le titre du mail que je reçois dans l'adresse CAGIP, c'est marqué "Claude Gateway pour
> CAGIP". Je t'avais dit que je ne voulais pas quelque chose qui laisse penser à un LLM. »*

Le produit **porte déjà cette règle** — *« rien de ce qui sort du projet ne doit suggérer qu'un
modèle l'a produit »* — et la fait respecter **mécaniquement sur les messages de commit** depuis
F-52. Elle n'a jamais été appliquée à ce qui part par courriel. C'est donc un défaut, pas une
demande nouvelle.

### Les cinq traces, relevées par balayage
| # | Où | Ce qui part |
|---|---|---|
| 1 | Nom d'expéditeur (`ClientMailOutbox`) | `claude-gateway pour CAGIP` — **ce qu'on voit dans sa boîte** |
| 2 | Pied de page (`ClientMailRenderer`) | *« Envoyé depuis claude-gateway pour CAGIP, à votre demande. »* |
| 3 | Bloc « Pages » (`ClientMailAttachments`) | *« liens privés : connexion à claude-gateway »* |
| 4 | **Courriel de vérification d'adresse** (`SmtpEmailService`) | *« depuis claude-gateway pour le client CAGIP »* — **le tout premier courriel qu'une adresse professionnelle reçoit** |
| 5 | **Page partagée** (`SharedPageComponent`) | le **logo Claude Portal**, et l'onglet du navigateur *« Claude Portal — passerelle professionnelle vers Claude »* |

Les traces 4 et 5 n'avaient pas été relevées par le PO : la première parce qu'elle part avant tout
usage, la seconde parce qu'elle n'apparaît qu'en ouvrant le lien.

## Décisions du PO (2026-09-21)
- **Expéditeur** : `NG IT Consulting`, sans mention du client — il sait qui il est.
- **Pied de page** : **supprimé**. Le message se termine sur son contenu.

## Comportement attendu
1. Le nom d'expéditeur des courriels client est **configurable** (`app.mail.sender-name`), avec
   `NG IT Consulting` pour défaut.
2. Aucun pied de page n'est ajouté au corps.
3. Le bloc « Pages » et le courriel de vérification ne nomment plus aucun outil.
4. La **page partagée** ne porte ni logo de marque ni titre de marque : son onglet prend le titre de
   la page, ou « Page partagée » à défaut.
5. **Rien ne change pour l'utilisateur** : son application garde son identité, son logo et son titre.

| Cas d'erreur | Comportement |
|---|---|
| Nom configuré vide ou blanc | repli sur le défaut — un expéditeur sans nom finit en indésirable |
| Nom trop long | tronqué à une longueur raisonnable, jamais rejeté |
| Page sans titre | l'onglet dit « Page partagée » |

## Critères d'acceptation
- [ ] Le nom d'expéditeur vaut `NG IT Consulting` et **ne contient plus** le nom de l'outil ni celui du client.
- [ ] Il se règle par configuration ; un réglage vide retombe sur le défaut.
- [ ] Le corps du courriel **n'a plus de pied de page**.
- [ ] Le bloc « Pages » ne nomme plus l'outil.
- [ ] Le courriel de **vérification d'adresse** ne nomme plus l'outil.
- [ ] La page partagée ne montre **ni logo ni titre de marque**.
- [ ] **Un test balaie tout ce qui part** vers un client et échoue si le nom de l'outil y réapparaît — c'est ce qui empêche la régression, pas la vigilance.
- [ ] L'application de l'utilisateur est **inchangée** : logo, titre, écrans.

## Correction de cadrage (2026-09-22, après vérification en production)

La version initiale de cette mini-spec classait le **titre global** en hors scope, au motif qu'il
« ne s'affiche que pour l'utilisateur ». **C'était faux**, et la vérification après déploiement l'a
montré : les métadonnées de `index.html` sont lues par Teams, Outlook et Slack pour composer
l'**aperçu d'un lien**, **sans exécuter le JavaScript**. Le correctif Angular (titre, logo) n'agit
qu'après chargement : l'aperçu affichait donc encore *« Claude Portal — passerelle professionnelle
vers Claude »* et le logo, **dans le fil de discussion du client**, avant même qu'il ne clique.

Trois couches se sont révélées l'une après l'autre, chacune invisible depuis la précédente :

1. les métadonnées `og:` / `twitter:` et le `<title>` ;
2. l'icône d'onglet, qui est le logo de marque ;
3. **deux** blocs de repli — celui que lisent les crawlers avant le démarrage d'Angular, **et** celui
   du `<noscript>`. Le second a été trouvé par la garde du script, pas par relecture.

**Le correctif** : la route `/p/` est servie par une coquille **`partage.html` dérivée de
`index.html` au moment du build**, dont titre, métadonnées, icône et replis sont neutres. Dérivée et
non maintenue à part : l'index référence les bundles par des noms qui changent à chaque compilation,
et une copie figée cesserait de fonctionner au build suivant, **en silence**. Le script **échoue le
build** si une trace subsiste, et un test vérifie le fichier **réellement produit** — la seule façon
de voir ce qu'un client reçoit.

## Hors scope
- Le titre global de l'application **pour ses propres écrans** : l'utilisateur voit son produit.
  Seule la route ouverte par un client est neutralisée.
- `PageViewTicketService.KEY_LABEL` : étiquette de **dérivation cryptographique**, jamais affichée.
  La modifier **invaliderait tous les liens de page en circulation**. On n'y touche pas.
- L'adresse d'envoi elle-même (`no-reply@ng-itconsulting.com`), déjà neutre.

## Technique
| Fichier | Changement |
|---|---|
| **`ClientMailIdentity`** *(nouveau)* | le nom d'expéditeur, borné, avec son repli |
| `ClientMailOutbox` | l'emploie au lieu de la chaîne codée en dur |
| `ClientMailRenderer` | plus de pied de page |
| `ClientMailAttachments` | phrase du bloc « Pages » neutralisée |
| `SmtpEmailService` | courriel de vérification neutralisé |
| `shared-page.component.ts` | ni logo ni titre de marque |

Aucune table, aucune migration, aucune route.

## Plan de test
- [ ] Nom d'expéditeur par défaut, configuré, vide, trop long.
- [ ] Le rendu ne contient aucun pied de page.
- [ ] Balayage : aucun texte sortant ne contient le nom de l'outil.
- [ ] La page partagée n'affiche ni logo ni titre de marque.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | aucun accès aux données ne change |
| Plans / limites | non | — |
| **Navigation / routing** | **oui** *(une route)* | Seule la **page partagée** (`/p/...`, ouverte par un client) change d'apparence. Les écrans de l'utilisateur ne sont pas touchés — vérifié route par route. |
