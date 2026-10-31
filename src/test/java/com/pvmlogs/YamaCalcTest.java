package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * T-135: Yama's magic defence follows his target's style (+60 while it uses magic, -30 otherwise, outside phase 3), his
 * phase 3 from a third of his hitpoints takes the Phase 3 version's stats; his Void Flares spawn at 71 in phase 3 and
 * always take a magic max hit; the Judge always takes a max hit. Figures from Eric's log of 2026-10-31
 * ({@code pvm-debug-20261031-140202}, build 2d9270f).
 */
public class YamaCalcTest
{
	private static final int YAMA = NpcID.YAMA;
	private static final int FLARE = NpcID.YAMA_VOIDFLARE;
	private static final int JUDGE = 14180;
	private static final int INDEX = 60992;

	private static BossRules rules;

	@BeforeClass
	public static void load()
	{
		rules = BossRules.load(new Gson(), null);
	}

	private static BossRules.DamageModifierRules modifiers(int npcId)
	{
		final BossRules.Boss boss = rules.forNpc(npcId);
		assertNotNull(boss);
		assertNotNull(boss.damageModifiers);
		return boss.damageModifiers;
	}

	private static List<BossRules.ModifierRule> yama(String style, double hpShare, Boolean tankMagic)
	{
		return new ModifierTracker(null).applying(INDEX, modifiers(YAMA), YAMA, style, 7588, 7591, false,
			(id, range) -> false, null, new ModifierTracker.Situation(hpShare, tankMagic));
	}

	private static boolean guaranteedMax(List<BossRules.ModifierRule> mods)
	{
		for (BossRules.ModifierRule m : mods)
		{
			if (Boolean.TRUE.equals(m.guaranteedMax))
			{
				return true;
			}
		}
		return false;
	}

	private static List<String> names(List<BossRules.ModifierRule> mods)
	{
		final List<String> out = new ArrayList<>();
		for (BossRules.ModifierRule m : mods)
		{
			out.add(m.name);
		}
		return out;
	}

	@Test
	public void hisMagicDefenceFollowsHisTargetsStyle()
	{
		// Phases 1 and 2: +60 while his target uses magic, -30 otherwise, on the Normal version's +0.
		assertEquals(60, ModifierTracker.magicDefenceFlat(yama("MAGIC", 0.97, true)));
		assertEquals(-30, ModifierTracker.magicDefenceFlat(yama("MAGIC", 0.97, false)));
		// Whose style it is, not the attack's: a cast while a partner tanks with melee meets -30.
		assertEquals(-30, ModifierTracker.magicDefenceFlat(yama("MAGIC", 0.5, false)));
		// No player targeted, or their style unknown: neither, the Normal's +0.
		assertEquals(0, ModifierTracker.magicDefenceFlat(yama("MAGIC", 0.97, null)));
		// Hitpoints unknown: neither (and no phase 3).
		assertTrue(yama("MAGIC", -1, true).isEmpty());
		assertNull(ModifierTracker.version(yama("MAGIC", -1, true)));
		// Live (D-2): his target's style is a player's own, not a warning.
		assertEquals(yama("MAGIC", 0.97, true), ModifierTracker.shownLive(yama("MAGIC", 0.97, true)));
	}

	@Test
	public void phaseThreeFromAThird()
	{
		// 833 of 2500 is the third; his last flares spawned with him at 229 and 305.
		for (int hp : new int[] {833, 305, 229})
		{
			final List<BossRules.ModifierRule> mods = yama("MAGIC", hp / 2500.0, true);
			assertEquals(hp + "", "Phase 3", ModifierTracker.version(mods));
			// The tank's style no longer moves it: the Phase 3 version's +81 is its own.
			assertEquals(hp + "", 0, ModifierTracker.magicDefenceFlat(mods));
		}
		assertNull(ModifierTracker.version(yama("MAGIC", 834 / 2500.0, true)));
		assertEquals(names(yama("RANGED", 0.2, false)), java.util.Collections.singletonList("phase 3"));
	}

	@Test
	public void theMagicChanceItMakes()
	{
		// A defence roll on his Magic 250 (magic defence rolls off the Magic level): (250 + 9) x (bonus + 64).
		final int plus60 = (250 + 9) * (60 + 64);
		final int minus30 = (250 + 9) * (-30 + 64);
		final int attack = 40_000;
		final double atPlus60 = CombatCalc.hitChanceFrom(attack, plus60);
		final double atMinus30 = CombatCalc.hitChanceFrom(attack, minus30);
		assertTrue(atPlus60 + " " + atMinus30, atMinus30 > atPlus60);
		// The Normal's 0 lies between.
		final double atZero = CombatCalc.hitChanceFrom(attack, (250 + 9) * 64);
		assertTrue(atZero > atPlus60 && atZero < atMinus30);
	}

