# Mini-spec — F-161 / SF-161-05 — La porte entre sur la carte

## Identifiant
`F-161 / SF-161-05` — feature parente `F-161`

## Objectif
Faire entrer sur la **carte des capacités** (F-156) les deux savoirs branchés par F-161 — la
**porte d'entrée** et le **journal des ruptures** — et apprendre à la carte à juger une capacité
qui, **par construction, ne laisse aucune trace mesurable**.

## Pourquoi cette subfeature existe
Le cadrage F-161 §2 s'y engage explicitement :

> **C'est une capacité dormante au sens exact de F-156** : rien à créer, un branchement à poser. Le
> diagnostic l'aurait signalée si ces deux capacités figuraient sur la carte — **et il faudra les y
> ajouter.**

SF-161-01/02/03 ont posé le branchement. La promesse de le **rendre visible au diagnostic** n'a pas
été tenue : `CapabilityMap` ignore encore la porte. Tant qu'elle l'ignore, un remaniement qui
supprimerait l'appel `checkRunnerDoor(...)` **ne casserait rien, ne ferait tomber aucun test**, et
le produit repaierait les 11 % de facture que F-161 vient d'économiser — le défaut exact que
F-157 a nommé.

## Le mur qu'on rencontre, et l'arbitrage qu'il impose
La carte de F-156 suppose que **toute capacité laisse une trace positive** : un outil appelé, une
grandeur d'usage, une table alimentée. Les deux capacités de F-161 **violent cette supposition, et
ce n'est pas un défaut — c'est leur définition** :

| Capacité | Sa réussite, c'est… | Ce qu'elle laisse dans les mesures |
|---|---|---|
| **La porte** | un tour qui **ne s'ouvre pas**, des jetons **non dépensés** | rien : ni `usage_turns`, ni `runner_audit` — c'est tout son intérêt |
| **Le journal** | une rupture consignée — et **zéro rupture est une bonne nouvelle** | une table dont le **vide ne prouve rien** |

