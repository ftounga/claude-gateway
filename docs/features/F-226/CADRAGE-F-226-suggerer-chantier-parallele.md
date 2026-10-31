# CADRAGE F-226 — Suggérer le chantier parallèle et clarifier les gestes

**Statut :** cadrage du 2026-10-31 (issu des audits du 31/10), à compléter par l'agent de livraison (découpage en SF)

**Demande PO (2026-10-31)** : « Quand je suis dans un chantier, je suis obnubilé. Suggère-moi graphiquement, joliment, de lancer en parallèle un autre chantier faisable. » Il veut aussi être sûr que le multi-chantier et le split soient faciles à utiliser.

## Constats (audit UX du 31/10, sur des captures)
- **Rien ne dit qu'un 2e chantier peut partir** : le badge « Chantiers 3 » compte les gestes attendus, pas les chantiers capables d'avancer seuls.
- **Le split se trouve mal** : aucun bouton « ouvrir à côté ». On y arrive par un lancement depuis le tableau, par la suite « Basculer sur le chantier N » (qui ouvre en réalité l'overlay *parallèle*), ou depuis le tableau du client. Cliquer un onglet « Chantier 2 » de la bande change de chantier sans ouvrir de volet : deux gestes presque identiques font deux choses différentes.
- **Vocabulaire dispersé** : Lancer en autonome, Avancer jusqu'au blocage, Lancer en parallèle, Ouvrir à côté, Basculer ici, Basculer sur le chantier N, Mettre au focus, Reprendre la main, Ouvrir.
- **Onglets tronqués** dans le tableau des chantiers (« Ch.1 R… », avec barre de défilement horizontale).
- **Onglets du split sur téléphone** sans titre ni état.
- En split, chaque volet répète la barre du haut et la bande des chantiers.
- **Pas de bouton Valider dans le tableau des chantiers** pour un plan à valider (seulement « plan à valider d'abord »).

## Périmètre
1. **Puce de suggestion**, sans pop-up, dans la barre d'état du bas : « ⚡ Ch.N peut avancer seul · [Lancer à côté] · ✕ ».
   - **Quand** : seulement aux moments creux (fin de tour où le chantier affiché attend le PO ou un tiers, juste après « Valider et lancer », ou pendant une course autonome du chantier affiché).
   - **Données** : un chantier non affiché, `unblocker.who === 'OUTIL'`, ne tournant pas, avec moins de 4 courses en cours. Données déjà dans le parcours, sans appel de plus.
   - **Clic** : l'overlay « Lancer en parallèle ? » existant.
   - **Retenue** : une suggestion à la fois ; ✕ la masque jusqu'à ce que l'état du chantier change ; elle se coupe dans le menu ⚙.
2. **Vocabulaire** ramené à trois verbes : **Afficher** (le focus), **Lancer à côté** (parallèle, split), **Reprendre la main**. La suite « Basculer sur le chantier N » devient « Lancer à côté » quand le chantier peut avancer seul, avec la pastille « qui débloque ».
3. Un bouton **Lancer à côté** explicite sur les onglets de la bande et les cartes du tableau. Un clic sur l'onglet = Afficher.
4. Onglets du tableau lisibles (titre court qui passe à la ligne ou ellipse avec info-bulle, sans défilement horizontal). Onglets du split sur téléphone avec titre et pastille d'état (« tourne », « à vous »).
5. **[Valider le plan]** dans le tableau des chantiers pour un chantier dont le plan attend, par numéro et sans changer de focus (`POST …/chantiers/{n}/plan/validate`, comme [Planifier] de SF-176-12).
6. Alléger le split : bande des chantiers commune aux deux volets, ou réduite dans le volet droit.

**Captures exigées** à 1440×900 et 390×844 ; contrastes AA (fond sombre du terminal).
