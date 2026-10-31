# Le coût affiché et la facture Anthropic (F-224)

## 1. Activer le rapprochement (clé Admin)

Le rapprochement « facturé par Anthropic / calculé par la gateway » (écran d'administration du coût) lit la facture
par l'API d'administration. Il reste **inactif** tant que la clé n'est pas posée.

1. Console Anthropic → **Settings → Admin keys** → *Create admin key* (rôle administrateur de l'organisation
   requis). La clé commence par `sk-ant-admin…`. Elle ne sert qu'à **lire** la facture et l'usage.
2. La poser dans le secret du backend (jamais dans le code, jamais dans un fichier versionné) :
   ```bash
   kubectl -n claude-gateway-staging patch secret backend-secrets \
     -p '{"stringData":{"APP_ANTHROPIC_ADMIN_KEY":"sk-ant-admin-…"}}'
   kubectl -n claude-gateway-staging rollout restart deploy/backend   # en dehors d'un tour long (F-84)
   ```
3. La tâche quotidienne (06:20 UTC, `APP_ANTHROPIC_ADMIN_CRON`) relit les 31 derniers jours ; le bouton
   **[Relire maintenant]** de l'écran fait de même à la demande.

La clé n'est jamais journalisée ni renvoyée par l'API de la gateway.

### 1 bis. Désigner la clé de la passerelle (SF-224-06)

« Facturé » couvre **toute l'organisation** ; « calculé » couvre **la passerelle seule**. Pour comparer à périmètre
égal, l'écran a besoin de l'**identifiant** de la clé API de la passerelle (`apikey_…`, pas la clé elle-même) :

```bash
kubectl -n claude-gateway-staging patch secret backend-secrets \
  -p '{"stringData":{"APP_ANTHROPIC_GATEWAY_KEY_ID":"apikey_…"}}'
```

L'identifiant se lit dans la liste par clé de l'écran (le nom et l'indice de la clé l'accompagnent). Posé, l'écran
ajoute la colonne « Clé de la passerelle » et l'écart passerelle (clé − calculé).

## 2. Ce que l'écran compare

| Colonne | Source |
|---|---|
| Facturé | `GET /v1/organizations/cost_report` (montant du jour, toutes clés de l'organisation) |
| Calculé | somme des `usage_turns.provider_cost_usd` du jour (tours, tours coupés, appels annexes) |
| Écart | facturé − calculé |
| Clé de la passerelle | la ligne « par clé » de `APP_ANTHROPIC_GATEWAY_KEY_ID` (même grille) ; écart passerelle = clé − calculé |
| Par clé | `GET /v1/organizations/usage_report/messages` groupé par clé, **chiffré à la grille** de la gateway (estimation : l'écriture de cache est comptée au tarif 1 h) |

**Aujourd'hui** (UTC) est partiel : la journée n'est pas finie et la facture arrive avec retard. Il est affiché à part,
sans écart, et n'entre pas dans les totaux (jours complets seulement). Tous les montants sont aussi rendus en euros
au taux unique `APP_COST_USD_TO_EUR`.

Un écart durable vient presque toujours d'une **autre clé de la même organisation** : la clé legalCase, les sessions
de développement (Claude Max au-delà du forfait est facturé à part, hors de cette organisation), ou un script.
La ventilation par clé le montre.

## 3. Séparer une clé par client (décision PO, pas de code)

Pour que la facture se lise par client sans calcul :

1. Console Anthropic → **Workspaces** → un espace par client (« CAGIP », « EDENRED »…). Une clé API créée dans un
   espace ne facture que cet espace ; le `cost_report` peut alors être groupé par `workspace_id`.
2. Aujourd'hui la gateway n'utilise **qu'une** clé plateforme pour tous les postes. Passer à une clé par client
   demande un choix de clé par poste dans la gateway (non fait) : à décider avant tout développement.
3. Sans aller jusque-là, une clé **dédiée à la gateway** (distincte de legalCase) suffit à rendre l'écart lisible.
