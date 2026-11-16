# Cadrage F-266 — Règles de tous mes postes (portée COMPTE)

**Date** : 2026-11-16 · **Demandeur** : PO

## Besoin observé
Le 15/11, le PO a appliqué au poste CAGIP la règle « Style des preuves (capturer_preuves) »
(proposition `4c5387e0-…`, REGLE, POSTE, APPLIED). Il veut la même règle chez EDENRED et chez tous ses
clients, sans la recopier poste par poste. Aujourd'hui une règle n'existe qu'en `GOUVERNANCE.md` d'un
poste ou d'un sujet (fichier sur la machine, poste en ligne requis pour l'écrire).

**Constat de passage (mesuré en prod)** : le `GOUVERNANCE.md` du poste CAGIP fait 18 766 caractères ;
l'injection le coupe à 8 000 (`GOVERNANCE_FILE_MAX_CHARS`). La section « Style des preuves » commence
au caractère 17 052 : **elle n'a jamais été lue par l'agent**. La portée COMPTE, injectée dans son
propre bloc borné, règle ce cas pour les règles recopiées. Relever la borne du poste est hors périmètre
(sujet coût/justesse à part).

## Existant vérifié
- `GovernanceProposalService` : types REGLE/SKILL/GABARIT/AGENT, portées POSTE/SUJET ; [Appliquer] écrit
  via le runner (POSTE) ou le stockage (SUJET hébergé).
- `AtelierChatService.buildSystemPrompt` : `GOUVERNANCE.md` du poste puis du sujet, encadrés, bornés
  à 8 000 car. chacun, puis règles des paquets (`GovernanceRulesProvider`).
- `GovernanceEffectiveService` / écran Forge « Ce qui s'applique vraiment » : extrait de 4 000 car.
- Carte de proposition du terminal (`proposal-card.component.ts`).

## Décisions
- **D1** Table `account_governance_rules` (user_id, titre, clé de titre normalisée unique par
  utilisateur, contenu ≤ 4 000 car., origine). Purge à la suppression du compte.
- **D2** Injection : bloc « Règles du client — tous mes postes » **avant** les règles du poste
  (général → précis), ordre stable (clé de titre), borné à 8 000 car. Une règle dont le titre figure
  (`## titre`, sans casse) dans le texte **injecté** du poste ou du sujet est omise. Rien de volatil :
  le bloc ne change que si une règle change (préfixe de cache stable). Toutes cibles dont la consigne est bâtie par
  `buildSystemPrompt` (terminal du poste, sujet, projet hébergé).
- **D3** `gouvernance_proposer` accepte `portee=COMPTE` pour **REGLE** seulement ; [Appliquer] écrit en
  base, sans poste ; conflit si la règle a changé depuis la proposition.
- **D4** « Valable sur tous mes postes » : depuis la carte d'une règle POSTE/SUJET appliquée (section
  extraite de la proposition, poste éteint accepté) et depuis la Forge (section lue dans le
  `GOUVERNANCE.md` du poste).
- **D5** Route propre pour le PO : `POST /api/governance/account-rules` (titre + contenu) — préférable
  à un INSERT SQL ; l'INSERT idempotent reste fourni.

- **D6** (découvert au développement, mesuré) : la consigne est coupée à 40 000 caractères (plafond
  de F-28) alors que doctrines et guides d'outils font déjà ~43 000 (sujet) à ~49 000 (terminal du
  poste) : CLAUDE.md, `GOUVERNANCE.md` et règles des paquets — placés en fin — tombent. Sans correctif,
  les règles COMPTE tomberaient aussi. Plafond porté à 120 000, CLAUDE.md borné à 30 000, coupe
  journalisée. Inclus dans SF-266-01.

## Découpage
- **SF-266-01** (backend, 1,5 j) : table, service, injection, portée COMPTE, routes (liste, créer,
  retirer, recopier depuis proposition / depuis poste), titres des règles du poste dans la vue Forge.
- **SF-266-02** (frontend, 1 j) : Forge (section « Tous mes postes », [Retirer], « Valable sur tous mes
  postes » par règle du poste) ; carte (« Pour tous mes postes », action de recopie).
