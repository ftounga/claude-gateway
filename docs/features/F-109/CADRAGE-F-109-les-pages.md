# F-109 — Les pages : des documents graphiques, comme dans Claude Code

> Cadrage du 2026-09-13, à la demande du PO. **Cadrage seul : la livraison attend le go du PO.**

## 1. Le besoin

> « Claude Code me permet de générer des fichiers HTML graphiques très souvent. On l'a déjà fait toi
> et moi plusieurs fois. Est-ce que notre application peut permettre ça aussi ? »

Les exemples réels du PO, faits dans cette session : la maquette de la Forge refondue, la page du
Radar, la page du volet Teams. **Une page vaut mieux qu'un long message** quand il faut montrer un
écran, comparer des options, dessiner une chaîne, présenter une décision à quelqu'un d'autre.

## 2. Ce qui existe, ce qui manque

| | Aujourd'hui | Avec F-109 |
|---|---|---|
| Écrire un `.html` | ✅ l'agent l'écrit sur la machine du poste | inchangé |
| Le voir | ❌ il faut ouvrir le fichier soi-même sur le poste | **aperçu dans l'application**, dans le terminal et en plein écran |
| Le retrouver | ❌ perdu dans un dossier | **les pages** d'un projet et d'un client, listées, versionnées |
| Le montrer | ❌ envoyer un fichier | **lien de partage** révocable |
| Que l'agent y pense | ❌ | l'agent **propose** une page quand elle est plus claire que du texte |

**Provider-First** : le modèle écrit la page ; la gateway la **range, la sert et la sécurise**. Aucun
service du fournisseur n'héberge des pages qu'on pourrait relayer : c'est la part de la gateway.

## 3. La sécurité : une page est du code, et elle contient des données du client

C'est le point dur. Une page HTML exécute du JavaScript écrit par un modèle, et contient souvent des
noms, des chiffres, des captures du client.

1. **Origine opaque, jamais celle de l'application.** Une page est servie avec l'en-tête
   `Content-Security-Policy: sandbox allow-scripts allow-popups` **sans** `allow-same-origin` : le
   navigateur la traite comme une origine unique et fermée. Elle **ne lit ni les cookies, ni le
   stockage, ni le jeton de l'application**, et ne peut pas appeler l'API au nom de l'utilisateur. Dans
   l'écran, elle s'affiche dans une `iframe sandbox="allow-scripts allow-popups"` (sans
   `allow-same-origin`, sans `allow-forms`, sans `allow-top-navigation`). **Aucun sous-domaine à créer.**
2. **Pas de sortie réseau** hors d'une liste close : `connect-src 'none'`, `form-action 'none'` ;
   scripts depuis `cdnjs.cloudflare.com` et `cdn.jsdelivr.net` seulement, polices depuis Google Fonts ;
   images et médias en `data:` ou servis avec la page. Une page ne peut pas exfiltrer ce qu'elle
   contient.
3. **Taille bornée** : 8 Mo par page, pièces jointes comprises.
4. **Privée par défaut** : lisible par son propriétaire seul (isolation `user_id`). Le partage (§6) est
   un geste explicite.
5. **Aucune page n'est publiée sans l'utilisateur**, tant que l'agent n'a pas été invité à publier : il
   **propose**, l'utilisateur accepte d'un clic, ou l'a demandé.

## 4. Ce que fait l'agent

- **Un outil `page_publish`** : titre, description d'une phrase, contenu HTML **ou** chemin d'un
  fichier `.html` du poste (lu par le runner), pièces jointes éventuelles. Republier avec le même
  identifiant crée une **nouvelle version** de la même page.
- **Un guide de conception** injecté dans la consigne quand l'outil est disponible, sur le modèle de
  ce que Claude Code applique : charte `DESIGN_SYSTEM.md` de l'application ou identité du client,
  typographie soignée, **thèmes clair et sombre**, lisible au téléphone, contenu réel (jamais de
  remplissage), titre court et distinctif, et la liste des CDN permis.
- **Quand proposer une page** : maquette d'écran, comparaison d'options, schéma d'architecture ou de
  flux, compte rendu à transmettre, tableau de bord de sujet. Pas pour une réponse courte.
