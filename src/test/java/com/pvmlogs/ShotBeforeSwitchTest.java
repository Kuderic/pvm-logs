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
		Loadout worn;

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
			// T-140: a melee blow carries a switch made on its own tick.
			final boolean melee = seen - thrown == MELEE;
			final int switched = PvmPerformancePlugin.switchSinceThrow(PvmPerformancePlugin.switchSearchFrom(thrown, melee),
				seen, changes);
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
	public void meleeCarriesASwitchOnItsOwnTick()
	{
		// T-140: a blow thrown on 100, seen on 101. A change on its own tick is in the blow; one on the tick it is seen
		// isn't. Before, a change on 100 took the figures from before it, and Eric's logs disagree: 7 of 48 such blows hit
		// over that loadout's max, each within the new one's ([734] of the 19:06 log: 56 against 51 and 56).
		for (int switchOn = 96; switchOn <= 101; switchOn++)
		{
			final Ticks ticks = new Ticks(WHIP).to(switchOn, BLOWPIPE);
			final Loadout carried = switchOn == 100 + MELEE ? WHIP : BLOWPIPE;
			assertEquals("switch on " + switchOn, carried, ticks.scored(100, MELEE));
		}
	}

	/**
	 * T-140: a cast proven by its drop (UNSEEN SHOT), seen three ticks later with the next weapon in hand and no switch
	 * on record for it: scored from its throw's row, as a projectile is, not from the live figures. Eric's 19:06 log:
	 * the Judge's staff casts at [977] and [987], scored at 28 against their 54s.
	 */
	@Test
	public void aCastProvenByItsDropIsItsThrowsRow()
	{
		final Ticks ticks = new Ticks(STAFF).to(100);
		ticks.worn = WHIP;
		assertEquals(Integer.MIN_VALUE, ticks.figuresTick(100, 103));
		final int at = PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, true, ticks.rows.containsKey(100), 100, 100);
		assertEquals(100, at);
		assertEquals(STAFF, ticks.rows.get(at));
		// Before, as a melee blow is, it took the live figures: the whip's.
		assertEquals(100, PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, false, true, 100, 100));
	}

	/**
	 * T-140: the row for the npc the attack was aimed at counts as the throw's (T-107): the Judge's first staff cast at
	 * [716], thrown while Yama's fight was the one shown, so that tick's shown row was Yama's.
	 */
	@Test
	public void theAimedRowIsTheThrowsWhenAnotherFightIsShown()
	{
		final int yama = 14176;
		final int judge = 14180;
		final double[] yamaRow = row(yama, 60);
		final double[] judgeRow = aimed(judge, 54, 46530);
		assertEquals(54, (int) PvmPerformancePlugin.rowFor(yamaRow, judgeRow, judge, 46530)[PvmPerformancePlugin.EXP_MAX]);
		assertEquals(60, (int) PvmPerformancePlugin.rowFor(yamaRow, judgeRow, yama, 54899)[PvmPerformancePlugin.EXP_MAX]);
		// Another Judge's row isn't this one's.
		assertEquals(yama, (int) PvmPerformancePlugin.rowFor(yamaRow, aimed(judge, 54, 1), judge, 46530)[PvmPerformancePlugin.EXP_NPC_ID]);
	}

	private static double[] row(int npcId, int max)
	{
		final double[] r = new double[PvmPerformancePlugin.EXP_WIDTH];
		r[PvmPerformancePlugin.EXP_MAX] = max;
		r[PvmPerformancePlugin.EXP_NPC_ID] = npcId;
		return r;
	}

	private static double[] aimed(int npcId, int max, int index)
	{
		final double[] r = java.util.Arrays.copyOf(row(npcId, max), PvmPerformancePlugin.EXP_WIDTH + 1);
		r[PvmPerformancePlugin.EXP_WIDTH] = index;
		return r;
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