	@Test
	public void theModLine()
	{
		assertEquals("MOD Yama: targeting you (magic), 97% of its hitpoints left: tank uses magic (magic defence +60)",
			DamageModifiers.situationLine("Yama", "you", new ModifierTracker.Situation(0.97, true),
				yama("MAGIC", 0.97, true)));
		assertEquals("MOD Yama: targeting another player (not magic), 50% of its hitpoints left: tank not using magic "
			+ "(magic defence -30)", DamageModifiers.situationLine("Yama", "another player",
			new ModifierTracker.Situation(0.5, false), yama("MAGIC", 0.5, false)));
		assertEquals("MOD Yama: targeting you (magic), 12% of its hitpoints left: phase 3 (the Phase 3 version's stats)",
			DamageModifiers.situationLine("Yama", "you", new ModifierTracker.Situation(0.12, true),
				yama("MAGIC", 0.12, true)));
	}

	@Test
	public void aWeaponsHighestBonusSaysMagic()
	{
		// A staff's magic bonus over its melee ones; a melee weapon's isn't.
		assertTrue(DamageModifiers.highestIsMagic(10, -1, 40, 0, 25 + 20));
		assertFalse(DamageModifiers.highestIsMagic(0, 75, 0, 0, 0));
		assertFalse(DamageModifiers.highestIsMagic(0, 0, 0, 0, 0));
		assertFalse(DamageModifiers.highestIsMagic(0, 0, 0, 90, 30));
	}

	@Test
	public void phaseThreeFlaresSpawnAtSeventyOne()
	{
		final BossRules.SpawnHp rule = rules.forNpc(FLARE).spawnHp;
		assertNotNull(rule);
		assertEquals(71, rule.hp);
		// [7943] and [8391]: the boss bar reads Yama at 229 and 305 as a flare's fight opens; each died to one 71.
		assertTrue(PvmPerformancePlugin.spawnsDamaged(rule, YAMA, 229, 2500));
		assertTrue(PvmPerformancePlugin.spawnsDamaged(rule, YAMA, 305, 2500));
		// [8095]: at 2032 a full flare, which took 77 then 62.
		assertFalse(PvmPerformancePlugin.spawnsDamaged(rule, YAMA, 2032, 2500));
		// The bar on something else, or not read.
		assertFalse(PvmPerformancePlugin.spawnsDamaged(rule, 12345, 100, 2500));
		assertFalse(PvmPerformancePlugin.spawnsDamaged(rule, YAMA, 0, 0));
	}

	@Test
	public void flaresAlwaysTakeAMagicMax()
	{
		final ModifierTracker t = new ModifierTracker(null);
		final List<BossRules.ModifierRule> magic = ModifierTracker.forPlayer(t.applying(1, modifiers(FLARE), FLARE, "MAGIC",
			100, 103, false, (id, r) -> false), -1, null, "MAGIC", "MAGIC");
		assertEquals(1, magic.size());
		assertTrue(guaranteedMax(magic));
		// A bow, standard ranged: so too. Melee: not (the wiki names magic and standard ranged).
		final List<BossRules.ModifierRule> bow = ModifierTracker.forPlayer(t.applying(1, modifiers(FLARE), FLARE, "RANGED",
			100, 102, false, (id, r) -> false), -1, "BOW", "RANGED", "RANGED");
		assertTrue(guaranteedMax(bow));
		final List<BossRules.ModifierRule> crossbow = ModifierTracker.forPlayer(t.applying(1, modifiers(FLARE), FLARE,
			"RANGED", 100, 102, false, (id, r) -> false), -1, "CROSSBOW", "RANGED", "RANGED");
		assertFalse(guaranteedMax(crossbow));
		assertTrue(t.applying(1, modifiers(FLARE), FLARE, "MELEE", 100, 101, false, (id, r) -> false).isEmpty());
	}

