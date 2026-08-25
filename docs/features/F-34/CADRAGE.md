# Cadrage — F-34 : Instructions par projet

## Identifiant / Statut / Date

`F-34` · `livrée (SF-34-01→02, 2026-08-25)` · 2026-08-25

## Objectif

Permettre à chaque projet de porter ses **propres instructions** pour l'agent, au lieu d'un unique
prompt système commun à tous les utilisateurs.

## Contexte

L'agent Managed Agents est provisionné **une fois pour la plateforme**, avec un prompt système figé
(« Tu es l'agent de l'Atelier claude-gateway… »). Il ignore donc tout des conventions du projet sur
lequel il travaille : outil de test, style de code, structure, contraintes métier.

C'est ce qui sépare une réponse générique d'une réponse pertinente, et c'est le mécanisme que Claude
Code utilise lui-même.

## Ce que l'API offre

`agent: {type: "agent_with_overrides", id, version?, system, …}` à la **création de session** :
surcharge le prompt système **pour cette session seulement**, sans toucher à l'agent plateforme.

## Décisions par défaut (à contredire si besoin)

| # | Décision | Pourquoi |
|---|----------|----------|
| D1 | Le fichier lu est **`CLAUDE.md`** à la racine du workspace, avec repli sur `.atelier/instructions.md` | Depuis F-31, les dépôts clonés en contiennent souvent déjà un : le lire donne un gain immédiat sans que l'utilisateur ait rien à faire. Le nom de fichier n'est pas une marque affichée dans l'interface — ADR-014 reste respecté |
| D2 | Le contenu est **ajouté** au prompt plateforme, jamais substitué | Les garde-fous de la plateforme doivent survivre à ce qu'écrit l'utilisateur |
| D3 | Borne configurable, défaut **20 000 caractères**, tronquée au-delà avec mention | Un fichier démesuré consommerait le contexte utile à chaque session |
| D4 | Fichier absent → comportement actuel, à l'identique | Aucune régression pour les projets existants |
| D5 | Une modification du fichier prend effet **à la session suivante** | La session persistante fige son prompt à l'ouverture ; le dire est plus honnête que de laisser croire à un rechargement à chaud |

## Découpage

| SF | Contenu |
|----|---------|
| **SF-34-01** | Lecture du fichier d'instructions + `agent_with_overrides` à l'ouverture de session (backend) |
| **SF-34-02** | Indication à l'écran que le projet porte des instructions, et lien pour les éditer (frontend) |

## Pièges identifiés

- **Injection de prompt** : le contenu vient de l'utilisateur. D2 (ajout, pas substitution) est la
  protection ; le prompt plateforme doit rester en tête et énoncer ses règles comme non négociables.
- La session étant persistante (SF-30-04), le prompt n'est lu **qu'à l'ouverture** — d'où D5.

## Hors scope

Instructions par dossier (imbrication à la Claude Code) ; édition assistée du fichier ; instructions
au niveau utilisateur, transversales à tous ses projets.
