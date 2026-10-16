## What DrawLess does

DrawLess is a client-side rendering optimization mod for Minecraft 26.2 on Fabric.
Its current focus is **Item Display scenes**, including animated ModelEngine NPCs
and resource-pack models built from display entities. It is designed to reduce
repeated rendering work in scenes with many model parts, especially where those
parts share geometry.

DrawLess caches reusable model layers and keeps shared model geometry in GPU
buffers. Its default **Live geometry** mode updates the current transforms,
lighting and tint, then batches supported parts by material. This lets it reuse
the model's shape while its position, pose and appearance continue to update.

With the default settings:

- **Entity movement and animation ticks remain live.**
- **Unsupported parts use ordinary rendering**, including materials and effects
  that the custom rendering path cannot handle.
- **NPC pose budgeting, tick pausing and experimental particle caches remain off.**

DrawLess runs entirely on the client; no server installation is needed. **Sodium
is optional**, and neither Sodium nor ImmediatelyFast is bundled with the mod.
The custom GPU rendering path currently requires OpenGL and uses ordinary
rendering when Iris is present.

Open **`/drawless settings`** to configure the mod. **`/drawless status`** shows
which rendering path is active and why parts may fall back to ordinary rendering.
DrawLess is in early development: optimization coverage and performance depend
on the scene, resource pack and renderer.

## Measured performance

[![DrawLess 0.1.0 benchmark on an animated Item Display spawn: baseline 242.91 FPS, DrawLess 294.50 FPS, +21.24% mean paired FPS improvement; Sodium enabled and ImmediatelyFast absent.]({{CARD_IMAGE_URL}})]({{REPORT_URL}})

In this animated Item Display spawn scene, DrawLess 0.1.0 improved mean observed FPS
by **21.24% across three bracketed ON comparisons**. Each ON run is compared with
the mean of its neighboring OFF controls; all seven 20-second phases are retained.
The mean bracketed baseline was **242.91 FPS**, versus **294.50 FPS** with DrawLess ON.

Tested with **Sodium**, without ImmediatelyFast, on a Ryzen 5 5600H and RTX 3060
Laptop GPU, Minecraft 26.2 / Fabric. The comparison changes the DrawLess master
switch with its default optimization preset selected; it does not compare against
unmodified Minecraft.

The scene contained **563 Item Display entities**. The operator observed unchanged
NPC appearance and animation; NPC pose budgeting, tick pausing and particle
experiments were disabled.

This is a single-session result for one scene. Performance depends on the scene,
hardware and settings. Exact framebuffer dimensions were not recorded; the full
report lists the available conditions and limitations.

[Full benchmark report]({{REPORT_URL}}) · [Raw measurements]({{RAW_URL}}) · [Methodology]({{METHOD_URL}}) · [All seven phases]({{ALL_PHASES_IMAGE_URL}})

### Detailed benchmark results

[![Mean observed FPS for all seven recorded OFF and ON phases.]({{ALL_PHASES_IMAGE_URL}})]({{REPORT_URL}})

### Measured optimization cost

Across **16,835 frames** in warm ON sample intervals, frame-weighted CPU costs were
**0.166 ms/frame** for live geometry capture, **0.026 ms/frame** for upload submission
and **0.076 ms/frame** for draw submission.

[![Measured live-cache CPU costs for all three ON phases, in milliseconds per frame.]({{CACHE_CPU_IMAGE_URL}})]({{REPORT_URL}})

These are three measured CPU scopes. They exclude cold-start construction, GPU
execution and unmeasured overhead; they do not represent the mod's total cost.
