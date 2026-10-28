package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * T-065: an attack is scored with the loadout it was THROWN with, whatever is
 * switched to before it is seen. Driven through the plugin's own look back
 * ({@link PvmPerformancePlugin#switchSinceThrow},
 * {@link PvmPerformancePlugin#figuresTickBeforeSwitch}) over a synthetic tick
 * cache: each tick's row holds the loadout worn after that tick's changes, as
 * the plugin's GameTick reads it, and the live figures are the loadout worn when
 * the attack is seen.
 *
 * <p>The bow's and blowpipe's figures are the ones T-065's brief gives for the
 * Leviathan (max 40, average 14.17 against max 22, average 4.99); no log lines
 * are copied.
 */
public class ShotBeforeSwitchTest
{
	private static final int LOOKBACK = 4;

	private static final class Loadout
	{
		final String name;
		final int max;
		final double average;

		Loadout(String name, int max, double average)
		{
			this.name = name;
			this.max = max;
			this.average = average;
		}
	}

	private static final Loadout BOW = new Loadout("Bow of faerdhinen", 40, 14.17);
	private static final Loadout BLOWPIPE = new Loadout("Blazing blowpipe", 22, 4.99);
	private static final Loadout WHIP = new Loadout("Abyssal whip", 30, 11.0);
	private static final Loadout STAFF = new Loadout("Tumeken's shadow", 50, 20.0);

	/** A fight's ticks: what was worn after each tick's changes, and which ticks changed it. */
	private static final class Ticks
	{
		final Map<Integer, Loadout> rows = new HashMap<>();
		final List<Integer> changes = new ArrayList<>();
		private Loadout worn;

		Ticks(Loadout start)
		{
			worn = start;
		}

		/** Runs ticks up to and including {@code tick}, switching to {@code to} on {@code tick} when given. */
		Ticks to(int tick, Loadout to)
		{
			final int from = rows.isEmpty() ? tick - 10 : rows.keySet().stream().max(Integer::compare).get() + 1;
			for (int t = from; t < tick; t++)
			{
				rows.put(t, worn);
			}
			if (to != null && to != worn)
			{
				worn = to;
				changes.add(tick);
			}
			rows.put(tick, worn);
			return this;
		}

		Ticks to(int tick)
		{
			return to(tick, null);
		}

		Ticks missing(int tick)
		{
			rows.remove(tick);
			return this;
		}

		/** What an attack thrown on {@code thrown}, seen {@code lag} ticks later, is scored with. */
		Loadout scored(int thrown, int lag)
		{
			final int seen = thrown + lag;
			to(Math.max(seen, rows.keySet().stream().max(Integer::compare).orElse(seen)));
			final Loadout live = rows.get(seen) != null ? rows.get(seen) : worn;
			final int at = figuresTick(thrown, seen);
			return at == Integer.MIN_VALUE ? live : rows.get(at);
		}

		int figuresTick(int thrown, int seen)
		{
			final int switched = PvmPerformancePlugin.switchSinceThrow(thrown, seen, changes);
			return PvmPerformancePlugin.figuresTickBeforeSwitch(thrown, switched, changes, rows::containsKey, LOOKBACK);
		}
	}

	private static final int PROJECTILE = 2;
	private static final int MELEE = 1;
	private static final int CAST_WITHOUT_PROJECTILE = 3;

	@Test
	public void aBowShotBeforeASwitchOnItsTickOrAfterIsTheBows()
	{
		for (int switchOn = 100; switchOn <= 102; switchOn++)
		{
			final Ticks ticks = new Ticks(BOW).to(switchOn, BLOWPIPE);
			assertEquals("switch on " + switchOn, BOW, ticks.scored(100, PROJECTILE));
		}
	}

	@Test
	public void aSwitchTheTickBeforeTheThrowIsTheNewWeapons()
	{
		final Ticks ticks = new Ticks(BOW).to(99, BLOWPIPE);
		assertEquals(BLOWPIPE, ticks.scored(100, PROJECTILE));
	}

	@Test
	public void meleeIsAsItWas()
	{
		// Master's rule for a blow seen the tick after its throw: a change on the observation's tick or the one before it
		// takes the figures from before the change. The same answer for every switch tick, the one before the throw too.
		for (int switchOn = 96; switchOn <= 101; switchOn++)
		{
			final Ticks ticks = new Ticks(WHIP).to(switchOn, BLOWPIPE);
			final int seen = 100 + MELEE;
			final Loadout masters = switchOn >= seen - 1 ? WHIP : BLOWPIPE;
			assertEquals("switch on " + switchOn, masters, ticks.scored(100, MELEE));
		}
	}

	@Test
	public void aCastWithoutAProjectileIsJudgedTheSameWay()
	{
		for (int switchOn = 97; switchOn <= 103; switchOn++)
		{
			final Ticks ticks = new Ticks(STAFF).to(switchOn, WHIP);
			assertEquals("switch on " + switchOn, switchOn >= 100 ? STAFF : WHIP,
				ticks.scored(100, CAST_WITHOUT_PROJECTILE));
		}
	}

	@Test
	public void aCastSeenOnItsOwnTickAfterASwitchIsTheNewWeapons()
	{
		// Booked from its animation with no lag: master looked back for a change on the tick before as well, which the cast
		// was already wielding.
		final Ticks before = new Ticks(WHIP).to(99, STAFF);
		assertEquals(STAFF, before.scored(100, 0));
		final Ticks same = new Ticks(STAFF).to(100, WHIP);
		assertEquals(STAFF, same.scored(100, 0));
	}

	@Test
	public void theLeviathanSequence()
	{
		// A bow shot thrown on 706 and seen on 708 with nothing switched; another thrown on 710, the blowpipe put on straight
		// after (on 710 or 711), that shot seen on 712; then the blowpipe's own shot thrown on 712.
		for (int switchOn = 710; switchOn <= 711; switchOn++)
		{
			final Ticks ticks = new Ticks(BOW).to(708);
			assertEquals(BOW, ticks.scored(706, PROJECTILE));
			ticks.to(switchOn, BLOWPIPE);
			final Loadout shot = ticks.scored(710, PROJECTILE);
			assertEquals("switch on " + switchOn, BOW, shot);
			assertEquals(40, shot.max);
			assertEquals(14.17, shot.average, 1e-9);
			assertEquals(switchOn - 1, ticks.figuresTick(710, 712));
			assertEquals(BLOWPIPE, ticks.scored(712, PROJECTILE));
		}
	}

	@Test
	public void switchingAwayAndBackInFlightIsStillTheThrowsWeapon()
	{
		final Ticks ticks = new Ticks(BOW).to(100, BLOWPIPE).to(101, BOW);
		assertEquals(BOW, ticks.scored(100, PROJECTILE));
		final Ticks other = new Ticks(BLOWPIPE).to(100, BOW).to(101, BLOWPIPE);
		assertEquals(BLOWPIPE, other.scored(100, PROJECTILE));
	}

	@Test
	public void aMissingRowLooksBackButNeverPastTheSwitchBeforeTheThrow()
	{
		// The tick before the switch has no row: the one before it holds the same loadout.
		final Ticks gap = new Ticks(BOW).to(101, BLOWPIPE).missing(100);
		assertEquals(99, gap.figuresTick(100, 102));
		assertEquals(BOW, gap.scored(100, PROJECTILE));
		// Here the bow went on on 98 and the rows from it are gone: the tick before 98 is the blowpipe's, which the shot was
		// not thrown with, so it is not used and the attack falls back to what is held when seen.
		final Ticks gone = new Ticks(BLOWPIPE).to(98, BOW).to(101, STAFF).missing(98).missing(99).missing(100);
		assertEquals(Integer.MIN_VALUE, gone.figuresTick(100, 102));
	}

	@Test
	public void nothingSwitchedReadsTheLiveFigures()
	{
		final Ticks ticks = new Ticks(BOW).to(103);
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.switchSinceThrow(100, 102, ticks.changes));
		assertEquals(Integer.MIN_VALUE, ticks.figuresTick(100, 102));
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.switchSinceThrow(100, 102, Arrays.asList(95, 99, 103)));
		assertEquals(101, PvmPerformancePlugin.switchSinceThrow(100, 102, Arrays.asList(99, 102, 101)));
	}

	@Test
	public void theHeldTickIsTheOneBeforeTheSwitch()
	{
		// Weapon, speed and switch check are read from here: the throw's own tick already shows a swap made on it.
		assertEquals(709, PvmPerformancePlugin.heldTick(710, 712));
		assertEquals(712, PvmPerformancePlugin.heldTick(Integer.MIN_VALUE, 712));
	}

	@Test
	public void theScoredLineSaysSo()
	{
		assertEquals(" (as held on tick 709, before a switch)", PvmPerformancePlugin.beforeSwitchNote(709));
	}
}
