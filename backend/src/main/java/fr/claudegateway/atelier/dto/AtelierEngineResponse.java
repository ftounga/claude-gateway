package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;

import fr.claudegateway.atelier.AtelierEngine;
import fr.claudegateway.atelier.AtelierEngineService.EngineStatus;
import fr.claudegateway.atelier.RunnerRecommendation;

/**
 * Moteur d'un projet, tel que l'écran le lit (F-39 / SF-39-07).
 *
 * @param engine           moteur résolu par la gateway ; l'utilisateur ne le choisit jamais
 * @param runnerConnected  un runner de ce projet est joignable maintenant. <b>Indépendant</b> de
 *                         {@code engine} : une cible « ma machine » dont le runner est éteint reste
 *                         en {@code LOCAL_MACHINE}, et l'écran dit « runner hors ligne »
 * @param runnerLastSeenAt dernière activité observée, {@code null} si aucun runner ne s'est jamais
 *                         signalé
 * @param recommendRunner  vrai s'il faut proposer le runner ici et maintenant (D6)
 * @param recommendReason  limite du bac à sable réellement rencontrée, {@code null} quand
 *                         {@code recommendRunner} est faux
 */
public record AtelierEngineResponse(
        AtelierEngine engine,
        boolean runnerConnected,
        OffsetDateTime runnerLastSeenAt,
        boolean recommendRunner,
        RunnerRecommendation recommendReason) {

    public static AtelierEngineResponse from(EngineStatus status) {
        return new AtelierEngineResponse(
                status.engine(), status.runnerConnected(), status.runnerLastSeenAt(),
                status.recommendRunner(), status.recommendReason());
    }
}
