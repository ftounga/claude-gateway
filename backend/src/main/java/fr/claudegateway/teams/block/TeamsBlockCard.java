package fr.claudegateway.teams.block;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * <b>Un bloc riche du fil d'un terminal Teams</b> (F-89 / SF-89-02).
 *
 * <p>Jusqu'ici un bloc de terminal ne portait que du texte ({@code output: string}). Ce n'est donc
 * pas de la mise en forme : c'est <b>étendre ce qu'un terminal sait afficher</b>. Et cela ne vaut
 * que là — <i>un terminal de projet reste textuel pour toujours</i> : une sortie de commande est
 * exactement ce que la machine a répondu, jamais une carte.</p>
 *
 * <h2>Ce que la forme impose, et pourquoi</h2>
 *
 * <p><b>Chaque ligne porte sa source.</b> {@link Line#messageId} et {@link Line#webUrl} ne sont pas
 * décoratifs : ils sont ce qui rend une affirmation vérifiable d'un clic. Une ligne qui n'en porte
 * aucun n'entre pas dans un bloc (validation à l'émission, {@link TeamsBlockCards}).</p>
 *
 * <p><b>La certitude est un mot, jamais un nombre.</b> {@link Certainty} a deux valeurs, écrites en
 * toutes lettres à l'écran. <b>Il n'existe aucun champ numérique de confiance dans ce modèle</b>, et
 * c'est délibéré : un chiffre donnerait une apparence de mesure à une interprétation. « 82 % » se
 * lit comme une mesure ; « à confirmer » se lit comme ce que c'est.</p>
 *
 * <p><b>Le bloc dit sa fenêtre et ses manques.</b> {@link #window} porte la fenêtre <b>réellement
 * lue</b> (D4) et {@link #gaps} ce qui n'a pas pu l'être (§3.3 du cadrage). Les deux sont
 * obligatoires : un trou se voit, un trou silencieux ne se voit jamais — et un compte rendu
 * plausible et faux est pire qu'un compte rendu qui refuse, parce qu'on décide dessus.</p>
 *
 * <p>Tolérant aux champs inconnus : un bloc écrit par une version ultérieure se relit sans casser,
 * et un bloc antérieur — sans carte du tout — reste un bloc textuel.</p>
 *
 * @param kind     genre du bloc, voir {@link Kind}
 * @param title    titre du bloc (le sujet de la réunion, l'intitulé de la liste)
 * @param subtitle seconde ligne d'en-tête, ou vide (participants, dates)
 * @param window   <b>la fenêtre réellement lue</b>, en toutes lettres
 * @param sections sections de lignes — une seule pour une liste, plusieurs pour une carte
 * @param moments  moments (image + phrase prononcée), vide hors d'un bloc moment
 * @param gaps     <b>ce qui n'a pas pu être lu</b>, en toutes lettres ; vide = aucun manque signalé
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TeamsBlockCard(Kind kind, String title, String subtitle, String window,
        List<Section> sections, List<Moment> moments, List<String> gaps) {

    /** Les trois genres de bloc riche. Liste close : l'écran en connaît exactement trois. */
    public enum Kind {
        /** La carte d'une réunion : plusieurs sections de lignes sourcées. */
        MEETING_CARD,
        /** Une liste : engagements, mentions — une seule suite de lignes sourcées. */
        LIST,
        /** Des moments : une image posée à côté de la phrase prononcée pendant qu'elle s'affichait. */
        MOMENTS
    }

    /**
     * <b>Le niveau de certitude, en toutes lettres.</b>
     *
     * <p>Deux valeurs, et jamais un score. {@link #A_CONFIRMER} est le <b>défaut</b> : en cas de
     * doute, on penche du côté qui n'affirme rien.</p>
     */
    public enum Certainty {
        /** C'est écrit noir sur blanc dans un message : on peut l'ouvrir et le lire. */
        EXPLICITE("explicite"),
        /** C'est une lecture de ce qui est écrit, pas ce qui est écrit. */
        A_CONFIRMER("à confirmer");

        private final String label;

        Certainty(String label) {
            this.label = label;
        }

        /** Ce que l'écran écrit. Un seul endroit le dit, pour que deux écrans ne le disent pas autrement. */
        public String label() {
            return label;
        }
    }

    /** Une section d'un bloc : un titre, et les lignes qui en relèvent. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(String title, List<Line> lines) {
    }

    /**
     * <b>Une ligne vérifiable</b> : ce qui est dit, par qui, quand, et <b>où le vérifier</b>.
     *
     * @param text      l'affirmation, en une phrase
     * @param author    qui l'a écrite ou dite, ou vide
     * @param at        quand, au format ISO-8601 rendu par l'adaptateur, ou vide
     * @param messageId identifiant opaque du message source, ou vide
     * @param webUrl    lien qui ouvre le fil à la bonne position, ou vide
     * @param certainty « explicite » ou « à confirmer » — jamais un nombre
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String text, String author, String at, String messageId, String webUrl,
            Certainty certainty) {

        /** Vrai si la ligne peut être vérifiée : elle porte au moins une façon d'y revenir. */
        public boolean hasSource() {
            return !isBlank(messageId) || !isBlank(webUrl);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    /**
     * <b>Un moment</b> : l'image de ce qui était à l'écran, à côté de la phrase prononcée pendant
     * qu'elle l'était.
     *
     * <p>C'est <b>l'horodatage</b> qui les rapproche, et c'est pour cela qu'il est obligatoire :
     * sans lui, il n'y a pas de moment, il y a une galerie d'images et une transcription à côté.</p>
     *
     * @param at      instant de la phrase, au format ISO-8601
     * @param quote   ce qui a été dit
     * @param speaker qui parlait, ou vide
     * @param imageId image du stockage de moments, ou vide quand il n'y en a pas
     * @param webUrl  lien qui ouvre la transcription à la seconde, ou vide
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Moment(String at, String quote, String speaker, String imageId, String webUrl) {
    }

    /** Toutes les lignes du bloc, sections confondues — ce que les bornes comptent. */
    public List<Line> allLines() {
        return sections == null ? List.of()
                : sections.stream().flatMap(section -> section.lines().stream()).toList();
    }
}
