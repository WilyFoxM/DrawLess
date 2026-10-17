package ru.wilyfox.drawless.performance;

/**
 * Render-thread-owned, single-slot reuse. A value must still be the owner's active
 * buffer. Ending or nesting a pass drops all references; nesting conservatively
 * disables reuse for the remainder of the enclosing pass.
 */
public final class PassLocalBufferReuse<K, V> {
    private int depth;
    private boolean active;
    private K key;
    private V value;

    public void begin(boolean enabled) {
        clear();
        active = ++depth == 1 && enabled;
    }

    public boolean active() { return active; }

    public V find(K requested, V currentBuffer) {
        return active && requested == key && currentBuffer == value ? value : null;
    }

    public void remember(K requested, V buffer) {
        if (active) { key = requested; value = buffer; }
    }

    public void end() {
        clear();
        active = false;
        if (depth == 0) throw new IllegalStateException("No buffer reuse pass to end");
        depth--;
    }

    private void clear() { key = null; value = null; }
}
