package fr.claudegateway.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostSpaceService;

/**
 * <b>Le catalogue d'outils Teams donné à l'agent — et la garde qui décide s'il l'est</b>
 * (F-89 / SF-89-01, cadrage §5.4).
 *
 * <h2>La garde est au niveau de l'outil, pas de l'écran</h2>
 *
 * <p>{@code buildTools(workspace)} décide déjà quels outils l'agent reçoit : c'est là que
 * {@code bash} est donné ou non selon la cible d'exécution. Les outils {@code teams_*} suivent
 * exactement le même chemin, sous <b>deux</b> conditions cumulatives : le workspace est un
 * <b>terminal Teams</b>, et l'utilisateur a le <b>droit Teams</b>.</p>
 *
 * <p><b>Sans l'une ou l'autre, les outils ne sont pas donnés — et c'est tout.</b> La nuance est le
 * motif même de cette conception : <i>l'agent ne refuse pas, il n'a pas la capacité</i>. Il ne dira
 * jamais « je pourrais mais vous n'avez pas payé » ; interrogé sur Teams, il dira qu'il ne sait pas
 * le lire. On ne met pas l'utilisateur devant une porte fermée à chaque phrase.</p>
 *
 * <h2>Un seul endroit</h2>
 *
 * <p>Le catalogue et sa garde vivent <b>ici</b>, et nulle part ailleurs. F-88 y ajoute ses outils de
 * lecture ({@code teams_read_conversation}, {@code teams_mentions}, {@code teams_search}…), F-89 /
 * SF-89-02 ses outils de présentation : aucun des deux n'a à réécrire la garde, et aucun ne peut
 * l'oublier. Un catalogue éparpillé serait un catalogue dont une entrée échapperait un jour au
 * droit.</p>
 *
 * <h2>Ce que le catalogue contient aujourd'hui</h2>
 *
 * <p>{@code teams_status} — l'outil que le runner sait exécuter depuis F-87 / SF-87-03, et que
 * l'agent n'avait jamais reçu : l'indicateur de la barre le consommait, l'agent non. Il est le bon
 * premier outil du catalogue parce qu'il est celui par lequel l'agent apprend qu'il <b>peut</b>
 * lire Teams, et, quand il ne le peut pas, <b>quoi dire</b> à l'utilisateur (lancer le navigateur
 * avec son port de débogage) : le résultat porte la phrase toute faite.</p>
 */
@Component
public class TeamsToolCatalog {

    /** L'état de la liaison (F-87 / SF-87-03). */
    public static final String STATUS = "teams_status";
    /** Retrouver une conversation par personne, groupe ou sujet (F-88 / SF-88-01). */
    public static final String FIND_CONVERSATIONS = "teams_find_conversations";
    /** Lire une conversation sur une fenêtre de temps (F-88 / SF-88-01). */
    public static final String READ_CONVERSATION = "teams_read_conversation";
    /** Là où l'on m'a mentionné, par le flux d'activité (F-88 / SF-88-02). */
    public static final String MENTIONS = "teams_mentions";
    /** Rechercher dans le contenu, par l'index de Teams (F-88 / SF-88-02). */
    public static final String SEARCH = "teams_search";
    /** Retrouver une réunion (F-88 / SF-88-02). */
    public static final String FIND_MEETINGS = "teams_find_meetings";
    /** La transcription d'une réunion enregistrée (F-88 / SF-88-02). */
    public static final String MEETING_TRANSCRIPT = "teams_meeting_transcript";
    /**
     * La règle de conformité d'une transcription au téléchargement bloqué (F-89 / SF-89-06, cadrage F-87
     * §9 bis) : elle voyage dans la consigne de l'outil, pour que l'agent la connaisse AVANT de lire.
     */
    public static final String DOWNLOAD_BLOCKED_RULE = "Si le résultat porte « downloadBlocked » à vrai, le "
            + "téléchargement a été bloqué par l'organisateur : sers-toi des répliques pour répondre et "
            + "résumer, mais ne recopie JAMAIS la transcription brute — ni dans un fichier, ni en bloc "
            + "intégral dans le fil — et signale ce blocage à l'utilisateur.";
    /**
     * <b>Les deux « rien »</b> (F-89 / SF-89-08) : constat du poste client — sur un zéro, l'agent a fait
     * rouvrir cinq fois des écrans déjà ouverts, alors que le contenu était arrivé sans être reconnu. Le
     * remède n'est pas le même, et la règle voyage là où l'agent la lit.
     */
    /**
     * <b>La règle NON négociable de l'échec de lecture Teams</b> (F-89 / SF-89-11). Remplace la
     * complaisance de {@link #NOTHING_RULE} par un ordre : sur un zéro, un <b>bloc d'échec</b> est déjà
     * posé dans le fil avec deux actions (Réessayer / Chercher dans le projet), et l'agent <b>s'arrête
     * là</b>. Il ne substitue JAMAIS le contenu du projet à Teams sans un geste explicite de
     * l'utilisateur — le repli sur le poste est un choix, jamais un réflexe silencieux.
     */
    public static final String READ_FAILURE_RULE = "RÈGLE NON NÉGOCIABLE — sur un zéro de lecture Teams, "
            + "NE réponds JAMAIS la question de fond depuis le projet, bash, grep ou les fichiers du poste : "
            + "un bloc d'échec est posé dans le fil avec deux choix pour l'utilisateur (Réessayer / Chercher "
            + "dans le projet). Arrête-toi et laisse-le choisir. Ne substitue le contenu du projet à Teams "
            + "qu'après son autorisation explicite.";
    public static final String NOTHING_RULE = "Sur un zéro, lis le manque : « NOTHING_SERVED » = Teams n'a rien "
            + "servi, propose UNE fois à l'utilisateur d'ouvrir l'écran voulu dans Teams ; « NOTHING_CLASSIFIED » "
            + "= le contenu est arrivé mais le runner ne l'a pas reconnu : NE demande PAS de rouvrir, cliquer ou "
            + "rafraîchir (ça n'y changera rien), dis-le tel quel, et donne l'inventaire des chemins non "
            + "reconnus de teams_status (diagnostic.observation.unknownPaths) si on te le demande. "
            + READ_FAILURE_RULE;
    /**
     * L'enregistrement d'une réunion (F-88 / SF-88-02) — <b>rapatrié sur la machine par Chrome</b>
     * depuis F-108 / SF-108-05. Une lecture : aucune confirmation.
     */
    public static final String MEETING_RECORDING = "teams_meeting_recording";
    /** Démarre l'extraction et l'alignement des captures d'un enregistrement (F-90 / SF-90-03). */
    public static final String MEETING_MOMENTS = "teams_meeting_moments";
    /** Où en est ce travail de captures, et — quand il est fini — ses moments (F-90 / SF-90-03). */
    public static final String MOMENTS_STATUS = "teams_moments_status";
    /**
     * <b>Démarre un enregistrement local</b> (F-91 / SF-91-02). Le seul outil du volet qui
     * <b>crée</b> au lieu de relire.
     */
    public static final String CAPTURE_START = "teams_capture_start";
    /** Arrête l'enregistrement local en cours (F-91 / SF-91-02). */
    public static final String CAPTURE_STOP = "teams_capture_stop";
    /** Où en est l'enregistrement local, et ceux d'avant (F-91 / SF-91-02). */
    public static final String CAPTURE_STATUS = "teams_capture_status";
    /**
     * <b>Rejoindre une réunion dans le Chrome managé</b> (F-128 / SF-128-01, cadrage §2bis) : ouvre et
     * navigue l'onglet Teams du Chrome managé vers l'URL de la réunion. Appelé <b>directement par le
     * backend</b> (service Réunions), hors boucle agent : il n'entre donc PAS dans {@link #CATALOG} (la
     * liste donnée au modèle) — c'est une commande d'orchestration de la Vigie, pas un outil de l'agent.
     */
    public static final String MEETING_JOIN = "teams_meeting_join";
    /**
     * <b>Liste les fichiers</b> d'une bibliothèque Teams / SharePoint / OneDrive (F-108 / SF-108-03).
     * Une <b>lecture</b> : aucune confirmation.
     */
    public static final String LIST_FILES = "teams_list_files";
    /**
     * <b>Rapatrie un fichier</b> sur la machine — dossier synchronisé, sinon téléchargé par Chrome
     * (F-108 / SF-108-03). Une <b>lecture</b> : aucune confirmation.
     */
    public static final String READ_FILE = "teams_read_file";
    /**
     * <b>Lit le texte d'un {@code .docx} déjà sur la machine</b> — la transcription Word
     * (F-108 / SF-108-06). Une <b>lecture</b> : aucune confirmation.
     */
    public static final String READ_DOCX = "teams_read_docx";

