package ru.wilyfox.drawless.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared menu surfaces and the DrawLess project palette. */
final class SettingsTheme {
  static final int BACKGROUND = 0xFF0A182A;
  static final int PANEL = 0xF2112135;
  static final int SURFACE = 0xFF15283E;
  static final int HOVER = 0xFF20364D;
  static final int ACCENT = 0xFFFDA24C;
  static final int SECONDARY = 0xFF7DCFB6;
  static final int TEXT = 0xFFE9EEF5;
  static final int MUTED = 0xFFA2B3C7;
  static final int DISABLED = 0xFF738398;

  private SettingsTheme() {}

  static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
    if (w <= 0 || h <= 0) return;
    int r = Math.clamp(radius, 0, Math.min(w, h) / 2);
    for (int i = 0; i < r; i++) {
      int inset = (int) Math.ceil(r - Math.sqrt(r * r - Math.pow(r - i - .5, 2)));
      g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
      g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
    }
    g.fill(x, y + r, x + w, y + h - r, color);
  }

  static String fit(Font font, String text, int width) {
    if (width <= 0) return "";
    if (font.width(text) <= width) return text;
    return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
  }
}
