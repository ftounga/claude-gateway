package fr.claudegateway.runner.teams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Ce que la collecte peut faire pendant qu'elle travaille (F-100) : dire où elle en est, faire remonter
 * un lot, savoir si elle doit s'arrêter.
 */
interface RadarSyncContext {

    /**
     * Le battement : la phase et l'avancement. Rend faux si la synchro a été close côté gateway (annulée,
     * abandonnée) — la collecte doit alors s'arrêter.
     */
    boolean progress(String phase, int done, int total);

    /**
     * Fait remonter un lot (SF-100-03). Rend la réponse de la gateway ; {@code status = STOPPED} quand la
     * synchro est close.
     *
     * @throws java.io.IOException quand la remontée n'a pas pu se faire — le lot n'est pas considéré lu
     */
    JsonNode submit(ObjectNode body) throws java.io.IOException;

    /** Vrai quand la gateway a dit d'arrêter. */
    boolean stopped();
}
