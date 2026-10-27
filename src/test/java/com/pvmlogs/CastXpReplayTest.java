package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Casts that are not attacks, over Eric's logs (T-024). Each paid Magic the
 * ledger read as an attack, and inside the weapon's cooldown that raised
 * {@code XP CONTRADICTS BOOKING}:
 * <ul>
 * <li>the 23:08 log (2026-10-26) raised it 7 times: a greater thrall's 88 on
 * 283 and 1607, Death Charge's 90 on 284, 1386, 1608 and 1718, and Vengeance's
 * 112 on 2803. With the recognition turned off, this replay raises exactly
 * those seven, so its check is the plugin's;</li>
 * <li>the 14:55 Sire log (2026-10-27, recoil and Vengeance on master) raised it
 * on 266, the Vengeance cast. Its varbit rose on the same tick as the drop, on
 * both casts (266 and 325).</li>
 * </ul>
 */
public class CastXpReplayTest
{
	private static final String CLAWS = "/com/pvmlogs/replay/vardorvis-claws-recoil-veng-20261026-2308.log";
	private static final String SIRE = "/com/pvmlogs/replay/sire-recoil-veng-20261027-1455.log";

	@Test
	public void theThrallDeathChargeAndVengeanceRaiseNoContradiction() throws IOException
	{
		final ThrallReplay r = new ThrallReplay(1.55, 0.45, 47, 4).run(CLAWS);
		assertEquals(Collections.emptyList(), r.contradictions);
		assertCast(r.casts, 283, "Resurrect Greater thrall (+88 Magic)");
		assertCast(r.casts, 284, "Death Charge (+90 Magic)");
		assertCast(r.casts, 1386, "Death Charge (+90 Magic)");
		assertCast(r.casts, 1607, "Resurrect Greater thrall (+88 Magic)");
		assertCast(r.casts, 2803, "Vengeance (+112 Magic)");
	}

	@Test
	public void theSireVengeanceCastOn266IsNotAnAttack() throws IOException
	{
		// As logged: x1.125 on the Sire, a 61 max at about 0.75 with Piety, a speed-4 weapon.
		final ThrallReplay r = new ThrallReplay(1.125, 0.75, 61, 4).run(SIRE);
		assertFalse(r.contradictions.toString(), r.contradictions.contains(266));
		assertCast(r.casts, 266, "Vengeance (+112 Magic)");
		assertCast(r.casts, 325, "Vengeance (+112 Magic)");
	}

	@Test
	public void thrallAndDeathChargeCastsAcrossTheVardorvisLogsRaiseNone() throws IOException
	{
		for (String log : Arrays.asList("vardorvis-8-kills-20261026.log", "vardorvis-4-kills-ghost-20261026-1526.log",
			"vardorvis-zombie-eats-20261026-1934.log"))
		{
			final ThrallReplay r = new ThrallReplay(1.7, 0.45, 47, 4).run("/com/pvmlogs/replay/" + log);
			assertEquals(log, Collections.emptyList(), r.contradictions);
			assertFalse(log, r.casts.isEmpty());
		}
	}

	private static void assertCast(List<String> casts, int tick, String what)
	{
		final String line = "CAST not an attack on " + tick + ": " + what;
		assertTrue(line + " in " + casts, casts.contains(line));
	}
}
