# Mini-spec — [F-109 / SF-109-02] L'outil de l'agent

---

## Identifiant

`F-109 / SF-109-02`

## Feature parente

`F-109` — Les pages : des documents graphiques, comme dans Claude Code
(cadrage validé par le PO : `CADRAGE-F-109-les-pages.md`, §3.5, §4, §7)

## Statut

`done` — mergée le 2026-09-13 (PR #567)

## Date de création

2026-09-13

## Branche Git

`feat/SF-109-02-outil-page-publish`

---

## Objectif

L'agent d'un terminal reçoit l'outil `page_publish` et un guide de conception : il **propose** une page
quand elle est plus claire qu'un message, et la publie — HTML fourni ou fichier du poste — une fois
l'utilisateur d'accord.

---

## Comportement attendu

### Cas nominal

1. **La garde, dans `buildTools`** (`PageToolCatalog.isOpenFor`) : l'outil est donné si et seulement si
   - le terminal s'exécute sur un **poste** (cible `RUNNER` — la boucle maison ; les projets hébergés
     passent par les Managed Agents, hors périmètre) ;
   - l'utilisateur a le **droit de l'espace du terminal** : Vigie pour un terminal Teams, Forge pour tout
     autre (`SpaceEntitlementService`, qui ouvre d'office au rôle `ADMIN` — `AdministratorEntitlement`).
2. **Le guide de conception** (`PageToolCatalog.DESIGN_GUIDE`) est ajouté à la consigne système **sous la
   même garde**. Il reprend l'esprit de ce que Claude Code applique à ses pages :
   - **quand proposer** : maquette d'écran, comparaison d'options, schéma d'architecture ou de flux,
     compte rendu à transmettre, tableau de bord d'un sujet — **jamais** pour une réponse courte ;
   - **l'accord** : proposer d'abord (« Voulez-vous que j'en fasse une page ? ») sauf demande explicite ;
   - **identité** : la charte du client ou du projet s'il en a une (fichier de charte, `DESIGN_SYSTEM.md`,
     couleurs du site du client connues dans le projet), sinon la charte de l'application (navy
     `#0B1020`, orange `#E07B39`, fond `#F5F6FA`, Space Grotesk / Inter / JetBrains Mono) ;
   - **typographie soignée** (hiérarchie claire, 2 familles au plus, interlignage, largeur de ligne) ;
   - **thèmes clair et sombre** par jetons CSS et `prefers-color-scheme` ;
   - **lisible au téléphone** (400 px, grilles qui passent en une colonne, aucun défilement horizontal
     hors tableaux) ;
   - **contenu réel** — jamais de remplissage ni de chiffres inventés ;
   - **titre court et distinctif** (2 à 4 mots, un nom, pas une phrase) et une description d'une phrase ;
   - **ce que la page peut faire** : scripts depuis `cdnjs.cloudflare.com` et `cdn.jsdelivr.net`,
     polices Google Fonts, images en `data:` ou en pièce jointe ; **aucun** appel réseau (`fetch`,
     formulaires), aucun stockage — ils sont bloqués ;
   - **pas de transcription brute** dans une page (règle F-87 §9 bis) ;
   - **republier avec `page_id`** pour une nouvelle version plutôt qu'une nouvelle page.
3. **L'appel** `page_publish` :
   - `title` (obligatoire), `description`, **exactement un** de `html` (contenu) ou `path` (fichier `.html`
     du poste, lu par le runner), `page_id` (republication), `attachments` : `[{name, path}]` (fichiers
     **texte** du poste — `css js mjs json svg csv txt md` ; les images vont en `data:`).
   - **L'accord d'un clic** : la porte d'autorisation existante s'ouvre (« Publier la page « titre » —
     privée, visible par vous seul ») ; « Tout autoriser pour ce message » la couvre, comme les commandes.
   - Les lectures du poste passent par `RunnerToolGateway.readFile` et sont **tracées** dans le journal
     du runner (`page_publish`, chemin).
   - La page est rangée par `PageService.publish` (SF-109-01) au **lieu du terminal** : espace, poste,
     terminal ou projet.
   - L'agent reçoit : « Page publiée : « titre » — version N. page_id : … ».
4. L'étape du terminal affiche `page_publish` avec pour cible le **titre** (jamais le contenu).

### Cas d'erreur

| Situation | Résultat d'outil (erreur, le tour continue) |
|-----------|---------------------|
| Outil appelé hors garde (projet hébergé, sans droit) — **second verrou** | refus, rien n'est lu ni rangé |
| Ni `html` ni `path`, ou les deux | « donne exactement un de html ou path » |
| `path` illisible sur le poste (absent, poste hors ligne) | le motif du runner |
| Fichier tronqué par le runner (> 512 Kio) | refus : « trop volumineux pour être lu depuis le poste — passe le HTML en contenu » |
| Pièce jointe binaire (`.png`…) depuis le poste | refus : « images en data: » |
| `page_id` mal formé, inconnu ou d'un autre compte | « page_id inconnu » (indiscernables) |
| Utilisateur refuse / délai dépassé | « Publication refusée par l'utilisateur » (+ motif) |
| Contrainte de SF-109-01 (titre, taille, quota) | le message du service |

---

## Critères d'acceptation

