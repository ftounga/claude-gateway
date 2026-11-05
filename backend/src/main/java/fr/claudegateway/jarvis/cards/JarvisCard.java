package fr.claudegateway.jarvis.cards;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <b>Une pancarte</b> (F-248 / SF-248-02, cadrage §4.1) — la fiche d'un fait <b>réellement lu</b> pour une réponse de
 * Jarvis, fabriquée par la passerelle (jamais par le modèle) à partir d'une ligne déjà relue sous {@code user_id}.
 *
 * <p>Ne porte que les champs de la <b>liste blanche</b> de son gabarit ({@link JarvisCardTemplates}) : un attribut hors
 * liste ne quitte jamais le serveur (D8). Les heures sont posées par la passerelle ; l'écran ne fait que formater.</p>
 *
 * @param id         {@code fait:<famille>:<ref>} — stable pour un même élément
 * @param type       {@code TICKET}, {@code MR}, {@code PIPELINE}, {@code DEPLOIEMENT}, {@code RESULTAT},
 *                   {@code RENDEZ_VOUS}, {@code COURRIEL}, {@code PERSONNE}, {@code ACCES}, {@code ATTENTE},
 *                   {@code GENERIQUE}
 * @param title      le nom (« PROJ-123 — Migration SSO »)
 * @param status     le mot du statut (toujours écrit) et son ton, ou {@code null}
 * @param fields     3 à 5 paires choisies par le gabarit
 * @param link       lien rapide (URL relevée, ou route de l'application), ou {@code null} — jamais deviné
 * @param observedAt heure d'observation posée par la passerelle
 * @param source     {@code tour}, {@code releve}, {@code veille}, {@code constat}, {@code direct}
 * @param group      le client (nom de l'univers), pour regrouper ; ou {@code null}
 * @param aliases    les formes sous lesquelles l'élément peut être prononcé (citation sur l'appareil, §4.3)
 * @param targeted   lu par une lecture <b>ciblée</b> (outil appelé pour la question) : montré d'office ; sinon montré
 *                   seulement s'il est cité (D2)
 */
public record JarvisCard(String id, String type, String title, Status status, List<Field> fields, String link,
        OffsetDateTime observedAt, String source, String group, List<String> aliases, boolean targeted) {

    public JarvisCard {
        fields = fields == null ? List.of() : List.copyOf(fields);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    /** La même fiche, lue par une lecture ciblée. */
    public JarvisCard asTargeted() {
        return targeted ? this : new JarvisCard(id, type, title, status, fields, link, observedAt, source, group,
                aliases, true);
    }

    /** Le statut : un mot écrit et son ton ({@code normal}, {@code ok}, {@code attend}, {@code alerte}, {@code eteint}). */
    public record Status(String word, String tone) {
    }

    /** Un champ de la liste blanche. */
    public record Field(String label, String value) {
    }
}