	/**
	 * Kill 1 and kill 2 replayed from the log's figures, not its lines: each Yama stretch's LUCK (A, E, sd), its purging
	 * staff casts scored at max 39 (0.81) or 38 (0.78) without the Mark, rescored with it (max 61 or 59; demonbane
	 * accuracy x1.96 for x1.48) against Yama's magic defence: -30 in phases 1 and 2 (his target was Eric's partner all
	 * kill 1, his attacks reaching Eric only as area hits; Eric's 42 of 42 casts fit -30, not +60) and the Phase 3
	 * version's +81 in phase 3. The Eye of Ayak's attacks keep their figures.
	 */
	@Test
	public void ericsKillsReplayed()
	{
		final int defNormal = (250 + 9) * 64;
		final double attack = (defNormal + 2) / (2 * (1 - 0.812)) - 1;
		final double marked = attack * 1.96 / 1.48;
		final double phases12 = CombatCalc.hitChanceFrom((int) marked, (250 + 9) * (-30 + 64));
		final double phase3 = CombatCalc.hitChanceFrom((int) marked, (250 + 9) * (81 + 64));
		assertEquals(0.925, phases12, 0.001);
		assertEquals(0.678, phase3, 0.001);
		// {phase, A, E, sd, casts at 39, casts at 38}
		final double[][] kill1 = {{1, 532, 234.3, 49.7, 12, 1}, {2, 413, 249.9, 51.7, 16, 0}, {3, 249, 172.8, 42.8, 11, 0},
			{3, 150, 62.7, 25.8, 4, 0}};
		final double[][] kill2 = {{1, 172, 123.7, 36.1, 7, 0}, {1, 247, 109.6, 34.0, 6, 1}, {2, 358, 147.1, 40.3, 1, 9},
			{2, 94, 62.5, 25.9, 4, 0}, {3, 158, 126.0, 36.5, 8, 0}, {3, 138, 62.7, 25.8, 4, 0}};
		final double[] one = replay(kill1, phases12, phase3);
		final double[] two = replay(kill2, phases12, phase3);
		// Before: z +7.14 and +6.52.
		assertEquals(1151.2, one[0], 0.5);
		assertEquals(1.47, one[1], 0.02);
		assertEquals(1037.0, two[0], 0.5);
		assertEquals(1.06, two[1], 0.02);
	}

	/** {E, z} over a kill's stretches, its casts rescored as the test above says. */
	private static double[] replay(double[][] stretches, double phases12, double phase3)
	{
		double a = 0;
		double e = 0;
		double v = 0;
		for (double[] s : stretches)
		{
			final double p = s[0] == 3 ? phase3 : phases12;
			final double[] old39 = cast(39, 0.812);
			final double[] old38 = cast(38, 0.78);
			final double[] new61 = cast(61, p);
			final double[] new59 = cast(59, p);
			a += s[1];
			e += s[2] - s[4] * old39[0] - s[5] * old38[0] + s[4] * new61[0] + s[5] * new59[0];
			v += s[3] * s[3] - s[4] * old39[1] - s[5] * old38[1] + s[4] * new61[1] + s[5] * new59[1];
		}
		return new double[] {e, (a - e) / Math.sqrt(v)};
	}

	/** One cast's mean and variance: a landed roll uniform over 0 to max, at chance p. */
	private static double[] cast(int max, double p)
	{
		final double mean = p * max / 2.0;
		return new double[] {mean, p * max * (2.0 * max + 1) / 6.0 - mean * mean};
	}

	@Test
	public void soulRendIsFiveTicks()
	{
		// The wiki: Soul Rend "has an attack speed of five ticks, compared to the staff's standard attack speed of three
		// ticks"; double accuracy and +30% max (the 33 at [7613] and the 42 at [7618]: 33 x 1.3 = 42.9).
		assertEquals(5, SpecialAttack.EYE_OF_AYAK.speedTicks(3));
		assertEquals(4, SpecialAttack.DRAGON_WARHAMMER.speedTicks(4));
		assertEquals(42, (int) Math.floor(33 * 1.30));
	}

	@Test
	public void theJudgeAlwaysTakesAMax()
	{
		final ModifierTracker t = new ModifierTracker(null);
		for (String style : new String[] {"MAGIC", "MELEE", "RANGED"})
		{
			final List<BossRules.ModifierRule> mods = ModifierTracker.forPlayer(t.applying(2, modifiers(JUDGE), JUDGE,
				style, 100, 101, false, (id, r) -> false), -1, null, "SLASH", "SLASH");
			assertTrue(style, guaranteedMax(mods));
		}
	}
}
