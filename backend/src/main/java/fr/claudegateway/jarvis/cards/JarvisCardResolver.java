package fr.claudegateway.jarvis.cards;

import java.util.Optional;
import java.util.UUID;

/**
 * <b>Une famille de pancartes, relue à l'ouverture</b> (F-248 / SF-248-06) — {@code fait:<famille>:<ref>}. Chaque
 * famille relit <b>une ligne par index</b>, filtrée par {@code user_id} (et {@code host_id} pour le registre F-247), et
 * ne rend que des champs passés par la liste blanche. Une ligne d'un autre compte est introuvable.
 */
public interface JarvisCardResolver {

    /** La famille servie ({@code attente}, {@code envoi}, {@code courriel}, {@code personne}, {@code etat}…). */
    String family();

    /** Le détail de {@code ref} pour ce compte, ou vide. Ne lève pas pour une référence illisible (vide). */
    Optional<JarvisCardDetail> detail(UUID userId, String ref);
}
