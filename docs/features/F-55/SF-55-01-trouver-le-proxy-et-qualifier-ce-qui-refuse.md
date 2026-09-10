# Mini-spec — F-55 / SF-55-01 — Trouver le proxy, et qualifier ce qui refuse

## Identifiant

`F-55 / SF-55-01`

## Feature parente

`F-55` — Assistant proxy dans l'application

## Statut

`done` — PR #336, mergée le 2026-09-10

## Date de création

2026-09-10

## Branche Git

`feat/SF-55-01-assistant-proxy-diagnostic`

---

## Objectif

> Ouvrir, depuis les deux branches en échec du parcours de mise en service, un **assistant proxy**
> qui fait deux choses que l'écran ne fait pas aujourd'hui : **retrouver l'adresse du proxy** sur le
> système du poste — **fichier PAC compris** — et **qualifier ce qui refuse**, en séparant le `407`
> de l'absence de route, qui n'ont pas le même remède.

---

## Déclencheur

Le parcours de F-45 s'arrête à une phrase : « le remède est un relais local — `px` ou `cntlm` ».
Entre cette phrase et un runner qui passe, il reste, dans l'ordre :

1. **trouver l'adresse du proxy**, que le poste ne dit pas spontanément — `netsh winhttp show proxy`
   répond `Direct access` sur un poste dont le navigateur sort parfaitement, parce que WinHTTP et le
   navigateur (WinINET) ne lisent pas la même configuration, et parce que l'adresse peut n'exister
   que dans un **fichier PAC**. C'est exactement ce qui a débloqué la séance du 2026-09-07 : ouvrir
   le `.pac` dans le navigateur et y lire la ligne `PROXY hôte:port` ;
2. **savoir quel remède appliquer**. Un `407` et une absence de réponse se ressemblent à l'écran —
   « ça ne passe pas » — et se soignent à l'opposé : le premier demande un **relais**, le second une
   simple **déclaration** dans le terminal courant. Confondre les deux fait installer un relais pour
   rien, ou attendre la DSI pour un problème qu'on pouvait lever seul en dix secondes ;
3. **savoir si le relais a une chance de marcher**, ce que seul le test de l'authentification
   **intégrée** dit (`--proxy-negotiate`, puis `--proxy-ntlm`). S'il échoue lui aussi, le poste n'a
   rien à installer : la demande part à la DSI.

Et, à chaque fois, **le motif** doit être dit. Sans lui, l'utilisateur cherche l'option du runner qui
ferait passer NTLM — elle n'existe pas et n'existera pas : `java.net.http.HttpClient` n'a aucun
support SSPI, et `Basic` est désactivée sur les tunnels `CONNECT` depuis Java 8u111.

---

## Comportement attendu

### Ouverture

Dans le parcours de mise en service (SF-45-05), les branches `407` et « aucun code » gagnent un
bouton **« Ouvrir l'assistant proxy »**. Il ouvre un second dialogue, par-dessus le parcours, qui
**ne le ferme pas** : le code d'appairage expire en cinq minutes, et fermer le parcours pour aller
chercher un proxy ferait perdre l'avancement (D1).

Le verdict déclaré à l'étape 1 du parcours est **transmis** à l'assistant, qui s'ouvre sur l'étape
utile : la qualification pour un `407` déjà constaté, la recherche d'adresse pour une absence de
route. Ouvert sans verdict, il s'ouvre sur la recherche d'adresse.

### Étape A — Retrouver l'adresse du proxy

L'assistant affiche les commandes de **lecture** du système consulté, chacune avec ce qu'elle
répond et pourquoi elle peut mentir :

| Système | Commandes | Ce qu'elles disent |
|---|---|---|
| Windows | `netsh winhttp show proxy` | Le proxy vu par **WinHTTP** — celui des outils système. `Direct access` ne prouve rien : le navigateur, lui, lit WinINET |
| Windows | `reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer` | Le proxy du **navigateur**, celui qui marche pendant que le terminal échoue |
| Windows | `reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v AutoConfigURL` | L'URL du **fichier PAC**, quand l'adresse n'est écrite nulle part ailleurs |
| macOS | `scutil --proxy` | `HTTPSProxy` / `HTTPSPort`, et `ProxyAutoConfigURLString` pour un PAC |
| Autre | `env \| grep -i proxy` | Ce que **ce** terminal a déjà, et rien d'autre |

