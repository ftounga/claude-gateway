# Mini-spec — F-138 / SF-138-01 — Les profils métier

## Identifiant
`F-138 / SF-138-01` — feature parente `F-138`

## Objectif
Que l'agent raisonne comme un **ingénieur d'infrastructure**, et non comme un développeur, quand
c'est ce métier-là qu'on exerce chez le client.

## Le défaut
> *« Tu es un assistant de développement qui travaille sur le projet de l'utilisateur, sur sa
> machine. »* — la **première phrase** du prompt, à chaque tour, sur les deux cibles.

La discipline d'investigation (F-119) dit *comment* vérifier. Elle ne dit pas **ce qui vaut preuve
dans ce métier** : une version installée, un certificat, un droit effectif, une route, un quota — et
la différence entre *ce qu'on a vu* et *ce qu'on nous a dit*.

## Le véhicule existe déjà, et il est vide
Le catalogue de gouvernance (F-51) sait porter des **règles injectées** dans la consigne système et
s'**activer par poste**. Il ne contient **qu'un seul paquet**. Un profil n'est donc pas un mécanisme
à construire : c'est une doctrine à écrire.

## Comportement attendu
1. Quatre profils sont publiés au catalogue : **Architecte**, **Infrastructure / Production**,
   **Sécurité**, **Données**.
2. Chacun porte un texte de rôle, ce qui vaut preuve, les réflexes d'investigation du domaine et la
   forme du livrable attendu.
3. Un profil s'active sur un poste comme tout paquet, et rejoint la consigne système.
4. **Un profil ne dépose aucun fichier** sur la machine du client.
5. **Aucun profil n'est activé par défaut** : le choix appartient au PO, poste par poste.

| Cas d'erreur | Comportement |
|---|---|
| Deux profils activés sur un poste | les deux textes s'ajoutent, chacun nommé — l'utilisateur voit d'où vient chaque consigne |
| Profil trop long | non semé, et la raison est journalisée (borne de F-51) |
| Semis en échec | le démarrage n'échoue **jamais** ; le catalogue garde ce qu'il avait |

## Critères d'acceptation
- [ ] Les quatre profils existent au catalogue, publiés, avec un résumé lisible.
- [ ] Un profil activé sur un poste rejoint la consigne système, **nommé**.
- [ ] Un profil **n'apporte aucun fichier** — vérifié par test : rien n'est écrit sur la machine.
- [ ] Aucun profil n'est **sélectionné par défaut** : activer reste un geste.
- [ ] Un profil ne cite aucune règle de plateforme et n'en desserre aucune.
- [ ] Le semis est **idempotent** : redémarrer ne duplique rien.
- [ ] Chaque texte tient dans la borne de F-51.

## Hors scope
Un écran de choix de profil — l'écran Gouvernance existant les liste déjà · la détection
automatique du métier · les profils hors du domaine du PO.

## Technique
| Élément | Changement |
|---|---|
| **`governance/profils/*.md`** *(nouveau)* | les quatre doctrines |
| **`GovernanceProfileSeeder`** *(nouveau)* | sème les profils, sans fichier ni contrôle |

Aucune table, aucune migration, aucune route.

## Plan de test
- [ ] Les quatre profils sont semés, publiés, avec des règles non vides.
- [ ] Aucun n'apporte de fichier.
- [ ] Aucun n'est retenu par défaut.
- [ ] Le semis rejoué ne duplique pas.
- [ ] Chaque texte respecte la borne.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | un paquet du catalogue est un **modèle**, pas une donnée de client ; son activation, elle, est déjà portée par `(user_id, host_id)` |
| **Plans / limites** | **oui** | Le bloc de règles injecté grossit. Il reste borné par `GovernanceRulesProvider.MAX_RULES_BLOCK_CHARS` (12 000) ; chaque profil est écrit court pour que « savoir durable + un profil » tienne largement. Aucun gate, aucun quota touché. |
| Navigation / routing | non | — |
