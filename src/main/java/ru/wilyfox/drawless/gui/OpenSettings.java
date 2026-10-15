package ru.wilyfox.drawless.gui;

import net.minecraft.client.Minecraft;

/** Defers opening the settings screen until chat command input is closed. */
public final class OpenSettings implements Runnable {
    private static volatile boolean pending;
    @Override public void run() {
        // ChatScreen closes itself after command execution. Open on the following client tick.
        pending=true;
    }
    public static void openPending(Minecraft client) {
        if(!pending)return;
        pending=false;
        if(!(client.gui.screen() instanceof DrawLessScreen)) client.gui.setScreen(new DrawLessScreen(client.gui.screen()));
    }
}
