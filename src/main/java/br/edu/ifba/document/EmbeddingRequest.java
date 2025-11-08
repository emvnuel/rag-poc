package br.edu.ifba.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmbeddingRequest {
    
    private String model;
    private String input;
    private List<String> inputList;
    
    // Default constructor for Jackson
    public EmbeddingRequest() {
    }
    
    public EmbeddingRequest(final String model, final String input, final List<String> inputList) {
        this.model = model;
        this.input = input;
        this.inputList = inputList;
    }
    
    public EmbeddingRequest(final String model, final String input) {
        this(model, input, null);
    }
    
    public EmbeddingRequest(final String model, final List<String> inputList) {
        this(model, null, inputList);
    }
    
    public String getModel() {
        return model;
    }
    
    public void setModel(final String model) {
        this.model = model;
    }
    
    public String getInputString() {
        return input;
    }
    
    public void setInput(final String input) {
        this.input = input;
    }
    
    public List<String> getInputList() {
        return inputList;
    }
    
    public void setInputList(final List<String> inputList) {
        this.inputList = inputList;
    }
    
    @JsonProperty("input")
    public Object getInput() {
        return inputList != null ? inputList : input;
    }
}
