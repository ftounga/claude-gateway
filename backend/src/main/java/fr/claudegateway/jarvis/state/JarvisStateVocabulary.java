package fr.claudegateway.jarvis.state;

import java.util.Set;

/**
 * <b>Le vocabulaire fermé du registre</b> (F-247 / SF-247-02, cadrage §4.1 ; amendements F-248 D14/D15) : familles
 * d'objets, attributs, sources et confiances. Une observation hors vocabulaire est ignorée, jamais écrite.
 */
public final class JarvisStateVocabulary {

    /** Les familles d'objets ; {@code ACCESS} = identités du poste en lecture seule (F-248 D14 : compte, type, validité, état). */
    public static final Set<String> KINDS = Set.of("TICKET", "MR", "PIPELINE", "DEPLOYMENT", "RELEASE",
            "CLOUD_RESOURCE", "INFRA_STATE", "DB_SCHEMA", "GIT_BRANCH", "PAGE", "ACCESS", "OTHER");

    /** Les attributs (liste fermée, extensible) ; {@code url} conservé quand la sortie le porte (F-248 D15). */
    public static final Set<String> ATTRIBUTES = Set.of("status", "assignee", "title", "version", "image",
            "result", "health", "sync", "replicas", "updated_at", "blocked_by", "ahead_behind", "last_commit",
            "priority", "sprint", "approvals", "conflicts", "pipeline", "target", "branch", "commit",
            "failed_step", "revision", "tag", "outputs", "resources", "schema_version", "page_version", "state",
            "url", "account", "identity_type", "validity", "chart", "namespace", "author", "draft", "value");

    public static final String SOURCE_TOUR = "TOUR";
    public static final String SOURCE_NOTE = "NOTE";
    public static final String SOURCE_RELEVE = "RELEVE";
    public static final String SOURCE_VEILLE = "VEILLE";
    public static final Set<String> SOURCES = Set.of(SOURCE_TOUR, SOURCE_NOTE, SOURCE_RELEVE, SOURCE_VEILLE);

    /** JSON d'API parsé. */
    public static final String HAUTE = "HAUTE";
    /** Texte de CLI reconnu par un motif strict, ou constat rédigé dont la citation a été vérifiée. */
    public static final String MOYENNE = "MOYENNE";
    public static final Set<String> CONFIDENCES = Set.of(HAUTE, MOYENNE);

    /** Bornes. */
    public static final int MAX_KEY = 300;
    public static final int MAX_VALUE = 300;

    private JarvisStateVocabulary() {
    }
}
