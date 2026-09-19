package fr.claudegateway.teams.meeting.dto;

import java.util.List;
import java.util.UUID;

/**
 * Demande de <b>push des actions d'une réunion vers le Radar</b> (F-128 / SF-128-06).
 *
 * <p>Le consultant reste maître : il envoie <b>les actions qu'il a retenues</b> (issues de
 * l'exploitation SF-128-05) et, s'il le souhaite, <b>le sujet cible</b>. Sans {@code subjectId}, le
 * service retombe sur le sujet auquel la réunion est déjà rattachée ({@code meeting.subjectId}).</p>
 *
 * @param subjectId le sujet du Radar où pousser les engagements, ou {@code null} pour reprendre celui de
 *                  la réunion
 * @param actions   les textes des actions retenues (bornés et dédoublonnés côté service)
 */
public record PushActionsToRadarRequest(UUID subjectId, List<String> actions) {
}
