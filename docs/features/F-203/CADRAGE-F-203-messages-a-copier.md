# Cadrage F-203 — Mails et messages Teams : un encart « Copier » qui garde la mise en forme

**Demande PO (2026-10-15)**, livraison autonome (vague). Constat vérifié sur `origin/main` 6e16f2c8 et en prod. Justesse avant coût ; charte `DESIGN_SYSTEM.md` (aucune couleur nouvelle) ; préfixe système stable (rien de volatil).

## Besoin
Je rédige souvent des mails ou messages Teams dans le terminal (prod 30 j : **95 réponses sur 988** contiennent un message rédigé, 24 un « Objet : »). Je veux un **encart** avec un bouton **Copier** qui copie avec la mise en forme (gras, italique, listes) pour coller directement dans Outlook ou Teams, sans sélectionner le texte.

## Existant
F-26 : `chat/copy-block/copy-block.component.ts` copie déjà en texte riche (`ClipboardItem` `text/html` + `text/plain`, repli `writeText`) et `shared/message-segments.ts:11` reconnaît les blocs ```` ```mail ````. **Absent du terminal de l'Atelier** : réponses rendues d'un bloc par `[innerHTML]="… | markdown"` (`atelier-terminal.component.html:854-867`, `1077-1090`), aucune doctrine « message à envoyer » côté Atelier.

## Décisions
- Doctrine stable de l'Atelier : tout mail ou message à envoyer (Teams, Slack, Jira…) est rendu dans un **bloc message** balisé (```` ```mail ```` / ```` ```message ````, objet facultatif en tête) — un bloc par message.
- Le terminal découpe les réponses en segments et rend ces blocs dans un **encart** (type, objet, corps mis en forme) avec **Copier** (texte riche + texte brut) ; feedback « Copié » ; le reste de la réponse inchangé. Réutiliser `copy-block`.
- Le HTML copié est propre pour Outlook/Teams (balises simples, pas de classes) ; le texte brut garde les retours à la ligne.
- Historique : les anciens messages ne changent pas (pas de rétro-découpage forcé).

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-203-01 | Doctrine stable « bloc message » côté Atelier, tests de présence + stabilité du préfixe | 🟢 |
| SF-203-02 | Terminal : segments + encart message avec Copier riche (réutilise copy-block), tests | 🟠 |