Quand une URL de PAC sort, l'assistant dit quoi en faire : **l'ouvrir dans le navigateur** — il y a
accès, c'est le seul composant du poste qui sort — et y lire la ligne `PROXY hôte:port`. Le produit
**ne télécharge ni n'interprète** le fichier : il est servi sur le réseau interne, la page n'y a pas
accès depuis son bac à sable, et exécuter du JavaScript de configuration d'entreprise n'est pas le
métier d'une passerelle.

L'utilisateur **saisit l'adresse trouvée** dans un champ. Toutes les commandes des étapes suivantes
sont alors composées avec **cette** adresse, au lieu du marqueur `hote:port` (D2).

### Étape B — Qualifier ce qui refuse

Deux pannes, deux remèdes, énoncés côte à côte :

| Ce que le terminal a répondu | Ce que ça veut dire | Le remède |
|---|---|---|
| `407` | Un proxy **a répondu**. La route existe ; c'est l'**authentification** qui manque | Tester l'authentification intégrée, puis un **relais local** |
| Aucun code, `Could not resolve host`, `Failed to connect` | **Aucun proxy n'est déclaré dans ce terminal** — il l'est côté système, et un shell ne le lit pas | **Déclarer** le proxy trouvé à l'étape A, puis refaire le contrôle |

Pour l'absence de route, l'assistant compose la déclaration avec l'adresse saisie, dans la forme du
système consulté (`export` / `$env:`), et le rappel que la variable **ne franchit pas** la fenêtre :
c'est ce terminal-là qui devra lancer le runner.

Pour le `407`, l'assistant compose **les deux commandes de test** de l'authentification intégrée :

```
curl -sS -o /dev/null -w "Kerberos : %{http_code}\n" --proxy-negotiate --proxy-user : -x http://hote:port <url>
curl -sS -o /dev/null -w "NTLM     : %{http_code}\n" --proxy-ntlm      --proxy-user : -x http://hote:port <url>
```

`--proxy-user :` — utilisateur et mot de passe **vides** — est ce qui demande à `curl` d'employer la
**session ouverte** du poste. Aucun identifiant n'est saisi ici, ni maintenant, ni plus tard (D4).

L'utilisateur déclare ce qu'il a obtenu — `200` en Kerberos, `200` en NTLM, ou `407` des deux
côtés — et l'assistant conclut :

| Déclaration | Conclusion affichée |
|---|---|
| `200` en **Kerberos** | L'authentification intégrée passe **pour `curl`**, en Kerberos. La JVM, elle, ne sait pas la faire — le remède est un **relais local** (`px`, qui porte Kerberos comme NTLM) |
| `200` en **NTLM** | Idem, en NTLM — le remède est un **relais local** (`px` ou `cntlm`) |
| `407` **des deux côtés** | Le proxy exige des **identifiants applicatifs** : rien à installer sur le poste, c'est une demande DSI — l'assistant renvoie à la **fiche « Pour votre DSI »** de F-45, sans en réécrire le contenu |

Chaque conclusion porte **le motif**, en une phrase : la JVM n'a aucun support SSPI, et `Basic` est
désactivée sur les tunnels `CONNECT` depuis Java 8u111 — `curl` et le navigateur s'authentifient
avec la session Windows, la JVM jamais, quelle que soit la version du runner.

La déclaration est **révisable** : un retour ramène aux trois choix.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| L'adresse saisie est vide | Les commandes gardent le marqueur `hote:port` — visiblement incomplètes plutôt que faussement prêtes |
| L'adresse saisie porte un schéma (`http://px:8080`), un chemin, ou une barre finale | Elle est **normalisée** en `px:8080` : c'est la forme qu'attendent `-x`, `px` et `cntlm` |
| L'adresse saisie porte des identifiants inline (`http://moi:secret@px:8080`) | Les identifiants sont **supprimés** à la normalisation et ne sont **jamais** réaffichés ni recopiés dans une commande (D7) |
| L'adresse saisie n'est pas exploitable (espaces, aucun hôte) | Un message le dit, et les commandes gardent le marqueur ; aucune commande n'est composée avec une valeur douteuse |
| Système d'exploitation non reconnu | Les gestes **génériques** (`env \| grep -i proxy`, `export`) sont affichés — jamais une étape vide |
| L'utilisateur n'a rien déclaré | Aucune conclusion n'est affichée ; les deux remèdes restent lisibles côte à côte |
| L'assistant est ouvert sans verdict de départ | Il s'ouvre sur l'étape A, et les deux branches de l'étape B restent atteignables |

