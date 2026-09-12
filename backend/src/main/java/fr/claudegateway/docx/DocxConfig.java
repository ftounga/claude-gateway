package fr.claudegateway.docx;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active la liaison des garde-fous de lecture d'un {@code .docx} (F-86 / SF-86-01). */
@Configuration
@EnableConfigurationProperties(DocxProperties.class)
public class DocxConfig {
}
