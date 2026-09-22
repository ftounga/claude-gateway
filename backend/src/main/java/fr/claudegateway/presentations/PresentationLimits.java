package fr.claudegateway.presentations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Les bornes des présentations (F-129 / SF-129-02, et SF-129-03 pour les slides). Réglables par
 * configuration ; des valeurs par défaut prudentes pour le coût (stockage + jetons).
 */
@Component
public class PresentationLimits {

    private final long maxPptxBytes;
    private final int maxSlides;
    private final long maxSlideBytes;

    public PresentationLimits(
            @Value("${app.presentations.max-pptx-bytes:26214400}") long maxPptxBytes,
            @Value("${app.presentations.max-slides:100}") int maxSlides,
            @Value("${app.presentations.max-slide-bytes:5242880}") long maxSlideBytes) {
        this.maxPptxBytes = maxPptxBytes;
        this.maxSlides = maxSlides;
        this.maxSlideBytes = maxSlideBytes;
    }

    /** Taille maximale d'un {@code .pptx} (défaut 25 Mo). */
    public long maxPptxBytes() {
        return maxPptxBytes;
    }

    /** Nombre maximal de slides rendues (défaut 100 — SF-129-03). */
    public int maxSlides() {
        return maxSlides;
    }

    /** Taille maximale d'une image de slide (défaut 5 Mo — SF-129-03). */
    public long maxSlideBytes() {
        return maxSlideBytes;
    }
}