- [ ] CA1 — terminal de projet sur poste + droit Forge : `page_publish` est dans la panoplie ; terminal Teams + droit Vigie : aussi.
- [ ] CA2 — sans droit de l'espace, ou projet hébergé (cible `SANDBOX`) : l'outil n'est pas donné et la consigne ne porte pas le guide.
- [ ] CA3 — un utilisateur `ADMIN` sans abonnement reçoit l'outil (via `SpaceEntitlementService`).
- [ ] CA4 — la consigne, garde ouverte, contient le guide : thèmes clair et sombre, téléphone, contenu réel, titre court, liste des CDN, « aucun appel réseau », transcriptions, `page_id`.
- [ ] CA5 — `html` fourni + accord : la page est rangée au lieu du terminal (espace, poste, terminal), l'agent reçoit le `page_id` et la version.
- [ ] CA6 — `path` : le fichier est lu par le runner, tracé, et rangé ; tronqué → refus.
- [ ] CA7 — republier avec `page_id` crée la version 2.
- [ ] CA8 — refus de l'utilisateur : rien n'est rangé ; « tout autoriser pour ce message » : pas de seconde demande.
- [ ] CA9 — second verrou : un appel forcé hors garde est refusé sans lecture ni rangement.
- [ ] CA10 — `page_id` d'un autre compte : refus indiscernable d'un identifiant inconnu.

---

## Périmètre

### Hors scope (explicite)

- Le bloc « Page publiée » dans le terminal, le panneau, le plein écran → SF-109-03.
- Les projets **hébergés** (Managed Agents) : aucun outil personnalisé n'y est branché aujourd'hui.
- Les pièces jointes **binaires** lues du poste (le runner lit du texte) : images en `data:`.
- La génération d'une page par le Radar hors terminal (le Radar et Teams s'en servent **depuis** le terminal Teams).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `pages.space` | `VIGIE` si terminal Teams, sinon `FORGE` | lieu du terminal |
| `pages.host_id` | `workspaces.host_id` du terminal | — |
| `pages.workspace_id` | le terminal (ou projet) du tour | — |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `title` | Oui | 120 | texte | Non | celles de SF-109-01 |
| `description` | Non | 300 | texte | Non | — |
| `html` / `path` | exactement un | 8 Mo / chemin du poste | — | — | — |
| `page_id` | Non | 36 | UUID d'une page du compte | — | — |
| `attachments[]` | Non | 20 | `{name, path}`, extensions texte | nom unique | — |

---

## Technique

### Endpoint(s)

Aucun (outil de la boucle).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `pages`, `page_versions` | INSERT / UPDATE | via `PageService` (SF-109-01) |
| `runner_audit` | INSERT | lecture du poste tracée |

### Migration Liquibase

- [x] Non applicable

### Composants impactés

- Nouveaux : `pages/PageToolCatalog` (garde, définition, guide), `pages/PageToolExecutor` (lecture du poste, rangement).
- `atelier/AtelierChatService` : constructeur `@Autowired` étendu (formes historiques conservées), `buildTools`, `buildSystemPrompt`, `executeTool`, `auditTarget`.

### Préoccupations transversales

- **Plans / limites : OUI.** Composants : `PageToolCatalog.isOpenFor` → `SpaceEntitlementService.isEntitled(userId, FORGE|VIGIE)` (règle ADMIN incluse) ; aucun service de limite modifié ; quota de stockage de SF-109-01 ; génération sur le quota du tour (inchangé, `QuotaService.recordUsage`).
- **Auth / Principal : non** (aucune route). **Contexte tenant : OUI** — `userId` du tour, page relue `findByIdAndUserId`. **Navigation : non.**

---

## Plan de test

### Tests unitaires

- [ ] `PageToolCatalogTest` — garde (runner + droit Forge / Vigie selon le terminal, sandbox fermé, sans droit fermé) ; schéma (exactement les champs, aucun champ destinataire ni URL) ; guide (mots-clés CA4).
- [ ] `PageToolExecutorTest` — html ; path lu et tracé ; tronqué ; ni l'un ni l'autre / les deux ; pièce jointe texte / binaire refusée ; `page_id` mal formé / inconnu ; message du service relayé.

### Tests d'intégration

- [ ] `AtelierChatServicePageToolTest` — buildTools (CA1-CA3), consigne (CA2, CA4), appel accordé → `PageService.publish` au lieu du terminal (CA5), refus → rien (CA8), tout autoriser → une seule demande, second verrou (CA9).
- [ ] `PageToolPublishIntegrationTest` (contexte Spring) — l'exécuteur range réellement v1 puis v2 ; `page_id` d'autrui refusé (CA7, CA10).

### Isolation utilisateur

- [x] Applicable — republier sur la page d'un autre compte est refusé comme un identifiant inconnu.

---

## Dépendances

### Subfeatures bloquantes

- SF-109-01 — statut : mergée.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — L'accord est la porte d'autorisation existante.** Le cadrage §3.5 veut un clic ; la porte
  (F-33, étendue aux écritures Teams par F-108) le donne déjà, avec « Tout autoriser pour ce message ».
  Inventer une seconde porte ferait deux gestes différents pour la même décision.
- **D2 — Le droit est celui de l'espace du terminal.** Un terminal Teams exige déjà le droit Vigie, un
  projet le droit Forge : l'outil suit, et la page se range dans l'onglet de cet espace.
- **D3 — Pièces jointes texte seulement depuis le poste** : `read_file` du runner rend du texte UTF-8 ;
  relire un binaire le corromprait en silence. Refuser est plus honnête.
