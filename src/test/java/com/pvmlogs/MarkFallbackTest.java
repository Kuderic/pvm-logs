package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;
import org.junit.Test;

/**
 * T-139: the Mark of Darkness read from Magic experience when its message isn't seen, beside other casts on its tick;
 * and Yama's MOD line, only when what applies changes. The cases are Eric's logs of 2026-10-31 (build 4c6b379):
 * {@code pvm-debug-20261031-180053} (KC 353) and {@code -183009} (KC 354). The plugin's side, the cast tick's weapon,
 * is in {@link MagicBonusesTest}.
 */
public class MarkFallbackTest
{
	/** [1799] of the 18:00 log: a +158 as one thrall spawned. Two thralls can't be, so 88 + 70 is the Mark's. */
	@Test
	public void aGreaterThrallAndTheMark()
	{
		assertEquals("Resurrect Greater thrall", XpLedger.markBeside(158, true));
		assertEquals("Resurrect Greater thrall and Mark of Darkness", XpLedger.castsPaying(158));
		// With no thrall spawned, the 158 would be Lesser Corruption and Ward of Arceuus: not taken for the Mark.
		assertNull(XpLedger.markBeside(158, false));
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.MAGIC, 158);
		assertNull(ledger.close(1799, 14176, 1.0, null));
		assertEquals("CAST not an attack on 1799: Resurrect Greater thrall and Mark of Darkness (+158 Magic)",
			ledger.castNote());
		assertEquals(158, ledger.castMagic());
	}

	/** 88 + 90 + 70: a Greater thrall, Death Charge and the Mark, three casts on one tick. */
	@Test
	public void aThrallDeathChargeAndTheMark()
	{
		assertEquals("Resurrect Greater thrall, Death Charge and Mark of Darkness", XpLedger.castsPaying(248));
		assertEquals("Resurrect Greater thrall and Death Charge", XpLedger.markBeside(248, true));
		// Read beside the thralls and Death Charge only: 248 is also a superior thrall, Ward of Arceuus and Greater
		// Corruption, and with no thrall, Death Charge, Ward of Arceuus and Lesser Corruption.
		assertNull(XpLedger.markBeside(248, false));
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.MAGIC, 248);
		assertNull("a cast, not an attack", ledger.close(10, 14176, 1.0, null));
		assertEquals(248, ledger.castMagic());
	}

	/** 90 + 70 with no thrall: Death Charge and the Mark. With one: Death Charge and a superior thrall. */
	@Test
	public void deathChargeAndTheMark()
	{
		assertEquals("Death Charge", XpLedger.markBeside(160, false));
		assertNull(XpLedger.markBeside(160, true));
	}

	/** A lone 70 is the Mark with no thrall spawning, a superior thrall with one: #157's rule, unchanged. */
	@Test
	public void aLoneSeventy()
	{
		assertEquals("", XpLedger.markBeside(70, false));
		assertNull(XpLedger.markBeside(70, true));
		assertEquals("Resurrect Superior thrall or Mark of Darkness", XpLedger.castsPaying(70));
	}

	/** Casts with no 70 in them are never the Mark, whatever spawned. */
	@Test
	public void noSeventyNoMark()
	{
		for (int magic : new int[] {88, 90, 112, 130, 178, 55})
		{
			assertNull("+" + magic, XpLedger.markBeside(magic, false));
			assertNull("+" + magic, XpLedger.markBeside(magic, true));
		}
		// The pairs that named a cast before keep their names.
		assertEquals("Resurrect Greater thrall and Death Charge", XpLedger.castsPaying(178));
		assertEquals("Vengeance and Spellbook Swap", XpLedger.castsPaying(242));
	}

	/** Each decoding has every spell once at most and one thrall at most; the fewest casts come first. */
	@Test
	public void decodingsAreCastable()
	{
		for (int magic = 50; magic <= 400; magic++)
		{
			int last = 0;
			for (int[] casts : XpLedger.castsSumming(magic))
			{
				assertTrue(casts.length >= last);
				last = casts.length;
				int sum = 0;
				int thralls = 0;
				for (int i = 0; i < casts.length; i++)
				{
					sum += XpLedger.CAST_XP[casts[i]];
					thralls += XpLedger.CAST_NAMES[casts[i]].contains("thrall") ? 1 : 0;
					for (int j = 0; j < i; j++)
					{
						assertTrue(casts[i] != casts[j]);
					}
				}
				assertEquals(magic, sum);
				assertTrue(thralls <= 1);
			}
		}
		assertEquals("Mark of Darkness", XpLedger.CAST_NAMES[XpLedger.MARK]);
	}

	/**
	 * #157 printed {@code MOD Yama} on every attack, its hitpoint share changing. Now once per change of what applies:
	 * the 18:30 log's [108]-[115] stretch (98%, 97%, ... with -30 throughout) prints once, then phase 3 once.
	 */
	@Test
	public void theModLineOnlyOnAChange()
	{
		final BossRules.ModifierRule notMagic = rule("tank not using magic", -30, null);
		final BossRules.ModifierRule phase3 = rule("phase 3", null, "Phase 3");
		final Map<Integer, String> seen = new HashMap<>();
		final List<String> printed = new ArrayList<>();
		final double[] shares = {1.0, 0.98, 0.97, 0.95, 0.90, 0.62, 0.40};
		for (double share : shares)
		{
			if (DamageModifiers.situationChanged(seen, 51034, Collections.singletonList(notMagic)))
			{
				printed.add(DamageModifiers.situationLine("Yama", "you", new ModifierTracker.Situation(share, false),
					Collections.singletonList(notMagic)));
			}
		}
		assertEquals(1, printed.size());
		assertEquals("MOD Yama: targeting you (not magic), 100% of its hitpoints left: tank not using magic"
			+ " (magic defence -30)", printed.get(0));
		for (double share : new double[] {0.33, 0.30, 0.20})
		{
			if (DamageModifiers.situationChanged(seen, 51034, Collections.singletonList(phase3)))
			{
				printed.add(DamageModifiers.situationLine("Yama", "you", new ModifierTracker.Situation(share, true),
					Collections.singletonList(phase3)));
			}
		}
		assertEquals(2, printed.size());
		assertTrue(printed.get(1), printed.get(1).contains("33% of its hitpoints left: phase 3"));
		// No player targeted: nothing applies, which is a change too; another npc keeps its own.
		assertTrue(DamageModifiers.situationChanged(seen, 51034, Collections.emptyList()));
		assertFalse(DamageModifiers.situationChanged(seen, 51034, Collections.emptyList()));
		assertTrue(DamageModifiers.situationChanged(seen, 9999, Collections.emptyList()));
	}

	/**
	 * KC 353 and 354 replayed: Yama's fights after the Mark was taken as down ([2223] of the 18:00 log, [385] of the
	 * 18:30), each purging staff cast's figures as logged swapped for those the same logs give a staff cast in phase 3
	 * with the Mark up: max 60 at chances 0.66 for a 38 at 0.54, 58 at 0.60 for a 37 at 0.47. A cast the old figure
	 * capped at the hitpoints left keeps that share. Each fight is {A, E, sd as logged, then each cast's old max,
	 * chances, mean, variance}.
	 */
	@Test
	public void kc353And354Replayed()
	{
		final double[] std = {38, 0.54, 10.35, 158.5};
		final double[] p61 = {38, 0.61, 11.6, 163.2};
		final double[][][] fights = {
			fight(270, 135.3, 45.6, 11, std, new double[] {37, 0.47, 8.78, 142.4}),
			fight(314, 146.2, 47.2, 13, std, p61),
			fight(118, 46.4, 25.6, 3, std, p61, new double[] {37, 0.47, 3.71, 18.1}),
			fight(339, 139.0, 46.0, 11, std),
			fight(238, 103.5, 39.8, 10, std),
			fight(186, 133.0, 45.2, 10, std, new double[] {38, 0.54, 10.34, 157.8}),
		};
		// Before: z +2.95, +3.56, +2.79 (KC 353); +4.34, +3.38, +1.17 (KC 354).
		final double[][] after = {{247.9, 0.31}, {277.2, 0.49}, {86.7, 0.76}, {242.9, 1.38}, {198.0, 0.63},
			{237.0, -0.74}};
		for (int i = 0; i < fights.length; i++)
		{
			final double[] r = replay(fights[i]);
			assertEquals("fight " + i, after[i][0], r[0], 0.1);
			assertEquals("fight " + i, after[i][1], r[1], 0.01);
		}
	}

	private static double[][] fight(double a, double e, double sd, int stdCasts, double[] std, double[]... others)
	{
		final double[][] f = new double[1 + stdCasts + others.length][];
		f[0] = new double[] {a, e, sd};
		for (int i = 0; i < stdCasts; i++)
		{
			f[1 + i] = std;
		}
		System.arraycopy(others, 0, f, 1 + stdCasts, others.length);
		return f;
	}

	/** {E, z} with the fight's casts rescored as {@link #kc353And354Replayed} says. */
	private static double[] replay(double[][] f)
	{
		double e = f[0][1];
		double v = f[0][2] * f[0][2];
		for (int i = 1; i < f.length; i++)
		{
			final double[] c = f[i];
			final double max = c[0] == 38 ? 60 : 58;
			final double p = c[0] == 38 ? 0.66 : 0.60;
			final double mean = p * max / 2;
			final double var = p * max * (2 * max + 1) / 6 - mean * mean;
			final double kept = Math.min(1.0, c[2] / (c[1] * c[0] / 2));
			e += mean * kept - c[2];
			v += var * kept * kept - c[3];
		}
		return new double[] {e, (f[0][0] - e) / Math.sqrt(v)};
	}

	private static BossRules.ModifierRule rule(String name, Integer magicDefenceChange, String version)
	{
		final BossRules.ModifierRule r = new BossRules.ModifierRule();
		r.name = name;
		r.magicDefenceChange = magicDefenceChange;
		r.version = version;
		if (version != null)
		{
			r.hpAtMost = 0.3334;
		}
		else
		{
			r.hpAbove = 0.3334;
			r.tankMagic = false;
		}
		return r;
	}
}
