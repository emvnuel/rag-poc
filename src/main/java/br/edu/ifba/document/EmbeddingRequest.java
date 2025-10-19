package br.edu.ifba.document;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddingRequest(String model, String input, List<String> inputList) {
    
    public EmbeddingRequest(final String model, final String input) {
        this(model, input, null);
    }
    
    public EmbeddingRequest(final String model, final List<String> inputList) {
        this(model, null, inputList);
    }
    
    @com.fasterxml.jackson.annotation.JsonProperty("input")
    public Object getInput() {
        return inputList != null ? inputList : input;
    }
}
