# Mini-spec — F-142 / SF-142-05 — La bibliothèque des diagrammes vient de la gateway

## Identifiant
`F-142 / SF-142-05` — feature parente `F-142`

## Objectif
Qu'un diagramme s'affiche **chez le client**, sur un poste derrière un proxy d'entreprise, sans aller
chercher quoi que ce soit sur un CDN public.

## Le défaut — constaté en production, deux causes
> PO, 2026-09-23, terminal AGENOR chez CAGIP : *« c'est la 2ᵉ fois qu'il me dit dans le document généré
> que le diagramme est non rendu : la bibliothèque n'a pas pu être chargée »*.

Ce n'est **pas** le réseau du client. Vérifié à la main, hors de l'application :

| # | Cause | Preuve |
|---|---|---|
| **1** | La version épinglée **n'existe pas** sur cdnjs | `GET .../mermaid/11.4.1/mermaid.min.js` → **404** (11.15.0 → 200, 10.9.1 → 200) |
| **2** | Le build v11 **n'expose pas** `window.mermaid` | le fichier commence par `var __esbuild_esm_mermaid_nm;(__esbuild_esm_mermaid_nm\|\|={}).mermaid=…` — le test `if(!window.mermaid)` échoue donc **même avec une URL valide** |
| **3** | Un CDN public reste **injoignable** derrière le proxy d'un client | c'est le cas d'usage réel : poste banque, proxy NTLM (F-44/F-45) |

**Pourquoi les tests ne l'ont pas vu** : le critère vérifiait que le HTML servi **contient la balise**
`<script src=…>`. C'est vrai — et ça ne dit rien de l'existence du fichier ni du global qu'il expose.
Un test peut passer sur une URL morte.

**Conséquence en chaîne** : le repli « icônes cloud officielles indisponibles → je bascule sur Mermaid »
de **SF-142-03** tombait lui aussi, puisqu'il vise `architecture-beta`, qui n'existe **que** en Mermaid v11.

## Comportement attendu
1. La page sert la bibliothèque depuis **la gateway** (`'self'`, déjà autorisé par la CSP) : plus aucun
   appel vers un CDN public au moment d'afficher un diagramme.
2. La version servie est **v11** — `architecture-beta` (diagrammes d'architecture cloud, repli de
   SF-142-03) en dépend.
3. Le runtime reconnaît le global **quel que soit son nom** (`window.mermaid` ou le namespace esbuild).
4. Le repli gracieux existant est **conservé** : un diagramme invalide montre son code, sans casser la page.
5. Une page **sans** diagramme reste rendue **octet pour octet identique** (non-régression de SF-142-01).

| Cas d'erreur | Comportement |
|---|---|
| Ressource absente de l'image (build mal fait) | **le build échoue** : un test vérifie la présence ET la forme du fichier — jamais une page qui promet un diagramme sans pouvoir le rendre |
| Diagramme invalide | repli inchangé : le code + « Diagramme invalide » |
| Vieille page déjà publiée | rendue au moment de servir : elle profite du correctif **sans être republiée** |

## Critères d'acceptation
- [x] Le HTML servi charge la bibliothèque depuis **une route de la gateway**, pas depuis un CDN.
- [x] La route sert le fichier **sans authentification** (une page publique est lue par n'importe qui)
      et avec un cache long (la version est dans l'URL).
- [x] Le runtime trouve l'objet mermaid **sous les deux noms** possibles.
- [x] Un test vérifie que la ressource **existe dans le jar** et expose bien l'objet attendu.
- [x] Une page sans diagramme reste **byte-identique**.
- [x] Le repli gracieux est conservé.

## Hors scope
Le rendu **serveur** du SVG (écarté en SF-142-01, et inutile ici) · la mise à jour de `diagrams`/graphviz
sur les postes (SF-142-03, inchangée) · les slides (SF-142-02 : le PNG y est déjà embarqué, aucun CDN).

## Technique
| Élément | Changement |
|---|---|
| `backend/src/main/resources/pages/mermaid-11.15.0.min.js` | la bibliothèque, embarquée (≈ 3,3 Mo) |
| `PageLibraryController` *(nouveau)* | `GET /pages/lib/{file}` — liste **close** d'un seul fichier, cache immuable |
| `SecurityConfig` | la route est publique, comme `/p/**` |
| `PageMermaidRuntime` | `SCRIPT_URL` devient la route locale ; détection du global élargie |

Aucune table, aucune migration.

**Pourquoi embarquer plutôt que proxifier** : un proxy sortant vers cdnjs replacerait la dépendance
réseau côté gateway — invisible pour le client, mais présente. Le fichier est figé, versionné, et servi
comme n'importe quelle ressource de l'application.

## Plan de test
### Gateway — `PageLibraryControllerTest` (3) + `PageMermaidRuntimeTest` (8) + `PagePublicRouteIntegrationTest` (9)
- [x] **Le test qui manquait** : la ressource existe dans le jar, pèse plus d'un mégaoctet (une page de
      404 en ferait quelques centaines d'octets) et contient le marqueur du bundle attendu.
- [x] Le HTML servi ne contient **plus aucune** occurrence de `cdnjs` ni de `jsdelivr` ; il pointe la route locale.
- [x] La route sert le fichier en `javascript`, cache `immutable` ; **liste close** : tout autre nom → 404,
      y compris une tentative de remontée de chemin.
- [x] La route est **publique** (test d'intégration sans jeton).
- [x] Une page sans diagramme reste byte-identique (CA4 d'origine, conservé).

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | Une route publique de plus (`/pages/lib/**`), déclarée **une par une** dans `SecurityConfig` à côté de `/p/**` : elle ne sert qu'un fichier statique d'une liste close, aucun paramètre utilisateur, aucune donnée de compte. |
| Contexte tenant | non | ressource statique, identique pour tous ; aucune donnée client |
| Plans / limites | non | aucun appel fournisseur, aucun jeton |
| **Navigation / routing** | **oui** | La CSP des pages est **inchangée** (`'self'` déjà autorisé) ; aucune route d'écran. |
