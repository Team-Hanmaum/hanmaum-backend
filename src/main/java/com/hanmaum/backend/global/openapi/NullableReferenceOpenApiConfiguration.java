package com.hanmaum.backend.global.openapi;

import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class NullableReferenceOpenApiConfiguration {
  @Bean
  OpenApiCustomizer nullablePropertyReferences() {
    return api -> {
      // OpenAPI 3.1 applies $ref siblings together. A sibling type:null would reject
      // both an object and null against a referenced object schema or object union.
      for (Schema<?> schema : api.getComponents().getSchemas().values()) {
        if (schema.getProperties() == null) continue;
        for (Schema<?> property : schema.getProperties().values()) {
          if (property.get$ref() == null
              || !(Boolean.TRUE.equals(property.getNullable())
                  || (property.getTypes() != null && property.getTypes().contains("null")))) {
            continue;
          }
          String reference = property.get$ref();
          property.set$ref(null);
          property.setNullable(null);
          property.setType(null);
          property.setTypes(null);
          // Keep this property's description and metadata without making the shared target
          // nullable.
          property.setAnyOf(
              List.of(new Schema<>().$ref(reference), new Schema<>().types(Set.of("null"))));
        }
      }
    };
  }
}
