# Les besoins en attente (F-205)

Le **registre des besoins** range les bugs et évolutions **de l'application** signalés depuis un terminal :
l'agent pose une carte « Enregistrer ce besoin ? » (outil `signaler_besoin`, réservé à l'administrateur,
hors terminal Teams), le PO clique [Enregistrer], et le besoin passe `EN_ATTENTE` avec ses pièces copiées
dans le stockage de la gateway (`besoins/{id}/{n}-{nom}`).

Ce document dit comment **Claude Code** récupère ces besoins, les cadre et les marque. Demande type :

> « Récupère les besoins en attente, cadre-les et dis-moi lesquels on peut livrer. »

## Cycle de vie

| Statut | Qui le pose | Sens |
|---|---|---|
| `PROPOSE` | l'outil | carte posée, pas encore décidée (n'apparaît pas dans la liste par défaut) |
| `EN_ATTENTE` | clic [Enregistrer] | à cadrer |
| `CADRE` | Claude Code / PO | cadré : `featureRef` = la feature créée (`F-123`) |
| `REJETE` | clic [Non] ou PO | écarté |
| `LIVRE` | Claude Code / PO | la feature qui le porte est en prod |
| `JOINT` | clic [Ajouter au besoin] | **complément** d'un besoin (SF-205-04) : il se lit dans `complements[]` de son besoin, jamais à part |

## 1. Par l'API (voie normale)

Les routes sont sous la garde administrateur et ne rendent **que les besoins du compte appelant**.
Le jeton est celui de la session du PO : dans le navigateur connecté à la Forge, outils de développement →
Stockage local → clé `cg_token`. **Ne jamais le committer ni le coller dans un fichier du dépôt** ; le
passer par variable d'environnement le temps de la session.

```bash
export CG=https://portal.ng-itconsulting.com/api   # ou http://localhost:8080 en local
export CG_TOKEN=…                                        # cg_token du navigateur, jamais écrit sur disque

# Les besoins en attente (JSON, plus récents d'abord, pièces décrites)
curl -s -H "Authorization: Bearer $CG_TOKEN" "$CG/admin/app-requests?status=EN_ATTENTE" | jq .

# Un besoin
curl -s -H "Authorization: Bearer $CG_TOKEN" "$CG/admin/app-requests/<id>" | jq .

# Une pièce (n = position, à partir de 1) — à ouvrir pour voir la capture / la vidéo
curl -s -H "Authorization: Bearer $CG_TOKEN" -o /tmp/besoin-<id>-1.png "$CG/admin/app-requests/<id>/attachments/1"

# Marquer cadré, avec la feature créée
curl -s -X PATCH -H "Authorization: Bearer $CG_TOKEN" -H 'Content-Type: application/json' \
     -d '{"status":"CADRE","featureRef":"F-210"}' "$CG/admin/app-requests/<id>" | jq .status

# Marquer livré (le featureRef est conservé)
curl -s -X PATCH -H "Authorization: Bearer $CG_TOKEN" -H 'Content-Type: application/json' \
     -d '{"status":"LIVRE"}' "$CG/admin/app-requests/<id>"
```

Ce que rend un besoin : `kind` (`BUG`|`EVOLUTION`), `title`, `description` (**les mots du PO, tels
quels** — c'est la source), `reformulation` (l'interprétation de l'agent, à vérifier), `observedContext`,
`status`, `featureRef`, dates, `attachments[]` (`position`, `name`, `contentType`, `size`, `copyStatus`
`PENDING|COPIED|FAILED`, `copyError`).

**Compléments (SF-205-04)** : un besoin rend aussi `complements[]` — ce que le PO a ajouté ensuite, du plus
ancien au plus récent (`id`, `description`, `reformulation` à jour, `observedContext`, `attachments[]`). Lire
**les mots d'origine puis chaque complément** : la dernière reformulation est la compréhension d'ensemble la
plus récente. Les pièces d'un complément se lisent par **son** id : `…/app-requests/<idComplément>/attachments/<n>`.
Un complément ajouté à un besoin `CADRE` ou `LIVRE` le remet `EN_ATTENTE` : à relire, sa `featureRef` est conservée.

Erreurs : `400 app_request_invalid` (statut inconnu, `featureRef` hors `F-123`), `404 app_request_not_found`,
`409 app_request_conflict` (besoin pas encore enregistré), `403` (non-administrateur).

## 2. Par la base, en lecture seule (`q-prod-ro.sh`)

Utile pour un état d'ensemble ; les **écritures** passent toujours par l'API (garde + journal).

```sql
-- Les besoins en attente et leurs pièces
SELECT r.id, r.kind, r.title, r.created_at, r.feature_ref,
       count(a.id) AS pieces,
       count(a.id) FILTER (WHERE a.copy_status = 'FAILED') AS pieces_en_echec
FROM app_requests r LEFT JOIN app_request_attachments a ON a.request_id = r.id
WHERE r.status = 'EN_ATTENTE'
GROUP BY r.id
ORDER BY r.created_at;

-- Le texte complet d'un besoin
SELECT kind, title, description, reformulation, observed_context
FROM app_requests WHERE id = '<id>';
```

## 3. Procédure de cadrage (Claude Code)

1. Lister `EN_ATTENTE` ; pour chacun, lire `description` d'abord (la parole du PO), puis les pièces.
2. **Vérifier l'existant avant de cadrer** : `grep` dans `docs/PRODUCT_SPEC.md` et le code — un besoin
   peut être déjà couvert (alors le dire au PO et proposer `REJETE` ou `LIVRE`).
3. Regrouper les besoins qui relèvent d'une même feature ; écrire le cadrage
   `docs/features/F-XXX/CADRAGE-…md` et la ligne `PRODUCT_SPEC.md` (numéro = max + 1, vérifier l'absence de
   doublon) — **après validation du PO**, comme toute nouvelle feature.
4. `PATCH` chaque besoin couvert : `CADRE` + `featureRef`. À la mise en prod de la feature : `LIVRE`.
5. Une pièce `FAILED` (poste hors ligne au moment du clic) : demander au PO de redéposer le fichier dans
   un terminal ; il n'y a pas de relance automatique de copie.
