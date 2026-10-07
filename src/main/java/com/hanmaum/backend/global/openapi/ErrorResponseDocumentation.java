package com.hanmaum.backend.global.openapi;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.code.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/** Registers common and domain error enums as reusable OpenAPI responses. */
public final class ErrorResponseDocumentation {
  private ErrorResponseDocumentation() {}

  public static void register(Components components, ResourceLoader resourceLoader) {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.setResourceLoader(resourceLoader);
    scanner.addIncludeFilter(new AssignableTypeFilter(ErrorCode.class));
    List<ErrorCode> codes = new ArrayList<>();
    for (var candidate : scanner.findCandidateComponents("com.hanmaum.backend")) {
      var type =
          ClassUtils.resolveClassName(
              candidate.getBeanClassName(), resourceLoader.getClassLoader());
      if (type.isEnum()) {
        for (var constant : type.getEnumConstants()) {
          codes.add(ErrorCode.class.cast(constant));
        }
      }
    }
    register(components, codes);
  }

  static void register(Components components, Collection<? extends ErrorCode> codes) {
    Map<String, ErrorCode> byCode = new TreeMap<>();
    for (var code : codes) {
      if (byCode.putIfAbsent(code.code(), code) != null) {
        throw new IllegalArgumentException("Duplicate public error code: " + code.code());
      }
    }
    for (var code : byCode.values()) {
      components.addResponses(
          code.code(),
          new ApiResponse()
              .description(code.status().value() + " · " + code.message())
              .content(
                  new Content()
                      .addMediaType(
                          "application/json",
                          new MediaType()
                              .schema(new Schema<>().$ref("#/components/schemas/ApiResponse"))
                              .example(errorExample(code)))));
    }
  }

  private static Map<String, Object> errorExample(ErrorCode code) {
    Map<String, Object> example = new LinkedHashMap<>();
    example.put("success", false);
    example.put("code", code.code());
    example.put("message", code.message());
    example.put("data", null);
    example.put(
        "errors",
        code == CommonErrorCode.INVALID_REQUEST
            ? List.of(Map.of("field", "fieldName", "reason", "입력 내용을 확인해주세요."))
            : List.of());
    example.put("timestamp", "2026-10-07T08:00:00Z");
    return example;
  }
}
