package fr.claudegateway.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.governance.GovernanceHostFiles.HostFileRead;
import fr.claudegateway.governance.GovernanceHostFiles.Presence;
import fr.claudegateway.governance.dto.GovernanceMapFileContent;
import fr.claudegateway.governance.dto.GovernanceMapFileView;
import fr.claudegateway.governance.dto.GovernanceMapView;

/**
 * <b>Ce que la machine sait</b>, lu depuis la gateway (F-92 / SF-92-02).
 *
 * <p>SF-92-01 a posé les fichiers de carte à la racine du poste. <b>Un fichier qu'on ne voit jamais
 * n'est pas un savoir, c'est un fichier</b> : tant que la carte ne se lit qu'en ouvrant un terminal,
 * elle n'existe pas pour l'utilisateur, et le but de la feature — « à chaque projet qu'on rajoute, la
 * connaissance de l'infra augmente » — reste invérifiable. Ce service fournit la matière que l'écran
 * met en forme.</p>
 *
 * <p><b>On ne lit que la carte.</b> Les chemins lisibles sont ceux des fichiers de genre
 * {@link GovernanceFileKind#MAP} apportés par les paquets <b>actifs sur ce poste</b> — pas un de
 * plus. Ouvrir une lecture arbitraire du disque d'un client sous couvert de gouvernance serait
 * exactement l'inverse de ce que cette feature protège.</p>
 *
 * <p><b>Le coût est borné.</b> Six fichiers, six allers-retours : c'est acceptable, un délai par
 * fichier ne l'est pas. Dès qu'une lecture revient <b>injoignable</b>
 * ({@link Presence#UNREACHABLE}), les suivantes ne partent pas et le relevé est rendu « non lu »,
 * avec son geste. Un fichier simplement illisible, lui, n'arrête rien : le suivant peut très bien
 * répondre.</p>
 *
 * <p><b>Trois « non » différents.</b> Poste sans machine, poste non gouverné, machine muette : trois
 * situations, trois gestes à proposer. Les fondre en « carte indisponible » enverrait l'utilisateur
 * chercher au mauvais endroit.</p>
 *
 * <p><b>Isolation.</b> Le poste est vérifié possédé par {@link GovernanceHostScope} avant d'arriver
 * ici, et les activations sont lues par {@code user_id} + {@code host_id}.</p>
 */
@Service
public class GovernanceMapReadingService {

    /** Message et geste quand le poste n'est pas une machine. */
    static final String NOT_A_MACHINE =
            "Ce poste n'est pas une machine : la carte vit à la racine d'un poste réel. "
                    + "Connectez une machine pour qu'elle ait une carte.";

    /** Message et geste quand rien n'est activé sur le poste. */
    static final String NOT_GOVERNED =
            "Aucune gouvernance active sur ce poste : activez « Le savoir durable » depuis l'écran "
                    + "Gouvernance pour que sa carte existe.";

    /** Message et geste quand la machine n'a pas répondu. */
    static final String UNREACHABLE =
            "La racine de ce poste n'a pas pu être lue : lancez le runner sur la machine, "
                    + "puis rechargez.";

    /** Message et geste quand un fichier de carte manque à la racine. */
    static final String FILE_MISSING =
            "Ce fichier de carte manque : reprenez « Appliquer » sur ce poste pour le reposer.";

    /** Message et geste quand un fichier de carte existe mais n'a pas pu être lu. */
    static final String FILE_UNREADABLE =
            "Ce fichier n'a pas pu être lu sur la machine : vérifiez les droits, puis rechargez.";

    private final GovernanceMapDestinations destinations;
    private final GovernanceHostFiles hostFiles;
    private final GovernanceHostScope hostScope;

    public GovernanceMapReadingService(GovernanceMapDestinations destinations,
            GovernanceHostFiles hostFiles, GovernanceHostScope hostScope) {
        this.destinations = destinations;
        this.hostFiles = hostFiles;
        this.hostScope = hostScope;
    }

