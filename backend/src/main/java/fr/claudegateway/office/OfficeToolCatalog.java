package fr.claudegateway.office;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>Les outils qui construisent un document Word et un classeur Excel</b> (F-129 / SF-129-07) :
 * le fichier est fabriqué <b>par la gateway</b>, à partir d'une description — le poste du client
 * n'installe rien, exactement comme pour le deck (SF-129-05).
 *
 * <p><b>Deux outils, pas un</b> : un schéma dont le sens change selon un champ « format » se
 * remplit mal ; deux schémas nets se remplissent bien.</p>
 */
@Component
public class OfficeToolCatalog {

    /** Le document Word. */
    public static final String BUILD_DOCUMENT = OfficeFormat.DOCX.tool();
    /** Le classeur Excel. */
    public static final String BUILD_SPREADSHEET = OfficeFormat.XLSX.tool();

    /** Le guide de doctrine ajouté à la consigne quand les outils sont donnés. <b>Texte fixe</b> :
     * le préfixe système reste stable, le cache de prompt n'est pas touché. */
    public static final String GUIDE = "--- Documents Office construits par la gateway ---\n"
            + "Pour produire un .docx, DÉCRIS-LE et appelle " + BUILD_DOCUMENT + " ; pour un .xlsx, "
            + BUILD_SPREADSHEET + ". La gateway construit le fichier et le dépose dans le projet. "
            + "N'installe RIEN sur la machine du client (python-docx, openpyxl, pip) : sur un poste "
            + "d'entreprise l'installation est bloquée, et le fichier ne se produisait pas.\n"
            + "LE DOCUMENT : {title, subtitle, blocks:[…]} où chaque bloc porte un « type » — "
            + "« heading » (text + level 1-3), « text » (lines[]), « bullets » (bullets[], "
            + "« ordered »: true pour numéroter), « table » (rows[[…]], première ligne = en-tête), "
            + "« image » (image + caption), « pagebreak ».\n"
            + "LE CLASSEUR : {title, sheets:[{name, columns:[…], rows:[[…]]}]}. Laisse les NOMBRES "
            + "en nombres dans rows : un classeur dont les montants sont du texte ne se somme pas.\n"
            + "LES IMAGES (document seulement) : donne le CHEMIN d'un fichier DÉJÀ déposé dans le "
            + "projet (un diagramme rendu par render_diagram, une image décorative) dans « images » : "
            + "{\"archi.png\": \"archi.png\"}. Une image absente est REFUSÉE.\n"
            + "LA CHARTE : le fichier sort par DÉFAUT à la charte de l'application (navy, filet "
            + "orange). N'y touche pas sans raison ; « theme »: \"plain\" rend le gabarit Office "
            + "neutre si le client le demande.\n"
            + "GRATUIT : aucun appel fournisseur, aucun jeton.\n"
            + "SI python-docx ou openpyxl SONT DÉJÀ présents sur le poste, l'ancienne voie (script "
            + "python) reste possible — mais ne les installe jamais.";

    /** Vrai si ce nom d'outil est celui d'une construction Office. */
    public static boolean isOfficeTool(String tool) {
        return OfficeFormat.ofTool(tool) != null;
    }

    private final OfficeBuilder builder;

    public OfficeToolCatalog(OfficeBuilder builder) {
        this.builder = builder;
    }

    /** Catalogue <b>vide</b> : les outils ne sont jamais donnés (formes historiques, tests). */
    public static OfficeToolCatalog none() {
        return new OfficeToolCatalog(null);
    }

    /** Vrai si les outils sont ouverts pour ce tour : il suffit qu'un constructeur soit configuré. */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        return builder != null && userId != null && workspace != null && builder.isAvailable();
    }

    /** Les outils à donner à l'agent pour ce tour, ou <b>la liste vide</b>. */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        return isOpenFor(userId, workspace) ? List.of(document(), spreadsheet()) : List.of();
    }

    /** La définition de l'outil « document Word ». */
    static AgentTool document() {
        return new AgentTool(BUILD_DOCUMENT,
                "CONSTRUIT un document Word (.docx) à partir d'une DESCRIPTION, côté gateway, et le "
                        + "dépose dans le projet. N'installe rien sur la machine du client. GRATUIT. "
                        + "Les images viennent de fichiers DÉJÀ déposés dans le projet "
                        + "(render_diagram, generate_image) : donne leur chemin.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "spec", Map.of("type", "object",
                                        "description", "La description : {title, subtitle, blocks:["
                                                + "{type, text|lines|bullets|rows|image, level, "
                                                + "ordered, caption}]}. Types de blocs : heading, "
                                                + "text, bullets, table, image, pagebreak. "
                                                + "« theme » facultatif : \"cg\" (la charte, défaut) "
                                                + "ou \"plain\" (gabarit Office neutre)."),
                                "images", Map.of("type", "object",
                                        "description", "Les images à insérer : {nom utilisé dans les "
                                                + "blocs -> chemin du fichier dans le projet}."),
                                "filename", Map.of("type", "string",
                                        "description", "Nom du .docx déposé ; dérivé du titre sinon.")),
                        "required", List.of("spec")));
    }

    /** La définition de l'outil « classeur Excel ». */
    static AgentTool spreadsheet() {
        return new AgentTool(BUILD_SPREADSHEET,
                "CONSTRUIT un classeur Excel (.xlsx) à partir d'une DESCRIPTION, côté gateway, et le "
                        + "dépose dans le projet. N'installe rien sur la machine du client. GRATUIT. "
                        + "Laisse les nombres en nombres : un classeur dont les montants sont du "
                        + "texte ne se somme pas.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "spec", Map.of("type", "object",
                                        "description", "La description : {title, sheets:[{name, "
                                                + "columns:[…], rows:[[…]]}]}. L'en-tête est figé et "
                                                + "filtrable. « theme » facultatif : \"cg\" (la "
                                                + "charte, défaut) ou \"plain\" (gabarit neutre)."),
                                "filename", Map.of("type", "string",
                                        "description", "Nom du .xlsx déposé ; dérivé du titre sinon.")),
                        "required", List.of("spec")));
    }
}
