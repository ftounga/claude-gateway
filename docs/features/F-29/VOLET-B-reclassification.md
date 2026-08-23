# F-29 — Volet B : demandes de reclassification auprès des éditeurs de filtrage

> **Hors code.** Tâche d'exploitation, à exécuter dans un navigateur : ces formulaires sont protégés
> par CAPTCHA et refusent les requêtes automatisées (vérifié : HTTP 403 sur les lookups en ligne de commande).
>
> **Prérequis remplis le 2026-08-23** : SF-29-01 (renommage) et SF-29-02 (signaux d'indexation)
> sont **déployées en production**. Soumettre avant aurait été contre-productif : les éditeurs
> re-crawlent le domaine à réception de la demande et auraient revu l'ancien contenu.

---

## État vérifié en production (2026-08-23, après F-29 complète)

| Élément | État |
|---------|------|
| `<title>` | `Claude Portal — passerelle professionnelle vers Claude` |
| Terme « proxy » / lexique de contournement | **0 occurrence** |
| Contenu lisible sans JavaScript | **807 caractères** dans `<app-root>` + `<noscript>` |
| Métadonnées | `meta description`, 6 balises Open Graph, Twitter card, lien canonique |
| `robots.txt` / `sitemap.xml` | 200, `text/plain` / `application/xml`, 7 URL |
| Pages légales | `/mentions-legales`, `/confidentialite`, `/cgu`, `/contact` — 200 |
| Éditeur identifié | NG-CONSULTING, SASU, R.C.S. Paris **995 322 450**, directeur de publication nommé |
| CGU art. 4 | **interdisent explicitement** le contournement de filtrage et le relais de trafic |
| Domaine racine | `https://www.ng-itconsulting.com/` — **200**, certificat Let's Encrypt valide, site corporate indexable (la page de parking `noindex` a disparu) |
| Apex `ng-itconsulting.com` | redirection HTTP vers `www` (limite connue : un apex ne peut pas porter de CNAME) |

## Texte de soumission (anglais — ces formulaires sont anglophones)

> **Domain:** portal.ng-itconsulting.com
>
> **Requested category:** Business / Technology (or Software as a Service / Web-based
> Applications, depending on the vendor's taxonomy)
>
> **Current category:** Anonymizer / Proxy Avoidance — this is a misclassification.
>
> **Comment:**
> portal.ng-itconsulting.com is a commercial B2B SaaS application operated by NG-CONSULTING,
> a French company registered with the Paris Trade and Companies Register under number
> 995 322 450. It provides authenticated professional access to Anthropic's Claude assistant
> for consultants and independent professionals.
>
> It is an application front-end with named user accounts, subscription billing and per-user
> data isolation. It is NOT an anonymization, proxy-avoidance or traffic-relaying service:
> users cannot browse arbitrary third-party websites through it, cannot hide their IP address,
> and cannot bypass any network control. All traffic terminates at our own application and,
> for AI requests, at Anthropic's public API endpoints. Our terms of service explicitly
> prohibit using the service to circumvent filtering or security controls, or to relay
> third-party traffic (section 4).
>
> We believe the earlier classification was triggered by our former product name, which
> contained the word "Proxy", and by the absence of crawlable content on a single-page
> application. Both have been corrected:
>   - the product was renamed "Claude Portal" and the word "proxy" no longer appears anywhere;
>   - the page now serves a full description without JavaScript, with meta description,
>     Open Graph tags, robots.txt and sitemap.xml;
>   - legal notices, privacy policy, terms of service and contact pages are published and
>     identify the publisher;
>   - our corporate website at https://www.ng-itconsulting.com/ is now online over HTTPS.
>
> Verifiable references:
>   - https://portal.ng-itconsulting.com/mentions-legales (company identification)
>   - https://portal.ng-itconsulting.com/cgu (acceptable use, section 4)
>   - https://www.ng-itconsulting.com/ (corporate site)
>
> Please re-crawl the domain and update its category.

**Si un champ « catégorie souhaitée » impose un choix unique**, privilégier dans l'ordre :
`Business` → `Information Technology` → `Software as a Service` → `Computers and Internet`.

**Soumettre aussi `www.ng-itconsulting.com`** chez les éditeurs qui classent séparément les
sous-domaines : le site corporate doit être catégorisé `Business`, ce qui renforce le domaine.

## Points de soumission

| Éditeur | Où soumettre | Notes |
|---------|-------------|-------|
| **Zscaler** | `sitereview.zscaler.com` | Jusqu'à 3 catégories proposées + commentaire + e-mail de suivi |
| **Netskope** | `netskope.com/url-lookup` | Bouton « Report Miscategorization » après recherche du domaine |
| **FortiGuard (Fortinet)** | `fortiguard.com/faq/wfratingsubmit` (ou `url.fortinet.net/rate/submit.php`) | Traitement annoncé sous ~24 h ; CAPTCHA |
| **Symantec / Blue Coat (Broadcom)** | `sitereview.bluecoat.com` | Base WebPulse ; « dispute the current categorization » |
| **Palo Alto Networks** | `urlfiltering.paloaltonetworks.com` | « Test a Site » puis demande de changement (PAN-DB) |
| **Cisco Talos** | `talosintelligence.com/reputation_center` | Recherche du domaine puis soumission d'une dispute |
| **Trellix (ex-McAfee)** | `trustedsource.org` | Customer URL Ticketing System |
| **Forcepoint** | `csi.forcepoint.com` | ThreatSeeker / URL category lookup |
| **Sophos** | `sophos.com` — formulaire « Sophos Web Intelligence / recategorization » | Via le support ou le formulaire public |

> Les URL ci-dessus ont été vérifiées le 2026-08-23. Ces portails évoluent : si l'une renvoie
> une 404, chercher « <éditeur> URL category recategorization request ».

---

## Méthode recommandée

1. **Constater d'abord** : sur chaque portail, rechercher le domaine et **noter la catégorie actuelle**.
   Un éditeur qui le classe déjà correctement n'a pas besoin d'être sollicité — et une demande
   inutile ajoute du bruit au dossier.
2. **Prioriser** les éditeurs dont on a la preuve qu'ils bloquent (ceux utilisés par les clients
   qui ont signalé le problème). Les autres peuvent être soumis dans un second temps.
3. **Renseigner une adresse e-mail de suivi** partout où le champ existe : c'est le seul moyen
   de savoir qu'une demande a abouti.
4. **Délais constatés dans l'industrie** : de 24 h (FortiGuard) à environ 5 jours ouvrés.
   Certains éditeurs ne notifient pas et se contentent de mettre à jour la base.
5. **Re-vérifier après une semaine** et relancer les éditeurs restés sur l'ancienne catégorie,
   en signalant la date de la première demande.

---

## Ce qui renforcerait encore le dossier

- **SF-29-03** (pages légales : mentions légales avec éditeur identifié, CGU, politique de
  confidentialité, contact). Un éditeur clairement identifié est l'un des critères que les
  analystes vérifient manuellement. **À livrer avant les relances.**
- **Un site vitrine sur le domaine racine** `ng-itconsulting.com`, qui **ne répond pas aujourd'hui**
  (vérifié). Un domaine dont seul un sous-domaine applicatif répond est un profil atypique qui
  pèse dans l'analyse. Projet distinct, hors F-29.

---

## Suivi des soumissions

| Éditeur | Catégorie constatée | Date de soumission | Réponse | Catégorie finale |
|---------|--------------------|--------------------|---------|-----------------|
| Zscaler | | | | |
| Netskope | | | | |
| FortiGuard | | | | |
| Symantec / Blue Coat | | | | |
| Palo Alto | | | | |
| Cisco Talos | | | | |
| Trellix | | | | |
| Forcepoint | | | | |
| Sophos | | | | |
