package fr.claudegateway.ocr;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import fr.claudegateway.ocr.provider.TextractProperties;

/**
 * Active la liaison des propriétés du pipeline OCR (F-05).
 */
@Configuration
@EnableConfigurationProperties({ OcrProperties.class, TextractProperties.class })
public class OcrConfig {
}
