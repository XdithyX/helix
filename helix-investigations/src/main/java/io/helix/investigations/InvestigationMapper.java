package io.helix.investigations;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Provides the single {@link ObjectMapper} used for all investigation
 * serialization and deserialization.
 *
 * <p>Default typing is activated only for the {@code java.lang.Object} wildcard
 * (i.e. the values in {@code ParsedArtifact.data}) so that typed domain objects
 * such as {@code ThreadDumpData} survive a round-trip without manual registration.
 * The type validator restricts allowed types to the {@code io.helix} package tree.
 */
final class InvestigationMapper {

  private static final PolymorphicTypeValidator TYPE_VALIDATOR =
      BasicPolymorphicTypeValidator.builder()
          .allowIfSubType("io.helix.")
          .allowIfSubType("java.util.")
          .allowIfSubType("java.lang.")
          .build();

  static ObjectMapper create() {
    return JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        // Record fields are accessible via constructor/getter; no field visibility override needed.
        // Activate default typing for Object-typed fields (ParsedArtifact.data values).
        .activateDefaultTypingAsProperty(
            TYPE_VALIDATOR,
            ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT,
            "@type")
        .build();
  }

  private InvestigationMapper() {}
}
