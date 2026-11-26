package com.pvmlogs;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T-223: the panel builds on the tick the player's hit is booked at its splat, at most once every 3 ticks, and every 5
 * ticks otherwise ({@link SidePanelCadence}). The replays at the end print what each weapon gives, before (every 5 ticks,
 * T-046), counted at the throw (#249's first version) and counted at the splat: builds a minute and the ticks from each
 * splat to the build that shows it.
 */
public class SidePanelCadenceTest
{
	@Test
	public void aBookingBuildsThreeOrMoreTicksAfterTheLastBuildNotOneOrTwo()
	{
		for (long since = 1; since < 3; since++)
		{
			assertFalse("booked " + since + " after", SidePanelCadence.due(false, true, true, true, since, true, 0, 0));
		}
		for (long since = 3; since < 10; since++)
		{
			assertTrue("booked " + since + " after", SidePanelCadence.due(false, true, true, true, since, true, 0, 0));
		}
	}

	@Test
	public void aBookingTooSoonWaitsForTheThirdTick()
	{
		final SidePanelCadence c = new SidePanelCadence();
		assertTrue(c.due(100, 1, false, true, true, true));
		c.built(100);
		// Booked a tick later: shown two ticks after that, on the third since the build.
		assertFalse(c.due(101, 2, false, true, true, true));
		assertFalse(c.due(102, 2, false, true, true, true));
		assertTrue(c.due(103, 2, false, true, true, true));
	}

	@Test
	public void withNoBookingEveryFiveTicksAsBefore()
	{
		final SidePanelCadence c = new SidePanelCadence();
		final List<Long> builds = new ArrayList<>();
		for (long t = 0; t < 30; t++)
		{
			if (c.due(t, 7, false, true, true, true))
			{
				c.built(t);
				builds.add(t);
			}
		}
		// The first tick builds (nothing built yet); then every 5.
		assertEquals(java.util.Arrays.asList(0L, 5L, 10L, 15L, 20L, 25L), builds);
		for (long since = 0; since < 10; since++)
		{
			assertEquals(PvmPerformancePanel.due(false, true, true, true, since),
				SidePanelCadence.due(false, true, true, true, since, false, since, 0));
		}
	}

	@Test
	public void updateDuringFightsOffStillWaitsForTheEnd()
	{
		final SidePanelCadence c = new SidePanelCadence();
		c.built(0);
		for (long t = 1; t < 40; t++)
		{
			assertFalse(c.due(t, (int) t, false, true, true, false));
		}
		// The fight over: at once.
		assertTrue(c.due(40, 40, false, false, true, false));
	}

	@Test
	public void outOfAFightAShapeChangeOrAForceBuildsAtOnce()
	{
		assertTrue(SidePanelCadence.due(true, true, true, true, 0, false, 0, 0));
		assertTrue(SidePanelCadence.due(false, false, true, true, 0, false, 0, 0));
		assertTrue(SidePanelCadence.due(false, true, false, true, 0, false, 0, 0));
	}

	@Test
	public void aTwoTickWeaponBuildsNoMoreThanOnceEveryThreeTicks()
	{
		final Replay r = Replay.run(every(2, 0, 600), 600, false);
		for (int i = 1; i < r.builds.size(); i++)
		{
			assertTrue("builds at " + r.builds.get(i - 1) + " and " + r.builds.get(i),
				r.builds.get(i) - r.builds.get(i - 1) >= SidePanelCadence.BOOKING_GAP_TICKS);
		}
		assertTrue(r.builds.size() <= 600 / 3 + 1);
		// Each booking shown inside the cap: a booking a tick after a build waits two (shown with the next one).
		assertTrue(r.maxDelay() <= SidePanelCadence.BOOKING_GAP_TICKS - 1);
	}

	@Test
	public void aFourOrFiveTickWeaponBuildsOnEveryBooking()
	{
		for (int speed : new int[]{4, 5})
		{
			final Replay r = Replay.run(every(speed, 0, 600), 600, false);
			assertEquals("speed " + speed, 0, r.maxDelay());
		}
	}

	@Test
	public void aSixOrSevenTickWeaponWaitsForItsNextAttackNotBuildingATickBefore()
	{
		for (int speed : new int[]{6, 7})
		{
			final Replay r = Replay.run(every(speed, 0, 600), 600, false);
			// From the third attack, when the last two have given the rhythm (the second may wait behind a 5-tick build).
			assertEquals("speed " + speed, 0, r.maxDelay(2));
			// One build an attack: none a tick before each booking that the booking would then wait behind.
			assertEquals("speed " + speed, 600 / speed + 1, r.builds.size(), 1);
		}
	}

	@Test
	public void aGapKeepsTheClockMovingEveryFiveTicks()
	{
		// 4-tick attacks to 40, none until 100 (a mechanic), then again.
		final List<Long> bookings = every(4, 0, 40);
		bookings.addAll(every(4, 100, 140));
		final Replay r = Replay.run(bookings, 140, false);
		// Built at the last booking before the gap, then by 5 ticks at most through it.
		for (int i = 1; i < r.builds.size(); i++)
		{
			assertTrue(r.builds.get(i) - r.builds.get(i - 1) <= PvmPerformancePanel.UPDATE_TICKS);
		}
		assertEquals(0, r.maxDelay());
	}

	@Test
	public void aProjectilesBuildComesWithItsSplatNotItsThrow()
	{
		// A 5-tick crossbow, each bolt's splat 2 ticks after the throw. Counted at the throw (#249's first version), the
		// build came 2 ticks before the splat and the hit waited for the next one; counted at the splat, it comes with it.
		final List<Long> throwsAt = every(5, 0, 600);
		final List<Long> splats = after(throwsAt, 2, 2);
		assertEquals(0, Replay.run(splats, splats, 600, false).maxDelay(2));
		assertEquals(3, Replay.run(throwsAt, splats, 600, false).maxDelay(2));
	}

	@Test
	public void theReplays()
	{
		// Rhythms as fights go: an eat or a mechanic every so often pushes the next attack back. Each hit's delay is from
		// its splat (when its damage and expected damage are in) to the build that shows it.
		final int ticks = 100 * 10;
		final String[] names = {"5-tick crossbow (Brutus- or Hydra-like; splat 1-2 ticks after the throw)",
			"4-tick melee (splat on the throw)", "blowpipe (2-tick; splat 1-2 after)", "4-tick powered staff (splat 2 after)",
			"6-tick godsword (melee)"};
		final int[] speeds = {5, 4, 2, 4, 6};
		final int[][] flight = {{1, 2}, {0, 0}, {1, 2}, {2, 2}, {0, 0}};
		for (int i = 0; i < names.length; i++)
		{
			final List<Long> throwsAt = fight(speeds[i], ticks);
			final List<Long> splats = after(throwsAt, flight[i][0], flight[i][1]);
			final Replay before = Replay.run(splats, splats, ticks, true);
			final Replay atThrow = Replay.run(throwsAt, splats, ticks, false);
			final Replay atSplat = Replay.run(splats, splats, ticks, false);
			System.out.println(String.format("T-223 %s: every 5 ticks %.1f builds/min, splat to build avg %.2f max %d;"
					+ " counted at the throw %.1f, avg %.2f max %d; counted at the splat %.1f, avg %.2f max %d", names[i],
				before.perMinute(ticks), before.avgDelay(), before.maxDelay(), atThrow.perMinute(ticks), atThrow.avgDelay(),
				atThrow.maxDelay(), atSplat.perMinute(ticks), atSplat.avgDelay(), atSplat.maxDelay()));
			assertTrue(atSplat.avgDelay() < before.avgDelay());
			// Shown inside the cap: a hit a tick after a build waits two at most.
			assertTrue(atSplat.maxDelay() <= SidePanelCadence.BOOKING_GAP_TICKS - 1);
			// The cap: never more than one build every 3 ticks.
			assertTrue(atSplat.perMinute(ticks) <= 100 / 3.0 + 1);
		}
	}

	/** Each throw's splat, {@code min} ticks after it for ten attacks, then {@code max} for ten (the player moving). */
	static List<Long> after(List<Long> throwsAt, int min, int max)
	{
		final List<Long> s = new ArrayList<>();
		for (int i = 0; i < throwsAt.size(); i++)
		{
			s.add(throwsAt.get(i) + (i / 10 % 2 == 0 ? min : max));
		}
		return s;
	}

	/** Bookings every {@code speed} ticks from {@code from}, before {@code to}. */
	static List<Long> every(int speed, long from, long to)
	{
		final List<Long> b = new ArrayList<>();
		for (long t = from; t < to; t += speed)
		{
			b.add(t);
		}
		return b;
	}

	/** A fight's bookings at {@code speed}: every 9th attack an eat (2 ticks later), every 120 ticks a 12-tick phase. */
	static List<Long> fight(int speed, int ticks)
	{
		final List<Long> b = new ArrayList<>();
		int n = 0;
		for (long t = 0; t < ticks; )
		{
			if (t % 120 >= 108)
			{
				t++;
				continue;
			}
			b.add(t);
			t += speed + (++n % 9 == 0 ? 2 : 0);
		}
		return b;
	}

	/**
	 * A fight's ticks through the cadence (or every 5 ticks for before): the builds, and each splat's delay to the first
	 * build on or after it. {@code counter}: the ticks the counter the cadence reads changes on.
	 */
	static final class Replay
	{
		final List<Long> builds = new ArrayList<>();
		final List<Long> delays = new ArrayList<>();
		/** Each booking's delay, by booking. */
		final List<Long> byBooking = new ArrayList<>();

		static Replay run(List<Long> bookings, long ticks, boolean before)
		{
			return run(bookings, bookings, ticks, before);
		}

		static Replay run(List<Long> counter, List<Long> bookings, long ticks, boolean before)
		{
			final Replay r = new Replay();
			final SidePanelCadence c = new SidePanelCadence();
			int count = 0;
			int counted = 0;
			int next = 0;
			long lastBuild = Long.MIN_VALUE / 2;
			final List<Long> waiting = new ArrayList<>();
			for (long t = 0; t < ticks; t++)
			{
				while (counted < counter.size() && counter.get(counted) == t)
				{
					count++;
					counted++;
				}
				while (next < bookings.size() && bookings.get(next) == t)
				{
					next++;
					waiting.add(t);
				}
				final boolean due = before ? t - lastBuild >= PvmPerformancePanel.UPDATE_TICKS
					: c.due(t, count, false, true, true, true);
				if (due)
				{
					c.built(t);
					lastBuild = t;
					r.builds.add(t);
					for (long b : waiting)
					{
						r.delays.add(t - b);
					}
					for (int i = r.byBooking.size(); i < next; i++)
					{
						r.byBooking.add(t - bookings.get(i));
					}
					waiting.clear();
				}
			}
			return r;
		}

		double perMinute(long ticks)
		{
			return builds.size() * 100.0 / ticks;
		}

		double avgDelay()
		{
			return delays.stream().mapToLong(Long::longValue).average().orElse(0);
		}

		/** The longest delay, from booking {@code from} (0-based) on. */
		long maxDelay(int from)
		{
			return byBooking.subList(Math.min(from, byBooking.size()), byBooking.size()).stream().mapToLong(Long::longValue)
				.max().orElse(0);
		}

		long maxDelay()
		{
			return delays.stream().mapToLong(Long::longValue).max().orElse(0);
		}
	}
}
