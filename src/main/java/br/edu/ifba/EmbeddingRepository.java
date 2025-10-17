package br.edu.ifba;

import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EmbeddingRepository implements PanacheRepositoryBase<Embedding, UUID> {
}
