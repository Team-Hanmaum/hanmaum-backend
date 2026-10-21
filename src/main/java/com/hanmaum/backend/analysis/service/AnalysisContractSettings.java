package com.hanmaum.backend.analysis.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only new acceptances use this value. Persisted runs never consult it again. */
@Component
public class AnalysisContractSettings {
  private final String version;

  public AnalysisContractSettings(@Value("${app.analysis.contract-version:v1}") String version) {
    if (!version.equals("v1") && !version.equals("v2"))
      throw new IllegalArgumentException("Analysis contract version must be v1 or v2");
    this.version = version;
  }

  public String version() {
    return version;
  }
}
