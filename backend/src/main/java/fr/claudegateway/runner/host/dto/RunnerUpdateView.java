package fr.claudegateway.runner.host.dto;

import java.util.List;

import fr.claudegateway.runner.update.RunnerUpdateProgress;

/**
 * Où en est le runner d'un poste par rapport à celui que la gateway distribue (F-111 / SF-111-01).
 *
 * <p>Ce que l'écran affiche dans la colonne des postes et l'en-tête du client, Forge et Vigie :
 * « à jour », « Mise à jour disponible — 1.0.0 → 1.1.0 », « Mise à jour requise », ou « mise à jour
 * manuelle une dernière fois » avec la commande. <b>Aucun refus n'en découle</b> : c'est une
 * information et, à partir de SF-111-04, une proposition.</p>
 *
 * @param status           {@link Status} en toutes lettres
 * @param required         vrai si une capacité dont le poste se sert manque au runner (ex. {@code teams})
 * @param installedVersion numéro sémantique du runner du poste, ou la valeur déclarée telle quelle
 * @param installedId      identifiant complet déclaré ({@code 1.0.0-202609131412-f30b4c0}), ou {@code null}
 * @param servedVersion    numéro sémantique du runner servi, ou {@code null}
 * @param servedId         identifiant complet du runner servi, ou {@code null}
 * @param installedJava    Java déclaré par le runner, ou {@code null}
 * @param requiredJava     Java minimal du runner servi
 * @param teamsMissing     vrai si un runner antérieur à F-111 n'annonce pas {@code teams}
 * @param notes            ce qu'apporte la version servie (liste courte), vide si inconnue
 * @param updatable        vrai si la gateway sert une version <b>signée</b> de ce runner (F-111 /
 *                         SF-111-03) : sans elle, aucune mise à jour d'un clic n'est possible
 * @param oneClick         vrai si le bouton « Mettre à jour » a un sens (F-111 / SF-111-04) : disponible,
 *                         signée, et runner qui comprend la commande ({@code contract >= 2})
 * @param progress         la dernière mise à jour de ce poste (F-111 / SF-111-04), ou {@code null}
 */
public record RunnerUpdateView(
        String status,
        boolean required,
        String installedVersion,
        String installedId,
        String servedVersion,
        String servedId,
        Integer installedJava,
        int requiredJava,
        boolean teamsMissing,
        List<String> notes,
        boolean updatable,
        boolean oneClick,
        RunnerUpdateProgress progress) {

    /** Les statuts possibles. */
    public enum Status {
        /** Rien à comparer : aucune version servie, ou aucune version lisible déclarée. */
        UNKNOWN,
        /** Le runner du poste est au moins aussi récent que celui servi. */
        UP_TO_DATE,
        /** Plus ancien, sous lanceur, Java suffisant : un clic suffira. */
        AVAILABLE,
        /** Plus ancien et sans lanceur : une dernière mise à jour manuelle. */
        MANUAL_LAST_TIME,
        /** Plus ancien, sous lanceur, mais la version servie exige un Java plus récent. */
        MANUAL_JAVA
    }

    public RunnerUpdateView {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** Vrai si le runner du poste est plus ancien que celui servi. */
    public boolean older() {
        return Status.AVAILABLE.name().equals(status) || Status.MANUAL_LAST_TIME.name().equals(status)
                || Status.MANUAL_JAVA.name().equals(status);
    }

    /**
     * La même vue, sachant si le poste <b>sert Teams</b> — ce que la vue d'ensemble n'apprend qu'une
     * fois les espaces posés (client actif dans la Vigie).
     */
    public RunnerUpdateView withTeamsUse(boolean usesTeams) {
        boolean nowRequired = required || (usesTeams && teamsMissing && older());
        return nowRequired == required ? this
                : new RunnerUpdateView(status, true, installedVersion, installedId, servedVersion, servedId,
                        installedJava, requiredJava, teamsMissing, notes, updatable, oneClick, progress);
    }

    /** La même vue avec ce que disent les artefacts servis (F-111 / SF-111-03, SF-111-04). */
    public RunnerUpdateView withArtifacts(List<String> newNotes, boolean newUpdatable, boolean newOneClick) {
        return new RunnerUpdateView(status, required, installedVersion, installedId, servedVersion, servedId,
                installedJava, requiredJava, teamsMissing, newNotes, newUpdatable, newOneClick, progress);
    }

    /** La même vue avec la dernière mise à jour du poste (F-111 / SF-111-04). */
    public RunnerUpdateView withProgress(RunnerUpdateProgress newProgress) {
        return new RunnerUpdateView(status, required, installedVersion, installedId, servedVersion, servedId,
                installedJava, requiredJava, teamsMissing, notes, updatable, oneClick, newProgress);
    }
}
