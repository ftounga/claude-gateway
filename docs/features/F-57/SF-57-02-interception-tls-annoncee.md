# Mini-spec — F-57 / SF-57-02 — L'interception TLS, annoncée sans être contournée

## Identifiant

`F-57 / SF-57-02`

## Feature parente

`F-57` — Transparence sur le poste de travail

## Statut

`done` — PR #335, mergée le 2026-09-10

## Date de création

2026-09-10

## Branche Git

`feat/SF-57-02-interception-tls`

---

## Objectif

Quand la chaîne TLS présentée par la gateway repose sur une racine **non publique**, le runner
l'annonce au démarrage — c'est un diagnostic proxy utile et honnête ; il ne contourne rien.

---

## Comportement attendu

### Cas nominal

Après le contrôle de vol réseau (SF-38-25), et **seulement s'il a réussi**, le runner regarde la
chaîne de certificats que la gateway a présentée.

1. Il lit les **racines publiques** : celles du magasin livré avec le JDK
   (`$JAVA_HOME/lib/security/cacerts`).
2. Il parcourt la chaîne du serveur. Si **un** certificat de la chaîne est lui-même une de ces
   racines, ou si l'émetteur du dernier certificat en est une → chemin public, **rien n'est dit**.
3. Sinon → la chaîne a été **re-signée** par un équipement du réseau. Le runner l'affiche :

```
TLS       : le trafic vers portal.ng-itconsulting.com est dechiffre et re-signe par un
            equipement du reseau — racine « Acme Corp Proxy CA », absente des racines
            publiques livrees avec Java.
            C'est le fonctionnement normal d'un proxy d'inspection d'entreprise. Le runner
            l'affiche pour le diagnostic ; il ne le contourne pas et ne relache aucune
            verification.
```

Quand la gateway est jointe en `http://` (profil local), ou quand la sonde échoue pour quelque
raison que ce soit, **rien n'est dit** : le silence est le comportement par défaut.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Gateway en `http://` | Aucune ligne. Il n'y a pas de chaîne TLS à regarder |
| La sonde TLS échoue (réseau tombé entre-temps, délai dépassé, proxy capricieux) | Aucune ligne, aucune erreur, aucun code de sortie modifié. Le démarrage continue |
| Le magasin `cacerts` est illisible | Aucune ligne : sans référence de racines publiques, on ne peut rien affirmer. Le silence plutôt qu'un faux positif |
| Chaîne vide ou tronquée par la couche réseau | Aucune ligne |
| Racine d'entreprise **ajoutée** au magasin du JDK par la DSI | Aucune ligne — faux négatif **assumé** (cadrage, décision 2) : mieux vaut se taire que crier à tort |

### Ce que le runner ne fait **jamais**

- Il ne désactive aucune vérification TLS, ne pose aucun `TrustManager` permissif, n'accepte aucun
  certificat qui aurait été refusé sans lui.
- Il n'ouvre aucun chemin réseau alternatif pour « éviter » l'inspection.
- Il ne transmet la chaîne, ni le nom de la racine, à la gateway.

La sonde est une **lecture**, sur une connexion qui suit exactement les mêmes règles que les autres.

---

## Critères d'acceptation

- [ ] Le verdict est rendu par une **fonction pure** prenant la chaîne (sujet/émetteur) et
      l'ensemble des racines publiques — testable sans réseau ni certificat réel.
- [ ] Chaîne rattachée à une racine publique → **aucun** message.
- [ ] Chaîne dont un maillon **est** une racine publique (cas des chaînes croisées) → **aucun**
      message.
- [ ] Chaîne rattachée à une racine absente des racines publiques → message nommant la racine.
- [ ] Le nom affiché est le **CN** de la racine, pas le distinguished name brut.
- [ ] Chaîne vide, racines vides, ou entrées malformées → **aucun** message, aucune exception.
- [ ] Le message dit explicitement que le runner **ne contourne pas** et **ne relâche aucune
      vérification**.
- [ ] Le message qualifie l'interception de **fonctionnement normal** d'un proxy d'entreprise — pas
      d'une attaque.
- [ ] Gateway en `http://` → la sonde n'est pas lancée.
- [ ] Une sonde qui échoue ne change ni le code de sortie, ni la suite du démarrage (test dédié).
- [ ] Aucun `TrustManager`, `HostnameVerifier` ou propriété système permissive n'apparaît dans le
      diff (**BLOQUANT** en review).

---

## Périmètre

### Hors scope (explicite)

- Contourner l'inspection, ou proposer de la contourner.
- Épingler un certificat (*pinning*) : la gateway est derrière un proxy chez la moitié des clients,
  épingler casserait ces postes.
