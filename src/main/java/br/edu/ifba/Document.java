package br.edu.ifba;

import java.time.LocalDateTime;
import java.util.UUID;

import br.edu.ifba.shared.GeneratedUuidV7;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class Document {

    @Id
    @GeneratedUuidV7
    @Column(columnDefinition = "UUID")
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(255) DEFAULT 'NOT_PROCESSED'")
    private DocumentStatus status;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String metadata;


    public Document(DocumentType type, String fileName, String content, String metadata) {
        this.type = type;
        this.fileName = fileName;
        this.content = content;
        this.metadata = metadata;
        this.status = DocumentStatus.NOT_PROCESSED;
    }

}
