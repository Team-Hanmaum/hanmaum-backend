package com.hanmaum.backend.careitem.dto;

public enum CareItemView {
  UPCOMING("SCHEDULE"),
  PAST("SCHEDULE"),
  CLOSED("SCHEDULE"),
  REMAINING("TASK"),
  COMPLETED("TASK"),
  UNCONFIRMED("QUESTION"),
  CONFIRMED("QUESTION");

  private final String itemType;

  CareItemView(String itemType) {
    this.itemType = itemType;
  }

  public String itemType() {
    return itemType;
  }
}
