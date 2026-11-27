# Mini-spec — F-279 / SF-279-02 — Les deux jauges, à l'écran

## Identifiant
`F-279 / SF-279-02`

## Feature parente
`F-279` — Deux jauges : ce qui part chez Anthropic, ce qui part chez AWS Bedrock

## Statut
`in-progress`

## Date de création
2026-11-27

## Branche Git
`feat/SF-279-02-deux-jauges-ecran`

---

## Objectif
Le PO voit, là où il travaille (barre du terminal) et sur l'écran Coût réel, ce que le mois a coûté chez Anthropic
et chez AWS Bedrock, en euros, quel canal sert en ce moment, et ce que ces chiffres ont d'inexact.

---

## Comportement attendu

### Cas nominal
1. **Barre du terminal**, à côté du budget de la semaine (SF-133-15), en compact :
   `Anthropic 1 060,12 € · Bedrock 66,40 €`, une barre bicolore (part de chaque pot dans le total du mois) et un
   point devant le canal en service. Infobulle : « Ce mois (HT) — Anthropic : … (prépayé ; recalé jusqu'au 25/11 /
   estimé) · AWS Bedrock : … (facturé en fin de mois) · En service : Bedrock — crédit Anthropic épuisé ».
   Mention courte `crédit épuisé` à côté d'Anthropic quand `channel.creditExhausted`, `relais` quand `channel.relay`
   sans cause connue.
2. **Écran Coût réel** (`/admin` → Coût réel), carte **« Les deux pots — ce mois »** au-dessus de « Facture
   Anthropic » : une ligne par pot (montant, mode de paiement, part recalée / estimée, dernier jour recalé, écart
   facture − calcul, note d'état), le total, le canal en service, la liste des inexactitudes (`caveats`), le taux
   appliqué, un bouton « Relire maintenant » (`POST /api/admin/cost/providers/refresh`).
3. Une seule lecture partagée (`ProviderSpendService` front), relue au plus toutes les 5 minutes quand un écran la
   redemande (le canal change).

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Non-administrateur (403) | barre : rien ; carte : « réservée à l'administration » |
| API en échec | barre : rien, l'écran tient ; carte : message d'erreur |
| Mois sans dépense | `0,00 €` des deux côtés, barre vide |
| Relecture en échec | snackbar « La facture n'a pas pu être relue », données inchangées |
| Écran étroit (< 768 px) | barre du terminal : la jauge est masquée (la carte reste) |

---

## Critères d'acceptation
- [ ] Barre du terminal : deux montants en €, canal en service marqué, mention crédit épuisé/relais.
- [ ] Rien pour un non-administrateur ou sur erreur, sans casser le terminal.
- [ ] Carte Coût réel : par pot montant, paiement, recalé/estimé, note ; total ; inexactitudes ; Relire maintenant.
- [ ] Aucun calcul de montant dans le navigateur (seule la part de la barre bicolore est une proportion d'affichage).
- [ ] Couleurs/polices/espacements du `DESIGN_SYSTEM.md` (jetons `--cg-*`).
- [ ] Une seule lecture partagée entre la barre et la carte.

---

## Périmètre

### Hors scope (explicite)
- Tout calcul serveur (SF-279-01). Solde prépayé Anthropic. Voix/audio. Jarvis. Forge (la barre du terminal suffit,
  le PO y passe 99 % de son temps).

---

## Contraintes de validation
Aucune saisie.

---

## Impacts techniques

| Composant | Changement |
|---|---|
| `admin/cost/admin-cost.models.ts` | types `ProviderSpend`, `ProviderGauge`, `ProviderChannel` |
| `admin/cost/admin-cost.service.ts` | `providers()`, `refreshProviders()` |
| `core/services/provider-spend.service.ts` *(nouveau)* | lecture partagée, 5 min |
| `shared/provider-gauges/provider-gauges.component.ts` *(nouveau)* | la jauge compacte |
| `admin/cost/admin-cost-providers.component.ts` *(nouveau)* | la carte « Les deux pots » |
| `atelier/terminal/atelier-terminal.component.*` | insère la jauge dans la barre |
| `admin/cost/admin-cost.component.*` | insère la carte |

Aucune route nouvelle côté front ; endpoints de SF-279-01.

---

## Plan de test
- `provider-gauges.component.spec.ts` : montants, point du canal, crédit épuisé / relais, rien sur 403 / erreur.
- `admin-cost-providers.component.spec.ts` : lignes, notes, inexactitudes, refresh (succès, échec), 403.
- `provider-spend.service.spec.ts` : une seule lecture tant qu'elle est fraîche.
- Terminal : les specs existantes du terminal passent (doublure du service).

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — (403 traité en silence) |
| Contexte tenant | non | agrégat plateforme admin |
| Plans / limites | non | lecture seule, rien ne bride |
| Navigation / routing | **oui** (écrans) | terminal (barre) et écran Coût réel reçoivent un composant ; aucune route, aucun guard modifié |
