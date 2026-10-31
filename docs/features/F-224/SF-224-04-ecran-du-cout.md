# SF-224-04 — L'écran du coût : en dollars, 7 derniers jours, la facture à côté

**Feature :** F-224 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-224-04-ecran-du-cout` · **Dépend de :** SF-224-02, SF-224-03 (mergées)

## Objectif
Le PO lit sur l'écran d'administration du coût ce qu'il paie : en dollars (la devise de sa carte), sur 7 jours glissants, à côté de ce qu'Anthropic facture.

## Comportement
1. **Sélecteur de période** : Cette semaine · **7 derniers jours** · Ce mois.
2. **Total** : en dollars d'abord (« 1 234,50 $ »), puis l'euro et le taux en second (« 1135,74 € · 1 $ = 0,92 € »). Chaque client : dollars puis euros. Repli sur l'euro seul face à une passerelle antérieure. Budgets inchangés (en euros).
3. **Carte « Facture Anthropic »** (`app-admin-cost-reconciliation`) :
   - sans clé Admin : « Rapprochement inactif » + comment la créer (texte de la passerelle) ; pas de bouton ;
   - avec : totaux 30 jours (facturé, calculé), date de la dernière lecture, tableau Jour · Facturé · Calculé · Écart (écart ≥ 1 $ mis en évidence), clic sur un jour → consommation par clé (« ≈ 8,00 $ (estimé à la grille) ») ; [Relire maintenant].

## Erreurs
| Situation | Comportement |
|---|---|
| 403 | « Cette section est réservée à l'administration. » |
| Lecture en échec | « Le rapprochement n'a pas pu être lu. » |
| Relecture en échec chez le fournisseur | snackbar, données précédentes gardées |

## Critères d'acceptation
- [x] « 7 derniers jours » appelle `period=7d` et s'intitule ainsi.
- [x] Dollars d'abord, euro et taux en second ; dollars par client.
- [x] Sans clé : indication, pas de bouton ; avec : facturé, calculé, écart, clés dépliables, relecture.

## Plan de test
`admin-cost.component.spec` : 2 nouveaux tests (dollars et 7 jours ; facture inactive puis active) ; suite front complète, build. Isolation : routes administrateur (garde serveur).

## Impact
`admin-cost.(component|models|service)`, nouveau `admin-cost-reconciliation.component`. Design system : encres de la charte, `--cg-gold-ink` pour l'écart, chiffres tabulaires, table repliable en `button` accessible (`aria-expanded`), 12 px sur téléphone. Préoccupations transversales : aucune.

## Hors périmètre
Le coût de l'usage Claude Max des sessions de développement (hors application, dit au PO) ; une clé par client (décision PO, `docs/ops/cout-facture-anthropic.md`).
