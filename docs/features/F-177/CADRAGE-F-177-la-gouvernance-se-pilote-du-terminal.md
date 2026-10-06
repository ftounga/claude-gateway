# Cadrage — F-177 La gouvernance se pilote depuis le terminal

> Demande PO le 2026-10-06 : *« J'ai rajouté de la gouvernance, est-ce bien pris en compte ? Est-ce que
> depuis le terminal central je peux lui demander de mettre en place tel type de gouvernance, de créer
> des skills ? Ou faut-il à chaque fois venir ici ? Ce serait mieux de créer des skills propres à un
> client. »* Audit : `docs/audits/AUDIT-2026-10-06-terminal-central-gouvernance-parite.md` §1.

## 1. Constat

- La règle « commentaires Jira courts » a été écrite par l'agent dans `GOUVERNANCE.md` du poste, mais
  **ce fichier n'est jamais réinjecté** : elle ne vaudra ailleurs que si l'agent pense à le relire.
- Aucun outil ne permet à l'agent de créer une règle, un skill ou un gabarit **dans le circuit**
  (versionné, visible, porté par le poste ou le sujet). Les paquets applicatifs sont globaux et ADMIN.
- Un `SKILL.md` écrit à la main est annoncé, mais hors circuit et invisible à l'écran Gouvernance.
- Dépôts en retard (CAGIP v2/4/6 contre v17) ou jamais faits (EDENRED), sans aucun signal.

## 2. Objectif

Dans n'importe quel terminal, *« mets dans la gouvernance que… »* ou *« crée-moi un skill pour ce
client »* produit une **proposition visible** que l'utilisateur valide d'un clic ; une fois validée, la
règle ou le skill **s'applique à chaque tour**, dans tout le poste (ou le seul sujet), et apparaît à
l'écran Gouvernance. Comme Claude Code : le savoir du client vit **sur son poste**, dans des fichiers
lisibles.

## 3. Décisions (par défaut, réversibles)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **`GOUVERNANCE.md` devient le fichier de règles du client**, au même rang que `CLAUDE.md` : celui du **poste** (racine) puis celui du **sujet** sont injectés dans la consigne à chaque tour (ordre poste → sujet, comme la hiérarchie `CLAUDE.md` de Claude Code), bornés (8 000 car. chacun), suivis par `prompt_source_files`. Littéral stable entre deux modifications → cache préservé. | Le fichier existe déjà (gabarit du paquet), c'est là que l'agent écrit. Provider-First : même mécanisme que CLAUDE.md. |
| D2 | **Pas de paquet privé en base pour l'instant.** Les paquets applicatifs restent le socle ADMIN partagé ; le spécifique client vit dans les fichiers du poste. Un « paquet privé » réutilisable entre postes = réserve. | Moins de surface, une seule source par client. |
| D3 | **L'agent propose, l'utilisateur valide.** Nouvel outil `gouvernance_proposer(type, portee, nom, contenu, raison)` — `type` ∈ {`REGLE`, `SKILL`, `GABARIT`} (puis `AGENT` F-182, `CROCHET` F-181), `portee` ∈ {`POSTE`, `SUJET`}. Il **n'écrit rien** : il émet une carte dans le fil avec le **diff** et [Appliquer] [Modifier] [Refuser]. [Appliquer] écrit via le runner (même chemin que le dépôt de gouvernance, digest tracé). Respecte D5 « rien ne s'écrit chez le client dans son dos ». | Demande PO ; garde-fou |
| D4 | **Doctrine** (littéral stable) : toute demande de règle durable, de skill ou de « mets dans la gouvernance » passe par `gouvernance_proposer` ; un `write_file` direct sous `.claude/skills/` ou sur `GOUVERNANCE.md` déclenche un rappel (« passe par la proposition »). | Éviter le hors-circuit |
| D5 | **Skills invocables** : `/` dans la saisie liste aussi les skills du sujet et du poste (catalogue servi par le back, à côté des commandes F-165) ; `/nom-du-skill texte` charge le skill **de façon déterministe** (outil `skill(nom)` qui renvoie son contenu, même lecture que le catalogue actuel). Plafond d'annonce de 15 conservé ; au-delà, les skills restent invocables par `/`. | Parité Claude Code |
| D6 | **Écran « Gouvernance du poste »** : ce qui s'applique réellement — `GOUVERNANCE.md` poste/sujets, skills (avec origine : paquet ou client), paquets activés **avec leur retard de dépôt** (« fichiers en v6, paquet en v17 — [Remettre à jour] ») et les activations jamais déposées. | Constat EDENRED/CAGIP |

## 4. Découpage

| SF | Titre | Contenu | Estim. |
|---|---|---|---|
| SF-177-01 | La règle écrite s'applique partout | D1 : injection `GOUVERNANCE.md` poste + sujet, bornes, suivi, test de cache stable. **Gain immédiat.** | 1 j |
| SF-177-02 | L'agent propose, je valide | D3, D4 : outil, carte diff (front), application via runner, doctrine. | 2 j |
| SF-177-03 | Mes skills au bout du `/` | D5 : catalogue servi, autocomplétion, outil `skill`. | 1,5 j |
| SF-177-04 | Ce qui s'applique vraiment | D6 : écran + remise à jour en un clic (réutilise `apply` existant). | 1,5 j |

## 5. Critères d'acceptation (extraits)

- Une règle validée dans le terminal du poste est présente dans la consigne d'un tour d'un **autre** sujet du même poste, et pas dans celle d'un autre poste.
- Sans clic [Appliquer], aucun fichier n'est écrit sur le poste.
- `/` liste les skills du client ; `/ticket-jira DECPB-200` charge ce skill sans dépendre du bon vouloir du modèle.
- L'écran signale CAGIP « en retard » et EDENRED « jamais déposé ».

## 6. Préoccupations transversales

- **Tenant** : écriture et lecture bornées au poste du `user_id` (`host_id` vérifié). Composants : `AtelierChatService` (assemblage consigne), `GovernanceDepositService`, `prompt_source_files`, contrôleur Gouvernance.
- **Navigation** : nouvel onglet dans l'écran Gouvernance (route existante `/gouvernance`).

## 7. Hors périmètre

Paquets privés partagés entre postes ; création de **contrôles serveur** par l'agent (jamais) ;
crochets (F-181) et agents nommés (F-182) — ils réutiliseront `gouvernance_proposer`.
