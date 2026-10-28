package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * T-050's glue: the fight's outcome distribution fed by the bookings, the
 * per-tick figures and T-049's tracker, as the plugin feeds it. The tracker is
 * the real one; the figures are {@link SpecOutcomesTest}'s model of the game's.
 */
public class DrainLuckTest
{
	private static final int INDEX = 5;
	private static final int NPC = NpcIds.GOBLIN;

	private final double[] tripShift = new double[2];

	private static double[] figuresAt(DrainRule.State s)
	{
		final double p = SpecOutcomesTest.accuracy(s.defence);
		final double[] attack = SpecOutcomesTest.attackAt(s);
		final double[] hammer = SpecOutcomesTest.roll(p, 75);
		final double[] f = new double[CombatCalc.AT_WIDTH];
		f[CombatCalc.AT_MEAN] = attack[0];
		f[CombatCalc.AT_VARIANCE] = attack[1];
		f[CombatCalc.AT_SPEC_MEAN] = hammer[0];
		f[CombatCalc.AT_SPEC_VARIANCE] = hammer[1];
		f[CombatCalc.AT_SPEC_LAND] = p;
		f[CombatCalc.AT_SPEC_ACCURACY] = p;
		f[CombatCalc.AT_SPEC_MAX] = 75;
		f[CombatCalc.AT_CAP] = Integer.MAX_VALUE;
		return f;
	}

	private static DrainRule.Target boss(boolean immune)
	{
		return new DrainRule.Target(200, 100, 0, 200, 200, 100, 0, 0, immune, false, false, false, 0);
	}

	private final DrainTracker tracker = new DrainTracker(null);

	private DrainLuck luck()
	{
		return new DrainLuck(tracker::tracked, (npcId, base, s) -> figuresAt(s), (e, v) ->
		{
			tripShift[0] += e;
			tripShift[1] += v;
		}, null);
	}

	private static Fight fight()
	{
		return new Fight("Boss", NPC, INDEX, 1000, 0L, null, 0);
	}

	// An attack made on `tick`, booked as the plugin books it: into the fight, then into the outcomes.
	private static void attack(DrainLuck luck, Fight f, int tick, DrainRule.State real)
	{
		luck.snapshot(f, NPC, tick);
		final double[] fig = figuresAt(real);
		f.recordExpected(50, SpecOutcomesTest.accuracy(real.defence), fig[CombatCalc.AT_MEAN], fig[CombatCalc.AT_VARIANCE]);
		luck.booked(f, tick, tick + 1, false, null, fig[CombatCalc.AT_MEAN], fig[CombatCalc.AT_VARIANCE]);
	}

	private static void special(DrainLuck luck, Fight f, int made, int booked, SpecialWeapon weapon, DrainRule.State real)
	{
		luck.snapshot(f, NPC, made);
		final double[] fig = figuresAt(real);
		f.recordExpected(75, fig[CombatCalc.AT_SPEC_LAND], fig[CombatCalc.AT_SPEC_MEAN], fig[CombatCalc.AT_SPEC_VARIANCE]);
		luck.booked(f, made, booked, true, weapon, fig[CombatCalc.AT_SPEC_MEAN], fig[CombatCalc.AT_SPEC_VARIANCE]);
	}

	private static Set<Integer> defences(SpecOutcomes o)
	{
		final Set<Integer> out = new HashSet<>();
		for (DrainRule.State s : o.probabilities().keySet())
		{
			out.add(s.defence);
		}
		return out;
	}

	@Test
	public void noDrainSpecialsLeaveTheFiguresAsTheyWere()
	{
		final DrainLuck luck = luck();
		final Fight f = fight();
		final Fight plain = fight();
		luck.start(f, tracker.track(INDEX, NPC, "Boss", boss(false)));
		final DrainRule.State base = DrainRule.State.of(boss(false));
		for (int tick = 0; tick < 40; tick += 4)
		{
			attack(luck, f, tick, base);
			final double[] fig = figuresAt(base);
			plain.recordExpected(50, SpecOutcomesTest.accuracy(200), fig[CombatCalc.AT_MEAN], fig[CombatCalc.AT_VARIANCE]);
			luck.tick(tick + 1);
		}
		// Identical, not merely close.
		assertEquals(plain.getSumExpectedAverageHit(), f.getSumExpectedAverageHit(), 0);
		assertEquals(plain.damageVariance(), f.damageVariance(), 0);
		assertTrue(f.hasDrainValue());
		assertEquals(0, f.getDrainValueDamage(), 1e-9);
		assertEquals(0, f.getSpecLuckDamage(), 0);
		assertEquals(0, tripShift[0], 0);
	}

