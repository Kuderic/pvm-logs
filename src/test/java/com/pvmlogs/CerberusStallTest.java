package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.pvmlogs.LedgerExamples.FakeScope;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * T-191: Cerberus held just above 400 hp until her 7th attack (Eric's stall) or her 14th (the wiki's No Ghosts) is a
 * strategy, not lost uptime. OSRS Wiki, Cerberus/Strategies, read 2026-11-09: the souls start "when Cerberus falls below
 * 400 health" and come "every seventh attack"; No Ghosts hits her "to just above 400 hitpoints and wait until the 14th
 * attack". Judged after the fight, from her attacks counted off her hits on the player.
 */
public class CerberusStallTest
{
	private static final int MAX = 61;

	private static BossRules.StallRule rule()
	{
		final BossRules.StallRule r = new BossRules.StallRule();
		r.name = "stall (strategy)";
		r.holdAboveHp = 400;
		r.hitsAbove = 2;
		r.beforeAttack = 14;
		r.attackGapTicks = 5;
		return r;
	}

	/**
	 * Her attacks as the plugin hears them: the triple attack first (magic, ranged, then a bite, 2 ticks apart), then one
	 * every 6 ticks, each a projectile seen 3 ticks before its hit. Attack k (from 1) starts on 6(k - 1).
	 */
	private static StrategyStall withAttacks(int attacks)
	{
		final StrategyStall s = new StrategyStall(rule());
		s.onHitOnPlayer(3, Collections.singletonList(TargetActions.Action.projectile(1242, -3, 0)));
		s.onHitOnPlayer(5, Collections.singletonList(TargetActions.Action.projectile(1245, -3, 0)));
		s.onHitOnPlayer(5, Collections.emptyList()); // the bite, the tick after its animation on 4
		for (int k = 2; k <= attacks; k++)
		{
			final int start = 6 * (k - 1);
			s.onHitOnPlayer(start + 3, Arrays.asList(TargetActions.Action.animation(4489, -2),
				TargetActions.Action.projectile(1242, -3, 0)));
		}
		return s;
	}

	private static void idle(StrategyStall s, int from, int to, int hp)
	{
		for (int t = from; t <= to; t++)
		{
			s.idle(t + 1, t, hp, MAX);
		}
	}

	@Test
	public void herAttacksAreCountedOffHerHitsOnThePlayer()
	{
		final List<Integer> starts = Arrays.asList(0, 2, 4, 6, 12, 18);
		final List<Integer> attacks = StrategyStall.attacks(starts, 5);
		assertEquals(Arrays.asList(0, 6, 12, 18), attacks);
		assertEquals(1, StrategyStall.startedBy(attacks, 5));
		assertEquals(2, StrategyStall.startedBy(attacks, 6));
		// A hit whose projectile landed earlier (a second TAKEN line for it) isn't an attack of its own.
		final StrategyStall s = withAttacks(3);
		s.onHitOnPlayer(16, Collections.singletonList(TargetActions.Action.projectile(1242, -5, -3)));
		assertEquals(3, s.settle().attacks);
	}

	@Test
	public void ericsStallToHerSeventhAttackIsExcused()
	{
		// Held at 437 (a max of 61 from 400) from tick 20 to her 7th attack, which starts on 36; then he attacks again.
		final StrategyStall s = withAttacks(8);
		idle(s, 20, 35, 437);
		final StrategyStall.Result r = s.settle();
		assertEquals(16, r.excused.size());
		assertEquals(16, r.idle);
		assertTrue(r.line, r.line.startsWith("STALL stall (strategy): 16 of 16 idle ticks excused, 20-35 at 437-437 hp"));
		// His stall waits two hits above, "then attack twice": 473 with a max of 61 (11-09 [20099]) is held too.
		final StrategyStall two = withAttacks(8);
		idle(two, 20, 35, 473);
		assertEquals(16, two.settle().excused.size());
	}

	@Test
	public void theCommonStallToHerFourteenthIsExcused()
	{
		// No Ghosts: held at 410 until her 14th attack, which starts on 78.
		final StrategyStall s = withAttacks(15);
		idle(s, 20, 77, 410);
		assertEquals(58, s.settle().excused.size());
	}

	@Test
	public void idleFromHerFourteenthOnOrFarAboveIsStillLost()
	{
		// From her 14th attack's start on, the stall is over.
		final StrategyStall late = withAttacks(16);
		idle(late, 70, 85, 410);
		assertEquals(8, late.settle().excused.size());
		// More than two max hits above 400: attacking couldn't take her under, so waiting there isn't the stall.
		final StrategyStall far = withAttacks(10);
		idle(far, 20, 35, 400 + 2 * MAX + 1);
		assertEquals(0, far.settle().excused.size());
		assertTrue(StrategyStall.isStall(rule(), 400 + 2 * MAX, MAX, 6));
		assertFalse(StrategyStall.isStall(rule(), 400 + 2 * MAX + 1, MAX, 6));
		assertFalse(StrategyStall.isStall(rule(), 410, MAX, 14));
		assertTrue(StrategyStall.isStall(rule(), 410, MAX, 13));
	}

	@Test
	public void idleWithNoStallPatternIsStillLost()
	{
		// Below 400 (after the stall, or with no stall), at 400 itself, unknown hp, no max hit.
		final StrategyStall s = withAttacks(10);
		idle(s, 40, 50, 300);
		idle(s, 51, 52, 400);
		idle(s, 53, 54, -1);
		s.idle(56, 55, 420, 0);
		final StrategyStall.Result r = s.settle();
		assertEquals(0, r.excused.size());
		assertTrue(r.line, r.line.startsWith("STALL stall (strategy): none of 16 idle ticks"));
		// A boss without the rule has none to judge.
		assertFalse(StrategyStall.usable(null));
		assertFalse(StrategyStall.usable(new BossRules.StallRule()));
	}

