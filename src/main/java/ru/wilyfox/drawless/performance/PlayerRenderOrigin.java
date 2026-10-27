package ru.wilyfox.drawless.performance;

/** Player provenance of an extracted avatar state or an armor model submit. */
public interface PlayerRenderOrigin {
  boolean drawless$isPlayer();

  void drawless$setPlayer(boolean player);
}
