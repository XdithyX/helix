package io.helix.agent.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class PromptTemplateEngine {
  public String renderResource(String resourcePath, Map<String, String> values) {
    try (InputStream stream = PromptTemplateEngine.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (stream == null) {
        throw new IllegalArgumentException("Prompt template not found: " + resourcePath);
      }
      return render(new String(stream.readAllBytes(), StandardCharsets.UTF_8), values);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read prompt template: " + resourcePath, e);
    }
  }

  public String render(String template, Map<String, String> values) {
    String rendered = template == null ? "" : template;
    Map<String, String> safeValues = values == null ? Map.of() : values;
    rendered = rendered.replace("{{finding}}", safeValues.getOrDefault("finding", ""));
    rendered = rendered.replace("{{evidence}}", safeValues.getOrDefault("evidence", ""));
    rendered = rendered.replace("{{jvmProfile}}", safeValues.getOrDefault("jvmProfile", ""));
    rendered = rendered.replace("{{frameworkContext}}", safeValues.getOrDefault("frameworkContext", ""));
    return rendered;
  }
}
