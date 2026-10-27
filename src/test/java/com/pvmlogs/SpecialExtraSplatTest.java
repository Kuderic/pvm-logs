package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-037: specials whose extra damage is the player's own splat (OSRS Wiki,
 * read 2026-10-27). Blood Sacrifice (ancient godsword): 25 eight ticks after a
 * landed hit. Saradomin's Lightning (Saradomin sword): 1-16 Magic damage when
 * the melee hit lands. Both in the special's expected damage and variance, and
 * the godsword's delayed splat taken as its special's.
 */
public class SpecialExtraSplatTest
{
	private static final int NONE = Integer.MAX_VALUE;

	private static double[] roll(int min, int max, int cap)
	{
		final double[] p = new double[Math.min(max, cap) + 1];
		for (int r = min; r <= max; r++)
		{
			p[Math.min(r, cap)] += 1.0 / (max - min + 1);
		}
		return p;
	}

	private static double[] fixed(int value)
	{
		final double[] p = new double[value + 1];
		p[value] = 1;
		return p;
	}

	private static double[] convolve(double[] a, double[] b)
	{
		final double[] c = new double[a.length + b.length - 1];
		for (int i = 0; i < a.length; i++)
		{
			for (int j = 0; j < b.length; j++)
			{
				c[i + j] += a[i] * b[j];
			}
		}
		return c;
	}

	private static double[] landing(double[] landed, double p)
	{
		final double[] out = new double[landed.length];
		for (int d = 0; d < landed.length; d++)
		{
			out[d] = p * landed[d];
		}
		out[0] += 1 - p;
		return out;
	}

	private static void assertMatches(String what, double[] pmf, DamageMoments m)
	{
		double mean = 0;
		for (int d = 0; d < pmf.length; d++)
		{
			mean += d * pmf[d];
		}
		double variance = 0;
		for (int d = 0; d < pmf.length; d++)
		{
			variance += (d - mean) * (d - mean) * pmf[d];
		}
		assertEquals(what + " mean", mean, m.mean, 1e-9);
		assertEquals(what + " variance", variance, m.variance(), 1e-7);
	}

	@Test
	public void bloodSacrificeAddsTwentyFiveToALandedHit()
	{
		final double p = 0.85;
		// A godsword's special max: 52 x 1.1 = 57.
		final DamageMoments m = CombatCalc.withExtraSplat(57, p, NONE, SpecialAttack.ANCIENT_GODSWORD);
		assertMatches("AGS", landing(convolve(roll(0, 57, NONE), fixed(25)), p), m);
		assertEquals(p * (57 / 2.0 + 25), m.mean, 1e-12);
		// Under Verzik's per-hitsplat cap of 10, the 25 is held to 10 like the hit.
		assertMatches("AGS capped", landing(convolve(roll(0, 57, 10), fixed(10)), p),
			CombatCalc.withExtraSplat(57, p, 10, SpecialAttack.ANCIENT_GODSWORD));
	}

	@Test
	public void saradominsLightningAddsOneToSixteenToALandedHit()
	{
		final double p = 0.7;
		final DamageMoments m = CombatCalc.withExtraSplat(48, p, NONE, SpecialAttack.SARADOMIN_SWORD);
		assertMatches("SS", landing(convolve(roll(0, 48, NONE), roll(1, 16, NONE)), p), m);
		assertEquals(p * (24 + 8.5), m.mean, 1e-12);
		assertMatches("SS capped", landing(convolve(roll(0, 48, 10), roll(1, 16, 10)), p),
			CombatCalc.withExtraSplat(48, p, 10, SpecialAttack.SARADOMIN_SWORD));
	}

	@Test
	public void theSplatCounts()
	{
		// The lightning is the special's second splat on the same attack: already counted.
		assertEquals(2, AttackSplats.of(ItemID.SARADOMIN_SWORD, true, 1, null).count);
		assertEquals(AttackSplats.BLOOD_SACRIFICE_TICKS, AttackSplats.delayedSplatTicks(SpecialAttack.ANCIENT_GODSWORD));
		assertEquals(-1, AttackSplats.delayedSplatTicks(SpecialAttack.SARADOMIN_SWORD));
		assertEquals(-1, AttackSplats.delayedSplatTicks(null));
	}

