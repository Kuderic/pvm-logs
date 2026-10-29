package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.StringReader;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * T-087: the Maggot King's slam ("Nearby": magic defence +400), the punish two
 * ticks into it (crush defence roll x0.15, damage x1.5 on the first hit), and
 * his burn stacks (-20 magic defence each), from the bundled rules through the
 * tracker to the figures; and Eric's kills of 2026-10-29 replayed.
 *
 * <p>The replays are derived from Eric's log (pvm-debug-20261029-135031), read
 * on his PC: per cast the logged accuracy, max and burn stacks (the last burn
 * splat within 4 ticks before the throw) and the damage of its hitsplats; per
 * punish the accuracy, max and damage. No log lines are copied.
 */
public class MaggotKingTest
{
	private static final int KING = 15742;
	private static final int INDEX = 35012;
	private static final int SLAM = 13925;

	/** The DPS calc's three Maggot King entries (monsters.json, Eric's cache of 2026-10-26), cut to what is read. */
	private static final String MONSTERS = "["
		+ king("Far", 200, 150, 80) + "," + king("Nearby", 200, 400, 5) + "," + king("Roaring", 100, 150, 80) + "]";

	private static BossRules rules;
	private static MonsterStatsProvider.Parsed data;

	private static String king(String version, int def, int magicDef, int fire)
	{
		return "{\"id\": 15742, \"name\": \"Maggot King\", \"version\": \"" + version + "\", \"level\": 741, \"speed\": 0,"
			+ " \"size\": 5, \"skills\": {\"atk\": 200, \"def\": " + def + ", \"hp\": 1500, \"magic\": 200, \"ranged\": 300,"
			+ " \"str\": 250}, \"offensive\": {\"atk\": 400, \"magic\": 200, \"magic_str\": 57, \"ranged\": 200, \"ranged_str\": 10,"
			+ " \"str\": 50}, \"defensive\": {\"flat_armour\": 0, \"crush\": 45, \"magic\": " + magicDef + ", \"heavy\": 43,"
			+ " \"standard\": 158, \"light\": 232, \"slash\": 100, \"stab\": 172}, \"attributes\": [],"
			+ " \"weakness\": {\"element\": \"fire\", \"severity\": " + fire + "}}";
	}

	@BeforeClass
	public static void load()
	{
		final Gson gson = new Gson();
		rules = BossRules.load(gson, null);
		data = MonsterStatsProvider.parse(gson, new StringReader(MONSTERS), rules);
	}

	private static BossRules.DamageModifierRules modifiers()
	{
		final BossRules.Boss boss = rules.forNpc(KING);
		assertNotNull(boss);
		assertNotNull(boss.damageModifiers);
		return boss.damageModifiers;
	}

	private static List<BossRules.ModifierRule> applying(ModifierTracker tracker, String style, int thrown, int lands)
	{
		return tracker.applying(INDEX, modifiers(), KING, style, thrown, lands, false, (id, range) -> false);
	}

	private static boolean has(List<BossRules.ModifierRule> mods, String name)
	{
		for (BossRules.ModifierRule m : mods)
		{
			if (name.equals(m.name))
			{
				return true;
			}
		}
		return false;
	}

	// ---- each version chosen by its state ----

	@Test
	public void outsideASlamHeIsTheFarVersionWithNoWarning()
	{
		final MonsterStatsProvider.Choice choice = MonsterStatsProvider.choose(data.versionsById.get(KING),
			rules.forNpc(KING), 1500, -1);
		assertEquals("Far", choice.stats.getVersion());
		assertNull(choice.stats.getWarning());
		// Before the bar is read, the same.
		assertEquals("Far", MonsterStatsProvider.choose(data.versionsById.get(KING), rules.forNpc(KING), -1, -1)
			.stats.getVersion());
	}

