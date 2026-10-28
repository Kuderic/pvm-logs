package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * T-041: the hitpoints replay (T-039 §3.2) with the line Eric picked: this kill's
 * median before food with 3 or more eats, else your usual, else 40% of max HP.
 * Every case is small enough to follow by hand.
 */
public class AvoidableEatingTest
{
	private static final int MAX = 99;

	/** Feeds one tick the way the plugin does: the tick's damage, its avoidable part, an eat, then the reading. */
	private static void tick(AvoidableEating replay, int tick, int damage, double avoidable, int eats, int hpAfter)
	{
		replay.damage(tick, damage);
		replay.avoidable(tick, avoidable);
		for (int e = 0; e < eats; e++)
		{
			replay.eat(tick);
		}
		replay.hitpoints(tick, hpAfter);
	}

	/**
	 * Start at 99. A 40 avoidable hit, a 30 chip hit, a shark: the shark was the
	 * avoidable hit's. With it the replay falls to 29 and eats once; without, it
	 * never goes below 69. Three eating ticks, all avoidable. One eat is too few
	 * for this kill's line, so 40% of 99, 39.
	 */
	@Test
	public void anEatTheAvoidableHitCaused()
	{
		final AvoidableEating replay = new AvoidableEating(0, 99);
		tick(replay, 1, 40, 40, 0, 59);
		tick(replay, 2, 30, 0, 0, 29);
		tick(replay, 3, 0, 0, 1, 49);
		final AvoidableEating.Result r = replay.finish(MAX, 3, () -> -1);
		assertEquals(1, r.eats);
		assertEquals(20, r.foodHeal);
		assertEquals(39, r.line);
		assertEquals(AvoidableEating.LINE_DEFAULT, r.lineSource);
		assertEquals(1, r.eatsWith);
		assertEquals(0, r.eatsWithout);
		assertEquals(3.0, r.avoidableTicks, 0);
	}

	/** Chip only: both replays eat the same, nothing is avoidable. */
	@Test
	public void chipEatingIsNotAvoidable()
	{
		final AvoidableEating replay = new AvoidableEating(0, 99);
		tick(replay, 1, 35, 0, 0, 64);
		tick(replay, 2, 35, 0, 0, 29);
		tick(replay, 3, 0, 0, 1, 49);
		final AvoidableEating.Result r = replay.finish(MAX, 3, () -> -1);
		assertEquals(r.eatsWith, r.eatsWithout);
		assertEquals(0.0, r.avoidableTicks, 0);
	}

	/**
	 * Half of it: with the avoidable hits the replay eats twice, without them once,
	 * so half the 6 eating ticks are theirs.
	 */
	@Test
	public void theTicksAreTheShareOfEatsSkipped()
	{
		final AvoidableEating replay = new AvoidableEating(0, 99);
		tick(replay, 1, 60, 0, 0, 39);
		tick(replay, 2, 0, 0, 1, 59);
		tick(replay, 3, 25, 25, 0, 34);
		tick(replay, 4, 0, 0, 1, 54);
		final AvoidableEating.Result r = replay.finish(MAX, 6, () -> -1);
		assertEquals(2, r.eatsWith);
		assertEquals(1, r.eatsWithout);
		assertEquals(3.0, r.avoidableTicks, 0);
	}

	/** Three eats or more: the line is this kill's median before food (30, 40, 50 -> 40), and your usual is not asked. */
	@Test
	public void threeEatsMakeTheLine()
	{
		final AvoidableEating replay = new AvoidableEating(0, 99);
		tick(replay, 1, 69, 0, 0, 30);
		tick(replay, 2, 0, 0, 1, 50);
		tick(replay, 3, 10, 0, 0, 40);
		tick(replay, 4, 0, 0, 1, 60);
		tick(replay, 5, 10, 0, 0, 50);
		tick(replay, 6, 0, 0, 1, 70);
		final AtomicBoolean asked = new AtomicBoolean();
		final AvoidableEating.Result r = replay.finish(MAX, 9, () ->
		{
			asked.set(true);
			return 60;
		});
		assertEquals(40, r.line);
		assertEquals(AvoidableEating.LINE_THIS_KILL, r.lineSource);
		assertFalse(asked.get());
	}

	/** Fewer: your usual line, and only then 40%. */
	@Test
	public void fewerFallBackToYourUsualThenTheDefault()
	{
		final AvoidableEating replay = new AvoidableEating(0, 99);
		tick(replay, 1, 60, 0, 0, 39);
		tick(replay, 2, 0, 0, 1, 59);
		AvoidableEating.Result r = replay.finish(MAX, 3, () -> 55);
		assertEquals(55, r.line);
		assertEquals(AvoidableEating.LINE_YOUR_USUAL, r.lineSource);
		r = replay.finish(80, 3, () -> -1);
		assertEquals(32, r.line);
		assertEquals(AvoidableEating.LINE_DEFAULT, r.lineSource);
	}

	/**
	 * The heal can show on the tick after the eat: it is still the food's, not blood
	 * fury's, and the hitpoints before the food are the tick before's.
	 */
	@Test
	public void theHealCanComeTheTickAfterTheEat()
	{
		final AvoidableEating replay = new AvoidableEating(0, 99);
		tick(replay, 1, 70, 70, 0, 29);
		tick(replay, 2, 0, 0, 1, 29);
		tick(replay, 3, 0, 0, 0, 49);
		final AvoidableEating.Result r = replay.finish(MAX, 3, () -> -1);
		assertEquals(20, r.foodHeal);
		assertEquals(1, r.eatsWith);
		assertEquals(0, r.eatsWithout);
		assertEquals(3.0, r.avoidableTicks, 0);
	}

