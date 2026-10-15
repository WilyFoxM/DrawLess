package ru.wilyfox.drawless.performance;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.item.*;
import net.minecraft.client.color.item.Constant;
import net.minecraft.client.color.item.CustomModelDataSource;
import net.minecraft.client.color.item.Dye;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.diagnostics.RenderMetrics;
import ru.wilyfox.drawless.mixin.*;

import java.util.*;

/** Cache only item layers; entity transforms, interpolation, lighting and submission stay vanilla. */
public final class DisplayModelBudget {
    private static final int MAX_ENTRIES = 2048;
    private static final long RETAIN_NANOS = 30_000_000_000L;
    private static final IdentityHashMap<Display.ItemDisplay, Entry> entries = new IdentityHashMap<>();
    private static final IdentityHashMap<Entity, Job> jobsByRoot = new IdentityHashMap<>();
    private static final ArrayList<Job> jobs = new ArrayList<>();
    private static final DisplayFrameBudget budget = new DisplayFrameBudget();
    private static ClientLevel level;
    private static boolean enabled, registered;
    private static long reused, rebuilt, deferred, bypassed, mandatory, constructorReused;
    // Never reset this token: a reused entity render state must not inherit a proof from an older frame.
    private static long layerFrame;
    private static final ThreadLocal<ConstructorSlot> constructorSlot=ThreadLocal.withInitial(ConstructorSlot::new);

