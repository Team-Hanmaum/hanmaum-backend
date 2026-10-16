package com.hanmaum.backend.ai.dto.v2;

import java.util.UUID;

/** Complete wire values; nullable keys are included. This is not a product write contract. */
public sealed interface AiV2CurrentValues {
  record Schedule(
      String title,
      String description,
      String status,
      String scheduledDate,
      String scheduledTime,
      String location)
      implements AiV2CurrentValues {
    @Override
    public String toString() {
      return "Schedule[redacted]";
    }
  }

  record Task(
      String title, String description, String status, String dueDate, UUID assigneeMembershipId)
      implements AiV2CurrentValues {
    @Override
    public String toString() {
      return "Task[redacted]";
    }
  }

  record Question(String title, String description, String status, String answerText)
      implements AiV2CurrentValues {
    @Override
    public String toString() {
      return "Question[redacted]";
    }
  }

  record Observation(String title, String description, String observedDate, String observedTimeText)
      implements AiV2CurrentValues {
    @Override
    public String toString() {
      return "Observation[redacted]";
    }
  }

  record Guidance(String title, String description, String sourceName, String receivedDate)
      implements AiV2CurrentValues {
    @Override
    public String toString() {
      return "Guidance[redacted]";
    }
  }
}
