package ru.wilyfox.drawless.performance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DisplayFrameBudgetTest {
  @Test
  void exactBudgetBoundaryStopsOrdinaryWorkAndChargesRemainNonnegative() {
    var b = new DisplayFrameBudget();
    b.begin(1, 1000, false);
    b.charge(999_999);
    assertEquals(999_999, b.spent());
    assertTrue(b.admit(0, false));
    b.charge(-100);
    assertEquals(999_999, b.spent());
    b.charge(1);
    assertEquals(b.limit(), b.spent());
    assertFalse(b.admit(0, false));
    assertTrue(b.admit(0, true));
    assertTrue(b.admit(250_000_000L, false));
  }

  @Test
  void anIsolatedGapExactlyAtThePauseThresholdDoesNotReduceTheBudget() {
    var b = new DisplayFrameBudget();
    b.begin(1, 1000, true);
    b.begin(250_000_001L, 1000, true);
    assertEquals(1_000_000, b.limit());
    b.begin(500_000_001L, 1000, true);
    assertTrue(b.limit() < 1_000_000, "Consecutive long gaps must influence adaptation");
  }

  @Test
  void zeroAndBackwardsIntervalsDoNotEnterTheFrameTimeAverage() {
    var duplicate = new DisplayFrameBudget();
    var backwards = new DisplayFrameBudget();
    for (var b : new DisplayFrameBudget[] {duplicate, backwards}) {
      b.begin(1, 1000, true);
      b.begin(66_666_668L, 1000, true);
    }
    duplicate.begin(66_666_668L, 1000, true);
    backwards.begin(66_666_667L, 1000, true);
    assertEquals(backwards.limit(), duplicate.limit());
  }

  @Test
  void softLimitDefersWorkButAllowsNearModelsAndDeadline() {
    var b = new DisplayFrameBudget();
    b.begin(1, 1000, false);
    assertTrue(b.admit(0, false));
    b.charge(1_100_000);
    assertFalse(b.admit(249_999_999, false));
    assertTrue(b.admit(250_000_000, false));
    assertTrue(b.admit(0, true));
    b.begin(16_666_668, 1000, false);
    assertEquals(0, b.spent());
    assertTrue(b.admit(0, false));
  }

  @Test
  void adaptsGraduallyAndRecoversWithoutAnAbruptBudgetJump() {
    var b = new DisplayFrameBudget();
    long now = 1;
    b.begin(now, 1000, true);
    for (int i = 0; i < 150; i++) {
      now += 66_666_667;
      b.begin(now, 1000, true);
    }
    long slow = b.limit();
    assertTrue(slow >= 250_000 && slow < 300_000);
    now += 5_000_000;
    b.begin(now, 1000, true);
    assertTrue(b.limit() < 400_000);
    for (int i = 0; i < 200; i++) {
      now += 5_000_000;
      b.begin(now, 1000, true);
    }
    assertTrue(b.limit() > 990_000);
    b.reset();
    b.begin(1, 5000, false);
    assertEquals(5_000_000, b.limit());
  }

  @Test
  void clampsBadInputAndIgnoresLongLoadingGaps() {
    var b = new DisplayFrameBudget();
    b.begin(1, -5, false);
    assertEquals(100_000, b.limit());
    b.begin(2_000_000_001L, 99999, true);
    assertEquals(5_000_000, b.limit());
    for (int i = 1; i <= 150; i++) b.begin(2_000_000_001L + i * 500_000_000L, 5000, true);
    assertTrue(
        b.limit() >= 1_250_000 && b.limit() < 1_500_000,
        "Persistent severe lag must still reduce the budget");
  }
}
