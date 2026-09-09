# F-48 — Le poste comme unité : découpage

> Cadrage d'ensemble : `docs/features/CADRAGE-postes-et-gouvernance.md` (arbitrages du PO,
> 2026-09-10). Ce document ne rouvre rien : il découpe.

---

## Ce que la feature déplace

Un projet cessait d'être un dossier avec son runner et son appairage. Le **poste** — une machine,
une racine, un runner, **un seul appairage** — devient l'unité, et les projets deviennent des
sous-dossiers de sa racine, chacun avec **sa conversation** et ses réglages.

Le gain est net : un seul code d'appairage par machine, un seul runner lancé, et **ouvrir un projet
de plus ne coûte plus rien**.

---

## Les trois subfeatures

| # | Titre | Portée | Statut |
|---|---|---|---|
| **SF-48-01** | Le poste : modèle, appairage unique et routage par poste | Backend : table `runner_hosts`, migration 064, `RunnerIdentity(tokenId, userId, hostId)`, les trois tables runner qui changent de clef, registre / relais / dispatcher indexés par poste, et le **projet transmis dans chaque `tool_call`** | Livrée |
| **SF-48-02** | Le confinement par sous-dossier, côté runner | Runner : la racine du poste au démarrage, le sous-dossier du tour **par appel**, et `PathGuard` qui referme dessus. **Régime local — la décision non réversible du cadrage** | Planifiée |
| **SF-48-03** | Connecter un poste, y ranger ses projets | Frontend : créer un poste, l'appairer une fois, rattacher un projet avec son chemin relatif, et l'écran du projet qui suit | Planifiée |

**Ordre imposé** : 01 → 02 → 03. Le modèle d'abord (tout s'y adosse), le confinement ensuite (il
consomme le champ `project` que 01 émet), les écrans en dernier (ils consomment les endpoints).

---

## Le point dur, et pourquoi il n'est pas rouvert

Le confinement reste **local** : le runner reçoit la racine du poste **et** le sous-dossier du tour,
et c'est `PathGuard` qui refuse de sortir — à chaque appel. La gateway *indique*, elle ne *garantit*
pas.

Déplacer cette garantie dans la gateway (régime A du cadrage) rendrait la promesse centrale du mode
runner dépendante d'un composant réseau : un défaut de la gateway ouvrirait toute la racine. C'est la
seule décision du cadrage marquée non réversible, et elle ne sera pas rouverte sans motif écrit.

---

## Ce que F-48 ne fait pas

- La **vue d'ensemble** des postes → F-49.
- Le **parcours d'accueil** jusqu'à la première commande → F-53.
- Les **points de contrôle** et le **catalogue de gouvernance** → F-50 à F-52.
- Le **partage d'un poste entre comptes** → F-17, hors périmètre V3.
