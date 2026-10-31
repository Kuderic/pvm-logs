package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * T-120: the Royal Titans give ranged accuracy x6 while the player is out of
 * their melee distance, and during their Elemental Blast wherever the player
 * stands (OSRS Wiki, Branda the Fire Queen and Eldric the Ice King).
 *
 * <p>The replay is Eric's kill of 2026-10-30 ({@code pvm-debug-20261030-103025},
 * build 4f6ca3c, [7168]-[7437]), from its figures, not its lines: 14 Bow of
 * Faerdhinen (c) shots scored at {@code max 44, chances 0.34, avg 7.45}, which
 * landed 12 of 14 for 300.
 */
public class RoyalTitansTest
{
	private static final int BRANDA = NpcID.RT_FIRE_QUEEN;
	private static final int ELDRIC = NpcID.RT_ICE_KING;
	private static final int BRANDA_INDEX = 48293;
	private static final int ELDRIC_INDEX = 48311;
	private static final String NAME = "The Royal Titans";
	/** Their ranged defence roll: Defence 100, ranged defence +700. */
	private static final int DEF_ROLL = (100 + 9) * (700 + 64);
	/** The bow's attack roll, from the log's acc 0.339 against it (3 places: within 83 of the roll). */
	private static final int BOW_ROLL = (int) Math.round(0.339 * 2 * (DEF_ROLL + 1));
	private static final int BOW_MAX = 44;

	private static BossRules rules;

	@BeforeClass
	public static void load()
	{
		rules = BossRules.load(new Gson(), null);
	}

	private static BossRules.DamageModifierRules modifiers()
	{
		final BossRules.Boss boss = rules.forNpc(BRANDA);
		assertNotNull(boss);
		assertEquals(boss, rules.forNpc(ELDRIC));
		assertNotNull(boss.damageModifiers);
		return boss.damageModifiers;
	}

	private static List<BossRules.ModifierRule> applying(ModifierTracker tracker, int index, int npcId, String style,
		int thrown, Boolean inMelee)
	{
		return tracker.applying(index, modifiers(), npcId, style, thrown, thrown + 2, false, (id, range) -> false,
			inMelee);
	}

	private static double rangedChance(List<BossRules.ModifierRule> mods)
	{
		return CombatCalc.hitChanceFrom(ModifierTracker.attackRoll(mods, "RANGED", BOW_ROLL),
			ModifierTracker.defenceRoll(mods, "RANGED", DEF_ROLL));
	}

	@Test
	public void theBowsRollIsTheLogs()
	{
		assertEquals(0.339, CombatCalc.hitChanceFrom(BOW_ROLL, DEF_ROLL), 0.0005);
		assertEquals(7.45, CombatCalc.hitChanceFrom(BOW_ROLL, DEF_ROLL) * BOW_MAX / 2.0, 0.01);
	}

