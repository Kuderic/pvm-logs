package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-015: the settings the plugin can see for itself, worked out instead of
 * asked - the ideal boost from what is on hand, the ideal prayer from what is
 * unlocked, whether a fight is on task, and the blowpipe dart.
 */
public class WorkedOutSetupTest
{
	private static final int NO_HERBLORE = 1;

	private static IdealBoost.Result boost(Skill skill, int base, boolean heartReady, int peak, int... inventory)
	{
		return IdealBoost.best(skill, base, NO_HERBLORE, inventory, heartReady, peak);
	}

	// --- Boost ---------------------------------------------------------------------------------------------------------

	@Test
	public void theStrongestBoostOnHandIsTheIdealAndADivineDoseWinsATie()
	{
		final IdealBoost.Result r = boost(Skill.STRENGTH, 99, true, 0, ItemID._3DOSE2COMBAT, ItemID._1DOSEDIVINECOMBAT);
		assertEquals(19, r.boost);
		assertEquals(ItemID._1DOSEDIVINECOMBAT, r.source);
		// Either order.
		assertEquals(ItemID._1DOSEDIVINECOMBAT,
			boost(Skill.STRENGTH, 99, true, 0, ItemID._1DOSEDIVINECOMBAT, ItemID._3DOSE2COMBAT).source);
	}

	@Test
	public void anyDoseCounts()
	{
		assertEquals(19, boost(Skill.ATTACK, 99, true, 0, ItemID._1DOSE2COMBAT).boost);
		assertEquals(19, boost(Skill.ATTACK, 99, true, 0, ItemID._4DOSE2COMBAT).boost);
	}

	@Test
	public void aHeartOnCooldownIsIgnored()
	{
		assertEquals(13, boost(Skill.MAGIC, 99, true, 0, ItemID.SATURATED_HEART).boost);
		final IdealBoost.Result onCooldown = boost(Skill.MAGIC, 99, false, 0, ItemID.SATURATED_HEART);
		assertEquals(0, onCooldown.boost);
		assertEquals(IdealBoost.NOTHING, onCooldown.source);
		// A potion beside it still counts.
		assertEquals(4, boost(Skill.MAGIC, 99, false, 0, ItemID.SATURATED_HEART, ItemID._4DOSE1MAGIC).boost);
	}

	@Test
	public void aPrePotSeenWithNoPotionLeftIsStillTheIdeal()
	{
		final IdealBoost.Result r = boost(Skill.STRENGTH, 99, true, 19, ItemID.SHARK, ItemID._4DOSEPOTIONOFSARADOMIN);
		assertEquals(19, r.boost);
		assertEquals(IdealBoost.SEEN_THIS_FIGHT, r.source);
	}

	@Test
	public void nothingOnHandMeansTheBaseIsTheIdealSoAtBaseCostsNothingAndBelowItCosts()
	{
		final IdealBoost.Result r = boost(Skill.STRENGTH, 99, true, 0, ItemID.SHARK);
		assertEquals(0, r.boost);
		assertEquals(IdealBoost.NOTHING, r.source);
		// At base: all of a boost of nothing, so the boost costs nothing. Brewed below base: none of it, so it costs.
		assertEquals(1.0, CombatCalc.boostShare(99, 99, r.boost), 1e-9);
		assertEquals(0.0, CombatCalc.boostShare(88, 99, r.boost), 1e-9);
		// A drained stat never sets the peak below nought.
		assertEquals(0, boost(Skill.STRENGTH, 99, true, -11).boost);
	}

