package br.edu.ifba.document;

import java.time.LocalDateTime;
import java.util.UUID;

import br.edu.ifba.shared.GeneratedUuidV7;
import com.pgvector.PGvector;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "embeddings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class Embedding {

    @Id
    @GeneratedUuidV7
    @Column(columnDefinition = "UUID")
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "chunk_text", columnDefinition = "TEXT", nullable = false)
    private String chunkText;

    @Type(PGvectorUserType.class)
    @Column(name = "vector", columnDefinition = "vector(1024)", nullable = false)
    private PGvector vector;

    @Column(nullable = false)
    private String model;

    public Embedding(final Document document, final Integer chunkIndex, final String chunkText, 
                     final PGvector vector, final String model) {
        this.document = document;
        this.chunkIndex = chunkIndex;
        this.chunkText = chunkText;
        this.vector = vector;
        this.model = model;
    }
}