	@Test
	public void anImmuneBossKeepsNothing()
	{
		final DrainLuck luck = luck();
		final Fight f = fight();
		luck.start(f, tracker.track(INDEX, NPC, "Vardorvis", boss(true)));
		assertFalse(luck.follows(f));
		attack(luck, f, 0, DrainRule.State.of(boss(true)));
		assertFalse(f.hasDrainValue());
		assertTrue(Double.isNaN(f.getDrainValueDamage()));
		assertTrue(Double.isNaN(f.getSpecLuckDamage()));
	}

	@Test
	public void aMeleeHammerBranchesOnceThoughTheTrackerDrainsToo()
	{
		final DrainLuck luck = luck();
		final Fight f = fight();
		final DrainRule.Target t = boss(false);
		luck.start(f, tracker.track(INDEX, NPC, "Boss", t));
		// Thrown on 10, its splat on 11: booked from the splat, then the tracker lands the drain that tick.
		tracker.ownThrow(10, SpecialWeapon.DRAGON_WARHAMMER, INDEX, NPC, "Boss", 11, t, false);
		special(luck, f, 10, 11, SpecialWeapon.DRAGON_WARHAMMER, DrainRule.State.of(t));
		tracker.ownSplat(INDEX, 11, 30);
		tracker.tick(11);
		assertEquals(140, tracker.tracked(INDEX).state.defence);
		luck.tick(11);
		for (int tick = 12; tick < 20; tick++)
		{
			luck.tick(tick);
		}
		// 200 or 140, never 98: the tracker's own drain was the booking's, not a second one.
		assertEquals(new HashSet<>(Arrays.asList(200, 140)), defences(luck.outcomes(f)));
		attack(luck, f, 20, tracker.tracked(INDEX).state);
		// Landed: good luck, and the drain was worth something.
		assertTrue(f.getSpecLuckDamage() > 0);
		assertTrue(f.getDrainValueDamage() > f.getDrainValueExpected());
		assertEquals(f.getRealizedExpected() - f.getSumExpectedAverageHit(), f.getSpecLuckDamage(), 1e-9);
		// The trip carries the same shift.
		assertEquals(-f.getSpecLuckDamage(), tripShift[0], 1e-9);
		final String line = luck.end(f);
		assertTrue(line, line.startsWith("SPEC LUCK Boss: 2 attacks, 1 of them my draining specials, 2 states;"));
	}

	@Test
	public void anElderMaulsDrainComesBeforeItsBooking()
	{
		final DrainLuck luck = luck();
		final Fight f = fight();
		final DrainRule.Target t = boss(false);
		luck.start(f, tracker.track(INDEX, NPC, "Boss", t));
		luck.snapshot(f, NPC, 10);
		// The maul's hit is read from the throw's experience: the tracker drains on the throw, the splat comes 2 ticks on.
		tracker.ownThrow(10, SpecialWeapon.ELDER_MAUL, INDEX, NPC, "Boss", 12, t, true);
		assertEquals(130, tracker.tracked(INDEX).state.defence);
		luck.tick(10);
		luck.tick(11);
		// Its own figures were read after its drain, as the plugin reads them (its drain lands before refreshExpected).
		special(luck, f, 10, 12, SpecialWeapon.ELDER_MAUL, tracker.tracked(INDEX).state);
		luck.tick(12);
		for (int tick = 13; tick < 20; tick++)
		{
			luck.tick(tick);
		}
		assertEquals(new HashSet<>(Arrays.asList(200, 130)), defences(luck.outcomes(f)));
	}

	@Test
	public void anOwnDrainNoBookingClaimsIsTakenAsItHappened()
	{
		final DrainLuck luck = luck();
		final Fight f = fight();
		final DrainRule.Target t = boss(false);
		luck.start(f, tracker.track(INDEX, NPC, "Boss", t));
		tracker.ownThrow(10, SpecialWeapon.DRAGON_WARHAMMER, INDEX, NPC, "Boss", 11, t, false);
		tracker.ownSplat(INDEX, 11, 30);
		tracker.tick(11);
		for (int tick = 11; tick <= 11 + DrainLuck.MATCH_TICKS + 1; tick++)
		{
			luck.tick(tick);
		}
		final SpecOutcomes o = luck.outcomes(f);
		assertEquals(new HashSet<>(Arrays.asList(140)), defences(o));
		assertFalse(o.branched());
	}

	@Test
	public void aPartyDrainReachesEveryState()
	{
		final DrainLuck luck = luck();
		final Fight f = fight();
		final DrainRule.Target t = boss(false);
		luck.start(f, tracker.track(INDEX, NPC, "Boss", t));
		tracker.ownThrow(10, SpecialWeapon.DRAGON_WARHAMMER, INDEX, NPC, "Boss", 11, t, false);
		special(luck, f, 10, 11, SpecialWeapon.DRAGON_WARHAMMER, DrainRule.State.of(t));
		tracker.ownSplat(INDEX, 11, 0);
		tracker.tick(11);
		luck.tick(11);
		// A party member's godsword for 20 on 14.
		tracker.partyDrain(14, SpecialWeapon.BANDOS_GODSWORD, 20, INDEX, NPC, "Boss", t);
		luck.tick(14);
		assertEquals(new HashSet<>(Arrays.asList(180, 120)), defences(luck.outcomes(f)));
		// Missed: bad luck.
		attack(luck, f, 16, tracker.tracked(INDEX).state);
		assertTrue(f.getSpecLuckDamage() < 0);
	}

