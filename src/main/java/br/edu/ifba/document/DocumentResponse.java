package br.edu.ifba.document;

import java.util.List;

public record DocumentResponse(List<String> chunks) {
}
