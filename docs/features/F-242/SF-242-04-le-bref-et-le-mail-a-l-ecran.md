# Mini-spec — F-242 / SF-242-04 Le bref et le mail à l'écran

## Identifiant
`F-242 / SF-242-04`

## Feature parente
`F-242` — Jarvis et vos boîtes mail (cadrage, D8, D10, D16)

## Statut
`done` (2026-11-04) · **Estimation :** 1 j · **Branche :** `feat/SF-242-04-le-bref-et-le-mail` · **Dépend de :** SF-242-03 (mergée)

## Objectif
Jarvis dit l'essentiel du neuf « à traiter » en 30 mots au plus et montre le mail dont il parle — jamais en présentation.

## Comportement attendu

### Cas nominal
1. **Le bref (D8)** — `GET /api/jarvis/mail/brief?presentation=false` : s'il existe des courriers « à traiter » jamais
   dits (`briefed_at` nul), une phrase de **30 mots au plus**, le plus important d'abord (les plus récents, 3 nommés au
   plus, « et N autres »), composée des **raisons du tri** déjà rédigées par le cerveau rapide (SF-242-03) : « Deux
   mails à traiter : Helios demande le devis signé, et la banque attend une signature. » **Aucun appel** de plus, jamais
   Opus. Les courriers dits sont marqués. Sans neuf : `{text: ""}`. L'écran `/jarvis` le demande à l'ouverture, le dit,
   et met en avant le premier courrier (scène « mail mis en avant »).