	@Test
	public void outOfMeleeDistanceIsSixTimes()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		final List<BossRules.ModifierRule> away = applying(tracker, BRANDA_INDEX, BRANDA, "RANGED", 7168, false);
		assertEquals(1, away.size());
		assertEquals("out of melee distance", away.get(0).name);
		assertEquals(6L * BOW_ROLL, ModifierTracker.attackRoll(away, "RANGED", BOW_ROLL));
		assertEquals(0.877, rangedChance(away), 0.001);
		// Next to her: nothing, the bow at its 0.339.
		final List<BossRules.ModifierRule> next = applying(tracker, BRANDA_INDEX, BRANDA, "RANGED", 7168, true);
		assertTrue(next.isEmpty());
		assertEquals(0.339, rangedChance(next), 0.0005);
		// Where the player stood unknown: no boost.
		assertTrue(applying(tracker, BRANDA_INDEX, BRANDA, "RANGED", 7168, null).isEmpty());
		// Live (D-2): the player's own position, so the overlay follows it too.
		assertEquals(away, ModifierTracker.shownLive(away));
	}

	@Test
	public void theBlastIsSixTimesNextToHimToo()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		// ICE_GIANT_EDRIC_KING_ATTACK_AOE01 at [7353]: the charge.
		tracker.onBossAnim(ELDRIC_INDEX, NAME, modifiers(), 11979, 7353);
		// [7361] and [7365]: Eric's two bow shots at Eldric, inside the blast.
		for (int thrown : new int[] {7361, 7365})
		{
			final List<BossRules.ModifierRule> next = applying(tracker, ELDRIC_INDEX, ELDRIC, "RANGED", thrown, true);
			assertEquals(1, next.size());
			assertEquals("elemental blast", next.get(0).name);
			// Live (D-2): it opens on the animation the player sees.
			assertEquals(next, ModifierTracker.shownLive(next));
			assertEquals(0.877, rangedChance(next), 0.001);
			// Out of melee distance as well: x6 once, not x36.
			final List<BossRules.ModifierRule> away = applying(tracker, ELDRIC_INDEX, ELDRIC, "RANGED", thrown, false);
			assertEquals(1, away.size());
			assertEquals(6L * BOW_ROLL, ModifierTracker.attackRoll(away, "RANGED", BOW_ROLL));
		}
		// Judged at the throw: thrown on the last tick of the 16, landing after it, it still has it.
		assertEquals(1, applying(tracker, ELDRIC_INDEX, ELDRIC, "RANGED", 7368, true).size());
		// Over 16 ticks from the charge.
		assertTrue(applying(tracker, ELDRIC_INDEX, ELDRIC, "RANGED", 7369, true).isEmpty());
		// Branda's own blast is hers: Eldric's doesn't open it.
		assertTrue(applying(tracker, BRANDA_INDEX, BRANDA, "RANGED", 7361, true).isEmpty());
		// FIRE_GIANT_BRANDA_QUEEN_ATTACK_AOE01 at [7295].
		tracker.onBossAnim(BRANDA_INDEX, NAME, modifiers(), 11981, 7295);
		assertEquals(1, applying(tracker, BRANDA_INDEX, BRANDA, "RANGED", 7301, true).size());
	}

	@Test
	public void magicAndMeleeAreUnchanged()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		tracker.onBossAnim(ELDRIC_INDEX, NAME, modifiers(), 11979, 7353);
		for (String style : new String[] {"MELEE", "MAGIC"})
		{
			for (Boolean inMelee : new Boolean[] {true, false})
			{
				final List<BossRules.ModifierRule> mods = applying(tracker, ELDRIC_INDEX, ELDRIC, style, 7361, inMelee);
				assertTrue(style + " " + inMelee, mods.isEmpty());
			}
		}
		// And an attack roll of another type isn't multiplied even by a ranged rule's list.
		final List<BossRules.ModifierRule> away = applying(tracker, BRANDA_INDEX, BRANDA, "RANGED", 7168, false);
		assertEquals(1000, ModifierTracker.attackRoll(away, "MAGIC", 1000));
		assertEquals(1000, ModifierTracker.attackRoll(away, "SLASH", 1000));
	}

	@Test
	public void meleeDistanceOfAFootprint()
	{
		// A 3x3 titan at (100, 200)-(102, 202).
		assertEquals(DamageModifiers.IN_MELEE, DamageModifiers.meleeDistance(100, 200, 3, 3, 101, 199));
		assertEquals(DamageModifiers.IN_MELEE, DamageModifiers.meleeDistance(100, 200, 3, 3, 103, 202));
		assertEquals(DamageModifiers.IN_MELEE, DamageModifiers.meleeDistance(100, 200, 3, 3, 99, 200));
		assertEquals(DamageModifiers.DIAGONAL, DamageModifiers.meleeDistance(100, 200, 3, 3, 99, 199));
		assertEquals(DamageModifiers.DIAGONAL, DamageModifiers.meleeDistance(100, 200, 3, 3, 103, 203));
		assertEquals(0, DamageModifiers.meleeDistance(100, 200, 3, 3, 101, 201));
		assertEquals(2, DamageModifiers.meleeDistance(100, 200, 3, 3, 101, 198));
		assertEquals(5, DamageModifiers.meleeDistance(100, 200, 3, 3, 107, 199));
		assertEquals("next to its footprint (in melee distance)", DamageModifiers.describeDistance(1));
		assertEquals("5 tiles from its footprint (out of melee distance)", DamageModifiers.describeDistance(5));
	}

	@Test
	public void ericsKillReplayed()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		tracker.onBossAnim(ELDRIC_INDEX, NAME, modifiers(), 11979, 7353);
		// The 14 shots: Branda's 12 each before her next melee animation (11974), so out of her melee distance (the log has
		// no tiles: inferred from Eric switching to his tentacle each time she came in); Eldric's 2 inside his blast.
		final int[] brandaShots = {7168, 7172, 7176, 7180, 7185, 7189, 7231, 7235, 7247, 7251, 7255, 7259};
		final int[] eldricShots = {7361, 7365};
		double landedBefore = 0;
		double landed = 0;
		double expected = 0;
		double expectedBefore = 0;
		for (int t : brandaShots)
		{
			final double p = rangedChance(applying(tracker, BRANDA_INDEX, BRANDA, "RANGED", t, false));
			landed += p;
			expected += p * BOW_MAX / 2.0;
		}
		for (int t : eldricShots)
		{
			final double p = rangedChance(applying(tracker, ELDRIC_INDEX, ELDRIC, "RANGED", t, true));
			landed += p;
			expected += p * BOW_MAX / 2.0;
		}
		for (int i = 0; i < 14; i++)
		{
			final double p = CombatCalc.hitChanceFrom(BOW_ROLL, DEF_ROLL);
			landedBefore += p;
			expectedBefore += p * BOW_MAX / 2.0;
		}
		assertEquals(4.7, landedBefore, 0.05);
		assertEquals(104.4, expectedBefore, 0.2);
		// Landed 12 of 14 for 300.
		assertEquals(12.3, landed, 0.05);
		assertEquals(270.1, expected, 0.3);

		// Branda's first stretch, [7168]-[7208]: A 215, E 99.9, sd 40.8; 6 bow shots and 3 tentacle hits.
		final double p0 = CombatCalc.hitChanceFrom(BOW_ROLL, DEF_ROLL);
		final double p6 = rangedChance(applying(tracker, BRANDA_INDEX, BRANDA, "RANGED", 7168, false));
		final double melee = 99.9 - 6 * p0 * 22;
		final double meleeVariance = 40.8 * 40.8 - 6 * bowVariance(p0);
		final double e = melee + 6 * p6 * 22;
		final double sd = Math.sqrt(meleeVariance + 6 * bowVariance(p6));
		assertEquals(171.0, e, 0.3);
		assertEquals(+1.0, (215 - e) / sd, 0.05);

		// The room: both titans A 1,200 against E 1,018.3 before (their 12 LUCK lines); the bow's +165.7 brings it to 1,184.0.
		// Melee is unchanged.
		final double room = 99.9 + 98.6 + 34.6 + 90.2 + 60.4 + 98.6 + 78.8 + 19.7 + 39.4 + 192.3 + 167.7 + 38.1;
		assertEquals(1018.3, room, 0.05);
		assertEquals(1184.0, room + expected - expectedBefore, 0.5);
	}

	/** One bow shot's variance: a landed roll uniform over 0-44, at chance p. */
	private static double bowVariance(double p)
	{
		final double meanSquare = BOW_MAX * (2.0 * BOW_MAX + 1) / 6.0;
		final double mean = p * BOW_MAX / 2.0;
		return p * meanSquare - mean * mean;
	}
}
