# Mini-spec — F-157 / SF-157-03 — Le verdict « débranchée »

## Identifiant
`F-157 / SF-157-03` — feature parente `F-157` — dépend de **SF-157-01** et **SF-157-02**

## Objectif
Séparer deux situations que F-156 confond : **« branchée, jamais déclenchée »** (un réglage) et
**« débranchée par un remaniement »** (une ligne de code manquante).

## Pourquoi la distinction change le travail
Devant « dormante », on va chercher une donnée, une condition, un amorçage. Si la capacité est en
réalité **débranchée**, cette recherche ne trouve rien — et on conclut que le diagnostic se trompe.
Le mot juste oriente le geste juste.

## Comportement attendu
1. Le diagnostic de F-156 reste **inchangé sans lecture de code** : sans dépôt désigné, les verdicts
   sont ceux d'avant. **La lecture enrichit, elle ne remplace pas.**
2. Avec un dépôt lu, une capacité **dormante** qui déclare des témoins est réexaminée :
   - **tous les témoins présents** → reste **DORMANTE**, et le constat le **dit** : « branchement
     vérifié dans le code, la capacité ne se déclenche pas pour une autre raison » ;
   - **au moins un témoin absent** → **DÉBRANCHÉE**, avec **le fragment manquant**, **le fichier**,
     et **ce que ce témoin prouvait** ;
   - **fichier non lu** → verdict **inchangé** : un fichier absent ne prouve rien.
3. Une capacité **ACTIVE** n'est pas réexaminée : elle se déclenche, la question du branchement ne
   se pose pas.
4. Un constat **DÉBRANCHÉE** n'a **pas de gain chiffré** : c'est un défaut à corriger, pas une
   optimisation à évaluer. Annoncer un montant reviendrait à chiffrer une hypothèse.

| Cas d'erreur | Comportement |
|---|---|
| Aucun dépôt désigné | verdicts de F-156, sans mention de branchement |
| Dépôt non reconnu | le refus remonte tel quel (SF-157-02) ; aucun verdict n'est modifié |
| Capacité sans témoin | verdict inchangé, et le constat dit que le branchement n'est **pas vérifiable** |

## Critères d'acceptation
- [ ] Sans lecture, les verdicts de F-156 sont **identiques** — non-régression vérifiée.
- [ ] Témoins tous présents → **DORMANTE**, avec la mention « branchement vérifié ».
- [ ] Témoin absent → **DÉBRANCHÉE**, avec fragment, fichier et ce qu'il prouvait.
- [ ] Fichier non lu → verdict **inchangé** (un absent ne prouve rien).
- [ ] Capacité ACTIVE → **jamais** réexaminée.
- [ ] Un constat DÉBRANCHÉE ne porte **aucun gain chiffré**.
- [ ] La parité (SF-156-04) traite **DÉBRANCHÉE** comme un **manque réel** : il faut du code.

## Hors scope
La **lecture raisonnée** (**SF-157-04**) · l'**écran** (**SF-157-05**) · toute correction automatique.

## Technique
| Élément | Changement |
|---|---|
| `CapabilityVerdict` | une valeur `DEBRANCHEE` |
| `WiringInspector` | confronte les témoins aux fichiers lus |
| `ProductDiagnosisService` | une passe d'enrichissement, **facultative** |
| `ParityService` | `DEBRANCHEE` → état `ABSENTE` (il faut du code) |

**Aucune migration, aucune route, aucun appel fournisseur.**

## Plan de test
- [ ] Sans lecture → verdicts inchangés (non-régression).
- [ ] Tous témoins présents → DORMANTE + mention.
- [ ] Un témoin absent → DÉBRANCHÉE + fragment + fichier + ce qu'il prouvait.
- [ ] Fichier non lu → inchangé.
- [ ] ACTIVE non réexaminée.
- [ ] DÉBRANCHÉE sans gain.
- [ ] Parité : DÉBRANCHÉE compte comme **manque réel**.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun endpoint ici |
| **Contexte tenant** | **oui** | l'enrichissement ne lit **rien** : il reçoit les fichiers déjà lus par `SourceReader`, sous `requireOwned`. Aucun accès données nouveau. |
| Plans / limites | non | aucun appel fournisseur |
| Navigation / routing | non | aucune route |
