package ru.wilyfox.drawless.bridge;

/** A newly extracted entity state can borrow immutable item layers from the display cache. */
public interface DisplayLayerOwnership {
    boolean drawless$borrowedLayers();
    void drawless$ownLayers();
    void drawless$borrowLayers();
    long drawless$safeLayerFrame();
    void drawless$safeLayerFrame(long frame);
}