---

## Critères d'acceptation

- [ ] Les branches `407` et « aucun code » du parcours de mise en service portent un bouton
      **« Ouvrir l'assistant proxy »**.
- [ ] Ce bouton ouvre l'assistant **sans fermer** le parcours.
- [ ] Ouvert depuis le `407`, l'assistant s'ouvre sur l'étape **B** ; depuis « aucun code » ou sans
      verdict, sur l'étape **A**.
- [ ] Sous Windows, l'étape A affiche les **trois** lectures : `netsh`, `ProxyServer`, `AutoConfigURL`.
- [ ] Sous macOS, elle affiche `scutil --proxy` ; sur tout autre système, `env | grep -i proxy`.
- [ ] L'étape A explique quoi faire d'une **URL de PAC** : l'ouvrir dans le navigateur et y lire la
      ligne `PROXY hôte:port`.
- [ ] Une adresse saisie remplace le marqueur `hote:port` dans **toutes** les commandes composées.
- [ ] `http://moi:secret@px.corp:8080/` saisi produit `px.corp:8080` — sans schéma, sans chemin, et
      **sans identifiants**.
- [ ] Une adresse inexploitable affiche un message et laisse le marqueur en place.
- [ ] L'étape B affiche **les deux** pannes et **les deux** remèdes distincts, `407` contre absence
      de route.
- [ ] La branche « absence de route » compose la **déclaration** du proxy dans la forme du système
      consulté.
- [ ] La branche `407` compose les **deux** commandes de test, `--proxy-negotiate` **puis**
      `--proxy-ntlm`, toutes deux avec `--proxy-user :`.
- [ ] Sous Windows, les commandes de test emploient `curl.exe` (et non `curl`, alias de
      `Invoke-WebRequest` dans PowerShell).
- [ ] Déclarer `200` en Kerberos ou en NTLM conclut sur le **relais local** et nomme le motif (aucun
      SSPI dans la JVM ; `Basic` désactivée sur les tunnels depuis Java 8u111).
- [ ] Déclarer `407` des deux côtés conclut sur une **demande DSI** et renvoie à la fiche de F-45,
      sans en réécrire le contenu.
- [ ] Tant que rien n'est déclaré, **aucune** conclusion n'est affichée.
- [ ] Le retour au diagnostic ramène l'étape B à ses trois choix.
- [ ] Aucun champ de l'assistant ne demande, n'affiche ni ne transmet un **mot de passe**.
- [ ] Aucun appel réseau n'est émis par l'assistant : il compose du texte, il n'exécute rien.

---

## Périmètre

### Hors scope (explicite)

- **Le relais local lui-même** — installation, configuration, lancement, vérification, redirection
  du runner : c'est SF-55-02.
- **Le séparateur `NO_PROXY`** : il appartient à la redirection du runner, donc à SF-55-02 (écran) et
  SF-55-03 (runner).
- **Télécharger ou interpréter le fichier PAC** : la page n'y a pas accès (bac à sable), et exécuter
  du JavaScript de configuration d'entreprise n'est pas le métier d'une passerelle.
- **Exécuter la moindre commande** ou poser une variable d'environnement : l'assistant compose, le
  poste exécute.
- **Réécrire la fiche « Pour votre DSI »** (SF-45-03) : l'assistant y renvoie.
- **Mémoriser l'adresse du proxy** d'une ouverture à l'autre : elle vaut pour un poste et un
  terminal, et une adresse restaurée à tort enverrait chercher au mauvais endroit.