	@Test
	public void aSlamMakesHimNearbyForItsFourTicks()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		assertNull(ModifierTracker.version(applying(tracker, "MAGIC", 100, 103)));
		tracker.onBossAnim(INDEX, "Maggot King", modifiers(), SLAM, 100);
		for (int land = 100; land < 104; land++)
		{
			assertEquals("land " + land, "Nearby", ModifierTracker.version(applying(tracker, "MAGIC", land - 2, land)));
		}
		assertNull(ModifierTracker.version(applying(tracker, "MAGIC", 102, 104)));
		// The Nearby entry: +400 magic defence, a 5% fire weakness, the same Defence.
		final MonsterStatsProvider.MonsterStats nearby = versionOf("Nearby");
		assertEquals(400, nearby.getDefMagic());
		assertEquals(5, nearby.getWeaknessSeverity());
		assertEquals(200, nearby.getDefenceLevel());
		assertEquals(150, versionOf("Far").getDefMagic());
	}

	private static MonsterStatsProvider.MonsterStats versionOf(String version)
	{
		for (MonsterStatsProvider.MonsterStats s : data.versionsById.get(KING))
		{
			if (version.equals(s.getVersion()))
			{
				return s;
			}
		}
		throw new AssertionError(version);
	}

	// ---- the punish ----

	@Test
	public void thePunishIsTheMeleeAttackLandingThreeTicksIntoTheSlam()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		tracker.onBossAnim(INDEX, "Maggot King", modifiers(), SLAM, 100);
		// Thrown 2 ticks in, landing the next: the punish.
		final List<BossRules.ModifierRule> punish = applying(tracker, "MELEE", 102, 103);
		assertTrue(has(punish, "punish"));
		assertTrue(has(punish, "punish, crush"));
		// A tick either side: not.
		assertFalse(has(applying(tracker, "MELEE", 101, 102), "punish"));
		assertFalse(has(applying(tracker, "MELEE", 103, 104), "punish"));
		// Magic never takes it.
		assertFalse(has(applying(tracker, "MAGIC", 101, 103), "punish"));
		// Crush only: 85% off the defence roll.
		final int defRoll = (200 + 9) * (45 + 64);
		assertEquals(defRoll * 15 / 100, ModifierTracker.defenceRoll(punish, "CRUSH", defRoll));
		assertEquals((200 + 9) * (100 + 64), ModifierTracker.defenceRoll(punish, "SLASH", (200 + 9) * (100 + 64)));
		// +50% on the first hit only.
		assertEquals(60, ModifierTracker.damage(punish, 40, 53, true, 0));
		assertEquals(40, ModifierTracker.damage(punish, 40, 53, true, 1));
		assertEquals(79, ModifierTracker.max(punish, 53, 0));
		assertEquals(26, ModifierTracker.max(punish, 26, 1));
	}

	/** The punish's moments on a two-hit weapon, against every pair of rolls: the bonus on the first only. */
	@Test
	public void thePunishOnTheFirstHitOnlyAgainstBruteForce()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		tracker.onBossAnim(INDEX, "Maggot King", modifiers(), SLAM, 100);
		final List<BossRules.ModifierRule> punish = applying(tracker, "MELEE", 102, 103);
		final int[] maxima = {40, 20};
		final double accuracy = 0.8;
		double mean = 0;
		double second = 0;
		for (int hit = 0; hit < 2; hit++)
		{
			double m = (1 - accuracy) * 0;
			double sq = 0;
			for (int r = 0; r <= maxima[hit]; r++)
			{
				final double v = hit == 0 ? r * 3 / 2 : r;
				m += accuracy / (maxima[hit] + 1) * v;
				sq += accuracy / (maxima[hit] + 1) * v * v;
			}
			mean += m;
			second += sq - m * m;
		}
		final DamageMoments got = ModifierTracker.moments(punish, maxima, accuracy, Integer.MAX_VALUE);
		assertEquals(mean, got.mean, 1e-9);
		assertEquals(second, got.variance(), 1e-9);
	}

	@Test
	public void thePunishIsNeverInTheLiveFigures()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		tracker.onBossAnim(INDEX, "Maggot King", modifiers(), SLAM, 100);
		final List<BossRules.ModifierRule> punish = applying(tracker, "MELEE", 102, 103);
		final List<BossRules.ModifierRule> live = ModifierTracker.shownLive(punish);
		assertFalse(has(live, "punish"));
		assertFalse(has(live, "punish, crush"));
		// The slam's version is a phase, which the live figures may follow.
		assertTrue(has(live, "slam"));
		assertEquals(Collections.emptyList(), ModifierTracker.shownLive(Collections.emptyList()));
	}

	// ---- burn stacks ----

	@Test
	public void burnSplatsCountTheStacksUntilTheyLapse()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		tracker.onBossDot(INDEX, "Maggot King", modifiers(), "burn", 3, 200);
		assertEquals(3, tracker.stacks(INDEX, "burn", modifiers(), 200));
		assertEquals(3, tracker.stacks(INDEX, "burn", modifiers(), 204));
		assertEquals(0, tracker.stacks(INDEX, "burn", modifiers(), 205));
		// Five at most, whatever the splat.
		tracker.onBossDot(INDEX, "Maggot King", modifiers(), "burn", 7, 210);
		assertEquals(5, tracker.stacks(INDEX, "burn", modifiers(), 210));
		// Poison is not burn.
		tracker.onBossDot(INDEX, "Maggot King", modifiers(), "poison", 2, 211);
		assertEquals(5, tracker.stacks(INDEX, "burn", modifiers(), 211));
		// Magic only, -20 each.
		final List<BossRules.ModifierRule> magic = applying(tracker, "MAGIC", 211, 213);
		assertTrue(has(magic, "burn"));
		assertFalse(has(applying(tracker, "MELEE", 211, 212), "burn"));
		for (BossRules.ModifierRule m : magic)
		{
			if ("burn".equals(m.name))
			{
				assertEquals(-20, (int) m.magicDefencePerStack);
			}
		}
	}

	/** By hand: Magic 200 and +150 against the roll Eric's 0.57 implies, and the same with three stacks. */
	@Test
	public void burnStacksAgainstHandWorkedAccuracy()
	{
		final int none = (200 + 9) * (150 + 64);
		final int three = (200 + 9) * (150 - 60 + 64);
		assertEquals(44726, none);
		assertEquals(32186, three);
		final int attack = 52497;
		// 1 - (D + 2) / (2 (A + 1)).
		assertEquals(1 - 44728.0 / 104996, CombatCalc.hitChanceFrom(attack, none), 1e-12);
		assertEquals(1 - 32188.0 / 104996, CombatCalc.hitChanceFrom(attack, three), 1e-12);
		assertEquals(0.574, CombatCalc.hitChanceFrom(attack, none), 0.001);
		assertEquals(0.693, CombatCalc.hitChanceFrom(attack, three), 0.001);
	}

	// ---- the elemental amulet ----

	@Test
	public void theElementalAmuletAddsTwoToEveryElement()
	{
		for (String element : new String[]{"fire", "water", "earth", "air"})
		{
			assertTrue(element, GearBonusCalc.elementalAmuletFits(ItemID.ELEMENTAL_AMULET, element));
		}
		assertTrue(GearBonusCalc.elementalAmuletFits(ItemID.AMULET_OF_FIRE, "fire"));
		assertFalse(GearBonusCalc.elementalAmuletFits(ItemID.AMULET_OF_FIRE, "water"));
		assertFalse(GearBonusCalc.elementalAmuletFits(ItemID.ELEMENTAL_AMULET, null));
		// Eric's melee amulet (torture, 19553) is no elemental amulet.
		assertFalse(GearBonusCalc.elementalAmuletFits(19553, "fire"));
		// Eric's Fire Wave: base 20, +25% magic damage, 80% weakness, a charged tome. Without the amulet 45, with it 48,
		// the highest first hit in his log (48 + 19 = 67).
		assertEquals(45, fireWave(0).maxHit());
		assertEquals(48, fireWave(2).maxHit());
		assertEquals(19, fireWave(2).secondMaxHit());
	}

	private static MagicDamage fireWave(int amulet)
	{
		final MagicDamage m = new MagicDamage();
		m.base = 20;
		m.flat = amulet;
		m.percent = 25;
		m.weaknessPercent = 80;
		m.tomePercent = 10;
		m.twinflame = true;
		return m;
	}

	// ---- Eric's kills ----

	/** {logged chance x 100, logged max, burn stacks at the throw, damage dealt}, kill by kill. */
	private static final int[][][] CASTS = {
		{{57, 63, 0, 0}, {57, 63, 0, 39}, {57, 63, 2, 21}, {57, 63, 4, 47}, {57, 63, 5, 46}, {57, 63, 3, 23},
			{57, 63, 3, 39}, {57, 63, 4, 39}, {57, 63, 5, 23}, {57, 63, 3, 56}, {57, 63, 3, 54}, {55, 57, 0, 18},
			{55, 57, 5, 4}, {55, 57, 3, 0}, {55, 57, 1, 22}, {55, 57, 3, 53}, {57, 63, 4, 44}, {57, 63, 5, 28},
			{57, 63, 4, 21}, {57, 63, 3, 58}, {57, 63, 5, 44}, {38, 57, 0, 0}, {57, 63, 0, 4}, {57, 63, 2, 0},
			{57, 63, 2, 26}},
		{{57, 63, 0, 9}, {57, 63, 2, 65}, {57, 63, 4, 50}, {67, 63, 2, 50}, {67, 63, 4, 0}, {67, 63, 4, 0},
			{67, 63, 2, 21}, {67, 63, 3, 0}, {67, 63, 2, 0}, {67, 63, 2, 0}, {67, 63, 2, 0}, {67, 63, 0, 0},
			{67, 63, 0, 44}, {67, 63, 2, 1}, {67, 63, 4, 0}, {67, 63, 4, 50}, {67, 63, 3, 18}, {67, 63, 5, 67},
			{65, 57, 5, 29}, {67, 63, 3, 56}, {67, 63, 5, 40}, {67, 63, 5, 37}, {67, 63, 4, 10}, {61, 61, 0, 42},
			{66, 63, 2, 0}, {66, 63, 2, 43}, {66, 63, 4, 32}, {66, 63, 3, 63}, {66, 63, 5, 58}, {66, 63, 3, 0}},
		{{57, 63, 0, 64}, {57, 63, 2, 60}, {57, 63, 4, 19}, {65, 63, 3, 1}, {65, 63, 3, 50}, {65, 63, 4, 12},
			{65, 63, 5, 7}, {65, 63, 3, 63}, {65, 63, 5, 19}, {65, 63, 4, 16}, {65, 63, 5, 47}, {65, 63, 3, 0},
			{65, 63, 0, 7}, {65, 63, 2, 44}, {65, 63, 4, 11}, {65, 63, 5, 23}, {64, 63, 0, 0}, {64, 63, 0, 51},
			{64, 63, 2, 47}, {64, 63, 4, 29}, {64, 63, 5, 0}, {64, 63, 3, 0}},
		{{57, 63, 0, 9}, {57, 63, 0, 0}, {57, 63, 0, 51}, {57, 63, 2, 1}, {57, 63, 4, 65}, {57, 63, 5, 35},
			{59, 63, 1, 0}, {59, 63, 0, 14}, {59, 63, 2, 7}, {59, 63, 4, 64}, {59, 63, 5, 43}, {59, 63, 3, 51},
			{59, 63, 2, 16}, {59, 63, 4, 36}, {59, 63, 3, 14}, {59, 63, 5, 1}, {59, 63, 0, 1}, {59, 63, 4, 49},
			{59, 63, 3, 0}, {59, 63, 3, 51}, {59, 63, 4, 0}, {59, 63, 0, 44}, {59, 63, 2, 0}, {59, 63, 2, 0},
			{59, 63, 2, 0}, {59, 63, 0, 0}},
	};

	/**
	 * {logged chance x 100, logged max, damage}: the Zombie axe attacks thrown 2 ticks after a 13925 the log shows,
	 * all four kills. The log sees the King's animations only inside the hits on the player, so most slams are missing.
	 */
	private static final int[][] PUNISHES = {
		{61, 51, 51}, {63, 54, 25}, {62, 54, 33}, {62, 54, 28}, {62, 53, 60}, {60, 53, 57}, {62, 53, 55}, {62, 53, 10},
		{62, 53, 30}, {62, 53, 36}, {62, 53, 40}, {62, 52, 31}, {61, 53, 58},
	};

	// The attack roll a logged chance implies against a defence roll.
	private static int attackRollFor(double chance, int defRoll)
	{
		return (int) Math.round((defRoll + 2.0) / (2.0 * (1.0 - chance)) - 1.0);
	}

	/** The twinflame staff's two splats, the second 40% of the first, as moments of one landed cast. */
	private static double[] twinflame(int firstMax)
	{
		double m = 0;
		double sq = 0;
		for (int r = 0; r <= firstMax; r++)
		{
			final double v = r + r * 40 / 100;
			m += v / (firstMax + 1);
			sq += v * v / (firstMax + 1);
		}
		return new double[]{m, sq};
	}

	/** {actual, expected, z} over these casts: as logged ({@code after} false), or with burn stacks and the amulet's +2. */
	private static double[] magic(int[][][] kills, boolean after)
	{
		double actual = 0;
		double expected = 0;
		double variance = 0;
		for (int[][] kill : kills)
		{
			for (int[] cast : kill)
			{
				final double logged = cast[0] / 100.0;
				final int none = (200 + 9) * (150 + 64);
				final double chance = after
					? CombatCalc.hitChanceFrom(attackRollFor(logged, none), (200 + 9) * (150 - 20 * cast[2] + 64))
					: logged;
				// The 63s are Fire Wave at 45 + 18; with the amulet's +2, 48 + 19. The rest as logged.
				final int first = after && cast[1] == 63 ? 48 : (int) Math.round(cast[1] / 1.4);
				final double[] landed = twinflame(first);
				final double mean = chance * landed[0];
				actual += cast[3];
				expected += mean;
				variance += chance * landed[1] - mean * mean;
			}
		}
		return new double[]{actual, expected, (actual - expected) / Math.sqrt(variance)};
	}

	@Test
	public void ericsCastsAreCalibratedWithBurnStacksAndTheAmulet()
	{
		final int[][][] all = CASTS;
		final int[][][] first = {CASTS[0]};
		final double[] before = magic(all, false);
		final double[] after = magic(all, true);
		assertEquals(2616, (int) before[0]);
		// Before: +35% over 103 casts; after: within noise.
		assertTrue("before z " + before[2], before[2] > 3);
		assertTrue("after z " + after[2], Math.abs(after[2]) < 1.5);
		// Kill 1 alone, 25 casts: +66% before, within two deviations after.
		assertTrue(magic(first, false)[2] > 2.5);
		assertTrue("kill 1 after z " + magic(first, true)[2], Math.abs(magic(first, true)[2]) < 2);
	}

	@Test
	public void ericsPunishesAreCalibrated()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		tracker.onBossAnim(INDEX, "Maggot King", modifiers(), SLAM, 100);
		final List<BossRules.ModifierRule> punish = applying(tracker, "MELEE", 102, 103);
		final int crush = (200 + 9) * (45 + 64);
		double actual = 0;
		double plain = 0;
		double expected = 0;
		double variance = 0;
		int landed = 0;
		double landedExpected = 0;
		for (int[] attack : PUNISHES)
		{
			final double logged = attack[0] / 100.0;
			final double chance = CombatCalc.hitChanceFrom(attackRollFor(logged, crush),
				ModifierTracker.defenceRoll(punish, "CRUSH", crush));
			final DamageMoments m = ModifierTracker.moments(punish, new int[]{attack[1]}, chance, Integer.MAX_VALUE);
			actual += attack[2];
			plain += ModifierTracker.moments(Collections.emptyList(), new int[]{attack[1]}, logged, Integer.MAX_VALUE).mean;
			expected += m.mean;
			variance += m.variance();
			landed += attack[2] > 0 ? 1 : 0;
			landedExpected += chance;
		}
		assertEquals(514, (int) actual);
		// As an ordinary swing: 214 expected. As the punish: 484, z +0.3; 13 landed against 12.3.
		assertTrue(plain < 230);
		assertTrue("z " + (actual - expected) / Math.sqrt(variance), Math.abs(actual - expected) / Math.sqrt(variance) < 1);
		assertEquals(13, landed);
		assertEquals(12.3, landedExpected, 0.2);
	}
}