	@Test
	public void theTableHoldsTheWikisFigures()
	{
		// At 99.
		assertEquals(12, BoostTable.get(ItemID._4DOSE1ATTACK).boost(Skill.ATTACK, 99, 1));
		assertEquals(19, BoostTable.get(ItemID._4DOSE2ATTACK).boost(Skill.ATTACK, 99, 1));
		assertEquals(21, BoostTable.get(ItemID._4DOSEPOTIONOFZAMORAK).boost(Skill.ATTACK, 99, 1));
		assertEquals(13, BoostTable.get(ItemID._4DOSEPOTIONOFZAMORAK).boost(Skill.STRENGTH, 99, 1));
		assertEquals(13, BoostTable.get(ItemID._4DOSERANGERSPOTION).boost(Skill.RANGED, 99, 1));
		assertEquals(13, BoostTable.get(ItemID._4DOSEDIVINEBASTION).boost(Skill.RANGED, 99, 1));
		assertEquals(4, BoostTable.get(ItemID._4DOSEDIVINEBATTLEMAGE).boost(Skill.MAGIC, 99, 1));
		assertEquals(10, BoostTable.get(ItemID.IMBUED_HEART).boost(Skill.MAGIC, 99, 1));
		assertEquals(10, BoostTable.get(ItemID._4DOSEFORGOTTENBREW).boost(Skill.MAGIC, 99, 1));
		assertEquals(6, BoostTable.get(ItemID._4DOSEANCIENTBREW).boost(Skill.MAGIC, 99, 1));
		assertEquals(26, BoostTable.get(ItemID.TOA_SUPPLY_STATS_2).boost(Skill.MAGIC, 99, 1));
		assertEquals(13, BoostTable.get(ItemID.RAIDS_VIAL_TWISTED_WEAK_1).boost(Skill.RANGED, 99, 1));
		assertEquals(17, BoostTable.get(ItemID.RAIDS_VIAL_KODAI_3).boost(Skill.MAGIC, 99, 1));
		assertEquals(21, BoostTable.get(ItemID.RAIDS_VIAL_ELDER_STRONG_4).boost(Skill.STRENGTH, 99, 1));
		assertEquals(0, BoostTable.get(ItemID.RAIDS_VIAL_ELDER_STRONG_4).boost(Skill.MAGIC, 99, 1));
		// A potion for one stat leaves the others alone.
		assertEquals(0, BoostTable.get(ItemID._4DOSE2STRENGTH).boost(Skill.ATTACK, 99, 1));
		assertNull(BoostTable.get(ItemID.SHARK));
		assertTrue(BoostTable.size() > 150);
	}

	@Test
	public void theMoonlightPotionDependsOnHerblore()
	{
		final BoostTable.Boost moonlight = BoostTable.get(ItemID._4DOSEMOONLIGHTPOTION);
		assertEquals(12, moonlight.boost(Skill.ATTACK, 99, 44));
		assertEquals(19, moonlight.boost(Skill.ATTACK, 99, 45));
		assertEquals(12, moonlight.boost(Skill.STRENGTH, 99, 54));
		assertEquals(19, moonlight.boost(Skill.STRENGTH, 99, 55));
	}

	// --- Prayer --------------------------------------------------------------------------------------------------------

	private static PrayerIdeal.Player player(int prayer, int defence)
	{
		final PrayerIdeal.Player p = new PrayerIdeal.Player();
		p.prayer = prayer;
		p.defence = defence;
		p.attack = 99;
		p.strength = 99;
		return p;
	}

	@Test
	public void pietyNeedsKnightWavesAndItsLevels()
	{
		final PrayerIdeal.Player p = player(77, 99);
		p.knightWaves = true;
		assertEquals("Piety", PrayerIdeal.best(PrayerIdeal.Style.MELEE, p).getName());
		p.defence = 67;
		assertEquals("Chivalry", PrayerIdeal.best(PrayerIdeal.Style.MELEE, p).getName());
		p.knightWaves = false;
		final PrayerIdeal.Goal combined = PrayerIdeal.best(PrayerIdeal.Style.MELEE, p);
		assertEquals("Ultimate Strength + Incredible Reflexes", combined.getName());
		assertEquals(1.15, combined.getAttackMultiplier(), 1e-9);
		assertEquals(1.15, combined.getStrengthMultiplier(), 1e-9);
	}

