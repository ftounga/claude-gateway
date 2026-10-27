package fr.claudegateway.atelier.journey;

import java.time.OffsetDateTime;

/**
 * <b>Une attente F-175 telle qu'une étape la lit</b> (F-220 / SF-220-01) : son état, <b>qui</b> doit agir,
 * <b>à qui</b> on l'a demandée et <b>depuis quand</b>. Jamais d'identifiant de compte.
 *
 * @param status {@code A_FAIRE}, {@code DEMANDE}, {@code FAIT} ou {@code ANNULE}
 * @param qui    {@code MOI}, {@code CLIENT}, {@code TIERS}, ou {@code null} si non qualifiée
 * @param who    à qui (la personne sollicitée, sinon la personne citée), ou {@code null}
 * @param since  depuis quand (la demande, sinon la création), ou {@code null}
 */
public record WaitInfo(String status, String qui, String who, OffsetDateTime since) {

    /** Une attente dont on ne connaît que l'état. */
    public static WaitInfo ofStatus(String status) {
        return new WaitInfo(status, null, null, null);
    }

    /** Encore ouverte : à demander ou demandée. */
    public boolean open() {
        return "A_FAIRE".equals(status) || "DEMANDE".equals(status);
    }

    /** L'utilisateur peut la lever lui-même (F-180 D1). */
    public boolean mine() {
        return "MOI".equals(qui);
    }
}
