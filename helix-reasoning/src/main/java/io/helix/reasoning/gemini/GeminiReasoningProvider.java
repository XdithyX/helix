package io.helix.reasoning.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.helix.agent.core.AgentJson;
import io.helix.agent.core.PromptTemplateEngine;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.model.Finding;
import io.helix.core.spi.ReasoningProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GeminiReasoningProvider implements ReasoningProvider {
  private static final String DEFAULT_MODEL = "gemini-2.5-flash";
  private static final URI DEFAULT_ENDPOINT = URI.create("https://generativelanguage.googleapis.com/v1beta/models/");

  private final String apiKey;
  private final String model;
  private final GeminiTransport transport;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final PromptTemplateEngine templates = new PromptTemplateEngine();

  public GeminiReasoningProvider() {
    this(System.getenv("GEMINI_API_KEY"), System.getenv("GEMINI_MODEL"), new HttpGeminiTransport(DEFAULT_ENDPOINT));
  }

  GeminiReasoningProvider(String apiKey, String model, GeminiTransport transport) {
    this.apiKey = apiKey;
    this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  @Override
  public String complete(String prompt) {
    return generate(prompt);
  }

  @Override
  public String explain(Finding finding, DiagnosticReport context) {
    String prompt = templates.render(
        """
        Explain this Helix finding using only sanitized fields.
        Finding: {{finding}}
        Evidence: {{evidence}}
        JVM profile: {{jvmProfile}}
        Framework context: {{frameworkContext}}
        """,
        Map.of(
            "finding", AgentJson.toJson(finding),
            "evidence", AgentJson.toJson(finding.evidence()),
            "jvmProfile", AgentJson.toJson(context.jvmProfile()),
            "frameworkContext", "{}"));
    return complete(prompt);
  }

  @Override
  public String narrateSummary(DiagnosticReport report) {
    String prompt = templates.render(
        """
        Summarize this Helix diagnostic report using only sanitized fields.
        Finding: {{finding}}
        Evidence: {{evidence}}
        JVM profile: {{jvmProfile}}
        Framework context: {{frameworkContext}}
        """,
        Map.of(
            "finding", AgentJson.toJson(report.findings()),
            "evidence", AgentJson.toJson(report.correlationSummary()),
            "jvmProfile", AgentJson.toJson(report.jvmProfile()),
            "frameworkContext", "{}"));
    return complete(prompt);
  }

  @Override
  public boolean isAvailable() {
    return apiKey != null && !apiKey.isBlank();
  }

  private String generate(String prompt) {
    if (!isAvailable()) {
      return "";
    }
    try {
      String response = transport.generate(apiKey, model, requestBody(prompt));
      JsonNode root = objectMapper.readTree(response);
      JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
      if (parts.isArray() && !parts.isEmpty()) {
        return parts.get(0).path("text").asText("");
      }
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Gemini reasoning request failed", e);
    } catch (IOException e) {
      throw new IllegalStateException("Gemini reasoning request failed", e);
    }
  }

  private String requestBody(String prompt) throws IOException {
    return objectMapper.writeValueAsString(Map.of(
        "contents", List.of(Map.of(
            "role", "user",
            "parts", List.of(Map.of("text", prompt))))));
  }

  interface GeminiTransport {
    String generate(String apiKey, String model, String requestBody) throws IOException, InterruptedException;
  }

  static URI generateContentUri(URI endpoint, String model, String apiKey) {
    String base = endpoint.toString();
    if (!base.endsWith("/")) {
      base += "/";
    }
    return URI.create(base
        + model
        + ":generateContent?key="
        + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
  }

  private static final class HttpGeminiTransport implements GeminiTransport {
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private HttpGeminiTransport(URI endpoint) {
      this.endpoint = endpoint;
    }

    @Override
    public String generate(String apiKey, String model, String requestBody) throws IOException, InterruptedException {
      URI uri = generateContentUri(endpoint, model, apiKey);
      HttpRequest request = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(30))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(requestBody))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IOException("Gemini returned HTTP " + response.statusCode());
      }
      return response.body();
    }
  }
}
