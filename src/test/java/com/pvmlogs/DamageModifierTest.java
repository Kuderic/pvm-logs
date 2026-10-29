package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * T-057: the bosses' damage modifiers - the Abyssal Sire halved while it walks,
 * the Leviathan capped while stunned, its wound, its specials and the enrage's
 * pathfinder - from the bundled rules through the tracker to the moments the
 * expected figures take.
 */
public class DamageModifierTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int LEVI = 12214;
	private static final int INDEX = 61375;
	private static final ModifierTracker.InsideCheck NOWHERE = (id, range) -> false;

	private final List<String> lines = new ArrayList<>();
	private final ModifierTracker tracker = new ModifierTracker(lines::add);

	private static BossRules.DamageModifierRules rules(int npcId)
	{
		final BossRules.Boss boss = RULES.forNpc(npcId);
		assertNotNull(boss);
		assertNotNull(boss.damageModifiers);
		return boss.damageModifiers;
	}

	private List<String> names(int npcId, String style, int landTick, boolean behind, ModifierTracker.InsideCheck in)
	{
		final List<String> out = new ArrayList<>();
		for (BossRules.ModifierRule m : tracker.applying(INDEX, rules(npcId), npcId, style, landTick, behind, in))
		{
			out.add(m.name);
		}
		return out;
	}

	private List<BossRules.ModifierRule> mods(int npcId, String style, int landTick, boolean behind,
		ModifierTracker.InsideCheck in)
	{
		return tracker.applying(INDEX, rules(npcId), npcId, style, landTick, behind, in);
	}

	// ---- the Abyssal Sire ---------------------------------------------------------------------------------------------

	/** Halved, rounded down, on its walking form (5889) only. */
	@Test
	public void theSireWalkingIsHalved()
	{
		final List<BossRules.ModifierRule> walking = mods(5889, "RANGED", 100, false, NOWHERE);
		assertEquals(Collections.singletonList("walking"), names(5889, "MELEE", 100, false, NOWHERE));
		assertEquals(30, ModifierTracker.max(walking, 61));
		assertEquals(10, ModifierTracker.damage(walking, 21, 61, true));
		for (int id : new int[]{5886, 5887, 5888, 5890, 5891, 5908})
		{
			assertTrue("npc " + id, mods(id, "MELEE", 100, false, NOWHERE).isEmpty());
		}
	}

	/**
	 * Eric's ten Sire kills of 2026-10-27: every splat on the walking form is at
	 * most half the max its attack was scored with, where on 5890 they reach it.
	 */
	@Test
	public void theSireLogAgrees() throws Exception
	{
		final Pattern scored = Pattern.compile("^\\[(\\d+)\\]   scored as an ordinary attack: max (\\d+)");
		final Pattern splat = Pattern.compile(
			"^\\[(\\d+)\\] hitsplat (\\d+) type \\d+ on Abyssal Sire \\(npc (\\d+),.*fromFlight=(true|false)");
		final List<BossRules.ModifierRule> walking = mods(5889, null, 0, false, NOWHERE);
		final List<String> log = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(DamageModifierTest.class.getResourceAsStream(
			"replay/sire-10-kills-20261027-0130.log"), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = in.readLine()) != null)
			{
				log.add(line);
			}
		}
		int walked = 0;
		int fullOn5890 = 0;
		for (int i = 0; i < log.size(); i++)
		{
			final Matcher h = splat.matcher(log.get(i));
			if (!h.find())
			{
				continue;
			}
			// A projectile's attack was scored before it landed; a melee blow is scored by its own splat, just after it.
			final boolean flew = Boolean.parseBoolean(h.group(4));
			final int tick = Integer.parseInt(h.group(1));
			int max = -1;
			for (int j = flew ? i - 1 : i + 1; j >= 0 && j < log.size() && Math.abs(j - i) < 40; j += flew ? -1 : 1)
			{
				final Matcher s = scored.matcher(log.get(j));
				if (s.find())
				{
					if (flew || Integer.parseInt(s.group(1)) == tick)
					{
						max = Integer.parseInt(s.group(2));
					}
					break;
				}
			}
			final int hit = Integer.parseInt(h.group(2));
			final int npc = Integer.parseInt(h.group(3));
			if (max <= 0 || hit <= 0)
			{
				continue;
			}
			if (npc == 5889)
			{
				walked++;
				assertTrue(log.get(i) + " against max " + max, hit <= ModifierTracker.max(walking, max));
			}
			if (npc == 5890 && hit > max / 2)
			{
				fullOn5890++;
			}
		}
		assertTrue(walked >= 10);
		assertTrue(fullOn5890 >= 10);
	}

	// ---- the Leviathan --------------------------------------------------------------------------------------------------

	/** A shadow spell landing at 100 stuns it for 15 ticks: an attack LANDING inside is capped at 10 from the front. */
	@Test
	public void theStunCapsFromTheFront()
	{
		assertTrue(mods(LEVI, "RANGED", 100, false, NOWHERE).isEmpty());
		tracker.onPlayerSpell(INDEX, "The Leviathan", rules(LEVI), "SHADOW_RUSH", 100);
		assertTrue(lines.get(0), lines.get(0).startsWith("MOD The Leviathan: stunned on at tick 100 (your SHADOW_RUSH, for 15"));
		assertEquals(Collections.singletonList("stunned, from the front"), names(LEVI, "RANGED", 100, false, NOWHERE));
		assertEquals(Collections.singletonList("stunned, from the front"), names(LEVI, "RANGED", 114, false, NOWHERE));
		// The edges: thrown inside, landing after it, is not capped; landing at the first tick is.
		assertTrue(names(LEVI, "RANGED", 115, false, NOWHERE).isEmpty());
		assertTrue(names(LEVI, "RANGED", 99, false, NOWHERE).isEmpty());
		final List<BossRules.ModifierRule> front = mods(LEVI, "RANGED", 105, false, NOWHERE);
		assertEquals(10, ModifierTracker.max(front, 44));
		// Not every spell stuns.
		tracker.clear();
		tracker.onPlayerSpell(INDEX, "The Leviathan", rules(LEVI), "ICE_BARRAGE", 100);
		assertTrue(names(LEVI, "RANGED", 105, false, NOWHERE).isEmpty());
	}

	/** Behind it while stunned: ranged cannot miss and hits at least 65% of the max; magic doubles. */
	@Test
	public void theWound()
	{
		tracker.onPlayerSpell(INDEX, "The Leviathan", rules(LEVI), "SHADOW_BARRAGE", 100);
		final List<BossRules.ModifierRule> ranged = mods(LEVI, "RANGED", 105, true, NOWHERE);
		assertEquals(Collections.singletonList("into the wound"), names(LEVI, "RANGED", 105, true, NOWHERE));
		assertEquals(1.0, ModifierTracker.accuracy(ranged, 0.66), 1e-12);
		assertEquals(28, ModifierTracker.damage(ranged, 3, 44, true));
		assertEquals(40, ModifierTracker.damage(ranged, 40, 44, true));
		// Eric's 33 at [1657] against a 44 max fits.
		assertTrue(33 >= ModifierTracker.damage(ranged, 0, 44, true));
		final List<BossRules.ModifierRule> magic = mods(LEVI, "MAGIC", 105, true, NOWHERE);
		assertEquals(0.5, ModifierTracker.accuracy(magic, 0.5), 1e-12);
		assertEquals(88, ModifierTracker.max(magic, 44));
		// A hit into the wound breaks the stun.
		tracker.onModifiersLanded(INDEX, "The Leviathan", rules(LEVI), Arrays.asList("into the wound"), 106);
		assertTrue(names(LEVI, "RANGED", 107, true, NOWHERE).isEmpty());
		assertTrue(lines.get(lines.size() - 1), lines.get(lines.size() - 1).startsWith(
			"MOD The Leviathan: stunned off at tick 106 (a hit with into the wound"));
	}

	/** A special takes a third off, from its start animation to its end, not before three ticks. */
	@Test
	public void theSpecialTakesAThird()
	{
		tracker.onBossAnim(INDEX, "The Leviathan", rules(LEVI), 10285, 200);
		final List<BossRules.ModifierRule> special = mods(LEVI, "RANGED", 201, false, NOWHERE);
		assertEquals(Collections.singletonList("special"), names(LEVI, "RANGED", 201, false, NOWHERE));
		assertEquals(29, ModifierTracker.max(special, 44));
		// A volley animation in its first ticks does not end it; its end animation does.
		tracker.onBossAnim(INDEX, "The Leviathan", rules(LEVI), 10278, 201);
		assertFalse(names(LEVI, "RANGED", 202, false, NOWHERE).isEmpty());
		tracker.onBossAnim(INDEX, "The Leviathan", rules(LEVI), 10287, 240);
		assertTrue(names(LEVI, "RANGED", 240, false, NOWHERE).isEmpty());
		assertFalse(names(LEVI, "RANGED", 239, false, NOWHERE).isEmpty());
		// The smoke blast, with no end seen: it runs out at its longest.
		tracker.onBossAnim(INDEX, "The Leviathan", rules(LEVI), 10288, 300);
		assertFalse(names(LEVI, "RANGED", 359, false, NOWHERE).isEmpty());
		assertTrue(names(LEVI, "RANGED", 360, false, NOWHERE).isEmpty());
		tracker.tick(360);
		assertTrue(lines.get(lines.size() - 1), lines.get(lines.size() - 1).contains("special off at tick 360 (ran out"));
	}

	/** The pathfinder: judged from where the attack goes out, whatever the player does while it flies. */
	@Test
	public void thePathfinder()
	{
		final ModifierTracker.InsideCheck inside = (id, range) -> id == 12220 && range == 1;
		final List<BossRules.ModifierRule> ranged = mods(LEVI, "RANGED", 500, false, inside);
		assertEquals(Collections.singletonList("pathfinder"), names(LEVI, "RANGED", 500, false, inside));
		assertEquals(1.0, ModifierTracker.accuracy(ranged, 0.6), 1e-12);
		assertEquals(8, ModifierTracker.damage(ranged, 0, 44, true));
		assertEquals(88, ModifierTracker.max(mods(LEVI, "MAGIC", 500, false, inside), 44));
		// Thrown from outside, it takes nothing, even if the player then steps in before it lands: the check is the one
		// asked when it went out.
		assertTrue(names(LEVI, "RANGED", 500, false, NOWHERE).isEmpty());
	}

	/** Behind is the half-plane away from the facing (0 south, 512 west, 1024 north, 1536 east). */
	@Test
	public void backShotGeometry()
	{
		// A 5x5 at (0, 0), centre (2, 2).
		assertTrue(DamageModifiers.isBehind(0, 0, 5, 5, 0, 2, 7));
		assertFalse(DamageModifiers.isBehind(0, 0, 5, 5, 0, 2, -3));
		assertTrue(DamageModifiers.isBehind(0, 0, 5, 5, 1024, 2, -3));
		assertFalse(DamageModifiers.isBehind(0, 0, 5, 5, 1024, 2, 7));
		assertTrue(DamageModifiers.isBehind(0, 0, 5, 5, 512, 7, 2));
		assertFalse(DamageModifiers.isBehind(0, 0, 5, 5, 512, -3, 2));
		assertTrue(DamageModifiers.isBehind(0, 0, 5, 5, 1536, -3, 2));
		// Level with its centre is not behind.
		assertFalse(DamageModifiers.isBehind(0, 0, 5, 5, 0, 7, 2));
	}

	@Test
	public void withinRangeEdges()
	{
		// A 3x3 at (10, 10) and range 1: tiles 9..13.
		assertTrue(DamageModifiers.withinRange(10, 10, 3, 3, 0, 9, 9, 0, 1));
		assertTrue(DamageModifiers.withinRange(10, 10, 3, 3, 0, 13, 13, 0, 1));
		assertFalse(DamageModifiers.withinRange(10, 10, 3, 3, 0, 8, 10, 0, 1));
		assertFalse(DamageModifiers.withinRange(10, 10, 3, 3, 0, 14, 12, 0, 1));
		assertFalse(DamageModifiers.withinRange(10, 10, 3, 3, 0, 11, 11, 1, 1));
	}

	/** The moments walk every roll: against a brute-force distribution, halving and the minimum hit and the cap. */
	@Test
	public void roundingAndVarianceAgainstBruteForce()
	{
		final List<BossRules.ModifierRule> walking = mods(5889, "MELEE", 0, false, NOWHERE);
		check(walking, new int[]{39}, 0.66);
		check(walking, new int[]{30, 15}, 0.5);
		tracker.onPlayerSpell(INDEX, "The Leviathan", rules(LEVI), "SHADOW_RUSH", 0);
		check(mods(LEVI, "RANGED", 1, true, NOWHERE), new int[]{44}, 1.0);
		check(mods(LEVI, "RANGED", 1, false, NOWHERE), new int[]{44}, 0.66);
		// Halving each hit is not halving the mean: 0..39 averages 19.5, but each halved and rounded down averages 9.5.
		final DamageMoments halved = ModifierTracker.moments(walking, new int[]{39}, 1.0, Integer.MAX_VALUE);
		assertEquals(380.0 / 40, halved.mean, 1e-9);
	}

	private static void check(List<BossRules.ModifierRule> mods, int[] maxima, double accuracy)
	{
		final double a = ModifierTracker.accuracy(mods, accuracy);
		double mean = 0;
		double variance = 0;
		for (int max : maxima)
		{
			double m = 0;
			double sq = 0;
			// Every outcome with its probability: a miss, or each roll lo..max - or, where a modifier takes the better of two
			// (T-079, T-083), each pair of them.
			final double miss = ModifierTracker.damage(mods, 0, max, false);
			m += (1 - a) * miss;
			sq += (1 - a) * miss * miss;
			final int lo = ModifierTracker.rollFloor(mods, max);
			final int k = max - lo + 1;
			final boolean two = ModifierTracker.rolls(mods) == 2;
			for (int r = lo; r <= max; r++)
			{
				for (int s = lo; s <= (two ? max : lo); s++)
				{
					final double p = two ? a / ((double) k * k) : a / k;
					final double v = ModifierTracker.damage(mods, Math.max(r, s), max, true);
					m += p * v;
					sq += p * v * v;
				}
			}
			mean += m;
			variance += sq - m * m;
		}
		final DamageMoments got = ModifierTracker.moments(mods, maxima, a, Integer.MAX_VALUE);
		assertEquals(mean, got.mean, 1e-9);
		assertEquals(variance, got.variance(), 1e-6);
	}

	/** A boss without modifiers takes nothing: no rules, no windows, no effect. */
	@Test
	public void aBossWithoutModifiersIsUnchanged()
	{
		assertTrue(tracker.applying(INDEX, null, 12223, "MELEE", 10, true, NOWHERE).isEmpty());
		assertEquals(0.7, ModifierTracker.accuracy(Collections.emptyList(), 0.7), 1e-12);
		assertEquals(39, ModifierTracker.max(Collections.emptyList(), 39));
		assertEquals("none", ModifierTracker.names(Collections.emptyList()));
		assertEquals(null, RULES.forNpc(12223).damageModifiers);
	}

	/** The id-keyed modifier logs as the Sire steps into and out of its walking form. */
	@Test
	public void theWalkLogsOnAndOff()
	{
		tracker.onNpcId(24479, "Abyssal Sire", rules(5889), 5888, 240);
		tracker.onNpcId(24479, "Abyssal Sire", rules(5889), 5889, 242);
		tracker.onNpcId(24479, "Abyssal Sire", rules(5889), 5890, 245);
		assertEquals(Arrays.asList("MOD Abyssal Sire: walking on at tick 242 (npc 5889)",
			"MOD Abyssal Sire: walking off at tick 245 (npc 5890)"), lines);
	}
}