- **Refaire le contrôle d'accès réseau** (SF-45-01) : l'assistant part de son verdict.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `step` | `'address'`, ou `'qualify'` si l'assistant est ouvert sur un `407` | Première étape utile compte tenu de ce qui est déjà su |
| `proxyAddress` | `''` | Rien n'est deviné ; le marqueur `hote:port` tient la place |
| `authVerdict` | `'unknown'` | Aucune conclusion tant que rien n'est déclaré |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / valeurs | Unicité | Normalisation |
|-------|-------------|-------------|------------------|---------|---------------|
| `proxyAddress` (saisie) | Non | 255 | `hôte` ou `hôte:port` ; schéma, chemin et identifiants tolérés en entrée | Non | `normalizeProxyAddress` — schéma, identifiants, chemin et barre finale **retirés** ; port conservé s'il est numérique et dans `1..65535` ; sinon `null` |

Notes :
- La normalisation est une fonction **pure**, testée seule : c'est elle qui garantit qu'un mot de
  passe collé par mégarde ne ressort dans aucune commande affichée.
- Aucune valeur saisie ne quitte le navigateur : l'assistant n'appelle aucun endpoint.

---

## Technique

### Endpoint(s)

Aucun. L'assistant ne fait **aucun** appel réseau.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

### Composants Angular

- `ProxyAssistantDialogComponent` (nouveau) — `frontend/src/app/atelier/runner/proxy-assistant-dialog.component.{ts,html,scss}`
  - fonctions pures exportées : `proxyLookupCommands(platform)`, `normalizeProxyAddress(raw)`,
    `proxyDeclareCommands(platform, address)`, `integratedAuthCommands(platform, address, url)`
  - signaux : `step`, `proxyAddress`, `authVerdict` ; calculés : `resolvedAddress`,
    `addressInvalid`, `lookupCommands`, `declareCommands`, `authCommands`
- `RunnerPairingDialogComponent` — un bouton par branche en échec, et l'ouverture du dialogue
  (`MatDialog`), en passant le verdict et le système consulté.