	/**
	 * Healing that isn't food (blood fury) stays in both replays, capped at full:
	 * here it is what keeps the replay without the avoidable hit off the line.
	 */
	@Test
	public void otherHealingStaysIn()
	{
		final AvoidableEating replay = new AvoidableEating(0, 99);
		tick(replay, 1, 50, 30, 0, 49);
		// Blood fury: +15 with nothing eaten.
		tick(replay, 2, 0, 0, 0, 64);
		tick(replay, 3, 30, 0, 0, 34);
		tick(replay, 4, 0, 0, 1, 54);
		final AvoidableEating.Result r = replay.finish(MAX, 3, () -> -1);
		// With: 49, 64, 34 <= 39 -> eat. Without: 79, 94, 64.
		assertEquals(1, r.eatsWith);
		assertEquals(0, r.eatsWithout);
		assertEquals(3.0, r.avoidableTicks, 0);
	}

	/** Capped at full: an overheal measures short, so the replay's food is the largest heal seen when none was uncapped. */
	@Test
	public void aCappedEatMeasuresShort()
	{
		final AvoidableEating replay = new AvoidableEating(0, 99);
		tick(replay, 1, 10, 0, 0, 89);
		tick(replay, 2, 0, 0, 1, 99);
		final AvoidableEating.Result r = replay.finish(MAX, 3, () -> -1);
		assertEquals(10, r.foodHeal);
	}

	/** Nothing eaten, or no eating ticks: nothing to charge, and no replay. */
	@Test
	public void noEatsNoTicks()
	{
		final AvoidableEating replay = new AvoidableEating(0, 99);
		tick(replay, 1, 80, 80, 0, 19);
		assertEquals(0.0, replay.finish(MAX, 0, () -> -1).avoidableTicks, 0);
		assertEquals(0, replay.finish(MAX, 5, () -> -1).eats);
		assertEquals(0.0, replay.finish(MAX, 5, () -> -1).avoidableTicks, 0);
	}

	/** A player whose usual line is full: the line is held under full, so the replay ends. */
	@Test
	public void aLineAtFullStillEnds()
	{
		final AvoidableEating replay = new AvoidableEating(0, 99);
		tick(replay, 1, 30, 0, 0, 69);
		tick(replay, 2, 0, 0, 1, 89);
		final AvoidableEating.Result r = replay.finish(MAX, 3, () -> 99);
		assertEquals(99, r.line);
		assertEquals(AvoidableEating.LINE_YOUR_USUAL, r.lineSource);
		// 69, eat to 89, eat to 99: above 98, so it stops.
		assertEquals(2, r.eatsWith);
		assertEquals(r.eatsWith, r.eatsWithout);
		assertEquals(0.0, r.avoidableTicks, 0);
	}

	/** Nothing at or before the replay's start counts, and ticks far on grow the record. */
	@Test
	public void ticksBeforeTheStartAreIgnoredAndLongFightsGrow()
	{
		final AvoidableEating replay = new AvoidableEating(100, 99);
		replay.damage(100, 50);
		replay.eat(99);
		tick(replay, 1100, 70, 70, 0, 29);
		tick(replay, 1101, 0, 0, 1, 49);
		final AvoidableEating.Result r = replay.finish(MAX, 3, () -> -1);
		assertEquals(1, r.eats);
		assertEquals(3.0, r.avoidableTicks, 0);
	}

	@Test
	public void medianOfAnEvenCountRoundsDown()
	{
		assertEquals(35, AvoidableEating.median(Arrays.asList(30, 40)));
		assertEquals(40, AvoidableEating.median(Arrays.asList(50, 30, 40)));
		assertEquals(36, AvoidableEating.median(Arrays.asList(31, 42)));
	}

	private static Fight ruled(String name, int line, String source)
	{
		final Fight f = new Fight(name, NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		f.recordAvoidable(1, 10, Collections.singletonMap("axe", 10), 3, line, source);
		f.end(true, 1000L);
		return f;
	}

	/** Your usual: the median of the like kills' own lines, newest first, the last 20 of them. */
	@Test
	public void yourUsualIsTheMedianOfLikeKillsOwnLines()
	{
		final List<Fight> history = new ArrayList<>();
		// Newest first: the plugin's order.
		history.add(ruled("Boss", 50, AvoidableEating.LINE_THIS_KILL));
		history.add(ruled("Boss", 30, AvoidableEating.LINE_DEFAULT));
		history.add(ruled("Other", 10, AvoidableEating.LINE_THIS_KILL));
		history.add(ruled("Boss", 40, AvoidableEating.LINE_THIS_KILL));
		history.add(new Fight("Boss", NpcIds.GOBLIN, 1, 700, 0L, null, 0));
		final LedgerHistory.Like like = new LedgerHistory.Like("Boss", 700, true);
		assertEquals(45, AvoidableEating.usualLine(history, like, Collections.emptySet()));
		// The fight just ended is not its own usual.
		assertEquals(40, AvoidableEating.usualLine(history, like, Collections.singleton(history.get(0))));
		assertEquals(-1, AvoidableEating.usualLine(history, new LedgerHistory.Like("Boss", 1400, true),
			Collections.emptySet()));
		assertEquals(-1, AvoidableEating.usualLine(Collections.emptyList(), like, null));
	}

	@Test
	public void yourUsualTakesTheLastTwenty()
	{
		final List<Fight> history = new ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			history.add(ruled("Boss", 60, AvoidableEating.LINE_THIS_KILL));
		}
		for (int i = 0; i < 30; i++)
		{
			history.add(ruled("Boss", 20, AvoidableEating.LINE_THIS_KILL));
		}
		assertEquals(60, AvoidableEating.usualLine(history, new LedgerHistory.Like("Boss", 700, true), null));
	}
}
