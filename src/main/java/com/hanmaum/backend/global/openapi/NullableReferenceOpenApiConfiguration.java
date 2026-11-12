package com.hanmaum.backend.global.openapi;

import io.swagger.v3.oas.models.media.Schema;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
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
      // Schema.equals/hashCode traverse children, so use identity for shared or cyclic graphs.
      Set<Schema<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
      for (Schema<?> schema : api.getComponents().getSchemas().values()) {
        rewriteNullableReferences(schema, visited);
      }
    };
  }

  private static void rewriteNullableReferences(Schema<?> schema, Set<Schema<?>> visited) {
    if (schema == null || !visited.add(schema)) return;
    // OpenAPI 3.1 applies $ref siblings together. A sibling type:null would reject
    // both an object and null against a referenced object schema or object union.
    if (schema.get$ref() != null
        && (Boolean.TRUE.equals(schema.getNullable())
            || (schema.getTypes() != null && schema.getTypes().contains("null")))) {
      String reference = schema.get$ref();
      schema.set$ref(null);
      schema.setNullable(null);
      schema.setType(null);
      schema.setTypes(null);
      // Keep local metadata; do not resolve or modify the referenced component through $ref.
      schema.setAnyOf(
          List.of(new Schema<>().$ref(reference), new Schema<>().types(Set.of("null"))));
    }

    Stream.of(schema.getProperties(), schema.getPatternProperties(), schema.getDependentSchemas())
        .filter(Objects::nonNull)
        .flatMap(properties -> properties.values().stream())
        .forEach(child -> rewriteNullableReferences(child, visited));
    Stream.of(schema.getAllOf(), schema.getAnyOf(), schema.getOneOf(), schema.getPrefixItems())
        .filter(Objects::nonNull)
        .flatMap(List::stream)
        .forEach(child -> rewriteNullableReferences(child, visited));
    Stream.of(
            schema.getItems(),
            schema.getNot(),
            schema.getContains(),
            schema.getPropertyNames(),
            schema.getContentSchema(),
            schema.getUnevaluatedProperties(),
            schema.getAdditionalItems(),
            schema.getUnevaluatedItems(),
            schema.getIf(),
            schema.getThen(),
            schema.getElse())
        .forEach(child -> rewriteNullableReferences(child, visited));
    // additionalProperties can also be a boolean, which is not a nested schema.
    if (schema.getAdditionalProperties() instanceof Schema<?> additionalProperties) {
      rewriteNullableReferences(additionalProperties, visited);
    }
  }
}
