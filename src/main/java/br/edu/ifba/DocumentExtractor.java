package br.edu.ifba;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public interface DocumentExtractor {
    String extract(InputStream inputStream) throws IOException;
    Map<String, Object> extractMetadata(InputStream inputStream) throws IOException;
    boolean supports(String fileName);
}
