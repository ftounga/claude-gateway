# Mini-spec — [F-271 / SF-271-02] La doctrine orale du parcours

## Identifiant
`F-271 / SF-271-02`

## Feature parente
`F-271` — Jarvis lance le travail (cadrage `CADRAGE-F-271-jarvis-lance-le-travail.md`, D4 + D5, maillons M3 et M5)

## Statut
`in-progress`

## Date de création
2026-11-21

## Branche Git
`feat/SF-271-02-doctrine-orale`

---

## Objectif
Un tour Opus né à la voix sait enchaîner « ticket → chantier → plan », reformuler puis confirmer avant de valider ou de
lancer, et valider à l'oral ce qui attendait un clic (amendement, étape externe ajoutée) — sans jamais aller au-delà de
la demande.

## Comportement attendu

### Cas nominal
1. `JarvisOralNote.TEXT` passe en **v5** (texte figé, joint **au message** d'un tour né à la voix, jamais à la consigne
   système). Paragraphe ajouté, « Parcours d'un ticket à la voix » :
   - « regarde les tickets que je peux prendre », « qu'est-ce que demande LDIC-325 » : **lecture**, faite directement ;
     la ligne 🎙 nomme les clés et un mot d'état (prêt, attend Adam) ;
   - « prends LDIC-325 », « pose-moi le plan » : la demande **vaut accord** pour ouvrir et planifier —
     `propose_guided_mode` avec `ticket` et `user_words` (ses mots), investigation, diagnostic, `confirmer_diagnostic`
     avec ses mots, `set_subject_plan` ; **arrêt** là ; la ligne 🎙 dit le nombre d'étapes et l'étape externe éventuelle ;
   - « valide », « lance », « valide et lance » : **n'agis pas** ; la ligne 🎙 reformule en nommant le ticket, la
     version du plan et jusqu'où ira la course, et finit par « Vous confirmez ? » ; au « oui » du tour suivant :
     `valider_plan` (citation = ce oui) si le plan attend sa validation, puis `lancer_chantier_autonome` — **une seule**
     confirmation pour les deux ; tu n'exécutes pas les étapes toi-même ; ligne 🎙 « C'est parti. » ;
   - départ refusé (préflight rouge, limite, poste éteint) : la ligne 🎙 dit **ce qui manque** (« il manque… »), rien
     n'est relancé ;
   - un amendement qui attend sa revalidation ou une étape externe ajoutée se valide aussi **à l'oral** : nomme-le
     (version, étape), « Vous confirmez ? », puis `valider_plan` au « oui » ; ne jamais dire qu'il « attend votre clic » ;
   - « où en est LDIC-325 ? » : lire le plan (`lire_plan_chantier`), répondre en une idée (étapes faites / total, blocage) ;
   - jamais d'initiative au-delà de la demande ; jamais un message à un tiers (inchangé).
2. `JarvisOralNote.VERSION` = `5`.
3. La consigne système d'un tour né à la voix reste **identique à l'octet** à celle d'un tour tapé du même terminal
   (cache F-134) ; le message rangé dans le fil reste la parole de l'utilisateur.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Tour tapé (terminal) | aucune consigne orale (inchangé) |
| Tour sans utilisateur (course, routine) | aucune consigne orale ; `valider_plan` / ouverture refusés (inchangé) |
| « oui » sans question armée côté écran | chemin normal du cerveau rapide (F-270, inchangé) |
| Citation absente ou inventée | outils refusent (SF-271-01, F-222) — la doctrine ne contourne rien |

## Critères d'acceptation
- [ ] Un tour né à la voix reçoit la v5 dans le message (`lancer_chantier_autonome`, `propose_guided_mode`, « Vous
      confirmez ? », « il manque », « attend votre clic » proscrit).
- [ ] La consigne système d'un tour né à la voix est identique à celle d'un tour tapé.
- [ ] Le fil garde la parole de l'utilisateur, pas la consigne.
- [ ] `VERSION` = `5`.
- [ ] Banc à vraie voix, 2 passages (boucle legacy) : pas de régression vs référence F-270 finale.

## Périmètre

### Hors scope (explicite)
- Le code de l'enchaînement « oui » → valider → lancer côté passerelle (SF-271-03) ; la course qui parle (SF-271-04).
- La consigne système, la liste d'outils d'Opus et celle du cerveau rapide : **inchangées** ici.

## Valeurs initiales
| Paramètre | Valeur |
|---|---|
| Version de la consigne orale | 5 |

## Contraintes de validation
Néant (texte figé).

## Technique

### Endpoint(s)
Aucun.

### Tables impactées
Aucune.

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
Aucun.

### Composants backend
`jarvis/JarvisOralNote` (texte + version).

## Plan de test

### Tests unitaires
- `AtelierChatServiceVoiceTurnTest` : v5 dans le message, système identique au tour tapé, fil = parole.

### Tests d'intégration
- Banc à vraie voix (2 passages legacy).

### Isolation workspace
Sans objet (aucune donnée lue ou écrite).

## Dépendances

### Subfeatures bloquantes
SF-271-01 (paramètres `ticket` / `user_words` de `propose_guided_mode`), mergée.

### Questions ouvertes impactées
Aucune.

## Préoccupations transversales
Aucune (ni auth, ni tenant, ni limites, ni navigation).

## Notes et décisions
- **D1** Le guide système (`JourneyToolCatalog.GUIDE`) garde ses « sans clic » : la doctrine orale précise, dans le message,
  qu'à l'oral le « oui » explicite vaut le clic — changer le guide aurait réécrit le préfixe de tous les tours.
