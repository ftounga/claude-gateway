# claude-gateway — Stratégie marketing (ciblage consultants)

## 1. Positionnement et message principal

**Positionnement** : un assistant LLM sécurisé et accessible par navigateur, hébergé par la
plateforme, destiné aux consultants qui, en mission, sont bloqués par les proxys/DSI et
recourent à des solutions bricolées (téléphone, mail, zip…).

**Message central** :
> « Accédez aux meilleurs LLM depuis votre navigateur en mission — simple, sécurisé, traçable.
> Pas besoin d'envoyer des docs par mail ou d'utiliser votre téléphone. Un outil prêt à l'emploi. »

**Ton & éthique** : ne pas promouvoir le contournement non autorisé ; promouvoir la transparence,
l'option BYOK, l'hébergement privé et la possibilité d'allowlist via les équipes IT.

## 2. Audience & personas

- **Persona A — Consultant indépendant (freelance)** : productivité/rapidité en mission, confidentialité, prix bas. Canaux : LinkedIn, communautés Slack, forums freelances.
- **Persona B — Cabinet boutique (2–20 consultants)** : intégration, templates (audit, rapport), support équipe. Canaux : contacts directs, partenariats, événements fermés.
- **Persona C — Développeur freelance / DevOps** : debugging rapide, scan de projets, génération de patchs. Canaux : GitHub, Discord, Slack de devs.

## 3. Tactiques (ne pas cibler DSI ni publicité grand public)

**Principes** : éviter la pub grand public et toute action visible des DSI (risque de blocage). Cibler les canaux privés et personnels des consultants.

**Canaux prioritaires** : réseaux d'individus (LinkedIn personnalisé) → communautés fermées (Slack/Discord/Telegram) → bouche-à-oreille (programme ambassadeur/referral) → marketplaces freelance (Malt, Upwork) → webinars privés sur invitation (10–20 pers.) → contenu SEO long-tail discret → paid ads très ciblés (optionnel).

**Lancement (90 jours)** :
- Sem 0–2 : page produit discrète, one-pager, démo vidéo courte, onboarding.
- Sem 2–4 : pilote privé 10–20 consultants (3–4 semaines gratuites), feedback & cas d'usage.
- Sem 4–6 : programme referral + témoignages anonymisés.
- Sem 6–12 : scale via outreach LinkedIn, partenariats, marketplaces.

## 4. Pricing & packaging

> **La grille tarifaire vit dans [`docs/TARIFS.md`](TARIFS.md) — source de vérité unique.**
> Ce document n'en recopie aucun montant : deux grilles publiées finissent en litige commercial le
> jour où un client cite celle qui l'arrange. C'est exactement ce qui s'était produit ici (F-64).

**Structure de l'offre** (les montants, quotas et durées sont dans [`TARIFS.md`](TARIFS.md)) :

- **Hosted** — la plateforme fournit les jetons : trois paliers (Solo, Pro, Gold), au mois ou à
  l'année, avec une allocation mensuelle de tokens et des **recharges** à l'unité en cas de
  dépassement.
- **BYOK** — le client apporte sa clé Anthropic : la plateforme seule est facturée, la consommation
  IA reste sur son compte fournisseur.
- **Option Atelier** — souscrite en supplément d'un plan Solo ou Pro, elle ouvre l'Atelier sans
  changer le quota. Gold et BYOK l'incluent.
- **Essai gratuit** — durée et allocation dans [`TARIFS.md`](TARIFS.md) §4, qui signale par ailleurs
  un écart **non tranché** entre la durée annoncée publiquement et celle réellement appliquée.

## 5. Go-to-market playbook

1. **Assets** : one-pager (value props + pricing + how it works), landing consultants (CTA « Request trial »), démo 2 min.
2. **Pilote** : outreach personnel 10–20 consultants, pilote 3–4 semaines avec onboarding.
3. **Preuve** : métriques (temps gagné, sessions/mois, docs traités), 2–3 cas anonymisés.
4. **Scale** : referral (1 mois offert), outreach LinkedIn ciblé, partenariats marketplaces.
5. **Rétention** : updates régulières, webinars, tips mensuels, rapports d'usage in-app + estimation de coût.

## 6. Messaging (lignes clés)

- Objet : « Un assistant LLM sécurisé pour vos missions (test gratuit) »
- Accroche : « Je sais combien c'est galère d'utiliser votre téléphone ou d'envoyer des docs pour analyser chez un client — j'ai construit un outil qui résout ça, prêt à l'emploi. »
- Bénéfices : « sécurisé, éphémère, exportable, option BYOK, essai gratuit. »
  *(La durée de l'essai est dans [`TARIFS.md`](TARIFS.md) §4 — ne pas l'annoncer depuis ce document :
  la durée annoncée et la durée appliquée divergent aujourd'hui, l'écart est ouvert en OQ-16.)*

## 7. Positionnement éthique & légal

- Politique claire : « We do not condone bypassing explicit client security policies. »
- Alternatives proposées : installs on-prem, procédure allowlist, BYOK.
- DPA & documents de confidentialité disponibles sur demande.

## 8. KPIs

- **Primaires** : trials démarrés, conversion trial→payant, ARPU, churn.
- **Opérationnels** : tokens/user, pages Textract/user, coût infra/user.
- **Croissance** : referrals/mois, LTV, CAC.

## 9. Risques & mitigations

- **Découverte publique → blocage DSI** : marketing ciblé canaux privés, instructions allowlist, option hébergement privé.
- **Usage détourné des politiques** : T&C clairs, disclaimers explicites, refus possible pour données sensibles.

## 10. Plan tactique 90 jours

- Sem 1–2 : assets, landing, invitations pilote.
- Sem 3–6 : pilotes, itération produit.
- Sem 7–10 : referral, montée en charge outreach.
- Sem 11–12 : mesure, A/B pricing, refinement messaging.
