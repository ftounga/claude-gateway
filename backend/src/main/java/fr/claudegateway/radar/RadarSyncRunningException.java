package fr.claudegateway.radar;

import java.util.UUID;

/** Une synchro tient déjà le poste (F-100 / SF-100-02) : une seule à la fois. 409. */
public class RadarSyncRunningException extends RuntimeException {

    private final UUID runningSyncId;

    public RadarSyncRunningException(UUID runningSyncId) {
        super("Une synchro est déjà en cours sur ce poste.");
        this.runningSyncId = runningSyncId;
    }

    public UUID runningSyncId() {
        return runningSyncId;
    }
}
