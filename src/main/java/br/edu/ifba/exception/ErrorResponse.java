package br.edu.ifba.exception;

public record ErrorResponse(String type, String title, int status, String detail, String instance) {
}
