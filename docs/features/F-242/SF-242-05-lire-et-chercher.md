# Mini-spec — F-242 / SF-242-05 Lire et chercher (Opus, fil Jarvis)

## Identifiant
`F-242 / SF-242-05`

## Feature parente
`F-242` — Jarvis et vos boîtes mail (cadrage, D10, D11, D17)

## Statut
`done` (2026-11-04) · **Estimation :** 0,75 j · **Branche :** `feat/SF-242-05-lire-et-chercher` · **Dépend de :** SF-242-02, F-245 SF-245-02 (mergées)

## Objectif
« Lis-moi le mail de la banque », « retrouve le devis d'Helios de septembre » fonctionnent dans le fil Jarvis, avec le corps complet relu par IMAP.

## Comportement attendu

### Cas nominal
1. **`JarvisMailToolCatalog`** : `courriel_lire` et `courriel_chercher`, définitions **figées** (même liste, même ordre à
   chaque tour), offertes **seulement** quand l'espace est le fil Jarvis (`isJarvisThread`) **et** que le compte a au
   moins une boîte reliée ; ajoutées à la fin de `AtelierChatService.buildTools`, aiguillées par le préfixe `courriel_`.
2. **`courriel_lire {courriel}`** : `courriel:<id>` (bloc Courrier, SF-242-04) ou `imap:<boîte>:<A|I>:<uidvalidity>:<uid>`
   (résultat de recherche) ; la boîte est **revérifiée sous le `user_id` du tour** ; corps relu en lecture seule (aucun
   drapeau « lu »), texte seul, ≈ 8 000 caractères, coupe annoncée ; en-têtes ; le tout **balisé** par
   `UntrustedMail.wrap()`.
3. **`courriel_chercher {boite, de, objet, texte, depuis, jusqu_a}`** : `SEARCH` IMAP standard (`FROM`, `SUBJECT`, `BODY`,
   `SINCE`/`BEFORE`, combinés par ET) dans « Tous les messages » (`\All`, archives comprises ; sinon la boîte de
   réception), **20 résultats au plus**, les plus récents d'abord, extrait de 300 caractères, références `imap:…`,
   balisés non fiables. Au moins un critère.
4. **`JarvisMailNote`** (D17, v1, figée) jointe **au message** des tours du fil Jarvis quand une boîte est reliée
   (jamais à la consigne système ; `JarvisOralNote` n'est pas modifiée).
5. Hors fil Jarvis, un appel `courriel_*` est refusé (défense en profondeur), et les outils ne sont jamais offerts.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Référence d'une boîte d'autrui (même forgée) | « introuvable » (erreur d'outil), rien n'est lu |
| Courriel sorti du dossier / `UIDVALIDITY` changée | « introuvable » |
| Recherche sans critère / date mal formée | erreur d'outil explicite |
| Mot de passe refusé | erreur d'outil : relier à nouveau dans « Mes boîtes » |
| Boîte injoignable | « ne répond pas » |

## Contraintes de validation
| Champ | Règle |
|---|---|
| corps | ≤ 8 000 caractères |
| résultats | ≤ 20, extrait ≤ 300 |
| dates | `AAAA-MM-JJ` |

## Critères d'acceptation
- [x] Les outils existent dans le fil Jarvis et sont **absents** du terminal d'un sujet (le test compare les listes d'outils).
- [x] La recherche rend des résultats bornés.
- [x] Chaque contenu est balisé non fiable.
- [x] La liste d'outils du fil Jarvis est identique d'un tour à l'autre (préfixe stable, testé).
- [x] L'utilisateur B ne peut pas lire une boîte de A, même en passant une référence de A.

## Plan de test
- **Unitaires** : `MailRef` (formes valides et forgées), critères de recherche, définitions figées (deux appels égaux).
- **Intégration** `JarvisMailToolsIntegrationTest` (GreenMail) : lire `courriel:` et `imap:` ; chercher (bornes, balisage) ;
  isolation A/B (référence de A par B ⇒ introuvable) ; hors fil Jarvis ⇒ refus ; outils offerts / absents
  (`toolsFor` fil Jarvis vs sujet, avec et sans boîte).
- **`AtelierChatService`** : la panoplie d'un sujet hébergé reste identique (test existant de F-245 inchangé) ; la note
  n'est jointe qu'au fil Jarvis avec une boîte.
- Suites complètes backend + frontend + build.

## Impact
- **Backend** : `jarvis/mail/JarvisMailToolCatalog`, `JarvisMailBox`, `MailRef`, `JarvisMailNote` (nouveaux) ;
  `atelier/AtelierChatService` (outils, aiguillage, note). Aucune table, aucun endpoint.

**Préoccupation transversale « Contexte tenant »** : `JarvisMailBox.resolve` revérifie la boîte par `user_id` ;
`toolsFor` lit `isJarvisThread` de l'espace **déjà possédé** (`requireOwned` du tour).

## Arbitrages (documentés dans la PR)
- **`SEARCH` IMAP standard plutôt que `X-GM-RAW`** : `X-GM-RAW` exige le module `gimap`, absent de jakarta.mail (une
  dépendance nouvelle) ; critères structurés, portables, testables sur GreenMail.
- **Référence `imap:` sans état** (boîte, dossier, UIDVALIDITY, UID), revérifiée à chaque appel : aucun cache de
  résultats, multi-pods sûr.

## Hors périmètre
Les écritures (SF-242-06).
