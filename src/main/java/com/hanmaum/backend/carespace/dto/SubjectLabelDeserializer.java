package com.hanmaum.backend.carespace.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Do not silently turn a JSON number or boolean into a care subject label. */
public class SubjectLabelDeserializer extends ValueDeserializer<String> {
  @Override
  public String deserialize(JsonParser parser, DeserializationContext context) {
    if (!parser.hasToken(JsonToken.VALUE_STRING)) {
      return (String) context.handleUnexpectedToken(String.class, parser);
    }
    return parser.getString();
  }
}
