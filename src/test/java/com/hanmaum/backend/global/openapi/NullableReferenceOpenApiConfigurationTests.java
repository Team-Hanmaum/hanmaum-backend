package com.hanmaum.backend.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class NullableReferenceOpenApiConfigurationTests {
  @ParameterizedTest(name = "{0}")
  @MethodSource("nestedLocations")
  void rewritesNullableReferencesAtNestedSchemaLocations(
      String location, BiConsumer<Schema<Object>, Schema<Object>> attach) {
    var reference = nullableReference().description("중첩된 객체 또는 null").readOnly(true);
    var nested = new Schema<Object>();
    attach.accept(nested, reference);
    // More than one level of array/map nesting must not stop traversal.
    var root = new Schema<>().items(new Schema<>().additionalProperties(nested));

    customize(root);

    assertNullableReference(reference);
    assertThat(reference.getDescription()).isEqualTo("중첩된 객체 또는 null");
    assertThat(reference.getReadOnly()).isTrue();
  }

  static Stream<Arguments> nestedLocations() {
    return Stream.of(
            new Location("properties", (p, c) -> p.addProperty("value", c)),
            new Location("items", Schema::setItems),
            new Location("additionalProperties", Schema::setAdditionalProperties),
            new Location("allOf", (p, c) -> p.setAllOf(List.of(c))),
            new Location("anyOf", (p, c) -> p.setAnyOf(List.of(c))),
            new Location("oneOf", (p, c) -> p.setOneOf(List.of(c))),
            new Location("not", Schema::setNot),
            new Location("prefixItems", (p, c) -> p.setPrefixItems(List.of(c))),
            new Location("contains", Schema::setContains),
            new Location("patternProperties", (p, c) -> p.setPatternProperties(Map.of("^key", c))),
            new Location("propertyNames", Schema::setPropertyNames),
            new Location("contentSchema", Schema::setContentSchema),
            new Location("unevaluatedProperties", Schema::setUnevaluatedProperties),
            new Location("additionalItems", Schema::setAdditionalItems),
            new Location("unevaluatedItems", Schema::setUnevaluatedItems),
            new Location("if", Schema::setIf),
            new Location("then", Schema::setThen),
            new Location("else", Schema::setElse),
            new Location("dependentSchemas", (p, c) -> p.setDependentSchemas(Map.of("key", c))))
        .map(location -> Arguments.of(location.name(), location.attach()));
  }

  @Test
  void handlesSharedInstancesCyclesAndRepeatedCustomization() {
    var reference = nullableReference();
    var sharedArray = new Schema<>().items(reference);
    var root = new Schema<>().addProperty("first", sharedArray).addProperty("second", sharedArray);
    root.addProperty("self", root);
    sharedArray.setAdditionalProperties(root);

    assertThatCode(
            () -> {
              customize(root);
              customize(root);
            })
        .doesNotThrowAnyException();

    assertNullableReference(reference);
    assertThat(root.getProperties().get("first")).isSameAs(sharedArray);
    assertThat(root.getProperties().get("second")).isSameAs(sharedArray);
    assertThat(root.getProperties().get("self")).isSameAs(root);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void preservesBooleanMapRulesNonNullableReferencesAndExampleData(boolean allowed) {
    var reference = new Schema<>().$ref("#/components/schemas/Target");
    var nullableScalar = new Schema<>().types(Set.of("string", "null"));
    var example = Map.of("$ref", "literal example value", "nullable", true);
    var root =
        new Schema<>()
            .additionalProperties(allowed)
            .addProperty("object", reference)
            .addProperty("label", nullableScalar)
            .example(example);

    customize(root);

    assertThat(root.getAdditionalProperties()).isEqualTo(allowed);
    assertThat(reference.get$ref()).isEqualTo("#/components/schemas/Target");
    assertThat(reference.getAnyOf()).isNull();
    assertThat(nullableScalar.getTypes()).containsExactlyInAnyOrder("string", "null");
    assertThat(root.getExample()).isEqualTo(example);
  }

  @Test
  void rewritesNullableComponentAliases() {
    var alias = new Schema<>().$ref("#/components/schemas/Target").nullable(true);
    customize(alias);
    assertNullableReference(alias);
  }

  private static Schema<Object> nullableReference() {
    return new Schema<>().$ref("#/components/schemas/Target").types(Set.of("null"));
  }

  private static void customize(Schema<?> root) {
    new NullableReferenceOpenApiConfiguration()
        .nullablePropertyReferences()
        .customise(new OpenAPI().components(new Components().addSchemas("Root", root)));
  }

  private static void assertNullableReference(Schema<?> schema) {
    assertThat(schema.get$ref()).isNull();
    assertThat(schema.getType()).isNull();
    assertThat(schema.getTypes()).isNull();
    assertThat(schema.getNullable()).isNull();
    assertThat(schema.getAnyOf()).hasSize(2);
    assertThat(schema.getAnyOf().get(0).get$ref()).isEqualTo("#/components/schemas/Target");
    assertThat(schema.getAnyOf().get(1).getTypes()).containsExactly("null");
  }

  private record Location(String name, BiConsumer<Schema<Object>, Schema<Object>> attach) {}
}
