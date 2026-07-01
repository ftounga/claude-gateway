# CODING_RULES.md

## 1. Purpose
Ce document définit les standards d'ingénierie utilisés dans Claude Gateway. Chaque fichier source doit s'y conformer. En cas de choix d'implémentation possibles, ces règles prévalent sauf mention explicite contraire de `PROJECT.md`. Objectif : produire un logiciel maintenable pendant de nombreuses années.

## 2. General Principles
Le code doit toujours être : simple, lisible, prévisible, testable, maintenable, production-ready. Éviter les implémentations astucieuses. Préférer l'explicite à l'implicite. La lisibilité future prime sur le nombre de lignes.

## 3. Naming Conventions
Les noms décrivent clairement l'intention métier ; éviter les abréviations.

- **Bon** : `ConversationService`, `BillingService`, `UserRepository`, `SubscriptionController`.
- **À éviter** : `ConvSvc`, `Utils2`, `ServiceImpl2`, `TempManager`.

Chaque nom de classe communique immédiatement sa responsabilité.

## 4. Layer Responsibilities
- **Controllers** — uniquement : validation de la requête, contexte d'authentification, appel des services applicatifs, retour des réponses. Jamais de règles métier.
- **Services** — décisions métier, workflows applicatifs, validation des contraintes métier, coordination entre repositories et providers. La logique métier vit ici.
- **Repositories** — uniquement la persistance. Jamais de logique métier ni d'appel à des services externes.
- **Providers** — uniquement la communication avec des systèmes externes (Anthropic, Stripe). Isolés de la logique métier.

## 5. Dependency Rules
Les dépendances pointent toujours vers des abstractions. Les modules métier ne dépendent jamais directement de l'infrastructure ; l'infrastructure implémente des interfaces définies par l'application. Cela permet le remplacement futur des fournisseurs sans changer la logique métier.

## 6. Error Handling
Les erreurs doivent être explicites, journalisées, actionnables. Ne jamais exposer de détails d'implémentation internes aux utilisateurs. Les exceptions inattendues sont converties en erreurs applicatives signifiantes.

## 7. Logging
Le logging aide les opérateurs à comprendre le comportement du système. Ne jamais journaliser : mots de passe, clés API, tokens d'accès, contenu de documents personnels, secrets d'authentification. Les logs se concentrent sur la visibilité opérationnelle.

## 8. Testing
Chaque fonctionnalité métier inclut des tests automatisés. Priorités : services métier, intégrations providers, API REST, scénarios end-to-end. Le code sans test est considéré incomplet, sauf justification explicite.

## 9. Security
Les exigences de sécurité s'appliquent à chaque implémentation. Chaque endpoint vérifie : authentification, autorisation, validation des entrées. Les secrets n'apparaissent jamais dans le code source ; la configuration sensible est externalisée.

## 10. Documentation
Chaque décision architecturale significative est documentée. Les API publiques restent documentées. La documentation évolue avec l'implémentation. Une documentation obsolète est considérée comme un défaut.

## 11. AI Coding Guidelines
L'IA doit toujours :
1. Lire `PROJECT.md` avant d'implémenter une nouvelle fonctionnalité.
2. Respecter `ARCHITECTURE.md`.
3. Suivre ces règles de code.
4. Éviter les dépendances inutiles.
5. Préférer les modules existants à la création de nouveaux.
6. Garder les implémentations petites et focalisées.
7. Expliquer les décisions architecturales importantes lors de propositions de changement.

L'IA optimise la maintenabilité long terme plutôt que la vitesse de génération de code.

## 12. Definition of Good Code
Un bon code : résout le problème métier ; respecte l'architecture ; est facile à comprendre, tester et modifier ; est sécurisé ; est observable ; peut rester en production des années sans devenir un fardeau de maintenance. Chaque implémentation laisse le projet dans un meilleur état qu'avant.
