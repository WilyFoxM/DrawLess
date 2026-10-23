package ru.wilyfox.drawless.performance;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class PassLocalBufferReuseTest {
  @Test
  void endingAPassReleasesItsStrongPayloadReferences() throws IllegalAccessException {
    var reuse = new PassLocalBufferReuse<Object, Object>();
    reuse.begin(true);
    reuse.remember(new Object(), new Object());
    reuse.end();
    assertNoRetainedPayloads(reuse);
  }

  @Test
  void nestingReleasesTheOuterPayloadImmediately() throws IllegalAccessException {
    var reuse = new PassLocalBufferReuse<Object, Object>();
    reuse.begin(true);
    reuse.remember(new Object(), new Object());
    reuse.begin(true);
    assertNoRetainedPayloads(reuse);
    reuse.end();
    reuse.end();
  }

  private static void assertNoRetainedPayloads(Object reuse) throws IllegalAccessException {
    // The lifetime contract includes releasing strong references even while reuse is disabled.
    // Inspect owned references directly rather than depending on nondeterministic GC timing.
    for (var field : reuse.getClass().getDeclaredFields()) {
      if (!field.getType().isPrimitive() && !Modifier.isStatic(field.getModifiers())) {
        field.setAccessible(true);
        assertNull(field.get(reuse), "Retained pass payload: " + field.getName());
      }
    }
  }

  @Test
  void requiresIdentityAndStillActiveBuffer() {
    var reuse = new PassLocalBufferReuse<Object, Object>();
    Object material = new String("same"), equalMaterial = new String("same"), buffer = new Object();
    reuse.begin(true);
    reuse.remember(material, buffer);
    assertSame(buffer, reuse.find(material, buffer));
    assertNull(reuse.find(equalMaterial, buffer));
    assertNull(reuse.find(material, new Object()));
    reuse.end();
    assertNull(reuse.find(material, buffer));
    reuse.begin(true);
    assertNull(reuse.find(material, buffer));
    reuse.end();
  }

  @Test
  void disabledAndNestedPassesCannotReuseOrResurrectOuterBuffer() {
    var reuse = new PassLocalBufferReuse<Object, Object>();
    Object key = new Object(), buffer = new Object();
    reuse.begin(false);
    reuse.remember(key, buffer);
    assertNull(reuse.find(key, buffer));
    reuse.end();
    reuse.begin(true);
    reuse.remember(key, buffer);
    reuse.begin(true);
    assertFalse(reuse.active());
    assertNull(reuse.find(key, buffer));
    reuse.end();
    assertFalse(reuse.active());
    assertNull(reuse.find(key, buffer));
    reuse.end();
    reuse.begin(true);
    assertTrue(reuse.active());
    reuse.end();
    assertThrows(IllegalStateException.class, reuse::end);
  }
}
