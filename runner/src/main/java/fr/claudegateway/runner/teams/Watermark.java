package fr.claudegateway.runner.teams;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * <b>Le filigrane</b> (F-91 / SF-91-01, garde-fou n° 1 du cadrage) : ce qui empêche un
 * enregistrement d'être <b>anonyme</b>.
 *
 * <h2>La correction que le cadrage a apportée, et qu'il faut garder en tête</h2>
 *
 * <p>On ne peut <b>rien afficher dans la réunion des autres</b> — seul Teams le peut, et seulement
 * quand c'est lui qui enregistre. Ce que le produit peut garantir est plus modeste, et c'est
 * exactement cela qu'il garantit : <b>qu'un enregistrement ne soit jamais anonyme</b>.</p>
 *
 * <h2>Incrusté, pas posé à côté</h2>
 *
 * <p>Le filigrane est passé à {@code ffmpeg} comme <b>filtre vidéo</b> : il est dessiné dans chaque
 * trame, à la volée, pendant la capture. Ce n'est pas une coquetterie — un champ de métadonnées se
 * perd au premier réencodage, un fichier de provenance posé à côté se perd au premier envoi.
 * <b>La trace doit voyager avec l'artefact</b>, et la seule façon de le garantir est qu'elle soit
 * <i>dans l'image</i>.</p>
 *
 * <h2>Ce qu'il dit</h2>
 *
 * <p>Qui enregistre, depuis quand, avec quoi — et, pour une réunion, <b>que c'est une réunion</b>.
 * Un filigrane qui dirait seulement « enregistré » ne dirait pas l'essentiel : la personne
 * responsable.</p>
 *
 * @param identity  qui enregistre, tel qu'on peut le nommer depuis le poste
 * @param startedAt instant du démarrage, en UTC
 * @param purpose   l'usage, qui change le texte
 */
public record Watermark(String identity, Instant startedAt, CapturePurpose purpose) {

    /** Taille du texte incrusté. Lisible sur une vidéo redimensionnée, sans manger l'écran. */
    static final int FONT_SIZE = 18;
    /** Marge depuis le bord. */
    static final int MARGIN = 12;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRENCH).withZone(ZoneId.of("UTC"));

    public Watermark {
        identity = identity == null || identity.isBlank() ? "poste inconnu" : identity.strip();
        startedAt = startedAt == null ? Instant.EPOCH : startedAt;
    }

    /**
     * <b>La ligne incrustée dans l'image.</b>
     *
     * <p>Elle est volontairement en une seule ligne : deux lignes doublent la surface masquée et
     * la première chose qu'un utilisateur cherche à faire d'un filigrane gênant, c'est le rogner.</p>
     */
    public String line() {
        String kind = purpose == CapturePurpose.MEETING_WITH_OTHERS ? "réunion" : "écran";
        return "Enregistrement local (" + kind + ") - " + identity + " - " + STAMP.format(startedAt)
                + " UTC - Claude Gateway";
    }

    /**
     * <b>La mention en tête du compte rendu</b> : la même trace, en toutes lettres, pour le texte
     * qui remontera. La vidéo porte la sienne dans l'image ; le compte rendu porte celle-ci.
     */
    public String mention() {
        String kind = purpose == CapturePurpose.MEETING_WITH_OTHERS
                ? "une réunion à plusieurs" : "l'écran du poste";
        return "Ce compte rendu provient d'un ENREGISTREMENT LOCAL de " + kind + ", réalisé par "
                + identity + " le " + STAMP.format(startedAt) + " UTC depuis Claude Gateway — et non "
                + "d'un enregistrement Teams. Les participants n'en ont pas été avertis par Teams.";
    }

    /**
     * <b>Le filtre {@code drawtext}</b> à passer à {@code ffmpeg}, prêt à être inséré dans un
     * graphe de filtres.
     *
     * <p>Le fond semi-opaque n'est pas décoratif : sur un partage d'écran clair, un texte blanc sans
     * fond est illisible — donc absent en pratique.</p>
     *
     * @param font fichier de police ; <b>obligatoire</b> — sans police, l'appelant refuse de capturer
     */
    public String drawtextFilter(Path font) {
        if (font == null) {
            throw new IllegalArgumentException("un filigrane sans police n'est pas un filigrane");
        }
        return "drawtext=fontfile=" + escape(forwardSlashes(font))
                + ":text=" + escape(line())
                + ":fontcolor=white@0.92"
                + ":fontsize=" + FONT_SIZE
                + ":box=1:boxcolor=black@0.55:boxborderw=6"
                + ":x=" + MARGIN + ":y=" + MARGIN;
    }

    /**
     * Le chemin de la police en séparateurs avant. Sur Windows, {@code C:\Windows\Fonts\arial.ttf}
     * donnerait, une fois échappé, une suite d'antislashs qu'{@code ffmpeg} relit comme des
     * échappements : la forme {@code C:/Windows/Fonts/arial.ttf} est celle qu'il accepte partout, et
     * elle laisse le seul {@code :} à échapper.
     */
    static String forwardSlashes(Path font) {
        return font.toAbsolutePath().toString().replace('\\', '/');
    }

    /**
     * <b>L'échappement du graphe de filtres</b>, et la raison pour laquelle il ne se saute pas.
     *
     * <p>Un nom d'utilisateur qui contient {@code :} ou {@code '} — un compte de domaine, un nom
     * composé — couperait le filtre en deux options, et {@code ffmpeg} refuserait de démarrer. Le
     * résultat serait « la capture ne marche pas chez ce client-là », sans que personne comprenne
     * pourquoi.</p>
     *
     * <p>On échappe les séparateurs du graphe ({@code , ; [ ] =}), les séparateurs d'options
     * ({@code :}), les guillemets et l'antislash lui-même — en premier, sinon on échapperait les
     * échappements. {@code %} est échappé parce que {@code drawtext} l'interprète comme une
     * séquence de date.</p>
     */
    static String escape(String raw) {
        String value = raw == null ? "" : raw;
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '\\' || c == '\'' || c == ':' || c == ',' || c == ';' || c == '['
                    || c == ']' || c == '=' || c == '%') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
