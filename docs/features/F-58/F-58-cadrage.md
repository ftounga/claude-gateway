# Cadrage — F-58 — « Atelier » devient « Forge »

## Date

2026-09-10

## Le problème

Le mot **Atelier** ne dit pas ce qu'on y fait. Il a déjà porté un malentendu — F-39 a montré que
« Terminal » désignait un **comportement** et non un moteur — et il ne parle ni à un développeur ni
à un client. Le PO tranche pour **Forge** : l'endroit où l'on fabrique. Court, concret, et — c'est
ce qui l'a emporté sur « Console » — **sans collision** avec le vocabulaire déjà employé, ni avec la
*console admin* de F-20.

## Ce qui change

Le **vocabulaire vu par l'utilisateur**, et rien d'autre :

1. **Libellés d'interface** — entrée de menu, titres d'écran, en-têtes, boutons, infobulles,
   messages d'erreur affichés, textes de dialogue, valeurs pré-remplies.
2. **Documentation produit lisible par un utilisateur** — le centre d'aide embarqué
   (`backend/src/main/resources/help/*.md`) et le `README` du runner, que l'utilisateur lit avant
   d'installer.
3. **Textes du guide d'accueil (F-53)** — vérification explicite demandée par le cadrage.
4. **Messages de console du runner** — le runner parle à l'utilisateur ; ses phrases sont des
   libellés visibles au même titre qu'un bouton.

## Ce qui ne change pas

| Conservé | Pourquoi |
|---|---|
| La route `/atelier` (et `/atelier/:id`, `/atelier/:id/fichiers`) | Un onglet ouvert ou un lien partagé ne doit pas se briser sur un changement de vocabulaire. |
| Classes, paquets, sélecteurs, fichiers (`AtelierChatService`, `app-atelier-terminal`, `atelier/`) | Un renommage interne massif pour un mot d'interface serait du risque sans bénéfice — et il rendrait illisible l'historique de sept features livrées cette semaine. |
| Tables et colonnes (`atelier_messages`, `atelier_option_status`), migrations Liquibase | Idem, plus une migration de renommage qui n'apporte rien. |
| Endpoints et codes d'erreur (`/api/billing/atelier-option`, `atelier_forbidden`, `atelier_option_included`), métadonnée Stripe `kind=atelier_option` | Contrat public déjà en production ; le renommer casserait le webhook et les abonnements en cours. |
| Réglages et variables d'environnement (`app.atelier.*`, `APP_ATELIER_*`, `STRIPE_PRICE_ATELIER_OPTION`) | Déployés ; renommer imposerait une reconfiguration de la prod pour un mot. |
| Les documents d'ingénierie de `docs/**` (mini-specs, ADR, cadrages) | Ce sont des **archives datées**, pas de la documentation produit. Les réécrire falsifierait le récit des features déjà livrées. |
| Les commentaires de code qui citent l'historique (« F-28 / Atelier ») | Même raison. Un commentaire n'est pas un libellé. |

## Découpage

Une seule subfeature — le changement est homogène et indivisible : livrer la moitié des libellés
laisserait deux noms coexister à l'écran, ce qui est pire que l'ancien nom seul.

- `SF-58-01` — Le mot vu par l'utilisateur

## Accord de langue

« Atelier » est masculin, « Forge » est féminin. La substitution n'est **jamais** mécanique :
`l'Atelier` → `la Forge`, `déjà inclus` → `déjà incluse`, `Atelier … inclus` → `Forge … incluse`,
`du droit d'Atelier` → `du droit de Forge`. Toute phrase touchée est relue entière.
