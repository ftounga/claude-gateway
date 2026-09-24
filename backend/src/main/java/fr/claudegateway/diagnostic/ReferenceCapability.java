package fr.claudegateway.diagnostic;

/**
 * <b>Une capacité de référence</b> (F-156 / SF-156-04) : ce qu'un harnais de cet ordre sait faire,
 * et ce que notre produit en fait.
 *
 * <p><b>Une absence peut être délibérée.</b> Les <i>hooks</i> sont écrits « hors périmètre » dans
 * F-39 : les proposer à chaque rapport rendrait le diagnostic insupportable. Une référence écartée
 * porte donc {@code excludedBecause}, et n'apparaît <b>jamais</b> comme un manque.</p>
 *
 * @param id              identifiant stable de la référence
 * @param name            nom lisible
 * @param gives           ce que la capacité apporte, en une phrase
 * @param capabilityId    la capacité du produit qui la porte, ou {@code null}
 * @param excludedBecause la raison pour laquelle elle est écartée, ou {@code null}
 */
public record ReferenceCapability(
        String id,
        String name,
        String gives,
        String capabilityId,
        String excludedBecause) {

    /** Portée par le produit. */
    static ReferenceCapability carriedBy(String id, String name, String gives, String capabilityId) {
        return new ReferenceCapability(id, name, gives, capabilityId, null);
    }

    /** Écartée volontairement, avec sa raison. */
    static ReferenceCapability excluded(String id, String name, String gives, String because) {
        return new ReferenceCapability(id, name, gives, null, because);
    }

    /** Ni portée ni écartée : un manque réel, et le seul cas qui appelle une feature. */
    static ReferenceCapability missing(String id, String name, String gives) {
        return new ReferenceCapability(id, name, gives, null, null);
    }

    public boolean isExcluded() {
        return excludedBecause != null && !excludedBecause.isBlank();
    }

    public boolean isCarried() {
        return capabilityId != null && !capabilityId.isBlank();
    }
}
