# Mini-spec — F-137 / SF-137-01 — Les faits qui répondent à la question

## Identifiant
`F-137 / SF-137-01` — feature parente `F-137`

## Objectif
Qu'une question portant sur un composant **déjà connu** de l'infrastructure du client reçoive sa
réponse **sans aucun appel d'outil**.

## Ce que F-136 ne fait pas
Le sommaire dit **où** chercher — *« acces.md, sections Bastions, Coffres »* — mais ne contient
aucune réponse. L'agent sait qu'il sait ; il doit encore ouvrir le fichier, donc dépenser un tour de
boucle. F-137 lui met **les faits utiles** sous les yeux avec la question.

## La simplification par rapport au cadrage
Le cadrage annonçait « un index des faits par entité ». **Aucune table d'index n'est créée**, et
c'est délibéré : F-136 range déjà le **contenu** des fichiers de carte en base. Une recherche se fait
donc sur quelques centaines de kilo-octets déjà chargés — quelques millisecondes — sans second
stockage à tenir synchronisé. Une table d'index qui diverge du contenu qu'elle indexe est un défaut
silencieux ; ne pas la créer supprime la classe entière de défauts.

Point de bascule assumé et écrit : au-delà de ~20 000 faits (≈ 3 Mo par poste), il faudra un index
persistant. On en est à 2 593.

## Comportement attendu
1. À l'ouverture d'un tour, les **termes distinctifs** de la demande sont repérés : noms propres
   d'infrastructure, domaines, identifiants techniques — `lzi`, `CyberArk`, `portal.example.com`,
   `claude-gateway`.
2. Les **faits** de la carte du poste qui mentionnent ces termes sont joints à la **consigne du
   tour**, avec leur fichier d'origine.
3. Le message **persisté** reste la parole de l'utilisateur : seule la consigne **envoyée** est
   augmentée (patron de F-115 / SF-115-03).
4. Aucun terme reconnu, ou aucun fait correspondant ⇒ **rien n'est ajouté**.

### Pourquoi dans le message et non dans la consigne système
Ces faits dépendent de la **question** : ils changent à chaque tour. Placés dans la consigne
système, ils invalideraient le cache du préfixe à chaque demande — le défaut corrigé par F-134.
Placés dans le **dernier message**, ils n'invalident rien.

| Cas d'erreur | Comportement |
|---|---|
| Magasin vide ou en panne | rien n'est ajouté, le tour se déroule comme avant |
| Question sans terme reconnaissable | rien n'est ajouté |
| Terme trop courant (« serveur », « accès ») | ignoré : il ramènerait toute la carte |
| Beaucoup de faits correspondants | bornés, les plus **spécifiques** d'abord |

## Critères d'acceptation
- [ ] Une question citant un terme présent dans la carte reçoit les faits correspondants dans sa consigne.
- [ ] Le message **persisté** est inchangé — vérifié par test.
- [ ] Un mot courant ne déclenche rien.
- [ ] Le bloc est **borné** en nombre de faits et en caractères.
- [ ] **Isolation** : seuls les faits du poste **du tour** peuvent être joints.
- [ ] Rien dans la **consigne système** ne change (le cache du préfixe reste intact).
- [ ] Magasin absent ou en panne ⇒ aucun ajout, aucun échec.
- [ ] Un fait joint porte **son fichier d'origine**, pour que l'agent puisse y retourner.

## Hors scope
Une table d'index persistante · la péremption des faits (**F-139**) · tout écran.

## Technique
| Classe | Changement |
|---|---|
| **`HostFactLookup`** *(nouveau)* | termes distinctifs d'une question, et faits correspondants |
| `HostKnowledgeSource` | `factsFor(userId, workspaceId, question)` |
| `HostMapKnowledgeProvider` | l'implémente sur le magasin |
| `AtelierChatService` | joint le bloc à la **consigne du tour** |

Aucune table, aucune migration.

## Plan de test
- [ ] Un terme connu ramène ses faits ; un mot courant, rien.
- [ ] Le bloc est borné et priorise les termes rares.
- [ ] Le message persisté n'est pas modifié.
- [ ] La consigne système ne change pas.
- [ ] Isolation : les faits d'un autre poste ne remontent jamais.
- [ ] Magasin en panne ⇒ aucun ajout, aucun échec.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | La recherche part du `hostId` **du tour** et filtre `user_id` ; elle n'accepte aucun identifiant venu de l'appelant. Le test d'isolation de SF-136-02 est complété par le sien. |
| **Plans / limites** | **oui** *(indirect)* | La consigne du tour grossit de quelques centaines de caractères au plus. Le compteur de contexte (F-117) mesure ce qui est envoyé : il en tient compte sans changement. Le bloc est borné pour que cela reste vrai. |
| Navigation / routing | non | — |