- **Le Radar et Teams s'en servent** : « fais-moi une page du compte rendu de la réunion » produit une
  page avec les captures alignées (F-90) ; « une page de l'état du sujet MFA pour mon manager » à
  partir de la page sujet (F-103). La règle des transcriptions bloquées (F-87 §9 bis) s'applique : pas
  de transcription brute dans une page.

## 5. Ce que voit l'utilisateur

- **Dans le terminal** : un bloc **« Page publiée — <titre> »** avec une vignette, *Ouvrir* et *Plein
  écran*. *Ouvrir* affiche la page dans un panneau à droite du terminal, *Plein écran* dans un onglet
  de l'application.
  - **Amendement nécessaire à la règle de F-89** (« un terminal de projet reste textuel pour
    toujours ») : la règle protège les **sorties de commande**, qui restent textuelles. Une page
    publiée n'est pas une sortie de commande, c'est un **document rendu par l'agent** : son bloc est
    admis dans tous les terminaux. **À valider par le PO.**
- **Les pages d'un lieu** : un onglet **Pages** dans le projet (Forge) et dans le client (Vigie) —
  vignette, titre, date, version, *Renommer*, *Supprimer*, *Versions précédentes*, *Télécharger le
  fichier HTML*.
- **Une page est rattachée** au projet ou au client où elle a été publiée ; clôturer la mission
  (F-60) propose de télécharger les pages avant de les supprimer, comme l'export du Radar.

## 6. Le partage

- **Lien de partage** non devinable, **révocable** à tout moment, avec **expiration** (7 jours par
  défaut, 1 à 90), ouvrable **sans compte**, servi avec la même politique de sécurité (§3).
- **Avertissement au moment de partager** : « cette page contient peut-être des données de votre
  client ; vérifiez qu'il autorise leur diffusion ».
- **Journal** : création, ouverture d'un lien partagé (date, sans identifier le visiteur au-delà du
  nombre d'ouvertures), révocation.
- Hors périmètre : commentaires sur une page, édition à plusieurs.

## 7. Le droit et le coût

- **Inclus dans la Forge et dans la Vigie** : une page est un moyen de rendre le travail, pas une
  option. Le rôle `ADMIN` l'a d'office (règle F-107 §3 bis).
- **La génération consomme le quota** comme tout tour. Le stockage est borné (§3.3) ; **500 Mo par
  compte** au plus, les versions les plus anciennes au-delà de 10 par page sont purgées.

## 8. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-109-01 | Ranger et servir une page en sécurité | Tables `pages` et `page_versions` (migration, numéro à prendre au-dessus du dernier sur main), stockage objet S3 existant (`S3WorkspaceStorage` en patron), endpoint de service avec la politique §3, taille et quota bornés, isolation `user_id` ; tests de sécurité (en-têtes, origine opaque, `connect-src`, refus d'accès d'autrui) |
| SF-109-02 | L'outil de l'agent | `page_publish` (HTML fourni ou fichier du poste lu par le runner), nouvelle version par republication, guide de conception dans la consigne, gardé par le droit Forge ou Vigie dans `buildTools` |
| SF-109-03 | L'aperçu dans le terminal | Bloc « Page publiée », panneau latéral en `iframe` sandbox, plein écran ; amendement F-89 |
| SF-109-04 | Les pages d'un projet et d'un client | Onglet Pages (Forge projet, Vigie client), versions, renommer, supprimer, télécharger, proposition au moment de la clôture |
| SF-109-05 | Le partage | Lien révocable avec expiration, lecture sans compte, avertissement, journal |

## 9. Préoccupations transversales

- **Sécurité : oui**, centrale (§3). Composants : nouveau contrôleur de service des pages,
  configuration Spring Security (route de lecture partagée sans authentification, **seulement** elle),
  en-têtes de réponse, `iframe` du frontend.
- **Auth / Principal : oui** — une route publique pour les liens partagés. Vérifier que la chaîne de
  filtres n'ouvre rien d'autre (test de non-régression sur les routes protégées).
- **Plans / limites : oui** — droit Forge ou Vigie, quota de stockage par compte.
- **Navigation : oui** — onglet Pages, plein écran `/pages/:id`, lien partagé `/p/:token`.
- **Contexte tenant** : isolation `user_id` sur toutes les lectures privées.

## 10. Hors périmètre

- Héberger un site ou une application durable (formulaires, base de données, appels d'API).
- Commentaires et édition collaborative.
- Publication vers un service externe (SharePoint, Confluence).
