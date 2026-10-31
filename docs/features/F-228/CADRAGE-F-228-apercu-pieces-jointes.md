# CADRAGE F-228 — Aperçu des pièces jointes

**Statut :** cadrage du 2026-10-31 (issu des audits du 31/10), à compléter par l'agent de livraison (découpage en SF)

**Demande PO (2026-10-31)** : « En survolant ou en cliquant une pièce que j'ai posée dans un fil (Excel, PDF, photo), ouvrir une pop-up d'aperçu. »

## Existant (audit)
- Les pièces sont des puces dans la bulle (F-169-03 : nom et taille), mais ce sont des `<span>` inertes (`atelier-terminal.component.html:816-826`).
- Le lien pièce ↔ message est durable : `atelier_deposited_files.message_id`.
- Le fichier vit **sur le poste**, dans `.atelier/entrees/`.
- `ProjectFileRead` lit déjà des octets binaires (poste ou hébergé).
- Un zoom d'image existe (`proof-captures`, F-212).
- Aucune bibliothèque xlsx, pdf ou docx dans le front.

**Usage mesuré** : png 216, zip 9, pdf 8, xlsx 7, docx 6, m4a 4, mp4 2, csv 2. Environ 85 % sont des captures.

## Périmètre (au clic, pas au survol : le survol déclencherait une lecture sur le poste et ne marche pas sur mobile)
1. **Backend** : `GET /api/workspaces/{id}/deposits/{depositId}/content`.
   - Ne sert que les fichiers inscrits dans `atelier_deposited_files` du couple (`user_id`, `workspace_id`) : jamais un chemin libre.
   - Passe par `ProjectFileRead`, avec un plafond d'environ 20 Mo.
   - Poste hors ligne : erreur explicite.
2. **Front** : la puce devient cliquable.
   - **Image** : boîte de zoom (réutiliser F-212), avec [Copier] et [Télécharger].
   - **PDF** : lecteur natif du navigateur (blob).
   - **Vidéo et audio** : `<video>` / `<audio>`.
   - **SVG** : uniquement en `<img>`.
   - **Autres types** : [Télécharger].
3. **Tableurs** : csv (parseur maison) et xlsx (bibliothèque chargée **au clic seulement**, par exemple SheetJS), en lecture seule, premier onglet avec sélecteur d'onglets, 500 lignes au plus.
4. **Hors périmètre** : docx (6 dépôts), à reconsidérer sur mesure.

**Sécurité** : jamais de HTML brut. Provider-First et F-04 respectés (aucun OCR, aucune indexation). `DESIGN_SYSTEM.md` respecté, mobile compris.