    /**
     * <b>Les outils de LECTURE, dans l'ordre où ils sont donnés à l'agent</b> — et la seule liste
     * qui fasse foi côté gateway. Sa contrepartie côté runner ({@code TeamsTools.CATALOG}) porte
     * exactement les mêmes noms ; les deux sont verrouillées par un test de chaque côté, parce que
     * deux dépôts de la même vérité finissent par diverger quand personne ne les compare.
     *
     * <p><b>Ce sont ceux que le RUNNER exécute</b>, et c'est ce qui les distingue de
     * {@link #PRESENTATION} (F-89 / SF-89-02) : ceux-là ne quittent jamais la gateway — ils posent
     * un bloc dans le fil, ils ne touchent ni la machine ni le navigateur. Le runner n'a donc rien
     * à en connaître, et la liste qu'il mirroite ne doit surtout pas les contenir.</p>
     */
    public static final List<String> CATALOG = List.of(STATUS, FIND_CONVERSATIONS,
            READ_CONVERSATION, MENTIONS, SEARCH, FIND_MEETINGS, MEETING_TRANSCRIPT,
            MEETING_RECORDING, MEETING_MOMENTS, MOMENTS_STATUS, CAPTURE_START, CAPTURE_STOP,
            CAPTURE_STATUS, LIST_FILES, READ_FILE, READ_DOCX, "teams_create_folder",
            "teams_upload_file", "teams_rename", "teams_move", "teams_copy", "teams_delete",
            "teams_replace_version");

    /**
     * <b>Les outils qui CRÉENT</b> (F-91), par opposition à tous les autres, qui <b>relisent</b>.
     *
     * <p>Cette liste n'est pas un classement de confort : c'est ce qui permet au journal d'audit de
     * distinguer « a lu une conversation » de « a enregistré une réunion », et à un test de vérifier
     * qu'aucun outil de capture n'échappe à la garde du droit.</p>
     */
    public static final List<String> CAPTURE = List.of(CAPTURE_START, CAPTURE_STOP, CAPTURE_STATUS);

    /** Vrai si ce nom d'outil <b>crée un enregistrement</b> plutôt que de relire (F-91). */
    public static boolean isCapture(String tool) {
        return tool != null && CAPTURE.contains(tool);
    }

    /** La <b>carte de réunion</b> (F-89 / SF-89-02) : des sections de lignes sourcées. */
    public static final String MEETING_CARD = "teams_meeting_card";

    /** La <b>liste</b> (F-89 / SF-89-02) : engagements, mentions. */
    public static final String LIST = "teams_list";

    /** Les <b>moments</b> (F-89 / SF-89-02) : l'image, à côté de la phrase prononcée. */
    public static final String MOMENTS = "teams_moments";

    /**
     * <b>Les outils de PRÉSENTATION</b> (F-89 / SF-89-02), dans l'ordre où ils sont donnés — ceux
     * qui posent un bloc dans le fil. Ils s'exécutent <b>dans la gateway</b> : le runner n'en
     * connaît aucun, et {@link #CATALOG} ne les contient pas.
     */
    public static final List<String> PRESENTATION = List.of(MEETING_CARD, LIST, MOMENTS);

    /** Vrai si ce nom d'outil est un outil de <b>présentation</b>, qui pose un bloc dans le fil. */
    public static boolean isPresentation(String tool) {
        return MEETING_CARD.equals(tool) || LIST.equals(tool) || MOMENTS.equals(tool);
    }

    // ------------------------------------------------------------------ F-108 : les écritures

    /** Créer un dossier dans Teams / SharePoint / OneDrive (F-108 / SF-108-04). */
    public static final String CREATE_FOLDER = "teams_create_folder";
    /** Déposer un fichier de la machine (F-108 / SF-108-04). */
    public static final String UPLOAD_FILE = "teams_upload_file";
    /** Renommer un fichier ou un dossier (F-108 / SF-108-04). */
    public static final String RENAME = "teams_rename";
    /** Déplacer un fichier ou un dossier, même site (F-108 / SF-108-04). */
    public static final String MOVE = "teams_move";
    /** Copier un fichier ou un dossier, même site ou autre site (F-108 / SF-108-06). */
    public static final String COPY = "teams_copy";
    /** Supprimer un fichier ou un dossier (F-108 / SF-108-04). */
    public static final String DELETE = "teams_delete";
    /** Remplacer un document par une nouvelle version — télécharger → modifier → redéposer (F-108). */
    public static final String REPLACE_VERSION = "teams_replace_version";

    /**
     * <b>Les outils qui ÉCRIVENT</b> dans Microsoft 365 (F-108 / SF-108-02, cadrage §4.4) — et la
     * seule liste qui fasse foi. Chacun est soumis à l'autorisation du terminal, action et
     * emplacement nommés en clair ; aucun n'est couvert par « Tout autoriser pour ce message » :
     * <b>chaque écriture</b> est confirmée. La lecture et le téléchargement, eux, ne demandent rien.
     *
     * <p>Poster un message, répondre, réagir <b>ne sont pas ici</b> : ils restent hors périmètre.
     * {@code teams_copy} (F-108 / SF-108-06) en fait partie : un déplacement inter-site est une copie
     * puis une suppression, donc <b>deux</b> écritures, donc <b>deux</b> autorisations.</p>
     */
    public static final List<String> WRITE = List.of(CREATE_FOLDER, UPLOAD_FILE, RENAME, MOVE, COPY,
            DELETE, REPLACE_VERSION);

    /** Vrai si ce nom d'outil <b>écrit</b> dans Microsoft 365, et exige donc une confirmation. */
    public static boolean isWrite(String tool) {
        return tool != null && WRITE.contains(tool);
    }

