package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * T-098: an attack on one of Araxxor's hatched araxytes with a noxious halberd,
 * a crush weapon (crush the highest attack bonus worn) or a crossbow or ballista
 * (ranged the highest) lands for certain at its max hit (wiki, Araxxor/Strategies;
 * the DPS calc's reading for "can't miss" and "highest accuracy style").
 *
 * <p>Calibrated on Eric's log of 2026-10-29 21:20, from the counts its fight END
 * and {@code scored as} lines give: 15 araxytes, 30 Hunters' sunlight crossbow
 * shots, every one landing at its max (29 or 31) or the hitpoints left.
 */
public class AraxyteMaxHitTest
{
	private static final int INDEX = 61074;
	private static BossRules rules;

	@BeforeClass
	public static void load()
	{
		rules = BossRules.load(new Gson(), null);
	}

	private static BossRules.DamageModifierRules araxytes(int npcId)
	{
		final BossRules.Boss boss = rules.forNpc(npcId);
		assertNotNull(boss);
		assertNotNull(boss.damageModifiers);
		return boss.damageModifiers;
	}

	/** The modifiers an attack takes: the tracker's for the style, then the player's conditions. */
	private static List<BossRules.ModifierRule> taken(int npcId, String style, int weapon, String category,
		String attackType, String highest)
	{
		final List<BossRules.ModifierRule> mods = new ModifierTracker(null).applying(INDEX, araxytes(npcId), npcId, style,
			100, 101, false, (id, range) -> false);
		return ModifierTracker.forPlayer(mods, weapon, category, attackType, highest);
	}

	private static boolean guaranteed(List<BossRules.ModifierRule> mods)
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

	// ---- the conditions ----

	@Test
	public void everyHatchedAraxyteHasTheRuleAndTheEggsAndAraxxorDont()
	{
		for (int id : new int[]{NpcID.ARAXXOR_MINION_MIRRORBACK, NpcID.ARAXXOR_MINION_VENOM, NpcID.ARAXXOR_MINION_EXPLODE})
		{
			assertTrue(guaranteed(taken(id, "MELEE", ItemID.NOXIOUS_HALBERD, "POLEARM", "SLASH", "SLASH")));
		}
		// "Attacking the unhatched eggs ... rolls damage normally."
		for (int id : new int[]{NpcID.ARAXXOR_MINION_EGG_MIRRORBACK, NpcID.ARAXXOR_MINION_EGG_VENOM,
			NpcID.ARAXXOR_MINION_EGG_EXPLODE})
		{
			assertNull(rules.forNpc(id));
		}
		// Nor Araxxor himself: his rules are the enrage alone.
		for (BossRules.ModifierRule m : rules.forNpc(NpcID.ARAXXOR).damageModifiers.modifiers)
		{
			assertFalse(m.name, Boolean.TRUE.equals(m.guaranteedMax));
		}
	}

	@Test
	public void theNoxiousHalberdWhateverTheBonuses()
	{
		final int venom = NpcID.ARAXXOR_MINION_VENOM;
		assertTrue(guaranteed(taken(venom, "MELEE", ItemID.NOXIOUS_HALBERD, "POLEARM", "STAB", "STAB")));
		assertTrue(guaranteed(taken(venom, "MELEE", ItemID.BR_NOXIOUS_HALBERD, "POLEARM", "SLASH", "RANGED")));
		// Another halberd is just a halberd.
		assertFalse(guaranteed(taken(venom, "MELEE", ItemID.DRAGON_HALBERD, "POLEARM", "SLASH", "SLASH")));
	}

	@Test
	public void crushWhenCrushIsTheHighestBonus()
	{
		final int mirror = NpcID.ARAXXOR_MINION_MIRRORBACK;
		assertTrue(guaranteed(taken(mirror, "MELEE", ItemID.ELDER_MAUL, "BLUNT", "CRUSH", "CRUSH")));
		// A crush attack with slash the highest bonus worn rolls normally...
		assertFalse(guaranteed(taken(mirror, "MELEE", ItemID.ELDER_MAUL, "BLUNT", "CRUSH", "SLASH")));
		// ...and so does a slash with crush the highest.
		assertFalse(guaranteed(taken(mirror, "MELEE", ItemID.ZOMBIE_AXE, "AXE", "SLASH", "CRUSH")));
	}

	@Test
	public void aCrossbowOrBallistaWhenRangedIsTheHighestBonus()
	{
		final int venom = NpcID.ARAXXOR_MINION_VENOM;
		// Eric's: the Hunters' sunlight crossbow.
		assertTrue(guaranteed(taken(venom, "RANGED", ItemID.HUNTING_CROSSBOW_SUNLIGHT, "CROSSBOW", "RANGED", "RANGED")));
		assertTrue(guaranteed(taken(venom, "RANGED", ItemID.HEAVY_BALLISTA, "THROWN", "RANGED", "RANGED")));
		assertTrue(guaranteed(taken(venom, "RANGED", ItemID.LIGHT_BALLISTA, null, "RANGED", "RANGED")));
		// Ranged not the highest bonus worn.
		assertFalse(guaranteed(taken(venom, "RANGED", ItemID.HUNTING_CROSSBOW_SUNLIGHT, "CROSSBOW", "RANGED", "SLASH")));
		// Not a heavy ranged weapon: a bow, or a blowpipe.
		assertFalse(guaranteed(taken(venom, "RANGED", ItemID.TWISTED_BOW, "BOW", "RANGED", "RANGED")));
		assertFalse(guaranteed(taken(venom, "RANGED", ItemID.TOXIC_BLOWPIPE, "THROWN", "RANGED", "RANGED")));
		// Magic, whatever the bonuses.
		assertFalse(guaranteed(taken(venom, "MAGIC", ItemID.TUMEKENS_SHADOW, "POWERED_STAFF", "MAGIC", "MAGIC")));
	}

	@Test
	public void theHighestAccuracyStyleIsTheLargestBonusTiesToTheEarlier()
	{
		assertEquals("RANGED", ModifierTracker.highestAccuracy(40, 45, 30, 12, 98));
		assertEquals("CRUSH", ModifierTracker.highestAccuracy(40, 45, 46, 12, 30));
		// A tie goes to the first in slash, stab, crush, ranged, magic.
		assertEquals("SLASH", ModifierTracker.highestAccuracy(45, 45, 45, 45, 45));
		assertEquals("STAB", ModifierTracker.highestAccuracy(46, 45, 46, 0, 0));
		assertEquals("RANGED", ModifierTracker.highestAccuracy(0, 0, 0, 60, 60));
		assertEquals("MAGIC", ModifierTracker.highestAccuracy(0, 0, 0, 61, 60));
	}

	@Test
	public void theOtherModifiersAreKeptWhateverThePlayer()
	{
		// forPlayer drops only what names a condition: Araxxor's enrage names none.
		final BossRules.DamageModifierRules rulesOfHim = rules.forNpc(NpcID.ARAXXOR).damageModifiers;
		final ModifierTracker tracker = new ModifierTracker(null);
		tracker.onBossAnim(1, "Araxxor", rulesOfHim, 11488, 10);
		final List<BossRules.ModifierRule> mods = tracker.applying(1, rulesOfHim, NpcID.ARAXXOR, "MELEE", 11, 12, false,
			(id, range) -> false);
		assertEquals(1, mods.size());
		assertEquals(mods, ModifierTracker.forPlayer(mods, -1, null, "SLASH", "SLASH"));
	}

	// ---- the figures ----

	@Test
	public void itCannotMissAndHitsTheMaxWithNoVariance()
	{
		final List<BossRules.ModifierRule> mods = taken(NpcID.ARAXXOR_MINION_VENOM, "RANGED",
			ItemID.HUNTING_CROSSBOW_SUNLIGHT, "CROSSBOW", "RANGED", "RANGED");
		// The log's figures for the shot: max 29, accuracy 0.896.
		assertEquals(1.0, ModifierTracker.accuracy(mods, 0.896), 0.0);
		final DamageMoments shot = ModifierTracker.moments(mods, new int[]{29}, ModifierTracker.accuracy(mods, 0.896),
			Integer.MAX_VALUE);
		assertEquals(29, shot.mean, 1e-12);
		assertEquals(0, shot.variance(), 1e-9);
		assertEquals(29, ModifierTracker.max(mods, 29));
		// Without the rule, the same shot expected 13.0 with a variance near 100: the log's "avg 13.0".
		final DamageMoments plain = ModifierTracker.moments(java.util.Collections.emptyList(), new int[]{29}, 0.896,
			Integer.MAX_VALUE);
		assertEquals(0.896 * 29 / 2.0, plain.mean, 1e-9);
		assertTrue(plain.variance() > 80);
	}

	@Test
	public void theShotThatKillsIsCappedAtTheHitpointsLeft()
	{
		final List<BossRules.ModifierRule> mods = taken(NpcID.ARAXXOR_MINION_MIRRORBACK, "RANGED",
			ItemID.HUNTING_CROSSBOW_SUNLIGHT, "CROSSBOW", "RANGED", "RANGED");
		// 31 then the 27 left of its 58, as the log's max-hit splat of 31 and the 27 after it.
		final DamageMoments last = ModifierTracker.moments(mods, new int[]{31}, 1.0, 27);
		assertEquals(27, last.mean, 1e-12);
		assertEquals(0, last.variance(), 1e-9);
	}

	/**
	 * The log's 15 araxytes: 30 shots, 857 damage against 401 expected on that
	 * build. Under the rule each shot expects its max, capped by what is left, so
	 * each araxyte expects its 58: 870 by this count (the plugin's cap reads the
	 * hitpoints a thrall or the Mirrorback's share had already taken, which
	 * brings it to what was dealt).
	 */
	@Test
	public void theLogsAraxytesExpectWhatTheyTook()
	{
		final int[] maxes = {31, 31, 31, 31, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 31, 31, 31, 31, 31, 31,
			31, 31, 31, 31, 29, 29};
		final List<BossRules.ModifierRule> mods = taken(NpcID.ARAXXOR_MINION_VENOM, "RANGED",
			ItemID.HUNTING_CROSSBOW_SUNLIGHT, "CROSSBOW", "RANGED", "RANGED");
		double expected = 0;
		for (int i = 0; i < maxes.length; i += 2)
		{
			int left = 58;
			for (int shot = 0; shot < 2; shot++)
			{
				final double e = ModifierTracker.moments(mods, new int[]{maxes[i + shot]}, 1.0, left).mean;
				expected += e;
				left -= (int) e;
			}
		}
		assertEquals(15 * 58, expected, 1e-9);
	}
}
