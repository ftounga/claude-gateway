# Cadrage F-204 — Notifications actives par défaut, sans bannière

**Demande PO (2026-10-15)**, livraison autonome (vague). Constat vérifié sur `origin/main` 6e16f2c8 et en prod. Justesse avant coût ; charte `DESIGN_SYSTEM.md` (aucune couleur nouvelle) ; préfixe système stable (rien de volatil).

## Besoin
La bannière « Activer les notifications » est trop envahissante. Je préfère que ce soit activé par défaut et que la bannière disparaisse.

## Existant
- Bannière `terminal-push-invite.component.ts` (insérée `atelier-terminal.component.html:1477`), réapparaît sur les tours > 20 s.
- Préférences serveur déjà actives par défaut (`notification_preferences` en désinscription volontaire).
- Ce qui manque : l'abonnement Web Push de chaque appareil. **Contrainte navigateur** : la permission exige un geste utilisateur quand elle vaut `default` (iOS : PWA installée).

## Décisions
- **Supprimer la bannière.**
- Permission déjà `granted` ⇒ abonnement **silencieux** au démarrage (et réabonnement si l'abonnement a disparu).
- Permission `default` ⇒ la demande navigateur est greffée **une seule fois** sur un geste existant (premier « Envoyer » d'un tour, ou ouverture d'un terminal par clic), sans bannière ; refus ⇒ on ne redemande plus (réactivable dans Paramètres > Notifications).
- `denied` ⇒ rien ; Paramètres explique comment réautoriser.
- Paramètres > Notifications inchangé (désactivation possible).

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-204-01 | Retrait de la bannière, abonnement silencieux si `granted`, demande unique sur geste existant si `default`, tests | 🟢 |