    /**
     * <b>Le libellé clair d'une écriture</b> (F-108 / SF-108-02, cadrage §4.4) — ce que l'utilisateur
     * lit avant d'autoriser. Il nomme l'<b>action</b> et l'<b>emplacement</b>, jamais un identifiant
     * technique : « Créer le dossier « Livrables » dans Équipe Projet IAM › Général › Fichiers ».
     *
     * @param tool     l'outil d'écriture
     * @param item     ce sur quoi porte l'écriture (nom de dossier, de fichier…), déjà lisible
     * @param location l'emplacement en clair, ou vide s'il n'est pas connu
     */
    public static String describeWrite(String tool, String item, String location) {
        String name = item == null || item.isBlank() ? "(sans nom)" : item.strip();
        String where = location == null || location.isBlank() ? "" : " dans " + location.strip();
        return switch (tool == null ? "" : tool) {
            case CREATE_FOLDER -> "Créer le dossier « " + name + " »" + where;
            case UPLOAD_FILE -> "Déposer le fichier « " + name + " »" + where;
            case RENAME -> "Renommer « " + name + " »" + where;
            case MOVE -> "Déplacer « " + name + " »"
                    + (location == null || location.isBlank() ? "" : " vers " + location.strip());
            case COPY -> "Copier « " + name + " »"
                    + (location == null || location.isBlank() ? "" : " vers " + location.strip());
            case DELETE -> "Supprimer « " + name + " »" + where;
            case REPLACE_VERSION -> "Remplacer la version de « " + name + " »" + where;
            default -> "Écrire « " + name + " »" + where;
        };
    }

    /**
     * <b>Le libellé clair d'un appel d'écriture</b>, tiré de ses paramètres (F-108 / SF-108-04) — ce
     * que l'utilisateur lit avant d'autoriser. Chaque écriture a sa phrase : un renommage dit l'ancien
     * et le nouveau nom, un dépôt et un remplacement disent <b>le chemin du fichier local</b> — sans
     * lui, on pourrait faire autoriser l'envoi d'un fichier qu'on n'a pas choisi sous un nom anodin.
     * Les adresses web deviennent « site › bibliothèque › dossier ».
     *
     * @param args lecture d'un paramètre texte par son nom ({@code null} ou vide s'il manque)
     */
    public static String describeWriteCall(String tool, java.util.function.Function<String, String> args) {
        String name = param(args, "name");
        String target = param(args, "target");
        String file = param(args, "file");
        String location = first(param(args, "location"), param(args, "parent"));
        String destination = param(args, "destination");
        String item = target.isEmpty() ? name : lastSegment(target);
        String targetPlace = target.isEmpty() ? readableLocation(location) : readableLocation(parentOf(target));
        return switch (tool == null ? "" : tool) {
            case CREATE_FOLDER -> describeWrite(CREATE_FOLDER, name, readableLocation(location));
            case UPLOAD_FILE -> {
                String remote = name.isEmpty() ? lastSegment(file) : name;
                String place = readableLocation(first(location, destination));
                yield "Déposer le fichier local « " + (file.isEmpty() ? "(non précisé)" : file) + " »"
                        + (remote.isEmpty() ? "" : " sous le nom « " + remote + " »")
                        + (place.isEmpty() ? "" : " dans " + place);
            }
            case RENAME -> target.isEmpty()
                    ? describeWrite(RENAME, name, readableLocation(location))
                    : "Renommer « " + item + " » en « " + (name.isEmpty() ? "(sans nom)" : name) + " »"
                            + (targetPlace.isEmpty() ? "" : " dans " + targetPlace);
            case MOVE -> describeWrite(MOVE, item, readableLocation(first(destination, location)));
            case COPY -> describeWrite(COPY, item, readableLocation(first(destination, location)));
            case DELETE -> describeWrite(DELETE, item, targetPlace) + " (corbeille du site)";
            case REPLACE_VERSION -> describeWrite(REPLACE_VERSION, item, targetPlace)
                    + (file.isEmpty() ? "" : " par le fichier local « " + file + " »");
            default -> describeWrite(tool, first(name, target, file),
                    readableLocation(first(location, destination)));
        };
    }

