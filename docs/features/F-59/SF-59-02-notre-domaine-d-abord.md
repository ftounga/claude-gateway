# Mini-spec — F-59 / SF-59-02 — Notre domaine d'abord, GitHub en repli

## Identifiant

`F-59 / SF-59-02`

## Feature parente

`F-59` — Le relais servi par la gateway

## Statut

`done`

## Date de création

2026-09-10

## Branche Git

`feat/SF-59-02-notre-domaine-d-abord`

---

## Objectif

> Que l'assistant proxy propose le relais **depuis le domaine de la passerelle en premier**, GitHub en
> repli explicite — en disant pourquoi, et en montrant la licence.

---

## Comportement attendu

### Cas nominal

À l'ouverture de l'assistant proxy (F-55), une lecture de `GET /api/runner/relay/formats` dit ce que
**cette** gateway sert. Puis, à l'étape 3 « Installer et lancer le relais local », quand le verdict
d'authentification intégrée est `negotiate` ou `ntlm` :

1. **Depuis cette passerelle — en premier**, si le format existe pour le poste consulté :
   - une phrase qui dit **pourquoi** : *« si GitHub est filtré chez vous, ce lien-ci passe par le
     même domaine que la passerelle, déjà autorisé — sinon rien de ce produit ne fonctionnerait »* ;
   - un **bouton de téléchargement** (le navigateur sort déjà par le proxy, lui) ;
   - la **commande `curl`** équivalente pour le terminal, avec **l'option d'authentification qui vient
     de répondre `200`** (`--proxy-negotiate` ou `--proxy-ntlm`) et l'URL **absolue** de la gateway ;
   - la **version servie** (`px v0.11.0`) et un lien **« Licence MIT de px »** vers
     `/api/runner/relay/license`, ouvert dans un onglet.
2. **En repli, depuis GitHub** — les commandes actuelles de F-55, **inchangées**, précédées de la
   mention qui les remet à leur place : *« si la passerelle ne le sert pas, ou si vous préférez la
   source amont »*.
3. `cntlm` reste **un lien**, jamais un téléchargement : il est sous GPL et n'est pas redistribué.
   L'écran le dit, en une ligne, là où `cntlm` est proposé.

Quand la gateway ne sert **rien** pour ce poste (déploiement antérieur à F-59, plateforme non
empaquetée, notice absente, ou appel en échec), l'étape est **exactement celle d'aujourd'hui** : les
commandes GitHub, sans mention de repli, sans bouton, sans lien mort.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `/runner/relay/formats` en échec (réseau, `404`, gateway ancienne) | L'assistant reste ouvert et se comporte comme avant F-59 : **aucun** lien vers notre domaine. Aucun message d'erreur : ce n'est pas une panne de l'utilisateur. | — |
| Plateforme sans archive servie (Mac Intel, Linux non x86_64) | Le bloc « depuis cette passerelle » est **masqué** ; le chemin `pip3` et GitHub restent proposés. | — |
| Téléchargement refusé par la gateway (`404` tardif) | Message court en `MatSnackBar` : le fichier n'est pas servi par cette passerelle, la commande GitHub reste affichée. | 404 |
| Presse-papiers indisponible | Comportement existant : message invitant à sélectionner le texte. | — |

---

## Critères d'acceptation

- [ ] Sur un poste Windows, avec l'archive servie, l'étape 3 affiche **d'abord** l'option « depuis
      cette passerelle », **puis** l'option GitHub — dans cet ordre, et la seconde est **nommée comme
      un repli**.
- [ ] La raison est **écrite à l'écran** : le lien passe par le domaine de la passerelle, déjà
      autorisé chez le client.
- [ ] La commande `curl` proposée porte l'option d'authentification **du verdict** (`--proxy-ntlm` ou
      `--proxy-negotiate`), l'adresse du proxy saisie (ou son marqueur), et l'URL **absolue** de la
      gateway.
- [ ] Un lien **« Licence MIT de px »** est visible dès que l'option passerelle l'est, et pointe la
      notice servie par la gateway.
- [ ] La **version servie** est citée à l'écran.
- [ ] Quand `/relay/formats` répond que rien n'est servi (ou échoue), **aucun** lien vers notre
      domaine n'apparaît, et l'étape est identique à celle d'avant F-59.
- [ ] `cntlm` n'est **jamais** téléchargeable depuis la gateway ; l'écran indique qu'il reste à
      récupérer chez son éditeur, licence GPL.
- [ ] Le bouton de téléchargement enregistre le fichier sous le nom **qu'emploient les commandes
      affichées** (`px.zip` / `px.tar.gz`), pour que la suite du parcours colle (D5).
- [ ] Design system respecté : palette et typographies de `docs/DESIGN_SYSTEM.md`, boutons Material,
      espacements multiples de 4 px, aucun `window.alert/confirm/prompt`, notifications par
      `MatSnackBar`.
- [ ] Aucun comportement existant de l'assistant n'est modifié : étapes 1, 2, 4, configuration
      `cntlm`, vérification, redirection.

---

## Périmètre

### Hors scope (explicite)

- **Deviner la plateforme à la place de l'utilisateur** : le navigateur annonce `MacIntel` sur un M3
  (constaté en F-44). Ce qui est proposé suit la plateforme **déclarée** par le parcours, et les
  options non servies restent visibles sous leur propre intitulé.
