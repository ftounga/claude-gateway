# F-124 — Suivi d'activité et de revenu par client (TJM, cumul, CRA)

> Cadrage du 2026-09-16, à la demande du PO, décisions validées via AskUserQuestion + confirmation des
> défauts. **Go de livraison donné.** Périmètre volontairement **léger : suivi, pas facturation.**

## 1. Le besoin
Le PO est consultant solo multi-clients (Free, KG, Eden Red…). Il veut, **dans la Forge** :
- un **TJM** (taux journalier, € HT) **par poste** (un poste = un client) ;
- sur chaque poste (à gauche), le **TJM** et le **cumul de revenu depuis un mois de départ** ;
- **en haut de la Forge, bien en avant**, le **cumul total tous clients confondus** depuis le mois de départ ;
- un endroit **« CRA »** où il déclare, **en un message en langage naturel**, les jours travaillés par
  client pour un mois (« Free 20j, KG 13j, Eden Red 20j ») ; l'IA en déduit les jours par poste.

## 2. Décisions actées (PO, 2026-09-16)
| Point | Décision |
|---|---|
| Cumul affiché | **Euros = jours × TJM** (par poste et total) |
| Défaut si CRA non déclaré | **Mois complet** pour ce poste (jours ouvrés du mois) — voir §5 transparence |
| Rattachement TJM/CRA | **Au poste** (machine, F-74) tel qu'affiché dans la Forge |
| Format du CRA | **Message en langage naturel, parsé par l'IA** |
| Jours ouvrés | **lun–ven hors jours fériés France** |
| Mois de départ | **septembre 2025**, **cumul continu** (pas de remise à zéro annuelle) ; configurable |
| Portée | **Par utilisateur** (chacun ses postes / TJM / CRA), isolation `user_id` |
| Demi-journées | **Oui (0,5)** |
| Périmètre | **Suivi seulement** (€ HT, cumul) — **pas** de factures/TVA/export comptable |
| Édition | Renvoyer un CRA pour un mois **écrase** l'ancien |
| Mois visé par un message CRA | **Mois en cours** par défaut, sauf précision dans le message (« mon CRA **de septembre**… ») |

## 3. Le calcul
Pour chaque mois **du mois de départ jusqu'au mois courant**, et pour chaque poste ayant un TJM :
- si un **CRA est déclaré** pour (poste, mois) → jours = jours déclarés (**source = déclaré**) ;
- sinon → jours = **jours ouvrés du mois** (lun–ven hors fériés France) (**source = supposé**).
- revenu(poste, mois) = jours × TJM(poste).
- **cumul(poste)** = Σ des mois. **total Forge** = Σ des postes.

Un mois **partiel** (mois de départ commencé, mois courant en cours) : jours ouvrés **écoulés/borné au mois**
pour la partie « supposé » — à préciser en mini-spec (défaut prudent : mois courant non supposé tant que
non déclaré, pour ne pas gonfler le total avec un mois inachevé).

## 4. Le CRA par message
- Un **onglet/terminal « CRA »** dans la Forge (ou une zone dédiée — la mini-spec tranche l'emplacement,
  cohérent avec les terminaux existants).
- L'utilisateur écrit un message libre ; **l'IA extrait** une liste `{poste/client → jours, mois}`.
  Rapprochement du nom cité (« Free », « KG ») au **poste** (par nom/alias). Un nom non reconnu →
  **demandé**, jamais deviné.
- Restitution : l'IA **récapitule ce qu'elle a compris** et écrit les entrées (déclaré) ; renvoyer un
  CRA pour un mois **écrase**. Demi-journées acceptées.
- **Provider-First** : l'extraction est une capacité du modèle (on ne réimplémente pas un NLP maison) ;
  la Gateway orchestre, valide (poste connu, mois valide, jours ≤ jours ouvrés du mois), persiste.

## 5. Transparence « déclaré vs supposé » (recommandé, validé)
Le défaut « mois complet par poste » **sur-estime** quand plusieurs postes ne sont pas déclarés le même
mois (chacun compte un mois plein). Pour que ce soit **visible et corrigeable** :
- chaque mois d'un cumul distingue **déclaré** (CRA) et **supposé** (mois complet auto) — badge/couleur,
  charte, aucune couleur nouvelle ;
- le cumul par poste et le total affichent la **part supposée** (ex. « dont X € supposés »), pour qu'un
  coup d'œil suffise à savoir ce qui est réel vs estimé.

## 6. Découpage
| SF | Titre | Contenu |
|---|---|---|
| **SF-124-01** | **TJM par poste** | Modèle + config du TJM (€ HT/jour) par poste, isolation `user_id`+`host_id` ; affiché **à gauche de chaque poste** dans la Forge (charte). Réglage du **mois de départ** (défaut 2025-09). |
| **SF-124-02** | **Cumul & total** | Calcul du revenu (jours × TJM ; jours ouvrés lun-ven hors fériés FR ; défaut mois complet ; demi-journées) ; **cumul € par poste** (à gauche) + **total tous clients bien en avant en haut de la Forge** ; distinction **déclaré/supposé** (part supposée affichée). |
| **SF-124-03** | **CRA par message** | Endroit « CRA » ; message NL → extraction IA `{poste → jours, mois}` (mois courant par défaut, sauf précision) ; validation (poste connu, jours ≤ ouvrés), nom inconnu **demandé** ; persistance (écrase pour un mois) ; récap de ce qui a été compris. |

**Ordre** : SF-124-01 → SF-124-02 → SF-124-03.

## 7. Données (indicatif, mini-spec tranche)
- TJM & mois de départ : par (`user_id`, `host_id`) — nouvelle table `poste_billing` (ou colonnes).
- CRA déclarés : table `cra_entries` (`user_id`, `host_id`, `year_month`, `days` numeric(4,1)) — **seuls
  les déclarés sont stockés** ; le « supposé » est **calculé** à la volée (aucune ligne).
- Jours fériés France : calculés (fixes + Pâques), pas de service externe.
- Migrations Liquibase dédiées.

## 8. Hors périmètre
- Facturation : factures PDF, TVA, numérotation, export comptable, relances de paiement.
- Multi-devise (€ uniquement).
- TJM par projet/sous-tâche (on reste au poste).
- Prévisionnel / objectifs de CA (on mesure le réalisé).

## 9. Préoccupations transversales
- **Auth / tenant** : tout est **par utilisateur** (isolation `user_id` obligatoire sur TJM, CRA, cumul).
- **Navigation** : SF-124-01/02 ajoutent de l'affichage à la Forge et aux postes ; SF-124-03 ajoute un
  endroit « CRA » → vérifier le parcours et les 4 terminaux si pertinent.
- **Plans / limites** : aucune consommation de jetons hors le tour d'extraction du CRA (quota existant).
- **Composants** : Forge frontend (entête cumul, panneau poste), écran/terminal CRA, backend
  (`poste_billing`, `cra_entries`, service de cumul + jours ouvrés/fériés, extraction via le provider IA),
  `AtelierChatService`/catalogue si le CRA passe par un tour d'agent.
