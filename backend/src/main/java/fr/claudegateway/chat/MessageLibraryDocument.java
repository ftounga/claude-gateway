package fr.claudegateway.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Lien entre un message de chat et un document de la bibliothèque personnelle (F-08) importé dans ce
 * message (F-24 / SF-24-03). Persisté pour ré-injecter le texte OCR du document à <b>chaque</b> tour
 * (reconstruction de l'historique), comme claude.ai — et non plus au seul tour de l'import.
 */
@Entity
@Table(name = "message_library_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageLibraryDocument {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Message de chat dans lequel le document a été importé. */
    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    /** Document de la bibliothèque importé (= {@code documents.id}). */
    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
