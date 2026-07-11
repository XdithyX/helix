package io.helix.reporter.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.helix.core.model.DiagnosticReport;
import io.helix.core.spi.ReportOptions;
import io.helix.core.spi.Reporter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

public final class JsonReporter implements Reporter {
  private final ObjectMapper objectMapper;

  public JsonReporter() {
    this(defaultObjectMapper());
  }

  JsonReporter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void render(DiagnosticReport report, OutputStream out, ReportOptions options)
      throws IOException {
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, report);
  }

  @Override
  public String format() {
    return "json";
  }

  public static ObjectMapper defaultObjectMapper() {
    SimpleModule helixModule = new SimpleModule("helix");
    helixModule.addSerializer(Path.class, new JsonSerializer<>() {
      @Override
      public void serialize(Path value, JsonGenerator gen, SerializerProvider serializers)
          throws IOException {
        gen.writeString(value.toString());
      }
    });

    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .registerModule(helixModule)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
  }
}