    private DisplayModelBudget() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> entries.remove(entity));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (level != client.level || !DrawLessConfig.get().displayModelBudget) {
                if (enabled || !entries.isEmpty()) clear();
            }
        });
    }

    public static void clear() {
        entries.clear(); jobsByRoot.clear(); jobs.clear(); level = null; enabled = false;
        budget.reset(); reused = rebuilt = deferred = bypassed = mandatory = constructorReused = 0;
        clearConstructorLayers();
    }

    public static void beginFrame(Minecraft client, Camera camera) {
        boolean requested = DrawLessConfig.get().enabled && DrawLessConfig.get().displayModelBudget
                &&(DrawLessConfig.get().extremeDisplayMode==ru.wilyfox.drawless.config.ExtremeDisplayMode.OFF
                ||DrawLessConfig.get().extremeDisplayMode==ru.wilyfox.drawless.config.ExtremeDisplayMode.TICKS_ONLY
                ||DrawLessConfig.get().extremeDisplayMode==ru.wilyfox.drawless.config.ExtremeDisplayMode.STATIC_CACHE
                ||DrawLessConfig.get().extremeDisplayMode.liveCache());
        if (!requested || client.level == null) {
            if (enabled || !entries.isEmpty()) clear();
            return;
        }
        if (level != client.level) clear();
        level = client.level; enabled = true;
        layerFrame++;
        long now = System.nanoTime();
        var config = DrawLessConfig.get();
        budget.begin(now, config.displayModelBudgetMicros, config.adaptiveDisplayModelBudget);
        reused = rebuilt = deferred = bypassed = mandatory = constructorReused = 0;
        try (var ignored = RenderMetrics.getInstance().scope("render/displayBudget/refresh")) {
            refreshPending(client, camera, now);
        }
    }

    /** Collect changed cached layers first, so all known parts on the same vehicle publish together. */
    private static void refreshPending(Minecraft client, Camera camera, long now) {
        jobsByRoot.clear(); jobs.clear();
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.entity.isRemoved() || entry.entity.level() != level || now - entry.lastSeen > RETAIN_NANOS) {
                iterator.remove(); continue;
            }
            var source = entry.entity.itemRenderState();
            if (source == null || source.itemStack().isEmpty()) { iterator.remove(); continue; }
            if (entry.matches(source.itemStack(), source.itemTransform())) { entry.dirtySince = 0; continue; }
            if (!canCache(client, entry.entity, source.itemStack(), source.itemTransform())) { iterator.remove(); continue; }
            if (entry.dirtySince == 0) entry.dirtySince = now;
            Entity root = entry.entity.getRootVehicle();
            Job job = jobsByRoot.get(root);
            if (job == null) {
                job = new Job(); jobsByRoot.put(root, job); jobs.add(job);
            }
            job.entries.add(entry);
            job.waiting = Math.max(job.waiting, now - entry.dirtySince);
            // A model may be translated away from its entity position: distance is only priority.
            job.distanceSq = Math.min(job.distanceSq, entry.entity.distanceToSqr(camera.position()));
        }
        jobs.sort(Comparator.<Job, Boolean>comparing(job -> job.waiting < 250_000_000L)
                .thenComparingDouble(job -> job.distanceSq).thenComparing(Comparator.comparingLong((Job job) -> job.waiting).reversed()));
        for (Job job : jobs) {
            if (!budget.admit(job.waiting, job.distanceSq <= 64)) { deferred += job.entries.size(); continue; }
            // Complete an admitted group even if its cost crosses the soft limit.
            long started = System.nanoTime();
            try {
                for (Entry entry : job.entries) {
                    var source = entry.entity.itemRenderState();
                    entry.pending = new ItemStackRenderState();
                    entry.resolver.updateForNonLiving(entry.pending, source.itemStack(), source.itemTransform(), entry.entity);
                }
                for (Entry entry : job.entries) {
                    var source = entry.entity.itemRenderState();
                    entry.publish(entry.pending, source.itemStack(), source.itemTransform());
                    rebuilt++;
                }
            } finally {
                budget.charge(System.nanoTime() - started);
                for (Entry entry : job.entries) entry.pending = null;
            }
        }
        jobsByRoot.clear(); jobs.clear();
    }

    /** Called instead of updateForNonLiving only inside ItemDisplayRenderer. No work when disabled. */
    public static boolean hasSafeLayers(Display.ItemDisplay entity, long now) {
        if (!enabled || !DrawLessConfig.get().displayModelBudget || entity.level()!=level) return false;
        var source=entity.itemRenderState();var entry=entries.get(entity);
        if(source==null || entry==null || !entry.matches(source.itemStack(),source.itemTransform())) return false;
        entry.lastSeen=now;
        return true;
    }

    /** Extraction already checked the stack and published immutable layers in this exact frame. */
    public static boolean hasSafeLayers(Display.ItemDisplay entity, ItemDisplayEntityRenderState state, long now) {
        if(!enabled||!DrawLessConfig.get().displayModelBudget||entity.level()!=level
                ||((ru.wilyfox.drawless.bridge.DisplayLayerOwnership)state).drawless$safeLayerFrame()!=layerFrame)return false;
        var entry=entries.get(entity);
        if(entry==null||entry.layers!=state.item)return false;
        entry.lastSeen=now;
        return true;
    }

    public static boolean seedConstructorLayers(Display.ItemDisplay entity) {
        if(!enabled || !DrawLessConfig.get().displayModelBudget || entity.level()!=level) return false;
        var source=entity.itemRenderState();var entry=entries.get(entity);
        if(source==null || source.itemStack().isEmpty() || entry==null
                || !entry.matches(source.itemStack(),source.itemTransform())) return false;
        constructorSlot.get().layers=entry.layers;
        return true;
    }
    public static ItemStackRenderState takeConstructorLayers() {
        if(!enabled || !DrawLessConfig.get().displayModelBudget) return null;
        var slot=constructorSlot.get();var layers=slot.layers;slot.layers=null;
        if(layers!=null) constructorReused++;
        return layers;
    }
    public static void clearConstructorLayers(){constructorSlot.get().layers=null;}
    private static final class ConstructorSlot {ItemStackRenderState layers;}

    /** Protect layers already published to a previous frame when a borrowed state needs vanilla fallback. */
    public static ItemStackRenderState writableLayers(ItemDisplayEntityRenderState output) {
        var ownership=(ru.wilyfox.drawless.bridge.DisplayLayerOwnership)output;
        if(ownership.drawless$borrowedLayers()) {
            ((DisplayItemStateAccessor)output).drawless$setItem(new ItemStackRenderState());
            ownership.drawless$ownLayers();
        }
        return output.item;
    }

    public static boolean apply(ItemModelResolver resolver, ItemStack stack, ItemDisplayContext context,
                                Display.ItemDisplay entity, ItemDisplayEntityRenderState output) {
        var ownership=(ru.wilyfox.drawless.bridge.DisplayLayerOwnership)output;
        ownership.drawless$safeLayerFrame(0);
        if (!enabled || !DrawLessConfig.get().displayModelBudget) return false;
        var client = Minecraft.getInstance();
        if (entity.level() != level || stack.isEmpty()) { entries.remove(entity); return false; }
        Entry entry = entries.get(entity);
        long now = System.nanoTime();
        if (entry != null) {
            entry.lastSeen = now;
            // Empty replacements and unsupported/dynamic model changes must never be held back.
            boolean matches=entry.matches(stack, context);
            if (matches || (entry.context == context && canCache(client, entity, stack, context))) {
                ((DisplayItemStateAccessor) output).drawless$setItem(entry.layers);
                if(matches)ownership.drawless$safeLayerFrame(layerFrame);
                reused++;
                return true;
            }
            entries.remove(entity);
        }
        if (entries.size() >= MAX_ENTRIES || !canCache(client, entity, stack, context)) { bypassed++; return false; }
        long started = System.nanoTime();
        try (var ignored = RenderMetrics.getInstance().scope("render/displayBudget/firstModel")) {
            resolver.updateForNonLiving(output.item, stack, context, entity);
            // First appearance is mandatory: there is no previous image to show without popping.
            entries.put(entity, new Entry(entity, resolver, output.item, stack, context, now));
            ownership.drawless$safeLayerFrame(layerFrame);
            rebuilt++; mandatory++;
        } finally { budget.charge(System.nanoTime() - started); }
        return true;
    }

    private static boolean canCache(Minecraft client, Display.ItemDisplay entity, ItemStack stack, ItemDisplayContext context) {
        var id = stack.get(DataComponents.ITEM_MODEL);
        return id != null && staticModel(client.getModelManager().getItemModel(id), stack, context, entity, 0);
    }

    /** Unknown models/properties/tints use vanilla, including clocks, compass needles and special renderers. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean staticModel(ItemModel model, ItemStack stack, ItemDisplayContext context,
                                       Display.ItemDisplay entity, int depth) {
        if (model == null || depth > 32) return false;
        if (model.getClass() == EmptyModel.class) return true;
        if (model.getClass() == CuboidItemModelWrapper.class) {
            for (var tint : ((DisplayCuboidModelAccessor) model).drawless$tints())
                if (!(tint instanceof Constant || tint instanceof CustomModelDataSource || tint instanceof Dye)) return false;
            return true;
        }
        if (model.getClass() == CompositeModel.class) {
            for (var child : ((DisplayCompositeModelAccessor) model).drawless$models())
                if (!staticModel(child, stack, context, entity, depth + 1)) return false;
            return true;
        }
        if (model.getClass() == RangeSelectItemModel.class) {
            var accessor = (DisplayRangeModelAccessor) model;
            var property = accessor.drawless$property();
            if (!(property instanceof net.minecraft.client.renderer.item.properties.numeric.CustomModelDataProperty
                    || property instanceof net.minecraft.client.renderer.item.properties.numeric.Damage
                    || property instanceof net.minecraft.client.renderer.item.properties.numeric.Count)) return false;
            float value = property.get(stack, level, entity, entity.getId()) * accessor.drawless$scale();
            int index = Float.isNaN(value) ? -1 : DisplayRangeModelAccessor.drawless$lastIndex(accessor.drawless$thresholds(), value);
            ItemModel selected = index < 0 ? accessor.drawless$fallback() : accessor.drawless$models()[index];
            return staticModel(selected, stack, context, entity, depth + 1);
        }
        if (model.getClass() == SelectItemModel.class) {
            var accessor = (DisplaySelectModelAccessor) model;
            var property = accessor.drawless$property();
            if (!(property instanceof net.minecraft.client.renderer.item.properties.select.CustomModelDataProperty
                    || property instanceof net.minecraft.client.renderer.item.properties.select.DisplayContext
                    || property instanceof net.minecraft.client.renderer.item.properties.select.ItemBlockState
                    || property instanceof net.minecraft.client.renderer.item.properties.select.ContextEntityType)) return false;
            Object value = property.get(stack, level, null, entity.getId(), context);
            ItemModel selected = ((SelectItemModel.ModelSelector) accessor.drawless$models()).get(value, level);
            return staticModel(selected, stack, context, entity, depth + 1);
        }
        return false;
    }

    public static String diagnostic() {
        return enabled ? String.format(Locale.ROOT, "on; entries=%d; reused=%d; built=%d; deferred=%d; bypass=%d; first=%d; budget=%.3fms; preparation=%.3fms; constructorReuse=%d",
                entries.size(), reused, rebuilt, deferred, bypassed, mandatory, budget.limit()/1e6, budget.spent()/1e6,constructorReused) : "off";
    }

    public static void endFrame() {
        if (!enabled) return;
        var profiler = RenderMetrics.getInstance();
        if (!profiler.isEnabled()) return;
        profiler.incrementCounter("displayBudget/reused", reused);
        profiler.incrementCounter("displayBudget/rebuilt", rebuilt);
        profiler.incrementCounter("displayBudget/deferred", deferred);
        profiler.incrementCounter("displayBudget/vanillaBypass", bypassed);
        profiler.incrementCounter("displayBudget/mandatoryFirst", mandatory);
        profiler.incrementCounter("displayBudget/preparationNanos", budget.spent());
        profiler.incrementCounter("displayBudget/constructorReuse",constructorReused);
    }

    private static final class Job {
        final ArrayList<Entry> entries = new ArrayList<>();
        long waiting;
        double distanceSq = Double.POSITIVE_INFINITY;
    }
    private static final class Entry {
        final Display.ItemDisplay entity;
        final ItemModelResolver resolver;
        ItemStackRenderState layers, pending;
        ItemStack snapshot;
        ItemDisplayContext context;
        long lastSeen, dirtySince;
        Entry(Display.ItemDisplay entity, ItemModelResolver resolver, ItemStackRenderState layers,
              ItemStack stack, ItemDisplayContext context, long now) {
            this.entity = entity; this.resolver = resolver; lastSeen = now; publish(layers, stack, context);
        }
        boolean matches(ItemStack stack, ItemDisplayContext value) { return context == value && ItemStack.matches(snapshot, stack); }
        void publish(ItemStackRenderState value, ItemStack stack, ItemDisplayContext context) {
            layers = value; snapshot = stack.copy(); this.context = context; dirtySince = 0;
        }
    }
}
