package br.edu.ifba.lightrag.merge;

import br.edu.ifba.lightrag.core.Entity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * REST resource for entity management operations.
 * 
 * <h2>Endpoints:</h2>
 * <ul>
 *   <li>{@code POST /projects/{projectId}/entities/merge} - Merge multiple entities into one</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * POST /projects/01234567-89ab-cdef-0123-456789abcdef/entities/merge
 * Content-Type: application/json
 * 
 * {
 *   "sourceEntities": ["AI", "Artificial Intelligence", "Machine Intelligence"],
 *   "targetEntity": "Artificial Intelligence",
 *   "strategy": "LLM_SUMMARIZE",
 *   "targetEntityData": {
 *     "type": "CONCEPT"
 *   }
 * }
 * 
 * Response:
 * {
 *   "targetEntity": {
 *     "entityName": "Artificial Intelligence",
 *     "entityType": "CONCEPT",
 *     "description": "A unified description from LLM..."
 *   },
 *   "relationsRedirected": 5,
 *   "sourceEntitiesDeleted": 2,
 *   "relationsDeduped": 1
 * }
 * }</pre>
 * 
 * @see EntityMergeService
 * @see EntityMergeRequest
 * @see MergeResult
 * @since spec-007
 */
@Path("/projects/{projectId}/entities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EntityResources {
    
    private static final Logger LOG = Logger.getLogger(EntityResources.class);
    
    private final EntityMergeService mergeService;
    
    @Inject
    public EntityResources(EntityMergeService mergeService) {
        this.mergeService = mergeService;
    }
    
    /**
     * Merges multiple entities into a single target entity.
     * 
     * <p>This operation:
     * <ul>
     *   <li>Validates all source entities exist</li>
     *   <li>Redirects all relationships to the target entity</li>
     *   <li>Merges entity descriptions using the specified strategy</li>
     *   <li>Removes self-loop relationships created by the merge</li>
     *   <li>Deduplicates relationships with the same source-target pair</li>
     *   <li>Deletes the source entities and their embeddings</li>
     * </ul>
     * 
     * @param projectId The project UUID containing the entities
     * @param request The merge request containing source entities, target, and strategy
     * @return 200 OK with MergeResult on success
     *         400 Bad Request if validation fails
     *         500 Internal Server Error on unexpected failure
     */
    @POST
    @Path("/merge")
    public Response mergeEntities(
        @PathParam("projectId") String projectId,
        @Valid EntityMergeRequest request
    ) {
        LOG.infof("Entity merge request: project=%s, sources=%s, target=%s, strategy=%s",
            projectId, request.sourceEntities(), request.targetEntity(), request.strategy());
        
        try {
            MergeResult result = mergeService.mergeEntities(
                projectId,
                request.sourceEntities(),
                request.targetEntity(),
                request.getMergeStrategy(),
                request.targetEntityData()
            );
            
            return Response.ok(new MergeResponse(result)).build();
            
        } catch (IllegalArgumentException e) {
            LOG.warnf("Entity merge validation failed: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("VALIDATION_ERROR", e.getMessage()))
                .build();
                
        } catch (IllegalStateException e) {
            LOG.errorf(e, "Entity merge state error");
            return Response.status(Response.Status.CONFLICT)
                .entity(new ErrorResponse("MERGE_CONFLICT", e.getMessage()))
                .build();
                
        } catch (Exception e) {
            LOG.errorf(e, "Entity merge failed unexpectedly");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
                .build();
        }
    }
    
    /**
     * Response wrapper for successful merge operations.
     */
    public record MergeResponse(
        EntityResponse targetEntity,
        int relationsRedirected,
        int sourceEntitiesDeleted,
        int relationsDeduped,
        int totalOperations,
        boolean hasChanges
    ) {
        public MergeResponse(MergeResult result) {
            this(
                new EntityResponse(result.targetEntity()),
                result.relationsRedirected(),
                result.sourceEntitiesDeleted(),
                result.relationsDeduped(),
                result.totalOperations(),
                result.hasChanges()
            );
        }
    }
    
    /**
     * Entity data for API responses.
     */
    public record EntityResponse(
        String entityName,
        String entityType,
        String description,
        int sourceChunkCount
    ) {
        public EntityResponse(Entity entity) {
            this(
                entity.getEntityName(),
                entity.getEntityType(),
                entity.getDescription(),
                entity.getSourceChunkIds().size()
            );
        }
    }
    
    /**
     * Error response for merge failures.
     */
    public record ErrorResponse(
        String errorCode,
        String message
    ) {}
}
