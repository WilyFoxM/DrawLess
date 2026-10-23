package ru.wilyfox.drawless.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** A rebindable in-game opener; presses in other screens are discarded, never queued. */
public final class SettingsKeyBinding {
  private static final KeyMapping OPEN =
      KeyMappingHelper.registerKeyMapping(
          new KeyMapping(
              "key.drawless.open_settings",
              InputConstants.Type.KEYSYM,
              GLFW.GLFW_KEY_F8,
              KeyMapping.Category.register(Identifier.fromNamespaceAndPath("drawless", "main"))));

  private SettingsKeyBinding() {}

  public static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          boolean requested = false;
          while (OPEN.consumeClick()) requested = true;
          if (requested && client.player != null && client.gui.screen() == null)
            client.gui.setScreen(new DrawLessScreen(null));
        });
  }
}