	@Test
	public void aDelayedSplatIsClaimedOnceInItsWindow()
	{
		final AttackSplats.Delayed delayed = new AttackSplats.Delayed();
		delayed.expect(7, 109, 25, 100);
		// Not another npc, not another amount, not outside a tick either side.
		assertEquals(Integer.MIN_VALUE, delayed.claim(8, 109, 25));
		assertEquals(Integer.MIN_VALUE, delayed.claim(7, 109, 24));
		assertEquals(Integer.MIN_VALUE, delayed.claim(7, 107, 25));
		assertEquals(100, delayed.claim(7, 110, 25));
		assertEquals(Integer.MIN_VALUE, delayed.claim(7, 110, 25));
		delayed.expect(7, 120, 25, 111);
		delayed.prune(121);
		assertEquals(1, delayed.size());
		delayed.prune(122);
		assertEquals(0, delayed.size());
	}

	// ---- Replay-style: the godsword's 25, eight ticks after its special ----

	private static final List<String> WITHOUT_THRALL = Arrays.asList(
		"[100] fight START Vardorvis (npc 12223, index 50365, maxHp 700)",
		"[101] hitsplat 40 type 16 on Vardorvis (npc 12223, index 50365), landedAttack=true, fromFlight=false",
		"[101]   scored as a SPECIAL: max 57, chances 0.85, avg 45.48",
		"[107] hitsplat 30 type 16 on Vardorvis (npc 12223, index 50365), landedAttack=true, fromFlight=false",
		"[109] hitsplat 25 type 16 on Vardorvis (npc 12223, index 50365), landedAttack=true, fromFlight=false",
		"[113] hitsplat 33 type 16 on Vardorvis (npc 12223, index 50365), landedAttack=true, fromFlight=false",
		"[114] fight END");

	@Test
	public void theDelayedTwentyFiveIsTheSpecialsWithoutAThrall()
	{
		final ThrallReplay r = new ThrallReplay(1.55, 0.6, 52, 6).withAncientGodsword().run(WITHOUT_THRALL);
		assertEquals(Collections.singletonList("109: 25 (thrown 100)"), r.delayedSplats);
		// No blow booked for it: the special on 100, then the swings on 106 and 112.
		assertEquals(Arrays.asList(100, 106, 112), r.blows.get(1));
		assertEquals(Collections.emptyList(), r.extras);
		// Master, which didn't know it: a phantom blow on 108.
		final ThrallReplay before = new ThrallReplay(1.55, 0.6, 52, 6).run(WITHOUT_THRALL);
		assertEquals(Arrays.asList(100, 106, 108, 112), before.blows.get(1));
	}

	@Test
	public void theDelayedTwentyFiveIsTheSpecialsWithAThrallOut()
	{
		final List<String> lines = Arrays.asList(
			"[95] THRALL spawned: npc 10880 (index 53691), 1 tiles from me, interacting nothing, active varbit 1",
			"[100] fight START Vardorvis (npc 12223, index 50365, maxHp 700)",
			"[101] hitsplat 40 type 16 on Vardorvis (npc 12223, index 50365), landedAttack=true, fromFlight=false",
			"[101]   scored as a SPECIAL: max 57, chances 0.85, avg 45.48",
			"[105] THRALL projectile 1234 from index 53691 at npc 12223 (index 50365), lands t+0",
			"[105] hitsplat 2 type 16 on Vardorvis (npc 12223, index 50365), landedAttack=true, fromFlight=false",
			"[107] hitsplat 30 type 16 on Vardorvis (npc 12223, index 50365), landedAttack=true, fromFlight=false",
			// The delayed 25 and a thrall splat on the same tick.
			"[109] THRALL projectile 1234 from index 53691 at npc 12223 (index 50365), lands t+0",
			"[109] hitsplat 25 type 16 on Vardorvis (npc 12223, index 50365), landedAttack=true, fromFlight=false",
			"[109] hitsplat 3 type 16 on Vardorvis (npc 12223, index 50365), landedAttack=true, fromFlight=false",
			"[113] hitsplat 33 type 16 on Vardorvis (npc 12223, index 50365), landedAttack=true, fromFlight=false",
			"[114] fight END");
		final ThrallReplay r = new ThrallReplay(1.55, 0.6, 52, 6).withAncientGodsword().run(lines);
		assertEquals(Collections.singletonList("109: 25 (thrown 100)"), r.delayedSplats);
		assertEquals(Arrays.asList(100, 106, 112), r.blows.get(1));
		// The thrall keeps its own splats, the one beside the 25 included; the 25 is never weighed as its.
		for (ThrallReplay.Judged j : r.judged)
		{
			assertTrue(j.toString(), j.amount != 25);
		}
		assertTrue(r.judgedBetween(109, 109).toString(), r.judgedBetween(109, 109).get(0).thralls);
		assertEquals(Collections.emptyList(), r.extras);
	}
}
