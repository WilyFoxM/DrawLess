package ru.wilyfox.drawless.observe;

/** The one observer of this client, installed once during mod initialization. */
public final class Observation {
  private static Observer observer = Observer.NONE;

  private Observation() {}

  public static Observer get() {
    return observer;
  }

  public static void install(Observer next) {
    observer = next;
  }
}
