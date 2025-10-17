package br.edu.ifba.document;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WebsiteDocumentExtractor implements DocumentExtractor {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern TITLE_PATTERN = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_DESC_PATTERN = Pattern.compile("<meta[^>]+name=['\"]description['\"][^>]+content=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    @Override
    public String extract(final InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            final StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append("\n");
            }
            return extractTextFromHtml(html.toString());
        }
    }

    @Override
    public Map<String, Object> extractMetadata(final InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            final StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append("\n");
            }
            final String htmlString = html.toString();
            final String extractedText = extractTextFromHtml(htmlString);
            return extractHtmlMetadata(htmlString, extractedText);
        }
    }

    @Override
    public boolean supports(final String fileName) {
        return false;
    }

    public String fetchAndExtract(final String url) throws IOException, InterruptedException {
        final String html = fetchHtml(url);
        return extractTextFromHtml(html);
    }

    public Map<String, Object> fetchAndExtractMetadata(final String url) throws IOException, InterruptedException {
        final String html = fetchHtml(url);
        final String extractedText = extractTextFromHtml(html);
        final Map<String, Object> metadata = extractHtmlMetadata(html, extractedText);
        metadata.put("url", url);
        return metadata;
    }

    private String fetchHtml(final String url) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        } else {
            throw new IOException("Failed to fetch URL: " + url + " (HTTP " + response.statusCode() + ")");
        }
    }

    private String extractTextFromHtml(final String html) {
        String text = html;
        
        text = text.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        text = text.replaceAll("(?is)<head[^>]*>.*?</head>", " ");
        text = text.replaceAll("(?is)<nav[^>]*>.*?</nav>", " ");
        text = text.replaceAll("(?is)<footer[^>]*>.*?</footer>", " ");
        text = text.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        text = text.replaceAll("(?is)<iframe[^>]*>.*?</iframe>", " ");
        text = text.replaceAll("(?is)<svg[^>]*>.*?</svg>", " ");
        
        text = text.replaceAll("(?is)\\bstyle\\s*=\\s*\"[^\"]*\"", " ");
        text = text.replaceAll("(?is)\\bstyle\\s*=\\s*'[^']*'", " ");
        text = text.replaceAll("(?is)\\bstyle\\s*=\\s*\\{[^}]*\\}", " ");
        
        text = text.replaceAll("(?is)\\bon[a-z]+\\s*=\\s*\"[^\"]*\"", " ");
        text = text.replaceAll("(?is)\\bon[a-z]+\\s*=\\s*'[^']*'", " ");
        
        text = HTML_TAG_PATTERN.matcher(text).replaceAll(" ");
        
        text = text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&#x27;", "'")
                   .replace("&mdash;", "—")
                   .replace("&ndash;", "–")
                   .replace("&hellip;", "...")
                   .replace("&copy;", "©")
                   .replace("&reg;", "®")
                   .replace("&trade;", "™");
        
        text = WHITESPACE_PATTERN.matcher(text).replaceAll(" ");
        
        return text.trim();
    }

    private Map<String, Object> extractHtmlMetadata(final String html, final String extractedText) {
        final Map<String, Object> metadata = new HashMap<>();
        
        final Matcher titleMatcher = TITLE_PATTERN.matcher(html);
        if (titleMatcher.find()) {
            metadata.put("title", titleMatcher.group(1).trim());
        }
        
        final Matcher descMatcher = META_DESC_PATTERN.matcher(html);
        if (descMatcher.find()) {
            metadata.put("description", descMatcher.group(1).trim());
        }
        
        metadata.put("characterCount", extractedText.length());
        metadata.put("wordCount", countWords(extractedText));
        
        return metadata;
    }

    private int countWords(final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
}
