package br.edu.ifba.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class EmbeddingResponse {
    
    private String model;
    private List<Embedding> data;
    
    @JsonProperty("total_duration")
    private Long totalDuration;
    
    @JsonProperty("load_duration")
    private Long loadDuration;
    
    @JsonProperty("prompt_eval_count")
    private Integer promptEvalCount;
    
    // Default constructor for Jackson
    public EmbeddingResponse() {
    }
    
    public EmbeddingResponse(final String model, final List<Embedding> data, 
                            final Long totalDuration, final Long loadDuration, 
                            final Integer promptEvalCount) {
        this.model = model;
        this.data = data;
        this.totalDuration = totalDuration;
        this.loadDuration = loadDuration;
        this.promptEvalCount = promptEvalCount;
    }
    
    public String getModel() {
        return model;
    }
    
    public void setModel(final String model) {
        this.model = model;
    }
    
    public List<Embedding> getData() {
        return data;
    }
    
    public void setData(final List<Embedding> data) {
        this.data = data;
    }
    
    public Long getTotalDuration() {
        return totalDuration;
    }
    
    public void setTotalDuration(final Long totalDuration) {
        this.totalDuration = totalDuration;
    }
    
    public Long getLoadDuration() {
        return loadDuration;
    }
    
    public void setLoadDuration(final Long loadDuration) {
        this.loadDuration = loadDuration;
    }
    
    public Integer getPromptEvalCount() {
        return promptEvalCount;
    }
    
    public void setPromptEvalCount(final Integer promptEvalCount) {
        this.promptEvalCount = promptEvalCount;
    }
    
    @RegisterForReflection
    public static class Embedding {
        private List<Double> embedding;
        private Integer index;
        
        // Default constructor for Jackson
        public Embedding() {
        }
        
        public Embedding(final List<Double> embedding, final Integer index) {
            this.embedding = embedding;
            this.index = index;
        }
        
        public List<Double> getEmbedding() {
            return embedding;
        }
        
        public void setEmbedding(final List<Double> embedding) {
            this.embedding = embedding;
        }
        
        public Integer getIndex() {
            return index;
        }
        
        public void setIndex(final Integer index) {
            this.index = index;
        }
    }
}
