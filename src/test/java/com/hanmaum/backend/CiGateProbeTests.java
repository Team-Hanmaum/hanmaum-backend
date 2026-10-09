package com.hanmaum.backend;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/** Temporary probe for verifying the required GitHub Actions check; remove after verification. */
class CiGateProbeTests {
  @Test
  void intentionallyFailsToVerifyTheRequiredCiCheck() {
    fail("CI_GATE_PROBE: intentional failure to verify the required Build and test check.");
  }
}
