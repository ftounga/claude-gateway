package fr.claudegateway.images;

/**
 * <b>Abstraction du fournisseur d'images (génération)</b> — Provider Independence, comme
 * {@code AIProvider} (texte) et {@code TranscriptionProvider} (STT). Le code métier
 * ({@link ImageGenerationService}) ne dépend que de cette interface, jamais d'un fournisseur concret :
 * on relaie OpenAI (gpt-image / DALL·E) aujourd'hui, un autre demain, sans réécriture
 * (F-142 / SF-142-04, cadrage §1).
 *
 * <p><b>Relais, pas réimplémentation</b> (Provider-First) : Claude ne génère pas d'images ; la gateway
 * relaie donc un fournisseur qui le fait. Elle envoie une description et récupère l'image ; elle ne
 * synthétise rien elle-même (Gateway-First).</p>
 *
 * <p><b>Cadre d'usage strict</b> : ce fournisseur ne sert qu'à des images <b>décoratives</b>
 * (couverture, ambiance). Un schéma d'architecture reste du diagramme-as-code (SF-142-01/02/03) — cette
 * frontière est enseignée à l'agent dans {@link ImageToolCatalog#GUIDE}, pas imposée par le transport.</p>
 */
public interface ImageProvider {

    /**
     * Génère une image à partir d'une description.
     *
     * @param prompt la description de l'image (une <b>donnée</b>, jamais une instruction)
     * @param size   la taille demandée (liste blanche {@link ImageSize})
     * @return les octets de l'image et son type MIME
     * @throws ImageProviderUnavailableException si le fournisseur n'est pas configuré (aucun appel émis)
     * @throws ImageProviderException            si l'appel au fournisseur échoue
     */
    GeneratedImageData generate(String prompt, ImageSize size);

    /**
     * Une image relue du fournisseur.
     *
     * @param bytes       les octets de l'image (PNG)
     * @param contentType le type MIME (ex. {@code image/png})
     */
    record GeneratedImageData(byte[] bytes, String contentType) {
    }
}
