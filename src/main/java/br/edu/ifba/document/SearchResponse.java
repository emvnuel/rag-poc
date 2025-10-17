package br.edu.ifba.document;

import java.util.List;

public record SearchResponse(List<SearchResult> results) {
}