    /** Le relevé de la carte d'un poste <b>déjà vérifié possédé</b>. */
    @Transactional(readOnly = true)
    public GovernanceMapView describe(UUID userId, GovernanceHostRef host) {
        String hostName = hostScope.nameOf(userId, host);
        if (!hostFiles.supports(host)) {
            return empty(host, hostName, false, false, NOT_A_MACHINE);
        }
        Map<String, GovernancePackageFile> expected = expectedFiles(userId, host);
        if (expected.isEmpty()) {
            return empty(host, hostName, true, false, NOT_GOVERNED);
        }

        List<GovernanceMapFileView> files = new ArrayList<>(expected.size());
        boolean reachable = true;
        int present = 0;
        int sections = 0;
        int facts = 0;
        for (Map.Entry<String, GovernancePackageFile> entry : expected.entrySet()) {
            String path = entry.getKey();
            String fallbackTitle = entry.getValue().getPath();
            if (!reachable) {
                // La machine s'est déjà tue : on ne relance pas cinq délais pour l'apprendre cinq
                // fois. Le fichier est rendu « non lu », et le relevé porte le geste.
                files.add(unreadable(path, fallbackTitle));
                continue;
            }
            HostFileRead read = hostFiles.read(userId, host, path);
            if (read.presence() == Presence.UNREACHABLE) {
                reachable = false;
                files.add(unreadable(path, fallbackTitle));
                continue;
            }
            if (read.presence() == Presence.ABSENT) {
                files.add(new GovernanceMapFileView(path, fallbackTitle, false, true, List.of(), 0,
                        false, FILE_MISSING));
                continue;
            }
            if (read.presence() != Presence.PRESENT) {
                files.add(new GovernanceMapFileView(path, fallbackTitle, false, false, List.of(), 0,
                        false, FILE_UNREADABLE));
                continue;
            }
            GovernanceMapDigest.Digest digest =
                    GovernanceMapDigest.of(read.contentOrEmpty(), fallbackTitle);
            present++;
            sections += digest.sections().size();
            facts += digest.facts();
            files.add(new GovernanceMapFileView(path, digest.title(), true, true, digest.sections(),
                    digest.facts(), read.truncated(), null));
        }
        return new GovernanceMapView(host.ref(), host.publicId(), hostName, true, true, reachable,
                reachable ? null : UNREACHABLE, List.copyOf(files), expected.size(), present,
                sections, facts);
    }

    /**
     * Le contenu exact d'un fichier <b>de la carte</b>.
     *
     * @throws GovernancePackageNotFoundException si le chemin n'appartient pas à la carte de ce poste
     */
    @Transactional(readOnly = true)
    public GovernanceMapFileContent readFile(UUID userId, GovernanceHostRef host, String path) {
        String wanted = GovernancePath.normalizeOrNull(path);
        Map<String, GovernancePackageFile> expected = expectedFiles(userId, host);
        if (wanted == null || !expected.containsKey(wanted)) {
            // Volontairement identique qu'il s'agisse d'un chemin inconnu ou d'un fichier bien réel
            // de la racine : cette route n'est pas un explorateur de fichiers.
            throw new GovernancePackageNotFoundException(
                    "Ce chemin n'appartient pas à la carte de ce poste.");
        }
        String fallbackTitle = expected.get(wanted).getPath();
        HostFileRead read = hostFiles.read(userId, host, wanted);
        if (read.presence() != Presence.PRESENT) {
            String message = switch (read.presence()) {
                case ABSENT -> FILE_MISSING;
                case UNREACHABLE -> UNREACHABLE;
                case UNSUPPORTED -> NOT_A_MACHINE;
                default -> FILE_UNREADABLE;
            };
            return new GovernanceMapFileContent(wanted, fallbackTitle, false, "", false, message);
        }
        String content = clamp(read.contentOrEmpty());
        GovernanceMapDigest.Digest digest = GovernanceMapDigest.of(content, fallbackTitle);
        return new GovernanceMapFileContent(wanted, digest.title(), true, content,
                read.truncated() || content.length() < read.contentOrEmpty().length(), null);
    }

    // -------------------------------------------------------------- internes

    /**
     * Les fichiers de carte attendus sur ce poste.
     *
     * <p>Déléguée à {@link GovernanceMapDestinations} depuis F-93 / SF-93-01 : la même liste sert
     * désormais à <b>rendre</b> la carte et à <b>nommer la destination</b> d'une promotion dans un
     * message correctif. Deux copies auraient divergé au premier paquet ajouté.</p>
     */
    private Map<String, GovernancePackageFile> expectedFiles(UUID userId, GovernanceHostRef host) {
        return destinations.filesOf(userId, host);
    }

    private static GovernanceMapView empty(GovernanceHostRef host, String hostName,
            boolean supported, boolean governed, String message) {
        return new GovernanceMapView(host.ref(), host.publicId(), hostName, supported, governed,
                false, message, List.of(), 0, 0, 0, 0);
    }

    private static GovernanceMapFileView unreadable(String path, String title) {
        return new GovernanceMapFileView(path, title, false, false, List.of(), 0, false, UNREACHABLE);
    }

    /** Borne de contenu rendu, alignée sur celle de F-75 / SF-75-02 — et la coupe se <b>dit</b>. */
    private static String clamp(String content) {
        return content.length() <= GovernanceFileReadingService.MAX_CONTENT_CHARS ? content
                : content.substring(0, GovernanceFileReadingService.MAX_CONTENT_CHARS);
    }
}
