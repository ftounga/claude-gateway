# Cadrage — F-55 — Assistant proxy dans l'application

## Déclencheur

Le proxy d'entreprise n'est pas un cas limite : **il est le parcours principal**. Neuf postes
d'entreprise sur dix sortent par un proxy, et la première mise en service réelle
(2026-09-07, client bancaire) y a laissé l'essentiel de ses trois heures.

**F-45 a posé le diagnostic** : vérifier l'accès *avant* d'installer, lire le code de retour, nommer
ce qui refuse, et écrire la demande à la DSI. Elle s'arrête où commence le remède : elle dit
« un relais local — `px`, `cntlm` », et l'utilisateur reste seul devant cette phrase.

**F-55 pose le remède, au geste près.** Le contenu de référence est le volet proxy du protocole de
banc d'essai (`docs/features/F-38/BANC-ESSAI-RUNNER-2026-09-10.md`), écrit d'après le diagnostic
réel : il tient en deux pages parce qu'il a été vécu, pas imaginé.

## Ce que fait F-55

Un **assistant** dans l'application, ouvert depuis les deux branches en échec du parcours de mise en
service, qui conduit d'un proxy inconnu jusqu'à un runner qui passe :

1. **retrouver l'adresse du proxy** selon le système — `netsh` / `reg` **et le fichier PAC** sous
   Windows, `scutil` sous macOS, l'environnement sous Linux —, là où le diagnostic de F-45
   s'arrêtait à une seule commande ;
2. **distinguer un `407` d'une absence de route** — deux pannes qui se ressemblent à l'écran et
   **n'ont pas le même remède** : l'une se lève avec un relais, l'autre avec une simple déclaration
   dans le terminal ;
3. **tester l'authentification intégrée** (`--proxy-negotiate`, puis `--proxy-ntlm`) : c'est ce test
   qui dit si un relais local peut réussir, ou si la demande part à la DSI ;
4. **installer, configurer et lancer un relais local** — `px` sous Windows et Apple Silicon,
   `cntlm` ailleurs, **mot de passe haché, jamais en clair** ;
5. **le vérifier** — un `curl` à travers `127.0.0.1` — **avant** d'y rediriger le runner.

Et, à chaque étape, **le motif** : `java.net.http.HttpClient` n'a aucun support SSPI, et
l'authentification `Basic` est désactivée sur les tunnels `CONNECT` depuis Java 8u111. `curl` et le
navigateur s'authentifient avec la session Windows ; **la JVM, jamais** — aucune version du runner
n'y changera rien. Un utilisateur qui comprend cela cesse de chercher la case à cocher qui n'existe
pas.

## Ce que F-55 ne refait pas

F-45 est **acquise** et n'est pas réécrite : le contrôle d'accès avant installation (SF-45-01), la
cohérence Windows de la commande (SF-45-02), la fiche « Pour votre DSI » (SF-45-03), la
reconnaissance du `407` par le contrôle de vol (SF-45-04) et le parcours guidé (SF-45-05) restent
tels quels. L'assistant **s'y branche** ; il ne les duplique pas, et renvoie à la fiche DSI plutôt
que d'en réécrire le contenu.

## Découpage

| SF | Objet | Portée |
|----|-------|--------|
| **SF-55-01** | L'assistant : **retrouver l'adresse du proxy** (PAC compris) et **qualifier ce qui refuse** — `407` contre absence de route, puis le test de l'authentification intégrée | Frontend |
| **SF-55-02** | **Le relais local** : installer, configurer (`px` / `cntlm`, mot de passe **haché**), lancer, **vérifier**, puis y rediriger le runner — avec le piège du séparateur `NO_PROXY` | Frontend |
| **SF-55-03** | Le runner **accepte un `NO_PROXY` à la Windows** (`;`) et nomme le séparateur attendu | Runner |

## Cohérence de périmètre (vérifiée avant dev)

| Point | Verdict |
|-------|---------|
| Feature référencée dans `docs/PRODUCT_SPEC.md` | Oui — ligne F-55, cadrée le 2026-09-10 (commit `8bfe49f`) |
| Périmètre (`docs/PROJECT.md`, ADR-011) | Oui — aucun OCR/RAG/pgvector, aucune capacité IA, aucun multi-LLM |
| Gateway-First | Oui — l'application **explique et compose des commandes** ; rien n'est exécuté à la place du poste |
| Provider Independence (`AIProvider`) | Sans objet — aucun appel fournisseur |
| Isolation `user_id` | Sans objet — aucune donnée utilisateur nouvelle ; l'assistant ne lit ni n'écrit rien côté gateway |
| Nouvelles tables / migration Liquibase | **Aucune**, sur les 3 SF |
| Nouvel endpoint backend | **Aucun** — F-55 est écran + runner |
| V3 / multi-LLM runtime | Non concerné |

## Hors périmètre (F-55 entière)

- **Embarquer un relais dans le produit** : livrer `px` ou `cntlm` avec le runner ferait du produit
  un composant réseau du poste, à maintenir et à faire auditer, pour un besoin qui se lève en une
  ligne côté DSI.
- **Porter NTLM / Kerberos nous-mêmes** : ce serait manipuler les identifiants de session de
  l'utilisateur. Ni le produit ni le runner ne verront jamais un mot de passe de domaine.
- **Exécuter la configuration réseau du poste** : l'assistant **compose** les commandes, il ne les
  lance pas — ni installation, ni écriture de registre, ni variable d'environnement posée d'office
  (limite déjà assumée par F-45, conservée).
- **Détecter le proxy depuis le navigateur** : impossible (bac à sable web). L'assistant demande,
  guide et interprète.
- **Saisir, transporter ou stocker un mot de passe de domaine** : l'assistant montre comment
  produire un **haché** (`cntlm -H`) sur le poste, et rien de ce qui est saisi dans l'écran ne
  quitte le navigateur.
- **Le proxy à identifiants applicatifs** (compte de service dédié au runner) : exception DSI,
  hors produit — déjà tranché par F-45.