- Aucun service, aucun modèle partagé, aucune route.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | Aucun appel, aucun principal |
| Contexte tenant | Non | Aucune donnée lue ni écrite côté gateway |
| Plans / limites | Non | Aucun quota |
| Navigation / routing | Non | Un dialogue, aucune route ; le parcours de mise en service reste ouvert dessous |
| **Minuteurs du composant appelant** | **Oui, vérifié** | Le parcours (`RunnerPairingDialogComponent`) porte deux `setInterval` (compte à rebours, relevé d'état). L'assistant s'ouvre **par-dessus** sans le détruire : les deux minuteurs continuent et restent arrêtés dans son `ngOnDestroy`. Aucun minuteur n'est créé par l'assistant. |
| **Secrets** | **Oui** | Un mot de passe collé dans le champ d'adresse est retiré à la normalisation et n'est réaffiché nulle part ; aucune valeur ne quitte le navigateur |

---

## Plan de test

### Tests unitaires — frontend (fonctions pures)

- [ ] `proxyLookupCommands('windows')` renvoie `netsh`, `ProxyServer` et `AutoConfigURL`.
- [ ] `proxyLookupCommands('macos')` renvoie `scutil --proxy`.
- [ ] `proxyLookupCommands('other')` renvoie le geste générique et n'est jamais vide.
- [ ] `normalizeProxyAddress('px.corp:8080')` → `px.corp:8080`.
- [ ] `normalizeProxyAddress('http://px.corp:8080/')` → `px.corp:8080`.
- [ ] `normalizeProxyAddress('http://moi:secret@px.corp:8080')` → `px.corp:8080` et ne contient ni
      `moi` ni `secret`.
- [ ] `normalizeProxyAddress('px.corp')` → `px.corp` (port absent, laissé absent).
- [ ] `normalizeProxyAddress('  ')`, `normalizeProxyAddress('http://')`,
      `normalizeProxyAddress('px.corp:0')` et `normalizeProxyAddress('px.corp:99999')` → `null`.
- [ ] `integratedAuthCommands('windows', …)` emploie `curl.exe` ; les autres systèmes, `curl`.
- [ ] `integratedAuthCommands` renvoie `--proxy-negotiate` **puis** `--proxy-ntlm`, chacune avec
      `--proxy-user :`.

### Tests unitaires — frontend (composant)

- [ ] Ouvert avec le verdict `proxy-auth`, l'assistant ouvre l'étape B ; avec `no-answer` ou sans
      verdict, l'étape A.
- [ ] Une adresse saisie apparaît dans les commandes de test et de déclaration.
- [ ] Une adresse inexploitable affiche le message et laisse le marqueur `hote:port`.
- [ ] Aucune conclusion n'est rendue tant que `authVerdict` vaut `unknown`.
- [ ] Déclarer `negotiate` rend la conclusion « relais local » et la mention SSPI / 8u111.
- [ ] Déclarer `refused` rend la conclusion « demande DSI » et **aucune** commande d'installation.
- [ ] Le retour au diagnostic remet `authVerdict` à `unknown`.

### Tests unitaires — frontend (parcours de mise en service)

- [ ] La branche `407` porte le bouton d'ouverture de l'assistant, et le clic ouvre le dialogue avec
      le verdict `proxy-auth`.
- [ ] La branche « aucun code » porte le bouton, et le clic passe le verdict `no-answer`.
- [ ] La branche `200` ne porte **aucun** bouton d'assistant.
- [ ] Le parcours n'est pas fermé par l'ouverture de l'assistant.

### Isolation utilisateur

- [x] **Sans objet** : aucune donnée n'est lue ni écrite côté gateway, aucun appel n'est émis.

---

## Dépendances

### Subfeatures bloquantes

- `SF-45-01` (contrôle d'accès réseau et arbre de lecture) — **done**
- `SF-45-03` (fiche DSI) — **done**, référencée et non réécrite
- `SF-45-05` (parcours guidé, branches du diagnostic) — **done**, point d'accroche

---

## Décisions (arbitrages tracés)

| # | Décision | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| **D1** | L'assistant est un **second dialogue**, ouvert par-dessus le parcours, qui ne le ferme pas | Le code d'appairage expire en 5 minutes et le parcours porte l'avancement ; le fermer pour aller chercher un proxy le ferait perdre | Une étape de plus dans le parcours (déjà 715 lignes de gabarit) ; un écran à part (le parcours serait quitté) | Oui |
| **D2** | L'adresse du proxy est **saisie**, puis **compose** toutes les commandes | Le navigateur ne peut pas la lire (bac à sable) ; une fois trouvée, la retaper dans six commandes est le meilleur moyen de se tromper une fois | N'afficher que le marqueur `hote:port` et laisser l'utilisateur substituer | Oui |
| **D3** | Le test d'authentification passe par `-x http://<adresse>` plutôt que par l'environnement | Le cas « absence de route » est précisément celui où rien n'est déclaré dans le terminal : un test qui dépend de l'environnement n'y testerait rien | Reprendre la forme du banc d'essai, qui suppose `HTTPS_PROXY` déjà posé | Oui |
| **D4** | `--proxy-user :` — identifiants **vides** | C'est ce qui demande à `curl` d'employer la session ouverte du poste. Demander un identifiant ferait entrer un secret dans un écran qui n'a aucune raison d'en voir un | Un champ « identifiant / mot de passe » dans l'assistant | Non — c'est une règle de sécurité |
| **D5** | `407` et absence de route sont **séparés**, avec deux remèdes nommés | Les deux se lisent « ça ne passe pas » et se soignent à l'opposé ; les confondre fait installer un relais pour rien, ou attendre la DSI pour dix secondes de `export` | Un seul remède générique « configurez votre proxy » | Oui |
| **D6** | Le **motif** accompagne chaque conclusion (pas de SSPI dans la JVM, `Basic` désactivée depuis 8u111) | Sans lui, l'utilisateur cherche l'option du runner qui ferait passer NTLM — elle n'existe pas, et il la cherche longtemps | Ne donner que le geste | Oui |
| **D7** | Les identifiants inline d'une adresse collée sont **retirés** à la normalisation | Un `http://moi:secret@px:8080` copié depuis un fichier de configuration ne doit ressortir dans aucune commande affichée, ni dans aucune capture d'écran de support | Recopier l'adresse telle quelle dans `-x` | Non — c'est une règle de sécurité |
| **D8** | Le fichier PAC est **ouvert par l'utilisateur dans son navigateur**, jamais par la page | La page n'y a pas accès depuis son bac à sable, et interpréter du JavaScript de configuration d'entreprise n'est pas le métier d'une passerelle | Télécharger et évaluer le `.pac` côté écran, ou côté gateway | Oui |