- Remonter l'information à la gateway ou l'afficher dans l'application.
- Détecter quoi que ce soit **d'autre** que la chaîne de nos propres connexions.

---

## Valeurs initiales

Sans objet.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|---|---|---|---|---|---|
| Sujet / émetteur d'un maillon | Oui | — | Distinguished name RFC 2253 ; forme inattendue tolérée | Non | `trim()`, comparaison **insensible à la casse et aux espaces** |
| Nom affiché de la racine | Oui | — | `CN` extrait ; à défaut, le DN entier | Non | Guillemets français autour du nom |
| Délai de la sonde | Oui | — | 10 s de connexion, 10 s de lecture — aligné sur le contrôle de vol | Non | — |

Notes :
- Les DN sont comparés **normalisés** (casse, espaces autour des virgules) : deux encodages du même
  DN ne doivent pas produire un faux positif.
- La sonde emprunte **le même proxy** que le reste du runner (`ProxyResolver`).

---

## Technique

### Endpoint(s)

Aucun. La sonde fait un `GET` sur l'URL déjà utilisée par le contrôle de vol.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun.

### Classes runner

| Classe | Opération |
|---|---|
| `TlsInspection` (nouvelle) | Verdict pur + extraction du CN + message |
| `TlsProbe` (nouvelle) | Ouvre la connexion, lit la chaîne, ne lève jamais |
| `RunnerMain` | Affiche la ligne quand il y en a une |

---

## Plan de test

### Tests unitaires

- [ ] `TlsInspection` — chaîne rattachée à une racine publique → vide.
- [ ] `TlsInspection` — un maillon **est** une racine publique (chaîne croisée) → vide.
- [ ] `TlsInspection` — racine d'entreprise → racine nommée.
- [ ] `TlsInspection` — chaîne vide → vide.
- [ ] `TlsInspection` — ensemble de racines vide → vide (aucune référence, aucun verdict).
- [ ] `TlsInspection` — DN encodés différemment (casse, espaces) → aucun faux positif.
- [ ] `TlsInspection` — extraction du `CN`, y compris DN sans `CN`.
- [ ] `TlsInspection` — le message contient « ne le contourne pas » et « fonctionnement normal ».
- [ ] `TlsProbe` — URL en `http://` → aucun verdict, aucune connexion.
- [ ] `TlsProbe` — la lecture lève → aucun verdict, aucune exception propagée.

### Tests d'intégration

Sans objet : aucun endpoint. Le point d'intégration est `RunnerMain`, couvert par le fait que la
sonde rend un `Optional` vide en cas d'échec.

### Isolation workspace

- [x] Non applicable — raison : aucune donnée utilisateur n'est lue, écrite ni transmise. La sonde
      lit un certificat public présenté par un serveur public.

---

## Dépendances

### Subfeatures bloquantes

- `SF-38-25` (contrôle de vol réseau) — statut : done
- `SF-57-01` (bloc de transparence) — statut : done

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

| # | Décision | Pourquoi |
|---|---|---|
| D1 | Signal = racine **absente du magasin livré avec le JDK** | C'est la définition opératoire de « non publique » : une racine publique est dans le programme de racines embarqué. Une racine absente qui valide quand même prouve que la confiance a été ajoutée localement. |
| D2 | Faux **négatif** accepté, faux **positif** interdit | Une DSI qui a ajouté sa racine au magasin du JDK rendra la sonde muette. Tant mieux : un message qui crie à l'interception là où il n'y en a pas détruirait la confiance dans tous les autres messages du runner. |
| D3 | Un maillon qui **est** une racine publique suffit à se taire | Les chaînes croisées (racine héritée signée par une racine moderne) sont courantes ; ne regarder que l'émetteur du dernier maillon produirait des faux positifs sur des serveurs parfaitement publics. |
| D4 | La sonde est **séparée** du contrôle de vol | Le contrôle de vol décide si le runner démarre ; la sonde ne décide de rien. Les mêler ferait qu'un diagnostic optionnel pourrait faire échouer un démarrage. |
| D5 | Sondage via `HttpsURLConnection` avec le proxy résolu | `java.net.http.HttpClient` n'expose pas la chaîne du pair. `HttpsURLConnection` l'expose et sait faire le `CONNECT` à travers un proxy — c'est le seul chemin qui marche derrière un proxy d'entreprise, soit le cas qui nous intéresse. |
| D6 | Silence par défaut | Toute incertitude — magasin illisible, sonde en échec, chaîne tronquée — se résout par le silence. Le runner n'affirme que ce qu'il a constaté. |