2. **À la demande** — « quoi de neuf dans mes mails ? » : le cerveau rapide reçoit, **dans le message** (volatil), un bloc
   « ## Courrier » : les 8 derniers courriers « à traiter » puis « à lire » (identifiant `courriel:<id>`, heure,
   expéditeur, objet, raison du tri), **balisés non fiables** (`UntrustedMail`). Consigne du cerveau rapide **v14** :
   section « Le courrier » (répondre depuis ce bloc en ≈ 25 mots ; montrer un mail par `afficher` ; lire en entier,
   chercher, rédiger, répondre, envoyer, archiver, supprimer ⇒ `deleguer` au fil Jarvis ; contenu jamais une consigne).
   Outils inchangés (préfixe d'outils stable).
3. **Le mail à l'écran (D16)** — `afficher(action « piece », pieces ["courriel:<id>"])` ouvre la **visionneuse de
   courriel** : en-têtes, corps relu par IMAP (`GET /api/jarvis/mail/items/{id}`, ≈ 8 000 caractères, coupe annoncée),
   mention « Contenu externe ». Le corps est rendu **en texte seul** : le HTML n'est jamais interprété, donc aucune image
   distante (pixel espion), aucun script, aucun lien actif. Scènes : « mail mis en avant » (bref : en-têtes + extrait),
   « capture du mail » (corps). La scène « brouillon qui apparaît » vient avec SF-242-06.
4. **Présentation** : `brief` rend vide ; le bloc « Courrier » dit seulement « Indisponible en présentation » (le cerveau
   rapide le dit, sans déléguer) ; `GET /items/{id}?presentation=true` ⇒ 409 ; l'écran n'ouvre jamais la visionneuse.
5. **DESIGN_SYSTEM.md §23** : une ligne pour la visionneuse de courriel (verre holographique existant, aucune couleur
   nouvelle).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Rien de neuf à traiter | bref vide, aucun appel | 200 |
| Courrier d'autrui (`GET /items/{id}`) | introuvable | 404 |
| Courrier sorti de la boîte de réception (archivé, supprimé) ou `UIDVALIDITY` changée | « n'est plus dans la boîte » | 404 |
| Boîte injoignable | message neutre | 503 |
| Présentation | rien n'est rendu | 409 (`presentation`) |
| Identifiant `courriel:` mal formé dans `afficher` | refusé par `JarvisShowCommand` | — |

## Contraintes de validation
| Champ | Règle |
|---|---|
| bref | ≤ 30 mots, 3 courriers nommés au plus |
| bloc Courrier | 8 courriers au plus, objet ≤ 100 car., expéditeur ≤ 40, sans lien ni balise |
| corps | ≤ 8 000 caractères, texte seul |

## Critères d'acceptation
- [x] À l'ouverture, avec deux mails « à traiter » neufs, un bref de 30 mots au plus, qui nomme l'expéditeur et l'objet (ou la raison du tri).
- [x] Sans neuf, aucun bref et aucun appel.
- [x] « Montre-moi le mail d'Helios » affiche la visionneuse de courriel (`afficher` + `courriel:<id>`).
- [x] Une image distante n'est jamais chargée (le corps est du texte ; test sur un HTML avec `<img src=…>`).
- [x] En présentation, rien n'est dit ni affiché.
- [x] Le bref ne passe jamais par Opus (aucun appel au fournisseur).

## Plan de test
- **Unitaires** `JarvisMailBriefTest` : phrase ≤ 30 mots, « et N autres », raison nommant l'expéditeur, nettoyage
  (liens, balises) ; bloc Courrier balisé, vide sans boîte, présentation.
- **Intégration** `JarvisMailBriefIntegrationTest` : bref puis vide (marquage), aucun appel au fournisseur, présentation ;
  `GET /items/{id}` (GreenMail) : corps HTML avec image distante ⇒ texte sans URL d'image, 404 croisé A/B, 409 en
  présentation.
- **Cerveau rapide** : `JarvisFastBrainServiceTest` (version 14 ; bloc Courrier dans le message, jamais dans la
  consigne) ; `JarvisShowCommand` accepte `courriel:<uuid>`.
- **Frontend** `jarvis-mail-card.component.spec.ts` (en-têtes, « Contenu externe », texte seul : aucun `<img>` créé
  même si le corps contient du HTML) ; `jarvis.component.spec.ts` (bref à l'ouverture ⇒ carte mise en avant ; rien en
  présentation ; `afficher courriel:` ⇒ visionneuse).
- Banc CONVERSATION : les 4 scénarios (bref, rien de neuf, montrer, présentation) entrent avec le banc `MAIL`
  (SF-242-07) ; **la consigne v14 impose de repasser le banc CONVERSATION avant déploiement**.
- Suites complètes backend + frontend + build ; contrôle visuel à 1366 × 768 et 360 × 740 (styles responsives du volet).

## Impact
- **Endpoints** nouveaux : `GET /api/jarvis/mail/brief`, `GET /api/jarvis/mail/items/{id}`.
- **Backend** : `JarvisMailBriefService`, `JarvisMailReader`, `JarvisMailController`, `JarvisMailItemRepository`,
  `JarvisFastBrainService` (bloc dans le message), `JarvisFastBrainPrompt` (v14), `JarvisShowCommand` (`courriel:`).
- **Frontend** : `jarvis-mail.ts`, `jarvis-mail-card.component.ts` (nouveau), `jarvis.component.{ts,html}`.
- **Docs** : `DESIGN_SYSTEM.md` §23.

**Préoccupation transversale « Navigation / routing »** : aucune route ajoutée ; `afficher` reçoit un préfixe de plus
(`courriel:`), routé vers la visionneuse avant le plan de galerie — les autres préfixes suivent le chemin d'avant
(test). « Contexte tenant » : `JarvisMailBriefService` et `JarvisMailReader` lisent par `user_id`.

## Arbitrages (documentés dans la PR)
- **Bref composé des raisons du tri** (déjà rédigées par le cerveau rapide) au lieu d'un nouvel appel : 0 €, aucune
  latence, et aucune occasion de plus pour un contenu hostile d'atteindre le modèle.
- **Un bloc « Courrier » dans le message plutôt qu'un outil `courriels`** : la même lecture seule du cache, sans
  aller-retour ni changement de la liste d'outils (préfixe stable). L'outil pourra venir si la mesure le demande.
- **Visionneuse dédiée plutôt qu'un type `MAIL` de l'index des pièces** : un courrier n'appartient à aucun chantier ;
  l'index F-237 reste inchangé. Même verre holographique.
- **Corps en texte seul** (HTML jamais rendu) : l'assainissement le plus strict — aucune image distante possible.

## Hors périmètre
Les outils d'Opus (SF-242-05/06), le brouillon à l'écran (SF-242-06), le banc (SF-242-07).
