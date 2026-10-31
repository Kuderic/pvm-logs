package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * T-117 items 3 and 4 (Eric, 2026-10-30 09:01 and 09:11): the overlay's
 * prayer, gear and stat boost rows read the expected damage the setup cost,
 * {@code stat boost  97% (-45 dmg)}, from T-010's per-attack setup sums; uptime
 * and the eating rows stay in ticks. The figures are on {@link
 * LedgerRows.Numbers} for the side panel (T-118). And the Summary says
 * {@code off-prayer damage} and {@code largest hit taken}.
 */
public class SetupDamageTest
{
	private static LedgerRows.Row row(List<LedgerRows.Row> rows, String left)
	{
		for (LedgerRows.Row row : rows)
		{
			if (row.left.equals(left))
			{
				return row;
			}
		}
		return null;
	}

	/** Ten attacks, each ideally 10: 9 dealt with the setup used, 1 of it lost to the boost and 0.5 to gear. */
	private static Fight fight(boolean scored)
	{
		// Any id on EncounterGroup's unscored list for an unscored one: a Hespori flower.
		final Fight f = scored ? new Fight("Vardorvis", 12223, 1, 700, 0L, null, 0)
			: new Fight("Flower", net.runelite.api.gameval.NpcID.HESPORI_HEALER_ACTIVE, 2, 50, 0L, null, 0);
		for (int i = 0; i < 10; i++)
		{
			f.recordAttackMade(false, 0.8);
			f.recordAttackResolved(true, false, 8.5, 10, 1, 0.5);
			f.recordExpected(59, 0.5, 8.5, 10);
			f.recordTickSpent(true);
		}
		assertEquals(scored, f.isScored());
		return f;
	}

	@Test
	public void theSetupsDamageIsIdealLessActualAndItsPartsAddUp()
	{
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(fight(true));
		final LedgerRows.Scope scope = LedgerRows.of(room);
		assertEquals(100, scope.idealSetupDamage(), 1e-9);
		final LedgerRows.Numbers n = new LedgerRows.Numbers(scope);
		// ideal 100, actual 85: 15 lost to the setup.
		assertEquals(15, n.setupDamage, 1e-9);
		// T-010's sums: the boost 10, gear 5, and the prayer the rest, here none.
		assertEquals(10, n.boostDamage, 1e-9);
		assertEquals(5, n.gearDamage, 1e-9);
		assertEquals(0, n.prayerDamage, 1e-9);
		assertEquals(n.setupDamage, n.boostDamage + n.gearDamage + n.prayerDamage, 1e-9);
	}

	@Test
	public void onlyScoredFightsAndEveryScopeCarryIt()
	{
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		room.add(fight(true));
		room.add(fight(false));
		assertEquals(100, LedgerRows.of(room).idealSetupDamage(), 1e-9);
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		assertEquals(100, LedgerRows.of(raid).idealSetupDamage(), 1e-9);
		final SessionTotals trip = new SessionTotals(0L);
		for (int i = 0; i < 10; i++)
		{
			trip.recordAttackMade(false, 0.8, false);
			trip.recordAttackResolved(true, true, 9, 10, 1, 0);
		}
		assertEquals(100, LedgerRows.of(trip).idealSetupDamage(), 1e-9);
		assertEquals(10, new LedgerRows.Numbers(LedgerRows.of(trip)).setupDamage, 1e-9);
		// Nothing read: unknown, so no damage figures.
		final Encounter empty = new Encounter("Vardorvis", null, 0L);
		assertEquals(-1, LedgerRows.of(empty).idealSetupDamage(), 1e-9);
		assertTrue(Double.isNaN(new LedgerRows.Numbers(LedgerRows.of(empty)).setupDamage));
	}

	@Test
	public void theOverlaysSetupRowsReadDamageAndUptimeStaysInTicks()
	{
		final LedgerExamples.FakeScope kill = LedgerExamples.kill();
		final LedgerRows.Numbers n = new LedgerRows.Numbers(kill);
		final List<LedgerRows.Row> rows = LedgerRows.build(kill, LedgerRows.State.fight("Vardorvis", true, true, false),
			new LedgerRows.Settings());
		assertEquals("98% (-" + Math.round(n.boostDamage) + " dmg)", row(rows, " stat boost").right);
		assertEquals("98% (-" + Math.round(n.boostDamage) + ")", row(rows, " stat boost").shortRight);
		// Uptime and eating in ticks.
		assertEquals("89% (-25 ticks)", row(rows, " uptime").right);
		assertEquals("7 ticks", row(rows, LedgerRows.EATING).right);
		// A part that cost nothing has no bracket.
		assertEquals("100%", row(rows, " prayer").right);
		assertEquals("100%", row(rows, " gear").right);
	}

	@Test
	public void withDamageShowsFromOne()
	{
		assertEquals("97%", LedgerRows.withDamage("97%", 0));
		assertEquals("97%", LedgerRows.withDamage("97%", 0.49));
		assertEquals("97%", LedgerRows.withDamage("97%", Double.NaN));
		assertEquals("97% (-1 dmg)", LedgerRows.withDamage("97%", 0.5));
		assertEquals("97% (-45 dmg)", LedgerRows.withDamage("97%", 45.2));
		assertEquals("88% (-1,204 dmg)", LedgerRows.withDamage("88%", 1204));
	}

	@Test
	public void aScopeWithoutTheSumsFallsBackToTicks()
	{
		final LedgerExamples.FakeScope s = LedgerExamples.kill();
		s.efficiency = 0.98;
		s.idealSetupDamage = -1;
		s.expectedDamage = 0;
		final List<LedgerRows.Row> rows = LedgerRows.build(s, LedgerRows.State.fight("Vardorvis", true, true, false),
			new LedgerRows.Settings());
		final LedgerRows.Row boost = row(rows, " stat boost");
		assertFalse(String.valueOf(boost), boost != null && boost.right.contains("dmg"));
	}

	@Test
	public void theSummarySaysOffPrayerDamageAndLargestHitTaken()
	{
		final LedgerExamples.FakeScope kill = LedgerExamples.kill();
		kill.biggestHitTaken = 41;
		kill.damageTakenUnprotected = 85;
		final List<LedgerRows.Row> rows = LedgerRows.build(kill, LedgerRows.State.fight("Vardorvis", true, true, false),
			new LedgerRows.Settings());
		assertEquals("41", row(rows, " largest hit taken").right);
		assertEquals("85", row(rows, " off-prayer damage").right);
		for (LedgerRows.Row r : rows)
		{
			assertFalse(r.left, r.left.contains("without protection") || r.left.contains("biggest hit"));
		}
	}
}
