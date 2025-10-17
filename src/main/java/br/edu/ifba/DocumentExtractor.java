package br.edu.ifba;

import java.io.IOException;
import java.io.InputStream;

public interface DocumentExtractor {
    String extract(InputStream inputStream) throws IOException;
    boolean supports(String fileName);
}