	@Test
	public void oldKillsAndTheirCsvAreUnchanged()
	{
		final Fight old = fight();
		old.recordExpected(50, 0.5, 12.5, 100);
		old.end(true, 1000L);
		final Gson gson = new Gson();
		final Fight back = gson.fromJson(gson.toJson(old), Fight.class);
		assertFalse(back.hasDrainValue());
		assertEquals(12.5, back.getSumExpectedAverageHit(), 0);
		assertEquals(100, back.damageVariance(), 0);
		final String[] cells = PvmPerformancePlugin.csvRow(back).trim().split(",", -1);
		assertEquals(Arrays.asList("", "", "", "", "", ""), Arrays.asList(cells).subList(cells.length - 6, cells.length));
		// A kill with them keeps them through the history file.
		final Fight now = fight();
		now.recordExpected(50, 0.5, 12.5, 100);
		now.recordSpecOutcomes(-2, 30, 8, 10);
		final Fight saved = gson.fromJson(gson.toJson(now), Fight.class);
		assertEquals(10.5, saved.getSumExpectedAverageHit(), 1e-12);
		assertEquals(130, saved.damageVariance(), 1e-12);
		assertEquals(2, saved.getSpecLuckDamage(), 1e-12);
		assertEquals(8, saved.getDrainValueDamage(), 0);
		assertEquals(10, saved.getDrainValueExpected(), 0);
		assertNull(new DrainLuck(i -> null, null, null, null).end(saved));
	}

	@Test
	public void theScopesSumTheScoredFights()
	{
		final Fight a = fight();
		a.recordExpected(50, 0.5, 100, 400);
		a.recordSpecOutcomes(-5, 50, 20, 25);
		final Fight b = fight();
		b.recordExpected(50, 0.5, 100, 400);
		final Encounter room = new Encounter("Boss", null, 0L);
		room.add(a);
		room.add(b);
		assertEquals(20, room.getDrainValueDamage(), 1e-12);
		assertEquals(5, room.getSpecLuckDamage(), 1e-12);
		assertEquals(195, room.sumExpectedAverageHit(), 1e-12);
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		assertEquals(25, raid.getDrainValueExpected(), 1e-12);
		final SessionTotals trip = new SessionTotals(0L);
		trip.recordExpected(0.5, 200, 800);
		trip.recordOutcomeShift(-5, 50);
		a.end(true, 1000L);
		trip.recordFightEnded(true, a, 1000L);
		assertEquals(195, trip.getSumExpectedAverageHit(), 1e-12);
		assertEquals(850, trip.damageVariance(), 1e-12);
		assertEquals(5, trip.getSpecLuckDamage(), 1e-12);
		assertTrue(Double.isNaN(new SessionTotals(0L).getDrainValueDamage()));
		// Seconds as luck's: 100 scored ticks (60 s), 195 expected and nothing else: 5 damage is 60 x 5 / 195.
		assertEquals(60 * 5 / 195.0, DrainValue.seconds(5, 100, 195, 0), 1e-12);
		assertTrue(Double.isNaN(DrainValue.seconds(5, 0, 195, 0)));
	}

	@Test
	public void weakenIsInTheTable()
	{
		for (int id : new int[]{ItemID.DARKLIGHT, ItemID.ARCLIGHT, ItemID.EMBERLIGHT})
		{
			final SpecialAttack spec = SpecialAttack.forItem(id);
			assertEquals("Weaken", spec.getDisplayName());
			assertEquals(AttackType.STAB, spec.rollsAgainst());
			assertEquals(1.0, spec.getAttackRollMultiplier(), 0);
		}
		assertNull(SpecialAttack.DRAGON_WARHAMMER.rollsAgainst());
		assertEquals(SpecialWeapon.EMBERLIGHT, DrainLuck.weaponFor(ItemID.EMBERLIGHT));
	}

	@Test
	public void theOutcomesAreKeptForAFewFights()
	{
		final DrainLuck luck = luck();
		final Fight[] fights = new Fight[DrainLuck.KEEP_FIGHTS + 2];
		for (int i = 0; i < fights.length; i++)
		{
			fights[i] = fight();
			luck.start(fights[i], tracker.track(INDEX, NPC, "Boss", boss(false)));
		}
		assertFalse(luck.follows(fights[0]));
		assertTrue(luck.follows(fights[fights.length - 1]));
		final Map<DrainRule.State, Double> p = luck.outcomes(fights[fights.length - 1]).probabilities();
		assertEquals(1, p.size());
	}
}
