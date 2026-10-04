package fr.claudegateway.atelier.actions;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * <b>Le tableau des attentes vu d'un terminal</b> (F-175 / SF-175-01).
 *
 * @param hostId       le poste du terminal ({@code null} pour un terminal hébergé)
 * @param here         les attentes nées dans <b>ce</b> terminal — ouvertes, et fermées depuis 7 jours
 * @param host         celles des <b>autres</b> terminaux du même poste, mêmes règles, avec leur nom
 * @param aFaire       nombre d'attentes « À faire » du poste (ou du terminal seul s'il est hébergé)
 * @param demande      nombre d'attentes « Demandé », même portée
 * @param aRelancer    nombre d'attentes « Demandé » dont la relance est due (F-175 / SF-175-06)
 * @param oldestOpenAt la naissance de la plus ancienne attente ouverte, même portée — {@code null} s'il
 *                     n'y en a aucune
 */
public record TerminalActionBoardResponse(
        UUID hostId,
        List<TerminalActionResponse> here,
        List<TerminalActionResponse> host,
        int aFaire,
        int demande,
        int aRelancer,
        OffsetDateTime oldestOpenAt) {
}
