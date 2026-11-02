# Mini-spec — F-241 / SF-241-01 Le portrait : stockage chiffré et écran des réglages

## Identifiant
`F-241 / SF-241-01`

## Feature parente
`F-241` — Jarvis vous connaît : le portrait (cadrage `CADRAGE-F-241-jarvis-vous-connait-le-portrait.md`, §4 D1-D4, D19, D20 ; §5 ; §6 SF-241-01)

## Statut
`in-progress`

## Date de création
2026-11-02

## Branche Git
`feat/SF-241-01-portrait-stockage`

---

## Objectif
Le PO lit, écrit, date, efface son portrait et ses souvenirs dans les réglages de `/jarvis` ; tout est chiffré au repos et strictement isolé par `user_id`.

---

## Comportement attendu

### Cas nominal
- **Portrait** (une ligne par compte) : sept sections fixes (D1) — `QUI` « Qui vous êtes », `ENTOURAGE` « Votre entourage » (sensible), `MOMENT` « Ce qui compte en ce moment » (sensible), `GOUTS` « Vos goûts », `HUMOUR` « L'humour qui vous plaît », `INTERDITS` « À ne pas aborder », `DATES` « Dates qui comptent » (une ligne `JJ/MM[/AAAA] — libellé`). Chaque section porte son texte et son `updatedAt` (mis à jour seulement si son texte change).
- `GET /api/jarvis/portrait` rend les sept sections (vides si aucun portrait), les souvenirs numérotés `S1…Sn` (ordre de création), l'interrupteur `enabled`, la `version`, la taille (`chars`, `tokens` ≈ chars/4, `maxTokens` 1 500) et la marque `stale` (« à revoir ») de tout élément de plus de 12 mois.
- `PUT /api/jarvis/portrait` `{sections: {CLE: texte}, enabled?}` : remplace les sections fournies, incrémente `version`.
- **Souvenirs** : `POST /souvenirs` `{text, sensitive?, source?}` (201) ; `PATCH /souvenirs/{id}` `{text?, sensitive?}` ; `DELETE /souvenirs/{id}` (204) ; `POST /souvenirs/{id}/integrate` `{section}` déplace le souvenir à la fin de la section (une ligne) puis l'efface, en une transaction. Chaque écriture incrémente `version` (D5 : le bloc est rechargé dès qu'un souvenir change).
- `DELETE /api/jarvis/portrait` : **suppression physique** immédiate du portrait et de tous les souvenirs du compte (204).
- **Chiffrement (D19)** : le JSON des sections et le texte de chaque souvenir sont chiffrés par `ByokKeyCipher` (enveloppe KMS en prod, AES local en tests) ; en base, uniquement `dataKey.iv.ciphertext` (base64). Rien en clair, rien dans les journaux.
- **Suppression du compte** : `AccountService` purge les deux tables.
- **Écran** : un bouton « Mon portrait » dans les réglages de `/jarvis` (volet 3D et rangée 2D, à côté de l'humour) ouvre le volet « Mon portrait » : sept zones de texte (mention « sensible », date de mise à jour, marque « à revoir »), compteur `n / 1 500 jetons`, interrupteur « Jarvis se sert de mon portrait », liste des souvenirs (texte, date, sensible, modifier, effacer, « Intégrer au portrait »), « Tout effacer » derrière une confirmation `MatDialog`. **Volet et bouton masqués en présentation** (`?presentation=1`).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Total portrait + souvenirs > 6 000 caractères (≈ 1 500 jetons) | refus, message « Le portrait dépasse 1 500 jetons (n) : raccourcissez … » | 400 `portrait_too_large` |
| Clé de section inconnue / souvenir vide ou > 200 caractères / source inconnue | refus explicite | 400 `invalid_portrait` |
| 31ᵉ souvenir | « Ma mémoire est pleine, faites le tri dans les réglages. » | 409 `souvenirs_full` |
| Souvenir d'un autre compte (lecture, modification, effacement, intégration) | introuvable | 404 |
| Chiffrement non configuré (dormant) | portrait indisponible, rien stocké en clair | 503 (gestionnaire `ByokDisabledException` existant) |
| Non authentifié | refus | 401 |

---

## Critères d'acceptation
- [ ] CA1 — enregistrer, relire et modifier chaque section ; `updatedAt` ne bouge que pour une section modifiée.
- [ ] CA2 — ajouter, modifier, effacer, intégrer un souvenir ; « Tout effacer » vide les tables du compte.
- [ ] CA3 — au-delà de 1 500 jetons (6 000 caractères), 400 avec un message clair ; 31ᵉ souvenir : 409.
- [ ] CA4 — les colonnes sont chiffrées en base : le texte clair n'y apparaît pas.
- [ ] CA5 — l'utilisateur B reçoit 404 sur les souvenirs de A et ne voit jamais le portrait de A.
- [ ] CA6 — la suppression du compte purge les deux tables.
- [ ] CA7 — aucune couleur hors du design system ; volet masqué en présentation ; pas de débordement horizontal à 360 px.

---

## Périmètre
### Hors scope (explicite)
- L'usage du portrait par Jarvis (SF-241-03) ; l'entretien oral (SF-241-02) ; retenir en conversation (SF-241-05) ; le journal des allusions (SF-241-04).
- Tout point d'accès administrateur (il n'y en a **aucun**).

---

## Valeurs initiales
| Champ | Valeur initiale | Règle |
|---|---|---|
| `portrait_enabled` | `true` | D20 (vrai par défaut) |
| `version` | `1` à la création, +1 à chaque écriture | clé de rechargement du bloc (SF-241-03) |
| `interview_offers` | `0` | cadence D13 (SF-241-02) |
| souvenir `source` | `ECRAN` si absent | `VOIX`, `ECRAN`, `ENTRETIEN` |
| souvenir `noted_on` | date du jour (Europe/Paris) | D3 |

