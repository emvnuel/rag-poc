package br.edu.ifba.project;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import br.edu.ifba.document.Document;
import br.edu.ifba.shared.GeneratedUuidV7;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "projects", schema = "rag")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class Project {

    @Id
    @GeneratedUuidV7
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Keycloak subject ID (sub claim) of the project creator.
     * Nullable for backward compatibility with existing projects.
     * Legacy projects (owner_id = NULL) can only be modified by admins.
     */
    @Column(name = "owner_id", length = 255)
    private String ownerId;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<Document> documents = new HashSet<>();

    public Project(final String name) {
        this.name = name;
    }

    public Project(final String name, final String ownerId) {
        this.name = name;
        this.ownerId = ownerId;
    }
}
