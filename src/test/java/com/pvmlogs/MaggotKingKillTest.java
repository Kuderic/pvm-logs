package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-086: a Maggot King kill is one room and one fight; his larvae are unscored
 * adds whose shots are time in his fight; his death closes the room; the melee
 * punish on his slam is an attack of its own outside the weapon's speed; and
 * the twinflame staff casts every 6 ticks. Synthetic, in the shape of Eric's
 * kills of 2026-10-29 ([310]-[570] of the 13:50 log and the three after it,
 * nothing copied).
 */
public class MaggotKingKillTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int KING = NpcID.MAGGOT_KING;
	private static final int LARVA = NpcID.UR_MAGGOT_LARVAE;
	private static final int KING_INDEX = 35012;

	@Test
	public void theIdsAreTheGamevalsAndTheLogs()
	{
		assertEquals(15742, KING);
		assertEquals(15743, LARVA);
		assertEquals(15741, NpcID.MAGGOT_KING_CORPSE);
		// The animations the log shows inside TAKEN lines, named by the gamevals.
		assertEquals(13922, AnimationID.MAGGOT_KING_SCREECH);
		assertEquals(13925, AnimationID.MAGGOT_KING_MELEEATTACK);
		assertEquals(13933, AnimationID.MAGGOT_KING_RANGEATTACK);
		assertEquals(13934, AnimationID.MAGGOT_KING_MAGEATTACK);
	}

	@Test
	public void theKingIsOneGroupWithHisLarvaeAsUnscoredAdds()
	{
		assertEquals("Maggot King", EncounterGroup.of(KING));
		assertEquals("Maggot King", EncounterGroup.of(LARVA));
		// His corpse stands only to be looted.
		assertNull(EncounterGroup.of(NpcID.MAGGOT_KING_CORPSE));
		assertFalse(EncounterGroup.isUnscored(KING));
		assertTrue(EncounterGroup.isUnscored(LARVA));
		assertTrue(EncounterGroup.lootIsTheKill(KING));
		assertFalse(EncounterGroup.lootIsTheKill(LARVA));
		// A larva shot or a larva's death never ends his fight: the log's [383], [417], [452], [484], [516] and [547].
		assertTrue(EncounterGroup.addKeepsBossFight(LARVA, KING));
		assertFalse(EncounterGroup.addKeepsBossFight(KING, LARVA));
		assertFalse(EncounterGroup.otherFallEndsFight(LARVA, KING));
	}

	@Test
	public void hisDeathClosesTheRoom()
	{
		// No loot event in any of the four kills: the room closes when he dies.
		final Fight king = new Fight("Maggot King", KING, KING_INDEX, 1500, 0L, null, 0);
		final Encounter room = new Encounter(king.encounterName(), null, 0L);
		room.add(king);
		assertEquals("Maggot King", king.encounterName());
		assertFalse(room.closesOnDeathOf(LARVA, 36958, false));
		assertTrue(room.closesOnDeathOf(KING, KING_INDEX, false));
	}

	@Test
	public void theKillIsOneFightWithItsHitpointsCarried()
	{
		// Kill 1 as one fight: the casts and swings on the King, each larva shot a tick of time in his fight with nothing
		// scored, and no larva fight to take the King's splats (before: weapon 63 and 99 in two larva fights, [516]-[552]).
		final Fight king = new Fight("Maggot King", KING, KING_INDEX, 1500, 0L, null, 0);
		final Encounter room = new Encounter(king.encounterName(), null, 0L);
		room.add(king);
		final int[][] between = {{28, 15, 34, 33, 17, 28, 28, 17, 40, 39, 45}, {13, 3, 0, 16, 38}, {32, 20, 15, 42, 32},
			{28, 27, 40, 0, 58, 22, 66, 18}, {3, 0, 19, 7}, {43, 3, 0, 52, 41, 0}, {69, 51, 37, 33, 21}};
		int dealt = 0;
		int shots = 0;
		for (int[] segment : between)
		{
			for (int hit : segment)
			{
				king.recordAttackMade(true, 1, false);
				king.recordDamageDealt(hit, 0L, hit > 0);
				dealt += hit;
			}
			if (shots < between.length - 1)
			{
				final int attempts = king.getAttempts();
				king.recordTickSpent();
				assertEquals(attempts, king.getAttempts());
				shots++;
			}
		}
		assertEquals(6, shots);
		assertEquals(1, room.getFights().size());
		// Counted against the one 1,500, not a fresh 1,500 in each of seven fights.
		assertEquals(dealt, king.getDamageDealt());
		assertTrue(dealt <= 1500);
	}

	@Test
	public void thePunishIsInTheData()
	{
		final BossRules.Boss king = RULES.forNpc(KING);
		assertNotNull(king);
		assertNotNull(king.meleePunish);
		assertEquals(AnimationID.MAGGOT_KING_MELEEATTACK, king.meleePunish.onBossAnim);
		assertEquals(2, king.meleePunish.afterTicks);
		assertNull(RULES.forNpc(LARVA));
		// No other boss has one.
		assertNull(RULES.forNpc(NpcID.SNAKEBOSS_BOSS_RANGED).meleePunish);
	}

	@Test
	public void thePunishIsTheBlowTwoTicksAfterTheSlam()
	{
		// The slams the log shows beside the splats on Eric, and his axe 2 ticks later: 1429 -> 1431, 1434 -> 1436.
		final List<Integer> slams = Arrays.asList(1429, 1434);
		assertTrue(PvmPerformancePlugin.isPunishTick(slams, 1431, 2));
		assertTrue(PvmPerformancePlugin.isPunishTick(slams, 1436, 2));
		// Not the weapon's own swing a tick later, nor one a tick early.
		assertFalse(PvmPerformancePlugin.isPunishTick(slams, 1432, 2));
		assertFalse(PvmPerformancePlugin.isPunishTick(slams, 1430, 2));
		assertFalse(PvmPerformancePlugin.isPunishTick(Collections.emptyList(), 1431, 2));
	}

	@Test
	public void thePunishLeavesTheWeaponsCadenceAlone()
	{
		// [524]-[535]: the axe (5 ticks) on 524, a punish on 528, the axe on 529, a punish on 533, the axe on 534.
		int due = PvmPerformancePlugin.dueAfter(false, Integer.MIN_VALUE, 524, 5);
		assertEquals(529, due);
		due = PvmPerformancePlugin.dueAfter(true, due, 528, 5);
		assertEquals(529, due);
		// So the swing on 529 is on time, where before the punish had moved it to 533 ("XP CONTRADICTS BOOKING" at [528]).
		assertTrue(529 >= due);
		due = PvmPerformancePlugin.dueAfter(false, due, 529, 5);
		due = PvmPerformancePlugin.dueAfter(true, due, 533, 5);
		assertEquals(534, due);
		due = PvmPerformancePlugin.dueAfter(false, due, 534, 5);
		assertEquals(539, due);
		// A punish with the weapon already free starts no cooldown either: it may swing again the next tick.
		assertEquals(1845, PvmPerformancePlugin.dueAfter(true, 1734, 1844, 5));
		assertEquals(1845, PvmPerformancePlugin.dueAfter(true, Integer.MIN_VALUE, 1844, 5));
	}

	@Test
	public void aPunishAndASwingATickApartAreTwoAttacks()
	{
		// [467]-[469]: a 58 thrown on 467 lands on 468, a 22 thrown on 468 lands on 469, each with its own experience drop.
		final AttackSplats.Burst axe = new AttackSplats.Burst(467, 468, AttackSplats.ONE, AttackSplats.ONE, false,
			"Zombie axe");
		assertTrue(PvmPerformancePlugin.secondSplatIsOwnAttack(axe, 469));
		// Not two ticks on (a new burst anyway), not a special, not a weapon with more splats to give.
		assertFalse(PvmPerformancePlugin.secondSplatIsOwnAttack(axe, 470));
		assertFalse(PvmPerformancePlugin.secondSplatIsOwnAttack(null, 469));
		final AttackSplats.Burst special = new AttackSplats.Burst(467, 468, AttackSplats.ONE, AttackSplats.ONE, true,
			"Zombie axe");
		final AttackSplats scythe = AttackSplats.of(ItemID.SCYTHE_OF_VITUR, false, 3, null);
		assertTrue(scythe.count > 1);
		final AttackSplats.Burst threeSplats = new AttackSplats.Burst(467, 468, scythe, scythe, false, "Scythe of vitur");
		assertFalse(PvmPerformancePlugin.secondSplatIsOwnAttack(special, 469));
		assertFalse(PvmPerformancePlugin.secondSplatIsOwnAttack(threeSplats, 469));
	}

	@Test
	public void theTwinflameStaffCastsEverySixTicks()
	{
		// "an attack and cast speed of 6" (wiki); 72 of the log's 85 gaps between casts were 6.
		assertEquals(6, CombatCalc.spellClockTicks(ItemID.TWINFLAME_STAFF, Spell.FIRE_WAVE));
		assertEquals(6, CombatCalc.spellClockTicks(ItemID.TWINFLAME_STAFF, null));
		// The rest unchanged.
		assertEquals(4, CombatCalc.spellClockTicks(ItemID.NIGHTMARE_STAFF_HARMONISED, Spell.FIRE_SURGE));
		assertEquals(5, CombatCalc.spellClockTicks(ItemID.STAFF_OF_FIRE, Spell.FIRE_WAVE));
	}
}