- **Lancer, configurer ou superviser `px`** : l'assistant compose des gestes, il n'exécute rien.
- **Réécrire F-55** : les étapes 1, 2 et 4 ne bougent pas.
- **Un écran de téléchargement dédié** : le relais se propose là où le besoin naît.

---

## Valeurs initiales

Sans objet — aucun état persistant, rien n'est envoyé au serveur hormis une lecture publique.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| adresse du proxy (existant) | Non | 255 | `hote:port`, sinon marqueur | — | `trim()` (existant) |
| plateforme relais | — | — | `windows` \| `macos-aarch64` \| `linux-x64` | — | dérivée de la plateforme du poste |
| version servie (lue de l'API) | Non | 32 | affichée telle quelle, échappée par Angular | — | — |

---

## Technique

### Endpoint(s)

Aucun créé. Consommés (SF-59-01) : `GET /api/runner/relay/formats`, `GET /api/runner/relay/<plateforme>`,
`GET /api/runner/relay/license`.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable**.

### Composants Angular

- `ProxyAssistantDialogComponent` — l'option « depuis cette passerelle », le repli GitHub nommé, le
  lien de licence, le bouton de téléchargement.
- `AtelierService` — `proxyRelayFormats()`, `downloadProxyRelay(platform)`, URL de la notice.
- `atelier.models.ts` — `ProxyRelayFormats`.

---

## Plan de test

### Tests unitaires

- [ ] `relayOptions(...)` — Windows, archive servie : la **première** option porte les gestes
      « passerelle », l'option GitHub suit et est nommée comme repli.
- [ ] `relayOptions(...)` — rien de servi : sortie **identique** à celle d'avant F-59 (non-régression
      stricte, y compris l'ordre `cntlm` / `px` sous Linux).
- [ ] `relayOptions(...)` — la commande passerelle porte `--proxy-negotiate` sur verdict Kerberos et
      `--proxy-ntlm` sur verdict NTLM.
- [ ] `relayOptions(...)` — verdict `refused` : toujours **aucune** option, servie ou non.
- [ ] Composant — `/relay/formats` en échec : aucun lien passerelle, aucun message d'erreur.
- [ ] Composant — lien de licence présent, pointant `/api/runner/relay/license`.
- [ ] Composant — `cntlm` reste sans téléchargement.

### Tests d'intégration

- [ ] `AtelierService` — `proxyRelayFormats()` appelle `GET /api/runner/relay/formats`.
- [ ] `AtelierService` — `downloadProxyRelay('windows')` appelle `GET /api/runner/relay/windows` en
      `blob`.

### Isolation workspace

- [x] **Non applicable** — raison : l'écran ne lit aucune donnée utilisateur ; l'appel consommé est
      public et ne porte aucun identifiant.

---

## Dépendances

### Subfeatures bloquantes

- `F-59 / SF-59-01` — statut : done (mergée avant, contrat API figé)
- `F-55 / SF-55-02` — statut : done (l'écran greffé)

### Questions ouvertes impactées

- [ ] Aucune.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés / vérification |
|---|---|---|
| Auth / Principal | Non | Appel public, aucun jeton, aucun changement de session. |
| Contexte tenant | Non | Aucune donnée utilisateur lue. |
| Plans / limites | Non | Aucun quota. |
| Navigation / routing | Non | Aucune route ajoutée ni guard modifié ; l'assistant reste un `MatDialog` ouvert depuis les mêmes deux points qu'aujourd'hui. |

---

## Notes et décisions

**D1 — L'assistant émet désormais un appel réseau.** Il n'en émettait aucun (F-55). C'est assumé et
borné : une **lecture publique** de `/relay/formats`, en échec silencieux. L'alternative — afficher le
lien sans savoir s'il est servi — offrirait un lien mort à l'utilisateur le moins bien placé pour le
diagnostiquer, ce que F-44 avait déjà refusé (D3 de SF-44-02).

**D2 — Le bouton **et** la commande.** Le navigateur sort par le proxy sans rien demander : un bouton
suffit souvent. Mais l'utilisateur qui travaille dans un terminal déjà ouvert veut la commande, et
c'est elle qui prouve que le domaine passe. Les deux coûtent une ligne chacun.

**D3 — GitHub reste, nommé comme repli.** Le supprimer ferait dépendre le remède d'une seule source,
la nôtre — et rendrait l'assistant inutile sur une gateway antérieure à F-59.

**D5 — Le fichier enregistré s'appelle `px.zip` / `px.tar.gz`, pas de son nom amont.** C'est le nom
que la commande `curl` affichée juste en dessous emploie (`-o px.zip`), et celui que la commande de
décompression attend. Reprendre le nom versionné obligerait l'écran à connaître la convention de
nommage du projet amont — une duplication qui se périmerait à la première version suivante — et
ferait diverger le bouton de la commande. La version, elle, reste **citée** dans le titre.

**D4 — `cntlm` sans téléchargement, et l'écran le dit.** Le silence laisserait croire à un oubli. Une
ligne — GPL, non redistribué, à récupérer chez l'éditeur — évite la question et documente le choix
là où il se constate.
