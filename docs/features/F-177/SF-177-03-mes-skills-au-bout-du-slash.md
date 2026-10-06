# Mini-spec — F-177 / SF-177-03 — Mes skills au bout du `/`

## Identifiant
`F-177 / SF-177-03` — feature parente `F-177` (cadrage, décision D5). Branche :
`feat/SF-177-03-skills-slash`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Taper `/` liste aussi les skills du **sujet** et du **poste** ; `/nom-du-skill texte` charge ce skill
**de façon déterministe** (par la gateway), sans dépendre du bon vouloir du modèle.

## Comportement attendu

### Cas nominal
1. **Catalogue servi par le back** — `GET /api/workspaces/{id}/skills` → `[{name, path, description, origin}]` :
   - **sujet** : fichiers `.claude/skills/<nom>.md`, `.claude/skills/<nom>/SKILL.md`, `skills/<nom>.md` de
     l'arborescence du projet (cache de consigne si amorcé, sinon listage) ;
   - **poste** : `.claude/skills/` à la racine du poste, listé **directement** (jamais déduit de
     l'arborescence récursive et tronquée de la racine) ;
   - un skill du sujet masque celui du poste de même nom ; description = en-tête `description:` sinon
     première ligne ; borne 60 skills, 20 lectures directes de description ; terminal Teams : vide.
2. **Autocomplétion (front)** : le menu `/` fusionne commandes F-165, macros F-121 puis **skills**
   (« … · skill du poste / du sujet ») ; un nom déjà pris par une commande ou une macro reste à elle.
   Accepter complète `/<nom> ` sans rien envoyer.
3. **Invocation déterministe** : un message qui commence par `/<nom>` où `<nom>` est un skill du
   catalogue → la gateway joint le contenu du skill (borné 20 000 car.) à la **consigne du tour**, encadré
   (`--- Skill invoqué par l'utilisateur : /nom (chemin, origine) ---` … `--- Fin du skill invoqué ---`).
   Jamais dans la consigne système (cache F-134) ; le message persisté reste la parole de l'utilisateur.
4. **Outil `skill(nom)`** (hors Teams) : rend le contenu du skill ; sans nom ou nom inconnu, la liste des
   skills disponibles (dont ceux du poste, que le catalogue de la consigne n'annonce pas).
5. Le plafond d'annonce de 15 skills dans la consigne est **conservé** ; au-delà, ils restent invocables.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `/nom` inconnu | message envoyé tel quel, rien joint |
| Poste injoignable / dossier absent | pas de skills du poste (jamais d'erreur) |
| Terminal d'autrui | 404 |
| Skill illisible au chargement | rien joint ; l'outil rend la liste |

## Critères d'acceptation
- [x] `/` liste les skills du client (sujet + poste) avec leur origine.
- [x] `/ticket-jira DECPB-200` joint ce skill à la consigne du tour, sans passer par le modèle.
- [x] Le contenu invoqué n'entre pas dans la consigne système.
- [x] Isolation : 404 sur le terminal d'autrui.

## Plan de test
- `SkillCatalogServiceTest` (6) : sujet puis poste + masquage ; chargement d'un skill du poste à la racine ;
  inconnu ; terminal du poste sans listage récursif de la racine ; analyse de `/nom` ; noms et descriptions.
- `AtelierChatServiceSkillInvocationTest` (4) : `/nom` joint au message et pas au système ; inconnu
  inchangé ; outil offert ; outil rend le contenu.
- `SkillCatalogApiIntegrationTest` (2) : 200 propriétaire (projet hébergé), 404 autrui.
- Front `terminal-skills.spec` (4) : menu, priorité macro, acceptation sans envoi, filtre.
- Non-régression : suites complètes back et front.

## Impacts
Backend : paquet `atelier.skills` (`SkillCatalogService`, `SkillEntry`, `SkillCatalogController`),
`AtelierChatService` (outil `skill`, invocation). Front : `SkillCatalogService`, terminal (menu `/`).
Aucune table, aucune migration. Endpoint ajouté : `GET /api/workspaces/{id}/skills`.

### Préoccupations transversales
- **Contexte tenant** : ✔ — `SkillCatalogService.catalogOf` (`requireOwned`), lectures bâties depuis
  l'entité possédée (poste du projet). Aucun nouveau moyen de résoudre le tenant.
- Navigation : non (le menu `/` existant est enrichi, aucune route).

## Hors périmètre
Création de skill (SF-177-02) ; écran de gouvernance (SF-177-04) ; skills de paquets non déposés.

## Arbitrages (réversibles)
- Invocation côté gateway (jointe à la consigne) plutôt qu'expansion côté écran : l'écran n'a pas le
  contenu, et la gateway garde la main sur la borne.
- Une commande ou macro homonyme prime sur un skill.
