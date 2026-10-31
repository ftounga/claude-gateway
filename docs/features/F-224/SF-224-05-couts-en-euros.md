# SF-224-05 — Les coûts en euros

**Feature :** F-224 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-224-05-couts-en-euros` · **Dépend de :** SF-224-02, 03, 04 (mergées)

**Demande PO du 2026-10-31 :** « Je veux que les coûts soient en euro et non dollar. » Revient sur SF-224-04, qui affichait les dollars d'abord.

## Objectif
Le PO lit tous les coûts de l'administration (et le coût d'une course autonome) en euros, convertis au taux unique de la passerelle (`app.cost.usd-to-eur`, `APP_COST_USD_TO_EUR`, défaut 0,92).

## Choix : conversion côté serveur
La passerelle convertit et rend des champs `…Eur` **à côté** des champs `…Usd` (gardés pour compatibilité) ; l'écran n'en convertit aucun.
- Une seule source du taux **et** de l'arrondi (centime, `HALF_UP`) : c'est déjà la règle du résumé (`spentEur`) et des alertes ; recalculer dans le navigateur ferait une seconde définition.
- Écart du rapprochement en euros = facturé € − calculé € (les deux déjà arrondis) : la ligne s'additionne à l'œil.
- Le taux appliqué est rendu (`usdToEur`) pour la mention « taux 1 $ = 0,92 € ».

## Comportement
1. **Coût réel** (`admin-cost`) : total de la période, chaque client, budgets, alertes — en euros (`spentEur`, déjà rendu). Le dollar disparaît des montants ; sous le total, une mention discrète « taux 1 $ = 0,92 € » (si le taux est rendu).
2. **Facture Anthropic** (`admin-cost-reconciliation`) : facturé, calculé (totaux 30 j et par jour), écart, détail par clé — en euros. Une seule mention : « Montants convertis au taux 1 $ = 0,92 € ; la facture Anthropic est en dollars. » Écart mis en évidence dès **1 €**.
3. **Mesure des essais Vigie** (Administration → Codes d'accès) : coût d'un essai et de chaque synchro en euros (`costEur`).
4. **Course autonome** (tableau du client, `client-board`) : coût de la course en euros (`costEur`) ; **estimation** au lancement (tableau des chantiers du terminal) : « ≈ 1,23 € » (`eur`).

### Backend (champs ajoutés, rien de retiré)
| DTO | Ajouts |
|---|---|
| `ProviderCostReconciliationService.Reconciliation` | `usdToEur`, `billedTotalEur`, `calculatedTotalEur` |
| `…Day` | `billedEur` (null si non lu), `calculatedEur`, `gapEur` (null si non lu) |
| `…Key` | `amountEur` |
| `VigieTrialMeasureService.TrialMeasure` / `SyncMeasure` | `costEur` (2 décimales ; 4 pour une synchro, comme l'affichage d'avant) |
| `AutonomyService.RunView` | `costEur` |
| `AutonomyService.Estimate` | `eur` |

Le taux est lu de `ProviderPricingProperties.usdToEur()` (déjà garanti > 0, défaut 0,92). `AutonomyService` le reçoit par mutateur `@Autowired(required = false)` (aucun constructeur changé ; repli 0,92).

## Erreurs
| Situation | Comportement |
|---|---|
| Passerelle antérieure (champ `…Eur` absent) | l'écran affiche « — », jamais un dollar présenté comme euro |
| Taux absent du résumé | pas de mention de taux, montants en euros quand même |
| Facture du jour non lue | facturé et écart « — » (inchangé) |
| 403 / lecture en échec | messages inchangés |

## Contraintes de validation
Aucune saisie nouvelle. Taux : `> 0`, sinon 0,92 (règle existante de `ProviderPricingProperties`).

## Critères d'acceptation
- [x] Coût réel : total et chaque client en € ; aucun « $ » dans un montant ; mention « taux 1 $ = 0,92 € ».
- [x] Facture Anthropic : facturé, calculé, écart, clés en € ; mention unique du taux et de la devise de la facture ; écart ≥ 1 € mis en évidence, 0,50 € non.
- [x] API rapprochement : `usdToEur` = 0.92, `billedEur` 73.60 pour 80 $, `calculatedEur` 46.23 pour 50,25 $, `gapEur` 27.37, `amountEur` d'une clé 7.36 pour 8 $ ; champs `…Usd` inchangés.
- [x] Essais Vigie, course autonome, estimation : en €.

## Plan de test
- Backend : `ProviderCostReconciliationApiIntegrationTest` (champs €), `AutonomyServiceTest` (estimation en €), test de `VigieTrialMeasureService` si existant ; suite complète.
- Front : `admin-cost.component.spec` (euros, mention du taux, plus de « $ ») ; spec de la carte (€, seuil 1 €, mention) ; specs `client-board`, `terminal-chantiers-board`, `access-codes` si elles portent le coût ; suite complète (7 échecs préexistants de `atelier-terminal.component.spec` inchangés) ; build.
- Isolation : routes administrateur inchangées (403 testé) ; autonomie : vues déjà filtrées par `userId` (aucune requête ajoutée).

## Impact
- Backend : `ProviderCostReconciliationService`, `VigieTrialMeasureService`, `AutonomyService` (+ leurs tests). Aucune table, aucune migration, aucun endpoint nouveau (contrats enrichis).
- Front : `admin/cost/admin-cost.(component|models)`, `admin-cost-reconciliation.component`, `admin/access-codes`, `access-code-admin.models`, `postes/client-board`, `atelier/terminal/terminal-chantiers-board`, `core/models/autonomy.models`.
- Design system : encres existantes (`--cg-text-secondary` pour la mention, `--cg-gold-ink` pour l'écart), chiffres tabulaires.
- Préoccupations transversales : aucune (ni auth, ni tenant, ni limites, ni routes).

## Inventaire des autres coûts en dollars (vérifié par grep, laissés hors périmètre)
| Endroit | Pourquoi pas ici |
|---|---|
| **Routines** (`atelier-slash-routines`, `atelier-slash-cost-routines`, `terminal-routines-band`, `routine.models.formatMoney`) | les budgets sont **saisis** en dollars et appliqués côté serveur en dollars (0,10–50 $ par exécution, 1–2 000 $ par mois) : passer en euros change la devise de saisie et de plafond, donc une décision PO |
| **Plafond d'exécution** (`AtelierSpendCap`, message « … $ pour cette exécution ») | même plafond que les routines |
| **Vidéos** (`shared/videos/video-cost`, galerie ; devis `VideoQuoteService`, seuil de confirmation `APP_VIDEO_COST_RETOUCHE_CONFIRM_THRESHOLD_USD`) | le devis en dollars est rédigé par la passerelle et repris par l'agent dans sa réponse ; convertir l'écran seul afficherait deux devises pour la même vidéo |
| **Mesure des outils du poste** (`PosteToolsMeasureService`, note « $ avant → $ après ») | note de diagnostic technique, pas un écran de coût |

Suite proposée : **SF-224-06** — routines, plafond d'exécution et vidéos en euros (saisie en €, conversion au même taux, seuils convertis), après arbitrage PO sur la devise des plafonds.

## Hors périmètre
L'inventaire ci-dessus ; la facturation Stripe (déjà en euros) ; la consommation en jetons.

## Résultats
Backend : suite complète 6 617 tests, 0 échec, 0 erreur (1 ignoré). Front : 3 652 / 3 652 verts après rebase sur origin/main (3 643 / 3 643 avant) ; build OK (avertissements de budget SCSS préexistants).