	@Test
	public void lowPrayerLevelsHoldTheBestAttackAndStrengthPrayersTogether()
	{
		final PrayerIdeal.Goal at20 = PrayerIdeal.best(PrayerIdeal.Style.MELEE, player(20, 1));
		assertEquals(1.10, at20.getAttackMultiplier(), 1e-9);
		assertEquals(1.10, at20.getStrengthMultiplier(), 1e-9);
		final PrayerIdeal.Goal at5 = PrayerIdeal.best(PrayerIdeal.Style.MELEE, player(5, 1));
		assertEquals("Burst of Strength", at5.getName());
		assertEquals("None", PrayerIdeal.best(PrayerIdeal.Style.MELEE, player(1, 1)).getName());
	}

	@Test
	public void zealReplacesPietyOnceUnlocked()
	{
		final PrayerIdeal.Player p = player(90, 99);
		p.knightWaves = true;
		p.zeal = true;
		final PrayerIdeal.Goal zeal = PrayerIdeal.best(PrayerIdeal.Style.MELEE, p);
		assertEquals("Zeal", zeal.getName());
		assertEquals(1.28, zeal.getStrengthMultiplier(), 1e-9);
		// Unlocked but short of its Attack: Piety is gone, so Chivalry.
		p.attack = 70;
		assertEquals("Chivalry", PrayerIdeal.best(PrayerIdeal.Style.MELEE, p).getName());
	}

	@Test
	public void rangedAndMagicFollowTheirScrolls()
	{
		final PrayerIdeal.Player p = player(80, 70);
		assertEquals("Eagle Eye", PrayerIdeal.best(PrayerIdeal.Style.RANGED, p).getName());
		assertEquals("Mystic Might", PrayerIdeal.best(PrayerIdeal.Style.MAGIC, p).getName());
		p.deadeye = true;
		p.mysticVigour = true;
		assertEquals("Deadeye", PrayerIdeal.best(PrayerIdeal.Style.RANGED, p).getName());
		assertEquals("Mystic Vigour", PrayerIdeal.best(PrayerIdeal.Style.MAGIC, p).getName());
		p.rigour = true;
		p.augury = true;
		assertEquals("Rigour", PrayerIdeal.best(PrayerIdeal.Style.RANGED, p).getName());
		assertEquals("Augury", PrayerIdeal.best(PrayerIdeal.Style.MAGIC, p).getName());
		// Augury at 77 Prayer: at 76 it is Mystic Vigour.
		p.prayer = 76;
		assertEquals("Mystic Vigour", PrayerIdeal.best(PrayerIdeal.Style.MAGIC, p).getName());
		// Rigour needs 70 Defence.
		p.prayer = 80;
		p.defence = 69;
		assertEquals("Deadeye", PrayerIdeal.best(PrayerIdeal.Style.RANGED, p).getName());
	}

	@Test
	public void theRuinousBookHasItsOwnPrayers()
	{
		final PrayerIdeal.Player p = player(85, 99);
		p.ruinousBook = true;
		p.knightWaves = true;
		assertEquals("Decimate", PrayerIdeal.best(PrayerIdeal.Style.MELEE, p).getName());
		assertEquals("Annihilate", PrayerIdeal.best(PrayerIdeal.Style.RANGED, p).getName());
		assertEquals("Ancient Will", PrayerIdeal.best(PrayerIdeal.Style.MAGIC, p).getName());
	}

	// --- On task -------------------------------------------------------------------------------------------------------

	@Test
	public void onTaskWhenTheNpcIsTheTask()
	{
		final SlayerTasks.Verdict v = SlayerTasks.verdict(12, "Vardorvis", "Vardorvis", 0);
		assertTrue(v.onTask);
		assertTrue(v.known);
		// The table's extra names: Dusk for gargoyles, the Kraken for cave kraken.
		assertTrue(SlayerTasks.verdict(40, "Gargoyles", "Dusk", 0).onTask);
		assertTrue(SlayerTasks.verdict(40, "Cave kraken", "Kraken", 0).onTask);
		// Singular, whole words, any case.
		assertTrue(SlayerTasks.verdict(40, "Abyssal demons", "Abyssal demon", 0).onTask);
		assertTrue(SlayerTasks.counts("Hellhounds", "Cerberus"));
	}