	@Test
	public void cerberusHasTheRule()
	{
		final BossRules rules = BossRules.load(new Gson(), null);
		for (int id : new int[]{5862, 5863, 5866})
		{
			final BossRules.Boss boss = rules.forNpc(id);
			assertNotNull(boss);
			assertTrue(StrategyStall.usable(boss.stall));
			assertEquals(400, boss.stall.holdAboveHp);
			assertEquals(14, boss.stall.beforeAttack);
			assertEquals("stall (strategy)", boss.stall.name);
		}
		// Only Cerberus.
		int withStall = 0;
		final BossRules.RulesFile file = BossRules.read(new Gson(), new InputStreamReader(
			BossRules.class.getResourceAsStream("boss-rules.json"), StandardCharsets.UTF_8));
		for (BossRules.Boss boss : file.bosses)
		{
			withStall += boss.stall != null ? 1 : 0;
		}
		assertEquals(1, withStall);
	}

	@Test
	public void theStallMovesOnlyAtTheFightsEnd()
	{
		final Fight f = new Fight("Cerberus", 5862, 1, 600, 0L, null, 0);
		f.recordAttackMade(true);
		f.recordAttackResolved(true, false, 10, 10);
		for (int i = 0; i < 20; i++)
		{
			f.recordTickLost(false);
		}
		f.recordTickLost(true);
		// While it runs, the idle ticks are lost as any other: nothing moves with her attack count.
		assertEquals(20, f.getTicksLostOther());
		assertEquals(0, f.getTicksStall());
		assertEquals(0, f.getTicksMechanics());
		final int combat = f.getCombatTicks();
		final List<Integer> ticks = new ArrayList<>();
		for (int i = 0; i < 16; i++)
		{
			ticks.add(100 + i);
		}
		assertEquals(16, f.excuseStall(ticks));
		assertEquals(4, f.getTicksLostOther());
		assertEquals(1, f.getTicksLostEating());
		assertEquals(16, f.getTicksMechanics());
		assertEquals(16, f.getTicksStall());
		assertEquals(combat, f.getCombatTicks());
		// Never more than were lost.
		final List<Integer> more = new ArrayList<>(ticks);
		assertEquals(4, f.excuseStall(more));
		assertEquals(0, f.getTicksLostOther());

		final SessionTotals trip = new SessionTotals(0L);
		trip.recordTickLost(false, true);
		trip.recordTickLost(false, true);
		trip.excuseStall(5);
		assertEquals(0, trip.getTicksLostOther());
		assertEquals(2, trip.getTicksStall());
		assertEquals(2, trip.getTicksMechanics());
	}

	@Test
	public void nothingLiveCanReadHerAttackCount()
	{
		// The judge's only ways out are its name and the settlement at the fight's end: no count, no "stall now".
		final Set<String> instance = new HashSet<>();
		for (Method m : StrategyStall.class.getDeclaredMethods())
		{
			if (!Modifier.isStatic(m.getModifiers()) && !m.isSynthetic())
			{
				instance.add(m.getName());
			}
		}
		assertEquals(new HashSet<>(Arrays.asList("onHitOnPlayer", "idle", "settle", "name")), instance);
		// And the panel's snapshot carries no stall or attack count of its own: its figures come from the fight's record,
		// which holds the stall only once it has ended.
		for (java.lang.reflect.Field field : PanelSnapshot.class.getDeclaredFields())
		{
			final String name = field.getName().toLowerCase();
			assertFalse(name, name.contains("stall") || name.contains("attackcount"));
		}
	}

	@Test
	public void whereTheTimeWentShowsTheStallAsItsOwnPart()
	{
		final FakeScope s = LedgerExamples.kill();
		s.ticksMechanics = 25;
		s.ticksStall = 20;
		final LedgerRows.Numbers n = new LedgerRows.Numbers(s);
		assertEquals(25 * 0.6, n.mechanicsCost, 1e-9);
		assertEquals(20 * 0.6, n.stallCost, 1e-9);
		final LedgerRows.Settings settings = new LedgerRows.Settings();
		settings.parts = ExecutionParts.ALL;
		final List<LedgerRows.Row> rows = LedgerRows.build(s, LedgerRows.State.fight("Cerberus", true, true, false),
			settings);
		assertTrue(rows.toString(), rows.stream().anyMatch(r -> r.left.equals(LedgerRows.STALL) && r.right.equals("20 ticks")));
		assertTrue(rows.toString(), rows.stream().anyMatch(r -> r.left.equals(LedgerRows.MECHANICS) && r.right.equals("5 ticks")));
		// Execution is unchanged by the split: the stall is out of uptime, as every mechanics tick is.
		final FakeScope plain = LedgerExamples.kill();
		plain.ticksMechanics = 25;
		assertEquals(new LedgerRows.Numbers(plain).execution, n.execution, 1e-12);
	}

	@Test
	public void theTimelineDrawsTheStallAsAMechanicsStretch()
	{
		final int[] clock = {10};
		final FightTimeline t = new FightTimeline(() -> clock[0]);
		t.tickLost(false);
		clock[0] = 11;
		t.tickLost(true);
		assertEquals(2, t.lostTicks());
		t.excuseLost(10);
		t.excuseLost(11); // an eating tick stays eating
		t.excuseLost(99); // not recorded
		assertEquals(1, t.lostTicks());
		assertEquals(1, t.mechanicTicks());
	}
}