**Arbitrage tranché ici.** Les déclarer avec un signal serait mentir deux fois :
- un signal d'**outil ou d'usage** sur la porte la dirait « dormante » à chaque rapport, alors
  qu'elle tourne — et un diagnostic qui accuse une capacité qui marche perd sa crédibilité au
  deuxième rapport (`CapabilityVerdict`, doc de l'énumération) ;
- un signal de **table** sur le journal ferait conclure « jamais amorcée » sur une semaine **sans
  aucune panne**. `repo_index_paths` vide veut dire « jamais amorcé » ; `runner_disconnects` vide
  veut dire « rien n'a cassé ». La même règle sur les deux tables dirait une bêtise.

**Donc** : ces capacités sont déclarées **sans signal**, et jugées **par leur seul témoin de
branchement** (F-157) — une vérification **gratuite**, sans jeton, qui répond à la seule question
qu'on puisse honnêtement poser d'elles : *est-elle encore branchée ?*

## Comportement attendu
1. La carte porte deux capacités de plus : `porte-du-runner` et `journal-des-ruptures`, avec leurs
   chemins réels et leurs **témoins de branchement**.
2. Une capacité **sans signal** est reconnue comme **non mesurable par construction**. Sans lecture
   du code, son verdict est **INDÉTERMINÉE**, et le rapport **dit pourquoi** : rien à mesurer, seul
   le témoin peut trancher.
3. Avec le code lu et **tous ses témoins présents**, elle vaut **BRANCHÉE** : ce n'est pas un
   constat à traiter, elle est comptée avec ce qui est en ordre, **pas listée**.
4. Avec le code lu et **un témoin absent**, elle vaut **DÉBRANCHÉE** — le comportement F-157
   existant, inchangé.
5. **Aucun changement** pour les capacités mesurables : même verdict, même phrase, même comptage.

| Cas | Verdict | Listé dans les constats ? |
|---|---|---|
| Non mesurable, code non lu | `INDETERMINEE` | oui — « désigne le dépôt, la vérification est gratuite » |
| Non mesurable, tous témoins présents | `BRANCHEE` | **non** — comptée avec ce qui est en ordre |
| Non mesurable, un témoin absent | `DEBRANCHEE` | oui — un remaniement l'a détachée |
| Mesurable (les 9 capacités d'avant) | inchangé | inchangé |

## Cas d'erreur
- **Un fichier de la carte n'est pas lu** (dépôt partiel, lecture refusée) → aucun témoin vérifié →
  `INDETERMINEE` avec « branchement non vérifiable ». On **ne conclut pas** « débranchée » sur un
  fichier absent.
- **Un fragment de témoin devient faux** (renommage, reformatage) → la garde de build
  (`CapabilityMapTest`) **échoue au build**, avant qu'un rapport n'accuse à tort.
- **Une capacité déclarée sans signal *et* sans témoin** → la garde de build échoue : on ne pourrait
  strictement rien en dire, la déclarer serait du bruit.

## Critères d'acceptation
- [ ] `CapabilityMap` porte `porte-du-runner` et `journal-des-ruptures`, chemins **réels** vérifiés
      par la garde existante.
- [ ] La porte déclare **trois** témoins : la lecture du battement, le contrôle des capacités
      déclarées, et **l'appel de la porte dans la boucle du tour** — c'est ce dernier qu'un
      remaniement supprime sans rien casser.
- [ ] Le journal déclare ses témoins d'écriture sur **les deux transports** (long-polling et
      WebSocket) : une rupture perdue sur un seul transport passerait inaperçue.
- [ ] Une capacité sans signal n'est **jamais** dite « dormante » : elle vaut `INDETERMINEE`,
      `BRANCHEE` ou `DEBRANCHEE`.
- [ ] Une capacité sans signal dont les témoins sont vérifiés **n'apparaît pas** dans les constats
      et **est comptée** parmi les capacités en ordre.
- [ ] **Non-régression** : les 9 capacités mesurables rendent exactement les verdicts d'avant, avec
      ou sans lecture de code.
- [ ] La garde de build refuse une capacité **sans signal et sans témoin**.
- [ ] L'écran d'administration nomme le nouveau verdict et ne promet plus que les capacités
      comptées « se sont déclenchées » — certaines sont seulement **en place**.
- [ ] **ISOLATION** : aucun nouvel accès aux données. Le diagnostic reste filtré par `user_id` par
      les chemins existants ; les deux nouvelles capacités **ne lisent aucune table**.

## Plan de test minimal
**Unitaires (backend)**
- `CapabilityMapTest` : la garde des chemins et des fragments couvre les deux nouvelles capacités ;
  nouvelle garde « sans signal ⇒ au moins un témoin ».
- `ProductDiagnosisServiceTest` : une capacité sans signal ne vaut jamais `DORMANTE` ; elle vaut
  `INDETERMINEE` sans lecture de code, et sa phrase dit pourquoi.
- `WiringInspectorTest` : non mesurable + témoins présents → `BRANCHEE` (non listée) ; non mesurable
  + témoin absent → `DEBRANCHEE` ; **mesurable + témoins présents → verdict inchangé**.

**Intégration (backend)** — le rapport rendu par l'API contient les deux capacités et compte la
capacité branchée parmi celles en ordre, sans la lister.

**Isolation** — la subfeature n'ouvre **aucun accès aux données** : la non-régression d'isolation
est portée par les tests existants du diagnostic (`ProductDiagnosticApiIntegrationTest`).

**Frontend** — `verdictLabel('BRANCHEE')` rend « Branchée » ; le texte « rien à signaler » ne
prétend plus que tout s'est déclenché.

## Contraintes de validation
| Champ | Contrainte | Tranchée ici |
|---|---|---|
| Identifiant de capacité | minuscules, tirets, **unique** | garde existante `idsAreUnique` |
| Signal | liste **éventuellement vide** = non mesurable par construction | oui — c'est l'objet de la SF |
| Témoin | fragment **littéral**, présent dans un **chemin de la capacité** | gardes existantes, inchangées |
| Verdict | énumération fermée, + `BRANCHEE` | oui |

Aucune contrainte structurante n'est laissée indéterminée ; aucun sujet de `docs/OPEN_QUESTIONS.md`
n'est touché.

## Technique
| Élément | Changement |
|---|---|
| `CapabilityMap` | + `porte-du-runner`, + `journal-des-ruptures` (sans signal, avec témoins) |
| `ProductCapability` | fabrique « sans signal » et documentation de ce qu'une liste vide signifie |
| `CapabilityVerdict` | + `BRANCHEE` |
| `CapabilityFinding` | `isFinding()` faux pour `BRANCHEE` |
| `ProductDiagnosisService` | le cas « non mesurable » ne passe plus par le comptage de tables |
| `WiringInspector` | témoins vérifiés + non mesurable → `BRANCHEE` |
| `CapabilityMapTest` | la garde « au moins un signal » devient « au moins un signal **ou** un témoin » |
| `admin-diagnostic` (front) | le libellé du verdict, le type, et la phrase du « rien à signaler » |

**Aucune migration. Aucun endpoint. Aucun nouvel accès aux données. Aucun jeton consommé.**

## Préoccupations transversales
Aucune des quatre n'est cochée : ni auth/Principal, ni contexte tenant (aucun nouvel accès aux
données — la carte est une constante de code), ni plans/limites, ni navigation (aucune route
ajoutée ; l'écran `/admin/diagnostic` existe et ne change que de libellés).

## Hors périmètre
Le **ping conditionnel** (**SF-161-04**, gardé en réserve) · corriger la cause des déconnexions —
le cadrage §6 le refuse · rendre la porte mesurable en persistant ses refus (ce serait une table
de plus pour compter ce qu'on a justement décidé de ne pas payer) · ajouter `runner_disconnects`
au résolveur de signaux de table, pour la raison dite plus haut.
