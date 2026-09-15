package fr.claudegateway.runner.teams;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Les droits d'un élément de drive SharePoint / OneDrive</b> (F-108 / SF-108-07).
 *
 * <p>Le relevé catalogue réel du 2026-09-16 (CAGIP,
 * {@code docs/features/F-100/releves/releve-teams-catalogue-complet-2026-09-16-cagip.json}) a donné la
 * forme des réponses de l'API {@code v2.1} qui portent, elles, les <b>droits</b> d'un fichier — ce que
 * les adaptateurs SF-108-03/05/06, écrits « sur documentation », ne consultaient pas :</p>
 *
 * <ul>
 *   <li>{@code /_api/v2.1/drives/{id}/items/{id}} : {@code webUrl}, {@code accessViewpoint} —
 *       {@code canRead}/{@code canEdit}/{@code canDelete}/{@code canComment}/<b>{@code canDownload}</b>/
 *       {@code canManagePermissions} ;</li>
 *   <li>{@code /_api/v2.1/drives/{id}/items/{id}/labelPolicies} : {@code irmCapabilities} —
 *       {@code canPrint}/<b>{@code canExtract}</b>/{@code canRead}/{@code canWrite} (protection IRM).</li>
 * </ul>
 *
 * <p><b>À quoi ça sert</b> : savoir si un fichier est téléchargeable <b>avant</b> de tenter — plutôt
 * que de lancer un téléchargement voué à échouer et de rendre un manque flou. {@link #downloadRefusal()}
 * nomme la raison : droits ({@code canDownload=false}) ou politique de protection
 * ({@code irmCapabilities.canExtract=false}).</p>
 *
 * <p><b>Rien n'est recopié en aveugle</b> (règle de l'adaptateur Teams, F-87 / SF-87-01) : chaque champ
 * est lu par son nom. Aucune adresse pré-authentifiée (par exemple {@code @content.downloadUrl}), aucun
 * jeton, aucun autre champ du corps ne peut franchir cette couche — seuls {@code webUrl} et des booléens
 * nommés en sortent. L'accès inconnu (objet {@code accessViewpoint} absent) n'est <b>pas</b> traité
 * comme un refus : on ne bloque que sur un {@code false} explicite.</p>
 *
 * @param webUrl                adresse web de l'élément (jamais une adresse signée), ou {@code ""}
 * @param hasAccessViewpoint    vrai si l'objet {@code accessViewpoint} était présent (droits connus)
 * @param canRead               {@code accessViewpoint.canRead}
 * @param canEdit               {@code accessViewpoint.canEdit}
 * @param canDelete             {@code accessViewpoint.canDelete}
 * @param canComment            {@code accessViewpoint.canComment}
 * @param canDownload           {@code accessViewpoint.canDownload}
 * @param canManagePermissions  {@code accessViewpoint.canManagePermissions}
 * @param hasIrm                vrai si {@code irmCapabilities} était présent (protection IRM connue)
 * @param irmCanPrint           {@code irmCapabilities.canPrint}
 * @param irmCanExtract         {@code irmCapabilities.canExtract} (extraction / téléchargement du contenu)
 * @param irmCanRead            {@code irmCapabilities.canRead}
 * @param irmCanWrite           {@code irmCapabilities.canWrite}
 */
public record SharePointItemAccess(String webUrl, boolean hasAccessViewpoint, boolean canRead,
        boolean canEdit, boolean canDelete, boolean canComment, boolean canDownload,
        boolean canManagePermissions, boolean hasIrm, boolean irmCanPrint, boolean irmCanExtract,
        boolean irmCanRead, boolean irmCanWrite) {

    /** Raison nommée d'un refus de téléchargement pour cause de droits. */
    public static final String DOWNLOAD_DENIED =
            "Microsoft 365 n'autorise pas le téléchargement de ce fichier "
                    + "(accessViewpoint.canDownload = false) : droits d'accès ou politique du tenant. "
                    + "Rien n'est tenté ; aucun contournement.";

    /** Raison nommée d'un refus pour cause de protection IRM (extraction interdite). */
    public static final String EXTRACT_DENIED =
            "Ce fichier est protégé (IRM) et son extraction est interdite "
                    + "(irmCapabilities.canExtract = false) : la politique du tenant empêche le "
                    + "téléchargement du contenu. Rien n'est tenté ; aucun contournement.";

    /**
     * Code machine rendu par le script de page quand le téléchargement est refusé par les droits —
     * la décision est prise <b>dans la page</b> (elle seule voit {@code accessViewpoint}), le libellé
     * français vit ici (source unique). Voir {@link SharePointFiles#driveItemAccess}.
     */
    public static final String CODE_DOWNLOAD = "canDownload=false";

    /** Code machine rendu par le script de page quand l'extraction IRM est refusée. */
    public static final String CODE_EXTRACT = "canExtract=false";

    public SharePointItemAccess {
        webUrl = webUrl == null ? "" : webUrl.strip();
    }

    /**
     * Lit les droits depuis les deux corps réels ({@code item} et, optionnel, {@code labelPolicies}).
     * Chaque champ est lu par son nom ; tout le reste est ignoré. Un corps {@code null} donne des
     * droits « inconnus » (ne bloque rien).
     */
    public static SharePointItemAccess parse(JsonNode item, JsonNode labelPolicies) {
        JsonNode viewpoint = item == null ? null : item.get("accessViewpoint");
        boolean hasViewpoint = viewpoint != null && viewpoint.isObject();
        JsonNode irm = labelPolicies == null ? null : labelPolicies.get("irmCapabilities");
        boolean hasIrm = irm != null && irm.isObject();
        return new SharePointItemAccess(
                TeamsJson.text(item, "webUrl"),
                hasViewpoint,
                TeamsJson.flag(viewpoint, "canRead"),
                TeamsJson.flag(viewpoint, "canEdit"),
                TeamsJson.flag(viewpoint, "canDelete"),
                TeamsJson.flag(viewpoint, "canComment"),
                TeamsJson.flag(viewpoint, "canDownload"),
                TeamsJson.flag(viewpoint, "canManagePermissions"),
                hasIrm,
                TeamsJson.flag(irm, "canPrint"),
                TeamsJson.flag(irm, "canExtract"),
                TeamsJson.flag(irm, "canRead"),
                TeamsJson.flag(irm, "canWrite"));
    }

    /**
     * La raison — nommée — pour laquelle ce fichier ne peut pas être téléchargé, ou {@code ""} s'il
     * n'y a pas d'empêchement <b>connu</b>. On ne bloque que sur un {@code false} explicite : droits
     * inconnus ({@code accessViewpoint} absent) → on n'empêche rien, la tentative dira le reste.
     */
    public String downloadRefusal() {
        if (hasAccessViewpoint && !canDownload) {
            return DOWNLOAD_DENIED;
        }
        if (hasIrm && !irmCanExtract) {
            return EXTRACT_DENIED;
        }
        return "";
    }

    /** Vrai si les droits connus permettent le téléchargement (ou si les droits sont inconnus). */
    public boolean downloadable() {
        return downloadRefusal().isEmpty();
    }
}
