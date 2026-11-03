package ru.wilyfox.drawless.config;

public enum ExtremeDisplayMode {
  OFF("Off"),
  STATIC_CACHE("Static scene cache"),
  LIVE_CACHE("Live geometry cache"),
  LIVE_EXTRACT("Live extraction cache (experimental)");
  private final String label;

  ExtremeDisplayMode(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public boolean liveCache() {
    return this == LIVE_CACHE || this == LIVE_EXTRACT;
  }
}
