package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

// What the target has left: the boss health bar while it is fresh and names the target, every splat counted otherwise.
public class TargetHealthTest
{
	private static final int VARDORVIS = 12223;
	private static final int INDEX = 50365;

	@Test
	public void nothingKnownIsUnknown()
	{
		final TargetHealth h = new TargetHealth();
		assertEquals(-1, h.remaining(INDEX, 0));
		assertEquals(-1, h.maxHp(INDEX));
		assertEquals("unknown", h.source(INDEX, 0));
	}

	@Test
	public void countedIsTheTableLessEverySplatPlusEveryHeal()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(INDEX, VARDORVIS, 700, 0);
		h.damage(INDEX, VARDORVIS, 46, 1);   // the player
		h.damage(INDEX, VARDORVIS, 3, 2);    // the thrall
		h.damage(INDEX, VARDORVIS, 5, 3);    // burn
		h.heal(INDEX, VARDORVIS, 10, 4);
		assertEquals(700 - 46 - 3 - 5 + 10, h.remaining(INDEX, 5));
		assertEquals("counted", h.source(INDEX, 5));
		assertEquals(54, h.damageCounted(INDEX));
		assertEquals(10, h.healed(INDEX));
	}

	@Test
	public void countedNeverLeavesZeroToFull()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(INDEX, VARDORVIS, 700, 0);
		h.heal(INDEX, VARDORVIS, 50, 1);
		assertEquals(700, h.remaining(INDEX, 1));
		h.damage(INDEX, VARDORVIS, 800, 2);
		assertEquals(0, h.remaining(INDEX, 2));
	}

	@Test
	public void aFreshBarBeatsTheCount()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(INDEX, VARDORVIS, 700, 0);
		h.damage(INDEX, VARDORVIS, 100, 10);
		// The boss healed 40 the count never saw.
		h.hud(INDEX, VARDORVIS, 640, 700, 10);
		assertEquals(640, h.remaining(INDEX, 10));
		assertEquals("bar", h.source(INDEX, 10));
		assertEquals(640, h.remaining(INDEX, 10 + TargetHealth.HUD_FRESH_TICKS));
	}

	@Test
	public void aStaleBarFallsBackToTheCountAgainstItsFullHitpoints()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(INDEX, VARDORVIS, 700, 0);
		// Awakened: the bar says 1400 full, which beats the table from then on.
		h.hud(INDEX, VARDORVIS, 1400, 1400, 1);
		h.damage(INDEX, VARDORVIS, 100, 2);
		final int later = 2 + TargetHealth.HUD_FRESH_TICKS + 1;
		assertEquals("counted", h.source(INDEX, later));
		assertEquals(1300, h.remaining(INDEX, later));
		assertEquals(1400, h.maxHp(INDEX));
		assertEquals("bar", h.maxSource(INDEX));
		assertEquals(1400, h.observedMaxForId(VARDORVIS));
	}

	@Test
	public void aReadingThatCannotBeOneIsRefused()
	{
		final TargetHealth h = new TargetHealth();
		h.hud(INDEX, VARDORVIS, 800, 700, 0);
		h.hud(INDEX, VARDORVIS, -1, 700, 0);
		h.hud(INDEX, VARDORVIS, 0, 0, 0);
		assertEquals(-1, h.maxHp(INDEX));
	}

	/** A boss that transforms keeps its index and its damage; either id finds it. */
	@Test
	public void aTransformKeepsTheTally()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(INDEX, 2042, 500, 0);
		h.damage(INDEX, 2042, 100, 1);
		h.damage(INDEX, 2043, 50, 5);
		assertEquals(350, h.remaining(INDEX, 6));
		assertEquals(350, h.remainingForId(2042, 6));
		assertEquals(350, h.remainingForId(2043, 6));
	}

	/** The server hands a despawned npc's index to the next spawn; forgetting it is what keeps the two apart. */
	@Test
	public void aReusedIndexStartsClean()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(INDEX, VARDORVIS, 700, 0);
		h.damage(INDEX, VARDORVIS, 700, 100);
		h.forget(INDEX);
		h.setTableMaxHp(INDEX, NpcIds.GOBLIN, 5, 200);
		assertEquals(5, h.remaining(INDEX, 200));
		assertEquals(-1, h.remainingForId(VARDORVIS, 200));
	}

	@Test
	public void lookupsByIdTakeTheLatestTarget()
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(1, VARDORVIS, 700, 0);
		h.damage(1, VARDORVIS, 700, 10);
		h.setTableMaxHp(2, VARDORVIS, 700, 20);
		assertEquals(700, h.remainingForId(VARDORVIS, 21));
		h.clear();
		assertEquals(-1, h.remainingForId(VARDORVIS, 21));
	}
}
