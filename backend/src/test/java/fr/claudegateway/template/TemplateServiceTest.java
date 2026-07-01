package fr.claudegateway.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests unitaires de l'isolation {@code user_id} et de la normalisation pour la gestion des
 * modèles de prompts (F-13) : détail, mise à jour et suppression d'un modèle d'autrui sont
 * indistincts d'un modèle absent ; {@code name}/{@code content} sont trimés ; la catégorie par
 * défaut est {@code OTHER}.
 */
@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    @Mock
    private TemplateRepository templateRepository;

    @InjectMocks
    private TemplateService templateService;

    private final UUID alice = UUID.randomUUID();

    @Test
    void getOwnedReturnsTemplateOfUser() {
        UUID id = UUID.randomUUID();
        PromptTemplate template = PromptTemplate.builder()
                .id(id).userId(alice).name("Audit").category(TemplateCategory.AUDIT).content("c").build();
        when(templateRepository.findByIdAndUserId(id, alice)).thenReturn(Optional.of(template));

        assertThat(templateService.getOwned(id, alice)).isSameAs(template);
    }

    @Test
    void getOwnedThrowsForForeignTemplate() {
        UUID id = UUID.randomUUID();
        when(templateRepository.findByIdAndUserId(id, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.getOwned(id, alice))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    void createTrimsFieldsAndAppliesDefaultCategory() {
        when(templateRepository.save(any(PromptTemplate.class))).thenAnswer(i -> i.getArgument(0));

        PromptTemplate created = templateService.create(alice, "  Rapport  ", null, "  corps  ");

        assertThat(created.getUserId()).isEqualTo(alice);
        assertThat(created.getName()).isEqualTo("Rapport");
        assertThat(created.getContent()).isEqualTo("corps");
        assertThat(created.getCategory()).isEqualTo(TemplateCategory.OTHER);
    }

    @Test
    void createKeepsProvidedCategory() {
        when(templateRepository.save(any(PromptTemplate.class))).thenAnswer(i -> i.getArgument(0));

        PromptTemplate created = templateService.create(alice, "Audit", TemplateCategory.AUDIT, "c");

        assertThat(created.getCategory()).isEqualTo(TemplateCategory.AUDIT);
    }

    @Test
    void updateThrowsForForeignTemplate() {
        UUID id = UUID.randomUUID();
        when(templateRepository.findByIdAndUserId(id, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.update(id, alice, "n", TemplateCategory.OTHER, "c"))
                .isInstanceOf(TemplateNotFoundException.class);
        verify(templateRepository, never()).save(any());
    }

    @Test
    void updateModifiesOwnedTemplate() {
        UUID id = UUID.randomUUID();
        PromptTemplate template = PromptTemplate.builder()
                .id(id).userId(alice).name("old").category(TemplateCategory.OTHER).content("old").build();
        when(templateRepository.findByIdAndUserId(id, alice)).thenReturn(Optional.of(template));
        when(templateRepository.save(any(PromptTemplate.class))).thenAnswer(i -> i.getArgument(0));

        PromptTemplate updated = templateService.update(id, alice, "  New  ", TemplateCategory.REPORT, "  body  ");

        assertThat(updated.getName()).isEqualTo("New");
        assertThat(updated.getCategory()).isEqualTo(TemplateCategory.REPORT);
        assertThat(updated.getContent()).isEqualTo("body");
    }

    @Test
    void deleteThrowsForForeignTemplate() {
        UUID id = UUID.randomUUID();
        when(templateRepository.findByIdAndUserId(id, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.delete(id, alice))
                .isInstanceOf(TemplateNotFoundException.class);
        verify(templateRepository, never()).delete(any());
    }
}
