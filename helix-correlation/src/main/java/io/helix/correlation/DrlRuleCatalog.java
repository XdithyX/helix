package io.helix.correlation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DrlRuleCatalog {
  private static final String RESOURCE = "/rules/phase7-correlations.drl";
  private static final Pattern RULE = Pattern.compile("^\\s*rule\\s+\"([^\"]+)\"\\s*$");

  private DrlRuleCatalog() {}

  static List<String> bundledRuleNames() {
    InputStream stream = DrlRuleCatalog.class.getResourceAsStream(RESOURCE);
    if (stream == null) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher matcher = RULE.matcher(line);
        if (matcher.matches()) {
          names.add(matcher.group(1));
        }
      }
      return List.copyOf(names);
    } catch (IOException e) {
      return List.of();
    }
  }
}
