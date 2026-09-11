# Cadrage — F-66 · L'essai tient sa promesse

## Date

2026-09-11

## Le problème, tel qu'il se voit

La page d'accueil annonce **« 14 jours d'essai — aucune carte requise »**
(`frontend/src/app/landing/landing.component.html` et `.ts`) ; `app.billing.trial-days` vaut **5**.
La promesse publique est tenue à 36 %. Une troisième voix dit autre chose encore : la page de
facturation affiche « essai **5 jours** » en dur (`billing.component.html`).

## Le raisonnement qui tranche le sens de la correction

Il est contre-intuitif, et c'est pour cela qu'il est écrit ici plutôt que sous-entendu.

**Le risque financier d'un essai n'est pas porté par sa durée, il est porté par son quota.**
`app.quota.trial-tokens` vaut **200 000** jetons, et depuis F-63 le décompte est **pondéré au coût
réel** : chaque nature de jeton entre dans le compteur au prix de sa nature. Un jeton de quota vaut
`9,00 $/M` (`app.atelier.agent.cost.quota-token-cost-per-million-tokens`), donc un essai coûte
**au plus ≈ 1,80 $** de fournisseur, **quel que soit le style d'usage** du client — c'est
précisément ce que F-63 a rendu vrai.

Quatorze jours ne consomment donc **pas plus** que cinq : ils laissent seulement **plus de temps**
pour dépenser le **même plafond**. On aligne donc le **code sur la promesse** (14 jours), et non
l'inverse : c'est le meilleur argument commercial disponible **au même coût**.

## Ce que la vérification a trouvé — et qui change l'ordre des travaux

La phrase ci-dessus n'est vraie que si le plafond en est réellement un. Vérification faite, il ne
l'est pas tout à fait.

| Ce qui borne | Ce que ça borne **réellement** aujourd'hui |
|---|---|
| `app.billing.trial-days` (5) | La **date de fin** de l'essai : `trial_ends_at = création + 5 j`. Passée cette date, `EntitlementService.resolveMonthlyTokenQuota` rend **0** et les quatre chemins servis (`/chat`, `/ask`, Atelier, agents) sont bloqués par le pré-vol. Cette borne-là fonctionne. |
| `app.quota.trial-tokens` (200 000) | **Non pas l'essai, mais le mois calendaire.** Le compteur opposé (`usage_counters.billed_tokens`) a pour clé `(user_id, premier jour du mois UTC)` : au 1er du mois, une **nouvelle ligne** part de zéro, et l'essai **retrouve 200 000 jetons**. |

**Conséquence chiffrée** : un essai à cheval sur une fin de mois vaut **400 000 jetons ≈ 3,60 $**,
soit le double du plafond annoncé. Et la part des essais concernés dépend de la durée : à 5 jours,
les inscriptions du 27 au 31 sont à cheval (≈ 4 jours sur 30, **13 %**) ; à **14 jours**, toutes
celles du 18 au 31 le sont (≈ 14 jours sur 30, **47 %**).

Autrement dit : **allonger la durée sans refermer cette fuite multiplierait par ~3,5 le nombre
d'essais qui coûtent le double.** C'est pourquoi la fuite se referme **avant** que la durée ne
bouge, et non l'inverse.

## Ce que la feature fait

1. **SF-66-01** — L'essai devient une **enveloppe unique** : le plafond d'essai s'oppose à la
   consommation cumulée **depuis le début de l'essai**, pas au mois calendaire. Un essai vaut
   200 000 jetons, une seule fois, qu'il traverse ou non un 1er du mois. *(La fuite, d'abord.)*
2. **SF-66-02** — La durée passe de **5 à 14 jours**, défaut de code compris, et l'API expose la
   durée servie (`trialDays`) pour que l'écran cesse de la réciter de mémoire.
3. **SF-66-03** — L'API expose aussi l'**allocation** servie (`trialTokens`) : la carte « Gratuit »
   annonçait « 200 000 tokens » en dur, c'est-à-dire le même défaut, à un chiffre près.
4. **SF-66-04** — Les écrans annoncent l'essai **que le serveur sert** : la page de facturation lit
   les deux valeurs, et la page d'accueil (publique, donc sans API) tient ses deux mentions depuis
   **une seule** constante documentée.

## Ce que la feature ne fait pas

- **Elle ne touche pas `app.quota.trial-tokens` (200 000).** Le dimensionnement de l'essai est une
  décision commerciale. Elle est **signalée, pas prise** : 200 000 jetons représentent **quatre à
  dix tours de Forge** (20 000 à 50 000 jetons par tour) ; 500 000 coûteraient ≈ 4,50 $ par essai.
  → **À CONFIRMER PAR LE PO**, suivi en `OQ-16` point 9.
- Elle n'exige pas de carte bancaire à l'inscription, et ne rend pas l'essai payant (hors périmètre
  déclaré par `PRODUCT_SPEC.md`).
- Elle ne touche **rien chez Stripe** : ni price, ni produit, ni webhook.
- Elle ne modifie aucun schéma de base : **aucune migration Liquibase** n'est nécessaire — la
  fenêtre d'enveloppe se calcule à partir des lignes `usage_counters` existantes.

## Découpage

| SF | Titre | Nature | Statut |
|---|---|---|---|
| SF-66-01 | L'essai est une enveloppe, pas un abonnement mensuel gratuit | Backend | `done` — PR #370 |
| SF-66-02 | L'essai dure les quatorze jours annoncés | Backend | `done` — PR #371 |
| SF-66-03 | L'offre d'essai se décrit elle-même (`trialTokens`) | Backend | `done` — PR #372 |
| SF-66-04 | L'écran annonce l'essai qu'on sert | Frontend | `done` — PR #375 |

Ordre imposé : **la fuite (01) avant la durée (02)**, puis ce que l'API dit de l'offre (03), puis les
écrans (04). SF-66-03 s'est ajoutée en cours de route : la carte « Gratuit » annonçait aussi son
allocation en dur, c'est-à-dire le même défaut que celui que la feature répare.
