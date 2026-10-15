package com.hanmaum.backend.global.openapi;

import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class ProposalOpenApiConfiguration {
  @Bean
  OpenApiCustomizer proposalValueContract() {
    return api -> {
      var detail = api.getComponents().getSchemas().get("ProposalDetail");
      if (detail == null) return;
      // OpenAPI 3.1 applies $ref siblings together. A sibling type:null would reject
      // both an object and null against the object-only ProposalValues union.
      for (String name : List.of("currentValues", "proposedValues")) {
        var previous = (Schema<?>) detail.getProperties().get(name);
        var nullValue = new Schema<>();
        nullValue.setTypes(Set.of("null"));
        detail
            .getProperties()
            .put(
                name,
                new ComposedSchema()
                    .description(previous.getDescription())
                    .anyOf(
                        List.of(
                            new Schema<>().$ref("#/components/schemas/ProposalValues"),
                            nullValue)));
      }
    };
  }
}
