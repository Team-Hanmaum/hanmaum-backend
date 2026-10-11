package com.hanmaum.backend.carespace.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Preserve the public decimal-string contract instead of coercing JSON numbers. */
public class SpaceVersionDeserializer extends ValueDeserializer<String> {
  @Override
  public String deserialize(JsonParser parser, DeserializationContext context) {
    if (!parser.hasToken(JsonToken.VALUE_STRING)) {
      return (String) context.handleUnexpectedToken(String.class, parser);
    }
    return parser.getString();
  }
}
