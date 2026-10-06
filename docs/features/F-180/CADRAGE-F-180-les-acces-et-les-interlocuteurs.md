# Cadrage — F-180 Les accès et les interlocuteurs (le fil des attentes, v3)

> Demande PO le 2026-10-06 : *« J'apprécie vraiment le travail sur la gestion des actions à faire,
> c'est dynamique, vivant… Comment l'améliorer encore, visuellement et fonctionnellement ? C'est un
> vrai point à arbitrer : tracer des accès, faire que les sujets avancent plus vite ; ces points de
> blocage ralentissent vraiment le travail. »* Évolution de F-175 (Terminée, en prod).
> Audit : `docs/audits/AUDIT-2026-10-06-terminal-central-gouvernance-parite.md` §3.

## 1. Ce que disent les données (prod, 06/10)

- **Les accès sont le goulot** : 51 % des attentes, 12 des 20 ouvertes, 4,3 j en moyenne ; le reste se ferme le jour même.
- **Une personne porte 11 des 20 attentes ouvertes** ; les noms sont en texte libre et dédoublés.
- Un accès obtenu **disparaît en « Fait »** : rien ne dit qu'il existe, quand il expire, ni qu'on l'a déjà.
- Un même accès est éclaté en 4 lignes (compte, jeton, proxy, incident) ; le dédoublonnage ignore les Fait.
- Relance seulement affichée ; push jamais activé ; `subject_id` jamais rempli ; 12 attentes encore « à valider » depuis la reprise.

## 2. Objectif

Faire du fil des attentes un **processus de déblocage** : savoir **qui** bloque **quoi**, depuis
**quand**, relancer **une fois par personne**, et garder la trace des **accès obtenus**.

## 3. Décisions (par défaut, réversibles)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Nature et « qui doit agir »** : `categorie` ∈ {ACCES, VALIDATION, INFO, TRANSMISSION, INCIDENT} ; `qui` ∈ {MOI, CLIENT, TIERS}. Paramètres de `record_blocker`, proposés par l'agent ; reprise par heuristique **validée par l'utilisateur**. Les gestes « moi » (SSO, redémarrer le runner) se distinguent des attentes envers autrui. | Données §1 |
| D2 | **Interlocuteurs normalisés** : une table `interlocuteurs` par poste (nom affiché, variantes, rôle, équipe), alimentée par les attentes ; fusion des variantes proposée. Si le Radar a déjà la personne (`radar_people`), on la relie. | 55 % sur une personne, noms dédoublés |
| D3 | **Rattacher au sujet et à l'étape** : `subject_id` rempli à l'inscription ; lien vers l'étape du plan F-176 bloquée ; la carte affiche « bloque : étape 3 de data-platform ». | `subject_id` 0/37 |
| D4 | **Registre des accès** : quand une attente ACCES passe à Fait, carte « Ajouter au registre ? » → `acces_obtenus` (système, compte/profil, obtenu le, par qui, expiration facultative, sujet). **Aucun secret stocké.** Le registre (compact) est joint au message du tour avec les attentes ; le dédoublonnage compare aussi aux accès du registre et aux Fait récents (« déjà obtenu le … »). | « Tracer des accès » |
| D5 | **Vue par interlocuteur** dans le panneau : par personne — nombre, la plus ancienne, sujets bloqués ; [Relancer Rémi (5)] produit **un** brouillon groupé dans la saisie (ou un courriel à soi-même F-110 à transférer), jamais envoyé sans geste. | Relancer une fois au lieu de 11 |
| D6 | **Échéance et rappel** : échéance facultative ; quand une relance est due ou un accès expire, notification push (F-153) et ligne dans un **point du matin** (bande des attentes). Invitation à activer le push si aucun abonnement. | Relance jamais vue |
| D7 | **Mesure** : délai demande → réponse par personne et par catégorie, affiché dans la vue par interlocuteur (« Rémi répond en 4 j en moyenne »). | Savoir où ça coince |
| D8 | **Hygiène** : purge des 12 `review_pending`, backfill des 9 embeddings, fusion proposée des doublons détectés. | Données §1 |

## 4. Découpage

| SF | Titre | Contenu | Estim. |
|---|---|---|---|
| SF-180-01 | Remise en ordre | D8. | 0,5 j |
| SF-180-02 | Nature, qui agit, sujet | D1, D3 (migration, outil, cartes, reprise validée). | 1,5 j |
| SF-180-03 | Les interlocuteurs | D2 (table, fusion, lien Radar). | 1,5 j |
| SF-180-04 | Le registre des accès | D4 (table, carte, jointure au tour, dédoublonnage étendu, onglet « Accès »). | 2 j |
| SF-180-05 | Par interlocuteur et relance groupée | D5, D7. | 1,5 j |
| SF-180-06 | Échéances et rappels | D6. | 1 j |

## 5. Critères d'acceptation (extraits)

- Demander à l'agent un accès déjà au registre → « déjà obtenu le … (expire le …) », pas de nouvelle attente.
- La vue par interlocuteur montre Rémi avec 11 attentes, triées par ancienneté ; [Relancer] dépose un seul texte groupé, non envoyé.
- Aucun secret (jeton, mot de passe) n'est accepté dans le registre (filtre + test).

## 6. Préoccupations transversales

- **Tenant** : nouvelles tables avec `user_id` + `host_id` ; composants : `TerminalActionService`, `TerminalActionTurnNote`, `TerminalActionSemanticDedup`, panneau/bande/cartes front.

## 7. Hors périmètre

Envoi automatique de relances ; escalade hiérarchique ; détection de la réponse dans Teams ou une
boîte de réception (à reprendre quand la Vigie et le Radar serviront réellement) ; coffre à secrets.
