package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * T-074: idle ticks a boss's mechanic forces are neutral "mechanics" ticks, up
 * to each window's cap, rather than lost. Driven as the plugin drives it: each
 * tick the windows the allowances ride on, then the idle ticks the accounting
 * asks about. Synthetic, in the shapes of Eric's Leviathan logs (none copied).
 */
public class MechanicDowntimeTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);

	private final List<String> lines = new ArrayList<>();
	private final MechanicAllowances mechanics = new MechanicAllowances(lines::add);
	// The state windows the tracker would answer with on each tick: state -> tick -> {start, end}.
	private final Map<String, Map<Integer, int[]>> windows = new HashMap<>();

	private static BossRules.UptimeAllowance allowance(String state, String from, int max, int after)
	{
		final BossRules.UptimeAllowance a = new BossRules.UptimeAllowance();
		a.state = state;
		a.from = from;
		a.maxTicks = max;
		a.afterTicks = after;
		return a;
	}

	private static final BossRules.UptimeAllowance STUN = allowance("stunned", "cast", 7, 0);
	private static final BossRules.UptimeAllowance SPECIAL = allowance("special", "open", 4, 6);

	/** The window {start, end} answered on ticks {@code from} to {@code until} inclusive. */
	private void window(String state, int start, int end, int from, int until)
	{
		final Map<Integer, int[]> byTick = windows.computeIfAbsent(state, k -> new HashMap<>());
		for (int t = from; t <= until; t++)
		{
			byTick.put(t, new int[]{start, end});
		}
	}

	private void run(int from, int until, List<BossRules.UptimeAllowance> allowances)
	{
		for (int t = from; t <= until; t++)
		{
			final int tick = t;
			mechanics.observe(tick, allowances, state -> windows.getOrDefault(state, Collections.emptyMap()).get(tick));
		}
	}

	private int excuse(int... idle)
	{
		int excused = 0;
		for (int t : idle)
		{
			excused += mechanics.excuse(t) != null ? 1 : 0;
		}
		return excused;
	}

	private static int[] range(int from, int until)
	{
		final int[] out = new int[until - from + 1];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = from + i;
		}
		return out;
	}

	@Test
	public void theLeviathansProposedAllowancesAreInTheData()
	{
		final List<BossRules.UptimeAllowance> levi = RULES.forNpc(12214).uptimeAllowances;
		assertEquals(2, levi.size());
		assertEquals("stunned", levi.get(0).state);
		assertTrue(levi.get(0).fromCast());
		assertEquals(7, levi.get(0).maxTicks);
		assertEquals("special", levi.get(1).state);
		assertEquals(4, levi.get(1).maxTicks);
		assertEquals(6, levi.get(1).afterTicks);
		// Bosses not yet checked have none.
		assertNull(RULES.forNpc(5889).uptimeAllowances);
	}

	@Test
	public void aStunWithTwoIdleTicksIsExcused()
	{
		// Cast on 597, landing on 601, closed by the wound shot on 610.
		mechanics.noteCast(597);
		window("stunned", 601, 616, 597, 609);
		run(597, 612, Collections.singletonList(STUN));
		assertEquals(2, excuse(602, 603));
		run(613, 620, Collections.singletonList(STUN));
		assertEquals(Collections.singletonList(
			"MECHANIC stunned (window 601-609, covering 597-609): 2 idle ticks excused of 7 allowed, 0 still lost"), lines);
		// After it, an idle tick is lost as any other.
		assertEquals(0, excuse(611));
	}

	@Test
	public void aStunWithFifteenIdleTicksIsCapped()
	{
		// Cast on 597, the stun runs its 15 ticks from 601: covered 597 to 615.
		mechanics.noteCast(597);
		window("stunned", 601, 616, 597, 615);
		run(597, 600, Collections.singletonList(STUN));
		assertEquals(7, excuse(range(598, 612)));
		assertEquals(8, mechanics.stillLostTotal());
		run(601, 625, Collections.singletonList(STUN));
		mechanics.finish();
		assertEquals("MECHANIC stunned (window 601-615, covering 597-615): 7 idle ticks excused of 7 allowed, 8 still lost",
			lines.get(0));
		assertEquals("MECHANIC total: 7 idle ticks excused, 8 still lost inside allowances", lines.get(1));
	}

	@Test
	public void aStunCountedFromItsOpeningWhenNoCastWasSeen()
	{
		window("stunned", 601, 616, 597, 615);
		run(597, 605, Collections.singletonList(STUN));
		assertEquals(0, excuse(598, 599, 600));
		assertEquals(1, excuse(601));
	}

	@Test
	public void aSpecialWithDodging()
	{
		// The smoke blast from 191 to 201, as the 23:26 log's: idle from 196 to 207 while dodging and getting back.
		window("special", 191, 251, 191, 200);
		run(191, 201, Collections.singletonList(SPECIAL));
		// Covered to 206 (closed after 200, six after): four excused, the other seven inside still lost, 207 just lost.
		assertEquals(4, excuse(range(196, 207)));
		assertEquals(7, mechanics.stillLostTotal());
		run(202, 215, Collections.singletonList(SPECIAL));
		assertEquals(
			"MECHANIC special (window 191-200, covering 191-206): 4 idle ticks excused of 4 allowed, 7 still lost",
			lines.get(0));
		// The next special has its own budget.
		window("special", 300, 335, 300, 334);
		run(300, 310, Collections.singletonList(SPECIAL));
		assertEquals(4, excuse(301, 302, 303, 304, 305));
	}

	@Test
	public void overlappingWindowsSpendTheEarliestFirst()
	{
		mechanics.noteCast(640);
		window("stunned", 644, 659, 640, 658);
		window("special", 646, 656, 646, 655);
		final List<BossRules.UptimeAllowance> both = java.util.Arrays.asList(STUN, SPECIAL);
		run(640, 650, both);
		// Seven from the stun, then four more from the special.
		assertEquals(11, excuse(range(641, 652)));
		assertEquals(1, mechanics.stillLostTotal());
	}

	@Test
	public void aBossWithoutAllowancesIsUnchanged()
	{
		window("stunned", 601, 616, 597, 615);
		run(597, 620, Collections.emptyList());
		run(597, 620, null);
		assertEquals(0, excuse(range(598, 615)));
		assertEquals(0, mechanics.stillLostTotal());
		mechanics.finish();
		assertTrue(lines.isEmpty());
	}

	@Test
	public void aNewFightStartsOver()
	{
		mechanics.noteCast(597);
		window("stunned", 601, 616, 597, 615);
		run(597, 605, Collections.singletonList(STUN));
		assertEquals(1, excuse(600));
		mechanics.reset();
		assertEquals(0, excuse(602));
		assertEquals(0, mechanics.excusedTotal());
	}

	@Test
	public void theFigureSumsPerScope()
	{
		final Fight fight = new Fight("The Leviathan", 12214, 1, 900, 0L, null, 0);
		fight.recordAttackMade(true, 1, false);
		fight.recordTickSpent();
		fight.recordTickLost(false);
		fight.recordTickLost(true);
		fight.recordTickMechanic(false);
		fight.recordTickMechanic(false);
		assertEquals(2, fight.getTicksMechanics());
		assertEquals(2, fight.getTicksLost());
		// Combat ticks: the attack, the spent tick, two lost, two mechanics.
		assertEquals(6, fight.getCombatTicks());
		// The uptime leaves them out as it leaves eating out: one idle tick in six.
		assertEquals(2 / 6.0, fight.ticksLostShare(), 1e-12);
		final Fight other = new Fight("The Leviathan", 12214, 2, 900, 0L, null, 0);
		other.recordAttackMade(true, 1, false);
		other.recordTickMechanic(false);
		final Encounter room = new Encounter("The Leviathan", null, 0L);
		room.add(fight);
		room.add(other);
		assertEquals(3, room.getTicksMechanics());
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		assertEquals(3, raid.getTicksMechanics());
		final SessionTotals trip = new SessionTotals(0L);
		trip.recordTickMechanic(true);
		trip.recordTickMechanic(false);
		trip.recordTickLost(false, true);
		assertEquals(2, trip.getTicksMechanics());
		assertEquals(1, trip.getTicksLost());
		assertEquals(3, trip.getCombatTicks());
		assertEquals(2, trip.getScoredCombatTicks());
		trip.reset(1L);
		assertEquals(0, trip.getTicksMechanics());
		// Not before the first attack, as a lost tick is not.
		final Fight walking = new Fight("The Leviathan", 12214, 3, 900, 0L, null, 0);
		walking.recordTickMechanic(false);
		assertEquals(0, walking.getTicksMechanics());
		assertEquals(0, walking.getCombatTicks());
	}

	@Test
	public void anOldKillIsUnchanged()
	{
		// history.json as a build before T-074 wrote it: no ticksMechanics.
		final String saved = "{\"targetName\":\"The Leviathan\",\"targetId\":12214,\"maxHp\":900,\"damageDealt\":900,"
			+ "\"attempts\":50,\"hits\":40,\"ticksLostEating\":3,\"ticksLostOther\":17,\"combatTicks\":226,\"ended\":true}";
		final Fight loaded = new Gson().fromJson(saved, Fight.class);
		assertEquals(0, loaded.getTicksMechanics());
		assertEquals(20, loaded.getTicksLost());
		assertEquals(20 / 226.0, loaded.ticksLostShare(), 1e-12);
		// And one saved now keeps its figure.
		final Fight now = new Fight("The Leviathan", 12214, 1, 900, 0L, null, 0);
		now.recordAttackMade(true, 1, false);
		now.recordTickMechanic(false);
		final Fight back = new Gson().fromJson(new Gson().toJson(now), Fight.class);
		assertEquals(1, back.getTicksMechanics());
		assertNotNull(back);
	}
}