	@Test
	public void offTaskWithAnotherTaskOrNone()
	{
		final SlayerTasks.Verdict other = SlayerTasks.verdict(40, "Abyssal demons", "Vardorvis", 0);
		assertFalse(other.onTask);
		assertTrue(other.known);
		final SlayerTasks.Verdict none = SlayerTasks.verdict(0, null, "Vardorvis", 0);
		assertFalse(none.onTask);
		assertTrue(none.known);
		// A name that only contains the task's as part of a word does not count.
		assertFalse(SlayerTasks.counts("Rats", "Pirate"));
	}

	@Test
	public void unknownIsAssumedOnTaskAndSaysSo()
	{
		final SlayerTasks.Verdict unreadable = SlayerTasks.verdict(12, null, "Vardorvis", 0);
		assertTrue(unreadable.onTask);
		assertFalse(unreadable.known);
		assertTrue(unreadable.toString().startsWith("assumed yes"));
		// Konar's area is not checked.
		final SlayerTasks.Verdict konar = SlayerTasks.verdict(12, "Abyssal demons", "Abyssal demon", 7);
		assertTrue(konar.onTask);
		assertFalse(konar.known);
		assertTrue(SlayerTasks.size() > 100);
	}

	// --- Blowpipe dart -------------------------------------------------------------------------------------------------

	@Test
	public void theBlowpipeCheckMessageNamesItsDart()
	{
		assertEquals(BlowpipeDart.DRAGON, BlowpipeDart.fromCheckMessage("Darts: Dragon dart x 1,234. Scales: 12,345 (75.0%)."));
		assertEquals(BlowpipeDart.AMETHYST, BlowpipeDart.fromCheckMessage("Darts: Amethyst dart x 50. Scales: 300 (1.8%)."));
		assertEquals(BlowpipeDart.NONE, BlowpipeDart.fromCheckMessage("Darts: None. Scales: 0 (0.0%)."));
		assertNull(BlowpipeDart.fromCheckMessage("You eat the shark."));
		assertNull(BlowpipeDart.fromCheckMessage(null));
	}

	@Test
	public void dartsUsedOnTheBlowpipeAreTheDart()
	{
		assertEquals(BlowpipeDart.RUNE, BlowpipeDart.fromUseTarget("Rune dart -> Toxic blowpipe"));
		assertEquals(BlowpipeDart.DRAGON, BlowpipeDart.fromUseTarget("Dragon dart(p++) -> Toxic blowpipe"));
		assertNull(BlowpipeDart.fromUseTarget("Rune dart -> Fishing spot"));
		assertNull(BlowpipeDart.fromUseTarget("Shark -> Toxic blowpipe"));
	}

	// --- Drained attacks -----------------------------------------------------------------------------------------------

	@Test
	public void drainedAttacksAddUpAndOldFightsLoadWithNone()
	{
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		f.recordAttackMade(false, 0.0, true);
		f.recordAttackMade(false, 0.0, true);
		f.recordAttackMade(true, 1.0, false);
		assertEquals(2, f.getAttacksDrained());
		assertEquals(3, f.getAttacksMade());

		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(f);
		assertEquals(2, room.getAttacksDrained());
		final Raid raid = new Raid("Tombs of Amascut", 1, 0L);
		raid.add(room);
		assertEquals(2, raid.getAttacksDrained());

		final SessionTotals session = new SessionTotals(0L);
		session.recordAttackMade(false, 0.0, true);
		assertEquals(1, session.getAttacksDrained());
		session.reset(0L);
		assertEquals(0, session.getAttacksDrained());

		final Fight old = new Gson().fromJson("{\"targetName\":\"Vardorvis\",\"attacksMade\":30,\"attacksPotted\":5}",
			Fight.class);
		assertEquals(0, old.getAttacksDrained());
	}
}