    /**
     * Une adresse web SharePoint / OneDrive en clair : « ProjetIAM › Shared Documents › General ».
     * Un emplacement déjà en clair est rendu tel quel.
     */
    public static String readableLocation(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!value.toLowerCase(java.util.Locale.ROOT).startsWith("http")) {
            return value;
        }
        String afterScheme = value.substring(value.indexOf("://") < 0 ? 0 : value.indexOf("://") + 3);
        int slash = afterScheme.indexOf('/');
        String host = slash < 0 ? afterScheme : afterScheme.substring(0, slash);
        String path = slash < 0 ? "" : afterScheme.substring(slash);
        String query = "";
        int question = path.indexOf('?');
        if (question >= 0) {
            query = path.substring(question + 1);
            path = path.substring(0, question);
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        path = decode(path);
        if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".aspx")) {
            for (String pair : query.split("&")) {
                if (pair.toLowerCase(java.util.Locale.ROOT).startsWith("id=")) {
                    path = decode(pair.substring(3));
                }
            }
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        if (!segments.isEmpty() && segments.get(0).startsWith(":")) {
            segments = segments.subList(Math.min(2, segments.size()), segments.size());
        }
        if (segments.size() >= 2) {
            String prefix = segments.get(0).toLowerCase(java.util.Locale.ROOT);
            if ("personal".equals(prefix)) {
                segments = new ArrayList<>(segments.subList(2, segments.size()));
                segments.add(0, "OneDrive");
            } else if ("sites".equals(prefix) || "teams".equals(prefix)) {
                segments = segments.subList(1, segments.size());
            }
        }
        return segments.isEmpty() ? host : String.join(" › ", segments);
    }

    private static String lastSegment(String raw) {
        String value = raw == null ? "" : raw.strip();
        int question = value.indexOf('?');
        if (question >= 0) {
            value = value.substring(0, question);
        }
        while (value.endsWith("/") || value.endsWith("\\")) {
            value = value.substring(0, value.length() - 1);
        }
        int cut = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return decode(cut >= 0 ? value.substring(cut + 1) : value);
    }

    private static String parentOf(String raw) {
        String value = raw == null ? "" : raw.strip();
        int question = value.indexOf('?');
        if (question >= 0) {
            value = value.substring(0, question);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int cut = value.lastIndexOf('/');
        return cut > value.indexOf("://") + 2 ? value.substring(0, cut) : "";
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value.replace("+", "%2B"),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String param(java.util.function.Function<String, String> args, String name) {
        String value = args == null ? null : args.apply(name);
        return value == null ? "" : value.strip();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    /**
     * Préfixe commun à tous les outils du volet. Sert à une seule chose, mais elle compte : un test
     * peut vérifier qu'<b>aucun</b> outil commençant par {@code teams_} n'est donné là où le droit
     * n'est pas ouvert, sans avoir à énumérer un catalogue qui grandira.
     */
    public static final String PREFIX = "teams_";

    private final TeamsAccessService teamsAccess;
    private final HostSpaceService spaces;

    public TeamsToolCatalog(TeamsAccessService teamsAccess, HostSpaceService spaces) {
        this.teamsAccess = teamsAccess;
        this.spaces = spaces;
    }

    /**
     * Catalogue <b>vide</b>, sur le modèle d'{@code AtelierCheckpointRunner.none()} : aucun outil
     * Teams n'est jamais donné. C'est ce que reçoivent les formes d'{@code AtelierChatService}
     * conservées pour les appelants et les tests qui ne se soucient pas du volet Teams — leur
     * comportement est alors celui d'avant F-89, à l'identique.
     */
    public static TeamsToolCatalog none() {
        return new TeamsToolCatalog(null, null);
    }

    /**
     * <b>Ce que l'agent d'un terminal Teams sans droit doit savoir</b> (F-89 / SF-89-04).
     *
     * <p>Constat de production du 2026-09-13 : sans droit, le catalogue est vide <b>en silence</b>, et
     * l'agent — qui ignore où il est — répond comme un terminal de projet, en fouillant la machine.
     * La règle de SF-89-01 tient (aucun outil {@code teams_*}, pas davantage un outil qui refuserait) ;
     * ce qui manquait est la <b>parole</b> : la consigne système le dit, et dit quoi répondre.</p>
     */
    public static final String CLOSED_NOTICE = "--- Volet Teams non actif ---\n"
            + "Ce terminal est un terminal Teams, mais le volet Teams n'est pas actif sur ce compte : "
            + "aucun outil Teams ne t'est donné, tu ne peux lire ni messages, ni réunions, ni fichiers "
            + "Teams. Ne cherche pas la réponse sur la machine à la place (pas de bash, find, grep ni "
            + "lecture de fichiers pour retrouver un contenu Teams). Dis-le à l'utilisateur en une "
            + "phrase, et indique-lui comment l'ouvrir : un essai par code d'accès (écran Facturation, "
            + "« Vous avez un code d'accès ? ») ou l'option Teams (écran Facturation).";

    /**
     * <b>Ce que l'agent d'un terminal Teams dont le client a quitté la Vigie doit savoir</b>
     * (F-106 / SF-106-07).
     *
     * <p>Risque résiduel relevé à la livraison de F-106 : un terminal Teams déjà ouvert gardait ses
     * outils {@code teams_*} si son client était <b>retiré de la Vigie</b> ; seule l'ouverture était
     * gardée (SF-106-03). Désormais {@link #toolsFor} exige, à chaque tour, que le poste soit activé
     * dans la Vigie. Comme pour SF-89-04, ce qui manquait alors est la <b>parole</b> : sans droit le
     * catalogue est vide en silence, et l'agent fouille la machine ; la consigne le dit et dit quoi
     * répondre. Distincte de {@link #CLOSED_NOTICE} : ici le volet Teams est bien actif sur le compte,
     * mais ce <b>client</b> n'est plus suivi par la Vigie.</p>
     */
    public static final String REMOVED_FROM_VIGIE_NOTICE = "--- Client retiré de la Vigie ---\n"
            + "Ce terminal est un terminal Teams, mais son client n'est plus activé dans la Vigie : "
            + "aucun outil Teams ne t'est donné, tu ne peux lire ni messages, ni réunions, ni fichiers "
            + "Teams de ce client. Ne cherche pas la réponse sur la machine à la place (pas de bash, "
            + "find, grep ni lecture de fichiers pour retrouver un contenu Teams). Dis-le à "
            + "l'utilisateur en une phrase, et indique-lui comment le rétablir : réactiver ce client "
            + "dans la Vigie (écran Vigie, « Ajouter un client » puis activer ce client).";

    /**
     * Vrai si le workspace est un <b>terminal Teams</b> et que le droit du compte est <b>fermé</b>
     * (F-89 / SF-89-04) : c'est le cas où la consigne système doit porter {@link #CLOSED_NOTICE}.
     *
     * <p>Un terminal de projet n'est jamais « fermé » — le droit n'y est même pas consulté —, et le
     * catalogue {@link #none()} ne l'est jamais non plus : ses appelants ne connaissent pas le volet.</p>
     *
     * @param userId    propriétaire du terminal (celui du tour, jamais un paramètre client)
     * @param workspace terminal du tour
     * @return {@code true} si l'agent doit dire que le volet Teams n'est pas actif
     */
    public boolean isClosedFor(UUID userId, Workspace workspace) {
        return teamsAccess != null && workspace != null && workspace.isTeamsTerminal()
                && !teamsAccess.hasAccess(userId);
    }

    /**
     * Vrai si le workspace est un <b>terminal Teams</b>, que le droit du compte est <b>ouvert</b>,
     * mais que le poste <b>n'est plus activé dans la Vigie</b> (F-106 / SF-106-07) : c'est le cas où
     * la consigne système doit porter {@link #REMOVED_FROM_VIGIE_NOTICE}.
     *
     * <p>Mutuellement exclusif avec {@link #isClosedFor} : celui-ci exige le droit, celui-là son
     * absence. Un terminal de projet et le catalogue {@link #none()} ne le sont jamais.</p>
     *
     * @param userId    propriétaire du terminal (celui du tour, jamais un paramètre client)
     * @param workspace terminal du tour
     * @return {@code true} si l'agent doit dire que le client a été retiré de la Vigie
     */
    public boolean isRemovedFromVigie(UUID userId, Workspace workspace) {
        return teamsAccess != null && spaces != null && workspace != null
                && workspace.isTeamsTerminal() && teamsAccess.hasAccess(userId)
                && !isActiveInVigie(userId, workspace);
    }

    /**
     * Vrai si le poste de ce terminal est <b>activé dans la Vigie</b> pour l'utilisateur du tour
     * (F-106 / SF-106-07). Même garde que {@code RadarToolCatalog} : sans {@code hostId}, ou si le
     * poste est introuvable ou d'autrui ({@code isActive} lève), c'est <b>fermé</b>, sans bruit.
     */
    private boolean isActiveInVigie(UUID userId, Workspace workspace) {
        if (spaces == null || userId == null || workspace.getHostId() == null) {
            return false;
        }
        try {
            return spaces.isActive(userId, workspace.getHostId(), ClientSpace.VIGIE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Les outils Teams à donner à l'agent pour ce tour, ou <b>la liste vide</b>.
     *
     * @param userId    propriétaire du terminal (isolation : celui du tour, jamais un paramètre client)
     * @param workspace terminal du tour
     * @return le catalogue si le workspace est un terminal Teams, que le droit est ouvert <b>et</b>
     *         que le poste est activé dans la Vigie (F-106 / SF-106-07) ; la liste vide sinon
     */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        if (teamsAccess == null || workspace == null || !workspace.isTeamsTerminal()
                || !teamsAccess.hasAccess(userId) || !isActiveInVigie(userId, workspace)) {
            return List.of();
        }
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new AgentTool(STATUS,
                "Dit où en est la liaison Teams de cette machine : reliée, navigateur non détecté, "
                        + "ou Teams a changé de forme. Appelle-le AVANT toute lecture de Teams — et "
                        + "quand il dit que la liaison n'est pas établie, répète à l'utilisateur la "
                        + "phrase et le remède qu'il te rend, mot pour mot : ils contiennent la "
                        + "commande exacte à lancer. " + NOTHING_RULE,
                Map.of("type", "object", "properties", Map.of())));
        tools.addAll(readingTools());
        tools.addAll(captureTools());
        tools.addAll(fileReadingTools());
        tools.addAll(fileWriteTools());
        tools.addAll(presentationTools());
        return List.copyOf(tools);
    }

    /**
     * <b>Les sept outils de lecture</b> (F-88 / SF-88-03). Aucun n'est un bouton : l'agent les
     * compose. « Qu'est-ce qu'on attend de moi ? » appellera les mentions, puis la recherche sur les
     * variantes du nom, puis la lecture des fils récemment actifs — et il conclura ; une autre
     * question appellera autre chose.
     *
     * <p><b>Les descriptions portent la doctrine du volet</b>, parce que c'est le seul endroit où
     * l'agent la lit : le plafond y est <b>annoncé et dit négociable</b> (D4), les <b>trois
     * gisements</b> d'un engagement y sont nommés — et surtout, chaque outil rappelle que son
     * résultat porte <b>ce qu'il n'a pas pu lire</b>, et qu'il faut le <b>répéter</b>. Un compte
     * rendu qui tait un trou est un compte rendu faux, et c'est la règle qui prime sur toutes les
     * autres dans ce volet.</p>
     */
    private List<AgentTool> readingTools() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> number = Map.of("type", "integer");
        List<AgentTool> tools = new ArrayList<>();

        tools.add(new AgentTool(FIND_CONVERSATIONS,
                "Retrouve une conversation Teams par personne, par groupe ou par sujet, classée de "
                        + "la plus récemment active à la plus ancienne. Un tête-à-tête n'a pas de "
                        + "sujet : cherche alors par le NOM de la personne. C'est par là qu'on "
                        + "commence quand on ne sait pas encore quel fil lire. " + NOTHING_RULE,
                Map.of("type", "object",
                        "properties", Map.of("query", text, "limit", number))));

        tools.add(new AgentTool(READ_CONVERSATION,
                "Lit les messages d'une conversation sur une PÉRIODE. Sans conversation_id, lit le "
                        + "fil actuellement affiché dans Teams. La période par défaut est de 7 jours "
                        + "et 500 messages — ce plafond est NÉGOCIABLE : passe from (« 2026-09-01 », "
                        + "« 21d », « 3 semaines ») et max_messages (jusqu'à 2000) pour remonter "
                        + "plus loin. Le résultat porte TOUJOURS la fenêtre RÉELLEMENT lue "
                        + "(window.actualFrom / actualTo) et la liste des manques (gaps) : cite la "
                        + "phrase « text » telle quelle, et ne présente jamais une lecture "
                        + "incomplète comme complète. C'est le seul outil qui trouve les "
                        + "engagements qu'on a pris soi-même (« je te l'envoie demain ») : ils ne "
                        + "contiennent ni mention ni nom, aucune recherche ne les trouve. " + NOTHING_RULE,
                Map.of("type", "object",
                        "properties", Map.of("conversation_id", text, "from", text, "to", text,
                                "max_messages", number))));

        tools.add(new AgentTool(MENTIONS,
                "Là où l'on vous a MENTIONNÉ explicitement (@vous), lu dans le flux d'activité que "
                        + "Teams calcule déjà : c'est exact et peu coûteux, commence par là. "
                        + "Attention : cela ne couvre QUE les mentions explicites — ce qu'on vous "
                        + "demande sans vous mentionner n'y est pas. " + NOTHING_RULE,
                Map.of("type", "object",
                        "properties", Map.of("from", text, "to", text, "max_mentions", number))));

        tools.add(new AgentTool(SEARCH,
                "Cherche dans le CONTENU des messages, en posant la question à l'index de Teams. "
                        + "Sert à trouver ce qui vous concerne sans vous mentionner — « Francky "
                        + "s'occupe du MFA » : essaie plusieurs variantes du nom (prénom, nom, "
                        + "initiales). Si le résultat dit que la question n'a PAS pu être posée, "
                        + "ce n'est pas « il n'y a rien » : répète à l'utilisateur ce qu'il peut "
                        + "faire. " + NOTHING_RULE,
                Map.of("type", "object",
                        "properties", Map.of("query", text, "from", text, "to", text,
                                "max_results", number),
                        "required", List.of("query"))));

        tools.add(new AgentTool(FIND_MEETINGS,
                "Retrouve une réunion par date, par sujet ou par participant. Le résultat dit si "
                        + "elle a été ENREGISTRÉE et si une TRANSCRIPTION est annoncée : ce sont "
                        + "les deux champs qui décident si tu peux aller plus loin. " + NOTHING_RULE,
                Map.of("type", "object",
                        "properties", Map.of("query", text, "from", text, "to", text,
                                "limit", number))));

        tools.add(new AgentTool(MEETING_TRANSCRIPT,
                "La transcription d'une réunion enregistrée : les répliques horodatées, avec leur "
                        + "locuteur. Trouve d'abord la réunion avec " + FIND_MEETINGS + ". Si rien "
                        + "ne revient, dis-le : une réunion non enregistrée n'a pas de "
                        + "transcription, et il ne faut surtout pas en inventer le contenu. "
                        + "« source » dit d'où viennent les répliques (reseau ou ecran). "
                        + DOWNLOAD_BLOCKED_RULE + " " + NOTHING_RULE,
                Map.of("type", "object",
                        "properties", Map.of("meeting_id", text),
                        "required", List.of("meeting_id"))));

        // F-108 / SF-108-05 : l'outil TÉLÉCHARGE désormais — par Chrome, jamais par une adresse
        // signée entre nos mains. La description garde la règle d'origine : tant que « downloaded »
        // est faux, aucun fichier n'existe, et l'agent ne doit pas le laisser croire.
        tools.add(new AgentTool(MEETING_RECORDING,
                "RAPATRIE l'enregistrement d'une réunion SUR LA MACHINE : c'est Chrome qui le "
                        + "télécharge dans le dossier de travail du volet, aucune adresse signée ne "
                        + "passe par nous, et la vidéo ne quitte jamais la machine. C'est LONG : "
                        + "l'outil rend la main dès que le téléchargement a démarré (« inProgress ») "
                        + "— redemande-le pour suivre. Tant que « downloaded » est faux, ne laisse "
                        + "jamais croire qu'un fichier a été récupéré, et répète ce qui manque "
                        + "(téléchargement bloqué par l'organisateur ou le tenant, adresse non "
                        + "observée). Une fois téléchargé, il cherche la transcription (Teams, "
                        + "fichier .vtt, sinon transcription locale sur la machine) puis enchaîne "
                        + "avec " + MEETING_MOMENTS + " en lui donnant le seul « meeting_id ». Adaptateur "
                        + "écrit sur la documentation Microsoft, à confirmer sur poste réel.",
                Map.of("type", "object",
                        "properties", Map.of("meeting_id", text,
                                "recording_url", Map.of("type", "string",
                                        "description", "Adresse web du fichier .mp4, si tu la "
                                                + "connais et que l'outil dit ne pas l'avoir "
                                                + "observée."),
                                "download", Map.of("type", "boolean",
                                        "description", "false pour seulement localiser "
                                                + "l'enregistrement (défaut : true).")),
                        "required", List.of("meeting_id"))));

        // F-90 : les captures alignées. Traitement LOURD, donc asynchrone — la description le dit
        // au modèle, parce que c'est lui qui doit enchaîner démarrage puis suivi, et NE PAS
        // conclure avant la fin. Et elle dit l'indiscrétion : une capture montre ce qui était
        // VISIBLE, pas seulement ce qui a été dit.
        tools.add(new AgentTool(MEETING_MOMENTS,
                "DÉMARRE l'extraction des captures d'un enregistrement de réunion déjà présent sur "
                        + "la machine, et leur alignement sur la transcription : chaque phrase est "
                        + "posée à côté de l'image qui était à l'écran PENDANT qu'elle se disait. "
                        + "C'est un traitement LONG (plusieurs minutes) : cet outil rend la main "
                        + "tout de suite avec un job_id, puis tu suis avec "
                        + MOMENTS_STATUS + ". NE CONCLUS PAS avant que le travail soit terminé, et "
                        + "dis à l'utilisateur où il en est. Donne « meeting_id » chaque fois que "
                        + "tu le connais : c'est lui qui apporte la transcription ET l'instant de "
                        + "début, sans lequel aucune image ne peut être datée. PRÉVIENS "
                        + "l'utilisateur, une fois : une capture est plus indiscrète qu'une phrase "
                        + "— la transcription dit ce qui a été DIT, les captures montrent ce qui "
                        + "était VISIBLE, y compris un tableau de bord avec des noms de clients ou "
                        + "une messagerie ouverte à côté. La vidéo, elle, ne quitte jamais la "
                        + "machine.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "video", Map.of("type", "string",
                                        "description", "Chemin de l'enregistrement SUR LA MACHINE. "
                                                + "Obligatoire, SAUF si tu donnes « capture_id », "
                                                + "ou « meeting_id » d'une réunion dont "
                                                + MEETING_RECORDING + " a rapatrié "
                                                + "l'enregistrement."),
                                "capture_id", Map.of("type", "string",
                                        "description", "Identifiant d'un ENREGISTREMENT LOCAL "
                                                + "terminé (teams_capture_*). Préfère-le à "
                                                + "« video » chaque fois que tu l'as : il apporte "
                                                + "d'un coup la vidéo, la transcription produite "
                                                + "sur la machine, ET l'instant de début exact — "
                                                + "aucune image n'a besoin d'être datée à la main."),
                                "meeting_id", text,
                                "video_started_at", Map.of("type", "string",
                                        "description", "Instant ISO-8601 du début de "
                                                + "l'enregistrement, si la réunion ne le donne "
                                                + "pas."),
                                "offset_seconds", number,
                                "restart", Map.of("type", "string",
                                        "description", "« true » pour relancer un travail déjà "
                                                + "fait ou échoué. Sans cela, redemander le même "
                                                + "enregistrement REPREND, il ne recommence pas.")))));
        // Aucun champ n'est déclaré « required » ici : c'est « video » OU « capture_id », et le
        // runner refuse en NOMMANT ce qui manque quand ni l'un ni l'autre n'est donné
        // (F-91 / SF-91-03). Un schema ne sait pas dire « l'un des deux ».

        tools.add(new AgentTool(MOMENTS_STATUS,
                "Où en est un travail de captures, et — QUAND IL EST TERMINÉ — ses moments : "
                        + "heure, citation, locuteur et imageId. Pose chaque imageId TEL QUEL dans "
                        + "le bloc « " + MOMENTS + " » : n'invente JAMAIS un identifiant d'image — "
                        + "rends le moment sans image plutôt qu'avec une image qui n'existe pas. "
                        + "Tant que le travail n'est pas terminé, "
                        + "redis simplement où il en est. Et répète toujours ce que le résultat "
                        + "dit ne PAS avoir pu faire : images non remontées, paroles sans image en "
                        + "vigueur, images sans parole.",
                Map.of("type", "object",
                        "properties", Map.of("job_id", text, "video", text))));
        return tools;
    }

    /**
     * <b>Les trois outils d'enregistrement local</b> (F-91 / SF-91-02).
     *
     * <h2>Ils ne sont pas de la même nature que les autres, et leurs descriptions le disent</h2>
     *
     * <p>Tout le reste du catalogue <b>relit ce qui existait déjà</b>. Ceux-ci <b>créent</b> — et
     * les participants ne le sauront pas, là où Teams affiche un bandeau quand c'est lui qui
     * enregistre. Ces descriptions sont le seul endroit où le modèle apprend cette différence : elles
     * lui disent d'<b>exiger l'usage</b>, de <b>ne jamais cocher la confirmation à la place de
     * l'utilisateur</b>, et de <b>répéter</b> ce que le produit ne peut pas garantir.</p>
     *
     * <p><b>Et ils vivent dans ce catalogue</b>, donc sous la même garde que les autres : sans le
     * droit Teams, un terminal ne les reçoit pas — l'agent ne refuse pas, il n'a pas la capacité.</p>
     */
    private List<AgentTool> captureTools() {
        Map<String, Object> text = Map.of("type", "string");
        List<AgentTool> tools = new ArrayList<>();

        tools.add(new AgentTool(CAPTURE_START,
                "DÉMARRE un enregistrement local de l'écran et du son de la machine. C'est le SEUL "
                        + "outil du volet qui CRÉE quelque chose : tous les autres relisent ce que "
                        + "Teams a déjà. Sers-t'en quand la réunion N'EST PAS enregistrée par Teams "
                        + "— sans cela il n'y a ni enregistrement ni transcription à lire. "
                        + "DEUX USAGES, DEUX GESTES : « purpose »: « self » pour l'écran de "
                        + "l'utilisateur (démo, débogage) démarre sans autre formalité ; "
                        + "« purpose »: « meeting » pour une réunion à plusieurs exige "
                        + "« participants_informed »: true. NE COCHE JAMAIS cette confirmation "
                        + "toi-même : DEMANDE d'abord à l'utilisateur s'il a prévenu les "
                        + "participants, et ne rappelle l'outil qu'après sa réponse. Le produit NE "
                        + "PEUT PAS garantir que les participants soient informés — cela ne peut "
                        + "venir que de l'utilisateur, de vive voix ; répète-lui cette phrase, elle "
                        + "est dans le résultat. L'enregistrement porte un FILIGRANE incrusté dans "
                        + "l'image, et le résultat te rend « recordingNotice » : pose-le TEL QUEL "
                        + "en tête du compte rendu que tu écriras. La vidéo reste sur la machine.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "purpose", Map.of("type", "string",
                                        "enum", List.of("self", "meeting"),
                                        "description", "« self » = l'écran de l'utilisateur, "
                                                + "« meeting » = une réunion à plusieurs. "
                                                + "Obligatoire : je ne le devine pas."),
                                "participants_informed", Map.of("type", "boolean",
                                        "description", "UNIQUEMENT si l'utilisateur t'a dit avoir "
                                                + "prévenu les participants. Ne le mets jamais à "
                                                + "true de ta propre initiative."),
                                "subject", Map.of("type", "string",
                                        "description", "Sujet de la réunion, pour le compte rendu."),
                                "audio", Map.of("type", "boolean",
                                        "description", "Capturer le son (défaut : oui). Sans son, "
                                                + "il n'y aura AUCUNE transcription."),
                                "audio_device", Map.of("type", "string",
                                        "description", "Nom exact du périphérique audio, quand le "
                                                + "résultat précédent te l'a demandé (Windows)."),
                                "screen_device", text),
                        "required", List.of("purpose"))));

        tools.add(new AgentTool(CAPTURE_STOP,
                "ARRÊTE l'enregistrement local et DÉMARRE sa transcription SUR LA MACHINE — une "
                        + "capture locale n'a pas de transcription Teams, il faut la produire. "
                        + "C'est LONG : cet outil rend la main tout de suite, suis avec "
                        + CAPTURE_STATUS + " et NE CONCLUS PAS avant qu'elle soit terminée. Rien ne "
                        + "sort de la machine : ni la vidéo, ni l'audio, seulement le texte. Sans "
                        + "« capture_id », arrête celui qui tourne. Une fois la transcription "
                        + "finie, enchaîne sur " + MEETING_MOMENTS + " en lui donnant le "
                        + "« capture_id » — il y prendra tout seul la vidéo, les répliques et "
                        + "l'instant de début. Et répète toujours ce que le résultat dit ne PAS "
                        + "avoir pu faire : un enregistrement coupé au plafond de durée ne couvre "
                        + "pas toute la réunion, et le taire rendrait le compte rendu faux.",
                Map.of("type", "object", "properties", Map.of("capture_id", text))));

        tools.add(new AgentTool(CAPTURE_STATUS,
                "Dit si un enregistrement local tourne sur la machine, depuis combien de temps, où "
                        + "en est sa TRANSCRIPTION, et — quand elle est terminée — ses répliques "
                        + "(« cues »). Sans « capture_id », rend celui qui tourne. Appelle-le quand "
                        + "l'utilisateur demande « est-ce que ça enregistre toujours ? » — et quand "
                        + "il ne demande rien mais qu'un enregistrement traîne depuis longtemps, "
                        + "DIS-LE. Le moteur local ne dit PAS qui parle : ne devine jamais un "
                        + "locuteur, le résultat te le rappelle.",
                Map.of("type", "object", "properties", Map.of("capture_id", text))));
        return tools;
    }

    /**
     * <b>Les deux outils de lecture des fichiers</b> (F-108 / SF-108-03).
     *
     * <p>Ce sont des <b>lectures</b> : ni l'un ni l'autre ne demande de confirmation (cadrage §4.4).
     * Leurs descriptions portent ce que le modèle doit répéter : le chemin pris (synchronisé ou
     * navigateur), les gestes faits dans l'onglet, et la <b>provenance</b> — des adaptateurs écrits
     * sur la documentation Microsoft, à confirmer sur poste réel.</p>
     */
    private List<AgentTool> fileReadingTools() {
        Map<String, Object> text = Map.of("type", "string");
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new AgentTool(LIST_FILES,
                "Liste les dossiers et fichiers d'une bibliothèque Teams, SharePoint ou OneDrive "
                        + "professionnel. Donne « location » (l'adresse web d'un dossier : lien de "
                        + "la bibliothèque, lien d'une pièce jointe, vue AllItems), OU "
                        + "« conversation_id » (le dossier des fichiers partagés dans ce fil), OU "
                        + "« team » / « channel » (le site observé de l'équipe), OU "
                        + "« onedrive »: true. Sans rien, il rend les emplacements CONNUS, sans "
                        + "aucun geste. Si la bibliothèque est SYNCHRONISÉE sur la machine, elle est "
                        + "lue sur le disque (route SYNCED_FOLDER) ; sinon l'onglet relié est amené "
                        + "sur le site le temps de la lecture, puis REMIS (route BROWSER). C'est une "
                        + "lecture : aucune confirmation. Répète ce que le résultat dit ne PAS avoir "
                        + "pu lire : une réponse Microsoft non conforme rend ZÉRO élément et un "
                        + "manque nommé, jamais une liste à moitié. Ces adaptateurs sont écrits sur "
                        + "la documentation Microsoft et restent à confirmer sur poste réel.",
                Map.of("type", "object",
                        "properties", Map.of("location", text, "conversation_id", text,
                                "team", text, "channel", text,
                                "onedrive", Map.of("type", "boolean"),
                                "path", Map.of("type", "string",
                                        "description", "Sous-dossier du OneDrive, avec "
                                                + "« onedrive »: true.")))));
        tools.add(new AgentTool(READ_FILE,
                "Rapatrie un fichier Teams / SharePoint / OneDrive SUR LA MACHINE et rend son chemin "
                        + "local (« localPath ») : lis-le ensuite avec les outils du poste. Donne "
                        + "« file », l'adresse web du fichier. Dossier synchronisé : le fichier y est "
                        + "déjà. Sinon c'est CHROME qui le télécharge dans le dossier de travail du "
                        + "volet — aucune adresse signée ne passe par nous. C'est une lecture : "
                        + "aucune confirmation. Si « downloaded » est faux, dis pourquoi (téléchargement "
                        + "bloqué, accès refusé) et n'invente jamais le contenu ; si « inProgress » "
                        + "est vrai, redemande plus tard. Pour MODIFIER un document : lis-le ici, "
                        + "modifie la copie locale, puis redépose-la comme nouvelle version.",
                Map.of("type", "object",
                        "properties", Map.of("file", text),
                        "required", List.of("file"))));
        tools.add(new AgentTool(READ_DOCX,
                "Lit le TEXTE d'un fichier Word (.docx) DÉJÀ SUR LA MACHINE et le rend "
                        + "(« documentText ») : "
                        + "sers-t'en pour une TRANSCRIPTION Word rapatriée par " + READ_FILE + " ou "
                        + MEETING_RECORDING + ". Donne « file », le chemin ABSOLU du .docx sur la "
                        + "machine. C'est une lecture LOCALE : aucun réseau, aucun geste, aucune "
                        + "confirmation. Elle relaie le texte brut (paragraphes), elle n'analyse "
                        + "rien — l'analyse, c'est toi. Si « read » est faux, dis pourquoi (ce n'est "
                        + "pas un .docx lisible, fichier absent) et n'invente jamais le contenu ; si "
                        + "« truncated » est vrai, le texte a été coupé au plafond.",
                Map.of("type", "object",
                        "properties", Map.of("file", text),
                        "required", List.of("file"))));
        return tools;
    }

    /**
     * <b>Les sept écritures</b> (F-108 / SF-108-04, SF-108-06). Chacune est soumise à l'autorisation
     * du terminal
     * AVANT d'être émise (SF-108-02) ; leurs descriptions le disent au modèle, pour qu'il n'annonce
     * jamais une écriture comme faite avant le résultat, et pour qu'il enchaîne la modification d'un
     * document par lecture → modification locale → remplacement de version.
     */
    private List<AgentTool> fileWriteTools() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> address = Map.of("type", "string",
                "description", "Adresse web SharePoint / OneDrive (lien de la bibliothèque ou de "
                        + "l'élément, tel que " + LIST_FILES + " te l'a rendu).");
        Map<String, Object> localFile = Map.of("type", "string",
                "description", "Chemin ABSOLU du fichier sur la machine.");
        String common = " L'utilisateur doit l'AUTORISER dans le terminal avant qu'elle parte : "
                + "n'annonce rien comme fait avant le résultat, et si « done » est faux, dis ce qui "
                + "manque. Adaptateur écrit sur la documentation Microsoft, à confirmer sur poste réel.";
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new AgentTool(CREATE_FOLDER,
                "Crée un dossier dans une bibliothèque Teams / SharePoint / OneDrive. Refuse si un "
                        + "élément porte déjà ce nom." + common,
                Map.of("type", "object", "properties", Map.of("location", address, "name", text),
                        "required", List.of("location", "name"))));
        tools.add(new AgentTool(UPLOAD_FILE,
                "Dépose un fichier de la machine dans une bibliothèque. N'écrase JAMAIS : si le fichier "
                        + "existe, utilise " + REPLACE_VERSION + "." + common,
                Map.of("type", "object", "properties", Map.of("file", localFile, "location", address,
                                "name", Map.of("type", "string",
                                        "description", "Nom à donner au fichier déposé (défaut : "
                                                + "celui du fichier local).")),
                        "required", List.of("file", "location"))));
        tools.add(new AgentTool(RENAME,
                "Renomme un fichier ou un dossier, sans jamais écraser un élément existant." + common,
                Map.of("type", "object", "properties", Map.of("target", address, "name", text),
                        "required", List.of("target", "name"))));
        tools.add(new AgentTool(MOVE,
                "Déplace un fichier ou un dossier vers un autre dossier DU MÊME SITE, sans jamais "
                        + "écraser. Pour déplacer vers un AUTRE site, ce n'est pas cet outil : copie "
                        + "avec " + COPY + ", vérifie à destination, puis supprime l'original avec "
                        + DELETE + " (deux autorisations)." + common,
                Map.of("type", "object", "properties", Map.of("target", address, "destination", address),
                        "required", List.of("target", "destination"))));
        tools.add(new AgentTool(COPY,
                "Copie un fichier ou un dossier vers un autre dossier — DU MÊME SITE ou d'un AUTRE "
                        + "SITE — sans jamais écraser. Sur le même site, la copie est vérifiée à "
                        + "destination. Sur un AUTRE site, elle ne peut pas l'être depuis cet onglet : "
                        + "le résultat porte « verifyAtDestination » et « done » reste faux — VÉRIFIE "
                        + "à destination AVANT de supprimer l'original. C'est ainsi qu'on DÉPLACE "
                        + "entre sites : " + COPY + " puis " + DELETE + " (corbeille, restaurable), "
                        + "deux autorisations." + common,
                Map.of("type", "object", "properties", Map.of("target", address,
                                "destination", address,
                                "name", Map.of("type", "string",
                                        "description", "Nom à donner à la copie (défaut : celui de "
                                                + "la source).")),
                        "required", List.of("target", "destination"))));
        tools.add(new AgentTool(DELETE,
                "Supprime un fichier ou un dossier : il va dans la CORBEILLE du site, d'où il reste "
                        + "restaurable — dis-le." + common,
                Map.of("type", "object", "properties", Map.of("target", address),
                        "required", List.of("target"))));
        tools.add(new AgentTool(REPLACE_VERSION,
                "Remplace un document par une NOUVELLE VERSION tirée d'un fichier de la machine — c'est "
                        + "ainsi qu'on MODIFIE un document : " + READ_FILE + " → modification de la copie "
                        + "locale → cet outil. SharePoint garde l'historique des versions : l'ancienne "
                        + "reste restaurable, dis-le." + common,
                Map.of("type", "object", "properties", Map.of("target", address, "file", localFile),
                        "required", List.of("target", "file"))));
        return tools;
    }

    /**
     * <b>Les outils de présentation</b> (F-89 / SF-89-02) : ceux par lesquels l'agent <b>pose un
     * bloc</b> dans le fil plutôt que d'écrire du texte.
     *
     * <p>Ils vivent dans ce catalogue, donc sous la même garde : un terminal de projet ne les reçoit
     * <b>jamais</b>. C'est la règle non négociable du cadrage — <i>un terminal de projet reste
     * textuel pour toujours</i> — tenue par construction plutôt que par consigne.</p>
     *
     * <p><b>Trois schémas, et pas un champ de score.</b> La certitude est une énumération de deux
     * mots. Ce n'est pas une préférence de rédaction : un chiffre donnerait une apparence de mesure
     * à une interprétation, et « 82 % » se lit comme une mesure.</p>
     */
    private List<AgentTool> presentationTools() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> lineSchema = Map.of("type", "object",
                "properties", Map.of(
                        "text", Map.of("type", "string",
                                "description", "L'affirmation, en une phrase."),
                        "author", Map.of("type", "string",
                                "description", "Qui l'a écrite ou dite."),
                        "at", Map.of("type", "string",
                                "description", "Quand, au format ISO-8601, tel que l'outil de "
                                        + "lecture te l'a rendu. Ne le reformate pas."),
                        "messageId", Map.of("type", "string",
                                "description", "Identifiant du message source, tel que rendu."),
                        "webUrl", Map.of("type", "string",
                                "description", "Lien qui ouvre le fil à ce message."),
                        "certainty", Map.of("type", "string",
                                "enum", List.of("EXPLICITE", "A_CONFIRMER"),
                                "description", "EXPLICITE si c'est écrit noir sur blanc dans le "
                                        + "message ; A_CONFIRMER si c'est TA lecture de ce qui est "
                                        + "écrit. Dans le doute, A_CONFIRMER.")),
                "required", List.of("text"));
        Map<String, Object> linesSchema = Map.of("type", "array", "items", lineSchema);
        Map<String, Object> gapsSchema = Map.of("type", "array", "items", text,
                "description", "CE QUE TU N'AS PAS PU LIRE, tel que les outils de lecture te l'ont "
                        + "rendu : messages non reconnus, fil non atteint, plafond touché. "
                        + "Obligatoire. Une liste vide signifie « aucun manque signalé » — elle ne "
                        + "signifie jamais « j'ai tout lu ».");
        Map<String, Object> windowSchema = Map.of("type", "string",
                "description", "La fenêtre RÉELLEMENT lue, en toutes lettres : « du 5 au 12 "
                        + "septembre, 47 messages lus ». Jamais celle que tu avais demandée.");
        // F-91 / SF-91-03 — la mention d'un enregistrement local, à poser EN TÊTE du bloc. La
        // description est impérative parce que c'est le seul des quatre endroits où la trace voyage
        // qui dépende du modèle : les trois autres (filigrane dans l'image, journal d'audit,
        // en-tête du fichier de transcription) sont garantis par construction.
        Map<String, Object> noticeSchema = Map.of("type", "string",
                "description", "OBLIGATOIRE si ce compte rendu vient d'un ENREGISTREMENT LOCAL "
                        + "(teams_capture_*) : recopie TEL QUEL le « recordingNotice » que l'outil "
                        + "de capture t'a rendu. Il dit qui a enregistré, quand, et que les "
                        + "participants n'en ont pas été avertis par Teams. Ne le reformule pas, ne "
                        + "l'invente pas, et laisse-le vide pour un compte rendu bâti sur ce que "
                        + "Teams avait déjà.");

        List<AgentTool> tools = new ArrayList<>();
        tools.add(new AgentTool(MEETING_CARD,
                "Pose dans le fil la CARTE d'une réunion ou d'une conversation. Chaque ligne doit "
                        + "porter sa source : sans `messageId` ni `webUrl`, la ligne est REFUSÉE et "
                        + "la carte entière avec elle — ce qu'on affirme doit pouvoir s'ouvrir d'un "
                        + "clic. Mets en PREMIÈRE section ce qu'on attend du lecteur : c'est ce "
                        + "qu'il cherche, et il ne doit pas avoir à faire défiler pour le trouver.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string",
                                        "description", "Le sujet de la réunion."),
                                "subtitle", Map.of("type", "string",
                                        "description", "Date, durée, participants."),
                                "window", windowSchema,
                                "sections", Map.of("type", "array",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                        "title", text,
                                                        "lines", linesSchema),
                                                "required", List.of("title", "lines"))),
                                "gaps", gapsSchema,
                                "recordingNotice", noticeSchema),
                        "required", List.of("title", "window", "sections", "gaps"))));
        tools.add(new AgentTool(LIST,
                "Pose dans le fil une LISTE — des engagements, des mentions. Mêmes règles que la "
                        + "carte : chaque ligne porte sa source, et chaque ligne dit si elle est "
                        + "EXPLICITE ou A_CONFIRMER.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string",
                                        "description", "« Ce qu'on attend de vous », "
                                                + "« Vos engagements », « Vos mentions »."),
                                "subtitle", text,
                                "window", windowSchema,
                                "lines", linesSchema,
                                "gaps", gapsSchema,
                                "recordingNotice", noticeSchema),
                        "required", List.of("title", "window", "lines", "gaps"))));
        tools.add(new AgentTool(MOMENTS,
                "Pose dans le fil des MOMENTS : une image de ce qui était à l'écran, à côté de la "
                        + "phrase prononcée pendant qu'elle l'était. C'est l'HORODATAGE qui les "
                        + "rapproche — un moment sans heure est refusé. N'invente jamais un "
                        + "`imageId` : si tu n'en as pas, rends le moment sans image.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string",
                                        "description", "Le sujet de la réunion."),
                                "subtitle", text,
                                "window", windowSchema,
                                "moments", Map.of("type", "array",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                        "at", Map.of("type", "string",
                                                                "description", "Instant de la "
                                                                        + "phrase, ISO-8601."),
                                                        "quote", Map.of("type", "string",
                                                                "description", "Ce qui a été dit."),
                                                        "speaker", text,
                                                        "imageId", Map.of("type", "string",
                                                                "description", "Image remontée de "
                                                                        + "la machine, si tu en as "
                                                                        + "une."),
                                                        "webUrl", Map.of("type", "string",
                                                                "description", "Lien qui ouvre la "
                                                                        + "transcription à la "
                                                                        + "seconde.")),
                                                "required", List.of("at", "quote"))),
                                "gaps", gapsSchema,
                                "recordingNotice", noticeSchema),
                        "required", List.of("title", "window", "moments", "gaps"))));
        return tools;
    }
}
