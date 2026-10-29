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
	public void oneEntryPerBoss() throws Exception
	{
		// BossRules keeps the last entry per npc id, so a second entry for an id drops the first one's rules silently.
		final java.util.Set<Integer> seen = new java.util.HashSet<>();
		try (java.io.InputStream in = BossRules.class.getResourceAsStream("boss-rules.json"))
		{
			final com.google.gson.JsonObject file = new Gson().fromJson(
				new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
			for (com.google.gson.JsonElement b : file.getAsJsonArray("bosses"))
			{
				final com.google.gson.JsonObject boss = b.getAsJsonObject();
				for (com.google.gson.JsonElement id : boss.getAsJsonArray("npcIds"))
				{
					assertTrue(boss.get("name").getAsString() + ": npc " + id + " is in two entries", seen.add(id.getAsInt()));
				}
			}
		}
	}

	private static BossRules.StateRule state(String name)
	{
		for (BossRules.StateRule s : RULES.forNpc(KING).damageModifiers.states)
		{
			if (name.equals(s.name))
			{
				return s;
			}
		}
		throw new AssertionError(name);
	}

	@Test
	public void theBookingAndTheScoringAgreeOnThePunishTick()
	{
		// T-086 books the blow thrown 2 ticks into the slam as the punish; T-087's window opens on the tick it lands, a
		// melee blow's booking lag later. The two are one tick, or a punish is booked and scored as an ordinary swing.
		final BossRules.MeleePunish punish = RULES.forNpc(KING).meleePunish;
		final BossRules.StateRule window = state("punish");
		assertEquals(window.startDelay, punish.afterTicks + PvmPerformancePlugin.MELEE_BOOKING_LAG);
		assertEquals(punish.onBossAnim, window.startOnBossAnim[0]);
		assertEquals(1, window.ticks);
	}

	@Test
	public void thePunishIsScoredFromItsThrowsRow()
	{
		// The slam starts on 1429; the axe's punish is thrown on 1431 and its splat lands, and books it, on 1432.
		final BossRules.DamageModifierRules rules = RULES.forNpc(KING).damageModifiers;
		final ModifierTracker tracker = new ModifierTracker(null);
		tracker.onBossAnim(KING_INDEX, "Maggot King", rules, AnimationID.MAGGOT_KING_MELEEATTACK, 1429);
		final ModifierTracker.InsideCheck nowhere = (id, range) -> false;
		// The row refreshExpected writes on the throw's tick: a blow going out then lands in the punish window.
		final List<BossRules.ModifierRule> atThrow = tracker.applying(KING_INDEX, rules, KING, "MELEE", 1431, 1432, false,
			nowhere);
		// The figures read at the splat, as a melee blow's were: a blow going out then lands a tick past it.
		final List<BossRules.ModifierRule> atSplat = tracker.applying(KING_INDEX, rules, KING, "MELEE", 1432, 1433, false,
			nowhere);
		// The axe's 53 (the log's [458]) is 79 in the punish, and the crush defence roll is cut to 15%.
		assertEquals(79, ModifierTracker.max(atThrow, 53));
		final int defenceRoll = (200 + 9) * (45 + 64);
		assertEquals(defenceRoll * 15 / 100, ModifierTracker.defenceRoll(atThrow, "CRUSH", defenceRoll));
		assertEquals(53, ModifierTracker.max(atSplat, 53));
		assertEquals(defenceRoll, ModifierTracker.defenceRoll(atSplat, "CRUSH", defenceRoll));

		// recordAttackObserved marks it a punish; sampleExpected then scores it from the throw's row, not the splat's figures.
		final int thrown = 1432 - PvmPerformancePlugin.MELEE_BOOKING_LAG;
		assertTrue(PvmPerformancePlugin.isPunishTick(Collections.singletonList(1429), thrown,
			RULES.forNpc(KING).meleePunish.afterTicks));
		assertEquals(1431, PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, false, true, true, 1431, 1432));
		final double[] row = new double[PvmPerformancePlugin.EXP_WIDTH];
		row[PvmPerformancePlugin.EXP_MAX] = ModifierTracker.max(atThrow, 53);
		row[PvmPerformancePlugin.EXP_ACCURACY] = 0.9;
		row[PvmPerformancePlugin.EXP_NPC_ID] = KING;
		assertTrue(PvmPerformancePlugin.scoresFromRow(false, false, true, false, row, KING));
		assertEquals(79, (int) row[PvmPerformancePlugin.EXP_MAX]);
		// An ordinary swing is as before: the splat's own tick and figures.
		assertEquals(1432, PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, false, false, true, 1431, 1432));
		assertFalse(PvmPerformancePlugin.scoresFromRow(false, false, false, false, row, KING));
		// No row for the throw, or one for another target: the splat's figures, never nothing.
		assertEquals(1432, PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, false, true, false, 1431, 1432));
		assertFalse(PvmPerformancePlugin.scoresFromRow(false, false, true, false, row, LARVA));
		assertFalse(PvmPerformancePlugin.scoresFromRow(false, false, true, false, null, KING));
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
