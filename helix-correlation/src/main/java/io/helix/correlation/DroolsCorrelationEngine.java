package io.helix.correlation;

import io.helix.core.model.ParsedArtifact;
import io.helix.core.model.TimelineEvent;
import io.helix.core.spi.CorrelationResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

final class DroolsCorrelationEngine {
  private static final String RULE_RESOURCE = "/rules/phase7-correlations.drl";

  CorrelationResult correlate(
      List<ParsedArtifact> artifacts,
      List<TimelineEvent> timeline,
      List<String> bundledRuleNames) {
    KieHelper helper = new KieHelper()
        .setClassLoader(DroolsCorrelationEngine.class.getClassLoader())
        .addContent(readRules(), ResourceType.DRL);
    Results results = helper.verify();
    if (results.hasMessages(Message.Level.ERROR)) {
      throw new IllegalStateException("Invalid Phase 7 Drools rules: " + results.getMessages(Message.Level.ERROR));
    }

    KieSession session = helper.build().newKieSession();
    DroolsCorrelationContext context = new DroolsCorrelationContext(artifacts, timeline, bundledRuleNames);
    try {
      session.setGlobal("context", context);
      timeline.forEach(session::insert);
      session.fireAllRules();
      return context.toResult("drools", null);
    } finally {
      session.dispose();
    }
  }

  private static String readRules() {
    try (InputStream stream = DroolsCorrelationEngine.class.getResourceAsStream(RULE_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Missing bundled Phase 7 Drools rules: " + RULE_RESOURCE);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read bundled Phase 7 Drools rules", e);
    }
  }
}