---

## Contraintes de validation
| Champ | Obligatoire | Longueur max | Format / Valeurs | Normalisation |
|---|---|---|---|---|
| `sections.<CLE>` | Non | borne totale 6 000 car. | clés `QUI, ENTOURAGE, MOMENT, GOUTS, HUMOUR, INTERDITS, DATES` | `strip()` |
| souvenir `text` | Oui | 200 | non vide | `strip()`, espaces repliés |
| souvenir `source` | Non | — | `VOIX`, `ECRAN`, `ENTRETIEN` | majuscules |
| souvenirs | — | 30 par compte | — | — |
| total portrait + souvenirs | — | 6 000 car. (≈ 1 500 jetons, jetons ≈ car./4) | — | — |

---

## Technique
### Endpoint(s)
| Méthode | URL | Auth | Rôle |
|---|---|---|---|
| GET / PUT / DELETE | `/api/jarvis/portrait` | Oui | tout compte, **sa** ligne |
| POST | `/api/jarvis/portrait/souvenirs` | Oui | tout compte |
| PATCH / DELETE | `/api/jarvis/portrait/souvenirs/{id}` | Oui | propriétaire (404 sinon) |
| POST | `/api/jarvis/portrait/souvenirs/{id}/integrate` | Oui | propriétaire (404 sinon) |

### Tables impactées
| Table | Opération | Notes |
|---|---|---|
| `jarvis_portraits` (nouvelle) | CRUD | clé `user_id` ; `sections_enc`, `portrait_enabled`, `version`, `updated_at`, `last_interview_at`, `last_checkin_at`, `interview_offered_at`, `interview_offers` |
| `jarvis_souvenirs` (nouvelle) | CRUD | `id`, `user_id` (index), `text_enc`, `sensitive`, `noted_on`, `source`, `created_at` |

### Migration Liquibase
- [x] Oui — `241-jarvis-portraits.xml` (numéro = feature, patron du dépôt ; rollback = tables retirées)

### Composants Angular
- `JarvisPortraitComponent` (`frontend/src/app/jarvis/jarvis-portrait.component.ts`) — le volet « Mon portrait ».
- `JarvisPortraitClient` (`frontend/src/app/jarvis/jarvis-portrait.ts`) — appels HTTP + fonctions pures (compteur, « à revoir »).

### Préoccupations transversales
- [ ] Auth / Principal — non (lecture du `user_id` courant, inchangée)
- [x] Contexte tenant — composants qui résolvent le `user_id` : `JarvisPortraitController` (`CurrentUser.requireId()`), `JarvisPortraitService` (toutes les requêtes `…ByUserId`, `findByIdAndUserId`), `AccountService` (purge). Aucun autre composant ne lit ces tables.
- [ ] Plans / limites — non
- [x] Navigation / routing — aucune route ajoutée ; un volet dans `/jarvis`, masqué en présentation (`presentation()`), sans effet sur les autres chemins.

---

## Plan de test
### Tests unitaires
- [ ] `JarvisPortraitRulesTest` — borne 6 000 caractères (pile / au-delà), jetons ≈ car./4, « à revoir » à 12 mois (veille / lendemain), clés inconnues.
- [ ] `PortraitSealTest` — chiffrer/déchiffrer aller-retour ; le scellé ne contient pas le clair ; format `a.b.c`.
### Tests d'intégration
- [ ] `JarvisPortraitApiIntegrationTest` — GET vide ; PUT puis GET ; `updatedAt` stable pour une section inchangée ; 400 trop long (message) ; 400 clé inconnue ; souvenirs CRUD + intégration ; 409 au 31ᵉ ; DELETE tout effacer ; **colonnes chiffrées** (lecture JDBC brute : pas de clair) ; 401.
- [ ] Purge du compte (`AccountService.deleteAccount`) : tables vidées pour ce compte seulement.
- [ ] Liquibase : le contexte de test applique la migration (suite complète verte).
### Isolation
- [ ] Applicable — B ne lit pas le portrait de A (GET rend le sien, vide) ; B reçoit 404 en PATCH/DELETE/integrate d'un souvenir de A ; « Tout effacer » de B ne touche pas A.
### Écran
- [ ] `jarvis-portrait.spec.ts` — fonctions pures (compteur, à revoir) ; composant : sections affichées, enregistrer, souvenir effacé, « Tout effacer » confirmé par dialogue, compteur.
- [ ] `jarvis.component.spec.ts` — bouton « Mon portrait » absent en présentation.

---

## Dépendances
### Subfeatures bloquantes
- Aucune (peut partir avant F-240 ; F-240 est mergée).
### Questions ouvertes impactées
- Aucune.

---

## Notes et décisions
- **Arbitrage A1 (réversible)** : `POST /souvenirs/{id}/integrate` ajouté côté serveur (non listé au §5) pour que « Intégrer au portrait » soit **atomique** (déplacer = écrire la section + effacer le souvenir dans une transaction).
- **Arbitrage A2 (réversible)** : le scellé réutilise `ByokKeyCipher` tel quel (`GitTokenService` le fait déjà) ; aucune interface `SecretCipher` extraite (le contrat `encrypt(String)`/`decrypt` suffit). Format colonne `dataKey.iv.ciphertext` (le `.` n'est pas un caractère base64).
- **Arbitrage A3 (réversible)** : le déchiffrement est mis en cache mémoire par compte et par `version` (le bloc ne coûte qu'un déchiffrement KMS par modification, pas par tour).
