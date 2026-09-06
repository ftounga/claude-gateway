# F-42 — Alerte de quota & recharge en un clic — Découpage

## Problème

`EntitlementService.resolveMonthlyTokenQuota` rend `0` dès que l'abonnement n'est plus actif, et
`QuotaService.assertWithinQuota` refuse l'appel avec un `402`. Le blocage est **sec** : rien n'a
prévenu. Or la plateforme a déjà tout ce qu'il faut pour prévenir — le compteur d'usage par période
(`usage_counters`, F-10), le rapport d'usage (F-16) et **deux packs de rachat** au catalogue
(`TopUpCatalog` : `DAY` 200 k, `STANDARD` 1 M). Il ne manque que le fait de **regarder le compteur
au bon moment** et de **mettre la recharge à portée de clic**.

## Cible

Prévenir au franchissement d'un seuil de consommation — défaut `APP_QUOTA_ALERT_THRESHOLD=0.8` —
**une seule fois par période et par utilisateur**, et offrir la recharge en un clic depuis l'alerte.

## Le point technique central : « une seule fois »

Un utilisateur qui reçoit trente fois la même alerte pendant un tour d'agent ne la lira plus jamais.
La marque du « déjà émis » doit donc être **persistée**, et persistée **là où la période vit déjà**.

Trois emplacements étaient possibles :

| Option | Verdict |
|--------|---------|
| Une table `quota_alerts` dédiée | Rejetée — une table entière pour deux horodatages, alors que la ligne « un utilisateur, une période » existe déjà. Contredit `PROJECT.md` §14.2 (Simplicity First). |
| Un cache mémoire / `ConcurrentHashMap` | Rejetée — l'application est **stateless** et tourne sur 2 replicas (`PROJECT.md` §13.9). Une marque en mémoire se perd au redéploiement et diverge entre pods : l'alerte serait ré-émise à chaque roulement. |
| **Deux colonnes sur `usage_counters`** | **Retenue** — la ligne `(user_id, period_start)` est déjà l'unité exacte « un utilisateur, une période », déjà unique en base, déjà chargée et sauvegardée par `recordUsage`. La marque coûte **zéro lecture supplémentaire**, et la nouvelle période crée une nouvelle ligne, donc ré-arme l'alerte toute seule. |

Deux colonnes, pas une, parce que deux faits distincts doivent être retenus :

- `quota_alert_raised_at` — **le seuil a été franchi**. Posé une fois, jamais deux : c'est lui qui
  garantit l'unicité de l'émission.
- `quota_alert_dismissed_at` — **l'utilisateur a lu et écarté l'alerte**. Sans lui, l'alerte
  resterait affichée jusqu'à la fin du mois sans moyen de la fermer, ce qui la rendrait aussi
  invisible qu'une alerte répétée trente fois.

## Subfeatures

| # | Titre | Contenu | Effort |
|---|-------|---------|--------|
| **SF-42-01** | Le seuil franchi, marqué une seule fois | Configuration du seuil et du pack recommandé (env), migration des deux colonnes, `QuotaAlertService` évalué après chaque `recordUsage` sans jamais faire échouer l'appel, `GET /usage/alert` et `POST /usage/alert/dismiss`. | ~1 j |
| **SF-42-02** | La bannière d'alerte et la recharge en un clic | `QuotaAlertService` frontend, `QuotaAlertBannerComponent` dans la coquille applicative, bouton « Recharger » qui part directement au paiement du pack recommandé, bouton « Ignorer » qui écarte l'alerte pour la période. | ~1 j |

## Hors périmètre de la feature

- **Le dépassement facturé** (overage monétisé) — `OQ-08` reste **ouverte** et n'est pas tranchée ici.
- **L'envoi d'e-mails** : l'alerte vit dans l'application, elle ne sort pas par le canal e-mail.
- Toute modification des quotas eux-mêmes, du catalogue de packs, ou du comportement de blocage à
  la limite (`QuotaExceededException` reste identique).
