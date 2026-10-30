package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-107, from Eric's 22:32 log (build 1f79693, read in place, nothing
 * copied). Two shots at a Mirrorback araxyte were thrown while the fight on
 * Araxxor was the one shown, so the throw's row was Araxxor's and each shot
 * took figures read when its projectile was seen, two ticks later, with the
 * prayer as it was by then:
 * <ul>
 * <li>[1752]-[1754]: thrown with Deadeye up (prayed=true), read with it down:
 * max 26 and avg 26 for a shot that hit its max of 31. The hitpoints were not
 * the cause: 51 were left before it, and avg 26 is its whole max, uncapped.</li>
 * <li>[2658]-[2660]: thrown without it (prayed=false; a 25 that was its max
 * landed), read with it up: max 29.</li>
 * </ul>
 */
public class AimedRowAndCsvBossesTest
{
	private static final int ARAXXOR = NpcID.ARAXXOR;
	private static final int MIRRORBACK = NpcID.ARAXXOR_MINION_MIRRORBACK;
	private static final int MIRRORBACK_INDEX = 61666;

	private static double[] row(int npcId, int max, double accuracy, double average, double variance, int cap)
	{
		final double[] row = new double[PvmPerformancePlugin.EXP_WIDTH];
		row[PvmPerformancePlugin.EXP_NPC_ID] = npcId;
		row[PvmPerformancePlugin.EXP_MAX] = max;
		row[PvmPerformancePlugin.EXP_ACCURACY] = accuracy;
		row[2] = average;
		row[7] = variance;
		row[PvmPerformancePlugin.EXP_CAP] = cap;
		return row;
	}

	private static double[] aimed(int npcId, int index, int max, double average, int cap)
	{
		final double[] row = Arrays.copyOf(row(npcId, max, 1.0, average, 0, cap), PvmPerformancePlugin.EXP_WIDTH + 1);
		row[PvmPerformancePlugin.EXP_WIDTH] = index;
		return row;
	}

	@Test
	public void aShotAtAnAddIsScoredFromTheRowOfItsThrowForThatAdd()
	{
		// [1752]: the shown row is Araxxor's; the aimed row is the Mirrorback's, Deadeye up, max 31, 51 left.
		final double[] shown = row(ARAXXOR, 63, 0.88, 27.8, 404, 599);
		final double[] onAdd = aimed(MIRRORBACK, MIRRORBACK_INDEX, 31, 31.0, 51);
		final double[] picked = PvmPerformancePlugin.rowFor(shown, onAdd, MIRRORBACK, MIRRORBACK_INDEX);
		assertSame(onAdd, picked);
		// A projectile takes it as authoritative, as it would the shown row of its own npc.
		assertTrue(PvmPerformancePlugin.scoresFromRow(true, false, false, false, picked, MIRRORBACK));
		// An attack at the shown fight's own npc keeps its row.
		assertSame(shown, PvmPerformancePlugin.rowFor(shown, onAdd, ARAXXOR, 60206));
		// Another Mirrorback (same id, another index) is not the one aimed at.
		assertSame(shown, PvmPerformancePlugin.rowFor(shown, onAdd, MIRRORBACK, 99999));
		assertFalse(PvmPerformancePlugin.scoresFromRow(true, false, false, false,
			PvmPerformancePlugin.rowFor(shown, onAdd, MIRRORBACK, 99999), MIRRORBACK));
		// Nothing read that tick.
		assertNull(PvmPerformancePlugin.rowFor(null, null, MIRRORBACK, MIRRORBACK_INDEX));
	}

	@Test
	public void aShotLandingOnItsBookingTickIsExpectedFromBeforeItsSplat()
	{
		// [1754]: the shot lands on the tick it is booked. Its figures are the throw's (51 left, max 31), not read after its
		// own 31 was counted; the landing cap (T-103) sees the 51 noted before the splat and changes nothing.
		final double[] picked = PvmPerformancePlugin.rowFor(row(ARAXXOR, 63, 0.88, 27.8, 404, 599),
			aimed(MIRRORBACK, MIRRORBACK_INDEX, 31, 31.0, 51), MIRRORBACK, MIRRORBACK_INDEX);
		assertNull(LandingCap.recap((int) picked[PvmPerformancePlugin.EXP_MAX], 1.0, picked[2], 0,
			(int) picked[PvmPerformancePlugin.EXP_CAP], 51));
		// Expected 31 for the 31; then the second shot's 20 on the 20 left: A 51, E 51, where the log read E 46.
		assertEquals(31 + 20, picked[2] + 20, 1e-9);
	}

	@Test
	public void theRowsPrayerIsTheThrows()
	{
		// [2658]: thrown without Deadeye; the aimed row of that tick reads max 25, which is what landed (type 43, its max).
		// The figures read at [2660], with Deadeye back up, said 29.
		final double[] atThrow = aimed(MIRRORBACK, 51881, 25, 25.0, 58);
		final double[] picked = PvmPerformancePlugin.rowFor(row(ARAXXOR, 63, 0.88, 27.8, 404, 400), atThrow, MIRRORBACK,
			51881);
		assertEquals(25, (int) picked[PvmPerformancePlugin.EXP_MAX]);
		// And the second shot, thrown with it up, 29: A 25 + 29 = E 54, where the log read E 58.
		assertEquals(54, picked[2] + 29, 1e-9);
	}

	private static Fight fight(String name, int id, int index)
	{
		final Fight f = new Fight(name, id, index, 100, index, null, 0);
		f.recordAttackMade(false);
		return f;
	}

	@Test
	public void bossesOnlyKeepsABosssAddsAndLeavesSlayerMonstersOut()
	{
		final BossRules rules = BossRules.load(new Gson(), null);
		final Fight araxxor = fight("Araxxor", ARAXXOR, 1);
		final Fight mirrorback = fight("Mirrorback Araxyte", MIRRORBACK, 2);
		final Fight sire = fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_STASIS_AWAKE, 3);
		final Fight lung = fight("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 4);
		final Fight demon = fight("Abyssal demon", NpcID.KOUREND_ABYSSAL, 5);
		demon.groupAsMonster();
		final List<Fight> history = Arrays.asList(demon, lung, sire, mirrorback, araxxor);
		final List<Fight> bosses = PvmPerformancePlugin.exported(history, true, rules);
		assertEquals(Arrays.asList(lung, sire, mirrorback, araxxor), bosses);
		// The same rule as History's lists.
		for (Fight f : history)
		{
			assertEquals(f.getTargetName(), !HistoryBudget.isMonster(f, rules), bosses.contains(f));
		}
		// Everything, unticked.
		assertEquals(history, PvmPerformancePlugin.exported(history, false, rules));
	}

	@Test
	public void aSlayerMonsterIsLeftOutEvenBeforeItsRunWasGrouped()
	{
		// A demon saved before T-089 carries no monsterRoom flag: still a monster by name and id.
		final BossRules rules = BossRules.load(new Gson(), null);
		final Fight old = fight("Abyssal demon", NpcID.KOUREND_ABYSSAL, 6);
		assertTrue(PvmPerformancePlugin.exported(Arrays.asList(old), true, rules).isEmpty());
	}
}
