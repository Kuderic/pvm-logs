package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.game.ItemEquipmentStats;
import org.junit.Test;

/**
 * T-152: the Tombs of Amascut's expected figures. The Keris partisan of amascut's stats inside the raid, which RuneLite's
 * item stats leave out; the raid level on the defence roll of the bosses only (the DPS calc's facts). Eric's 11-02 solo
 * raid, level 340.
 */
public class TombsFiguresTest
{
	/** RuneLite's item stats for 30891 (static.runelite.net, read 2026-11-02): no stab bonus, no strength. */
	private static ItemEquipmentStats runelites()
	{
		return ItemEquipmentStats.builder().slot(3).aslash(-2).acrush(57).amagic(2).prayer(3).aspeed(4).build();
	}

	@Test
	public void theKerisOfAmascutInsideAndOut()
	{
		final ItemEquipmentStats in = CombatCalc.kerisOfAmascut(runelites(), true);
		assertEquals(108, in.getAstab());
		assertEquals(67, in.getStr());
		final ItemEquipmentStats out = CombatCalc.kerisOfAmascut(runelites(), false);
		assertEquals(58, out.getAstab());
		assertEquals(45, out.getStr());
		// The rest is RuneLite's.
		assertEquals(-2, in.getAslash());
		assertEquals(57, in.getAcrush());
		assertEquals(3, in.getPrayer());
		assertEquals(4, in.getAspeed());
		assertEquals(3, in.getSlot());
		// Without RuneLite's record: the wiki's figures.
		assertEquals(57, CombatCalc.kerisOfAmascut(null, true).getAcrush());
	}

	@Test
	public void itsMaxIsTheRaidsMaxHitSplat()
	{
		// Eric's other melee gear's strength (DPS calc's data): faceguard 6, fire cape 4, rancour 12, Bandos chest 4,
		// defender 6, tassets 2, Barrows gloves 12, Avernic treads 4, berserker (i) 8.
		final int gear = 58;
		// An effective strength of 164 (161 to 164 all do): the keris at +0, the plugin's 31; at +67, the type-43
		// max-hit splats' 48 on Akkha [31630] and Ba-Ba [32662].
		assertEquals(31, CombatCalc.maxHitFromStrength(164, gear));
		assertEquals(48, CombatCalc.maxHitFromStrength(164, gear + 67));
		// Outside the raid's +45 couldn't make 48.
		assertTrue(CombatCalc.maxHitFromStrength(164, gear + 45) < 48);
	}

	@Test
	public void theRaidLevelScalesTheBossesDefenceRoll()
	{
		// Zebak, ranged: (70 + 9) x (110 + 64), x (250 + 340) / 250.
		final int zebak = (70 + 9) * (110 + 64);
		assertEquals(32440, RaidScaling.tombsDefenceRoll(zebak, NpcID.TOA_ZEBAK, 340));
		// The old level scaling: (165 + 9) x 174.
		assertEquals(30276, (RaidScaling.tombs(70, 340) + 9) * (110 + 64));
		// Akkha's Shadow, ranged: its level scaled read its roll 14% low.
		final int shadow = (30 + 9) * (60 + 64);
		assertEquals(11412, RaidScaling.tombsDefenceRoll(shadow, NpcID.AKKHA_SHADOW, 340));
		assertEquals(9796, (RaidScaling.tombs(30, 340) + 9) * (60 + 64));
		// Every boss and the Wardens.
		for (int id : new int[]{NpcID.AKKHA_MELEE, NpcID.TOA_BABA, NpcID.TOA_KEPHRI_BOSS_SHIELDED,
			NpcID.TOA_KEPHRI_BOSS_ENRAGE, NpcID.TOA_WARDENS_P1_OBELISK_NPC, NpcID.TOA_WARDEN_ELIDINIS_PHASE2_MAGE,
			NpcID.TOA_WARDEN_ELIDINIS_PHASE2_EXPOSED, NpcID.TOA_WARDEN_TUMEKEN_PHASE3})
		{
			assertTrue(id + "", RaidScaling.tombsScalesDefence(id));
		}
	}

	@Test
	public void notTheSmallMonstersNorKephrisOverlords()
	{
		final int roll = 10_000;
		// Kephri's soldier, spitting and arcane scarabs; an egg (11729); a Baboon Brawler (11712).
		for (int id : new int[]{NpcID.TOA_KEPHRI_GUARDIAN_MELEE, NpcID.TOA_KEPHRI_GUARDIAN_RANGED,
			NpcID.TOA_KEPHRI_GUARDIAN_MAGE, 11729, 11712})
		{
			assertFalse(id + "", RaidScaling.tombsScalesDefence(id));
			assertEquals(roll, RaidScaling.tombsDefenceRoll(roll, id, 340));
		}
		// Outside the raid, nothing.
		assertEquals(roll, RaidScaling.tombsDefenceRoll(roll, NpcID.TOA_ZEBAK, 0));
	}
}
