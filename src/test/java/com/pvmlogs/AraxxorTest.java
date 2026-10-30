package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.StringReader;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * T-091: Araxxor is one room per kill with his araxytes scored in it, closed as
 * he becomes his corpse, and his enrage below 255 hitpoints adds the DPS calc's
 * Phases.ts levels to what the player's attacks are rolled against.
 *
 * <p>The kill replayed is Eric's of 2026-10-29 ({@code pvm-debug-20261029-171127},
 * [7487]–[7737]), from the counts its fight END and RECONCILE lines give, not
 * its lines: four fights on Araxxor and three on araxytes, on that build each in
 * a room of its own.
 */
public class AraxxorTest
{
	private static final int ARAXXOR = NpcID.ARAXXOR;
	private static final int INDEX = 41940;
	private static final String NAME = "Araxxor";

	/** The DPS calc's Araxxor entry (monsters.json, Eric's cache of 2026-10-26), cut to what is read. */
	private static final String MONSTERS = "[{\"id\": 13668, \"name\": \"Araxxor\", \"version\": \"\", \"size\": 7,"
		+ " \"skills\": {\"atk\": 320, \"def\": 135, \"hp\": 1020, \"magic\": 190, \"ranged\": 210, \"str\": 320},"
		+ " \"offensive\": {\"atk\": 218, \"magic\": 260, \"magic_str\": 5, \"ranged\": 283, \"ranged_str\": 35, \"str\": 10},"
		+ " \"defensive\": {\"flat_armour\": 0, \"crush\": 15, \"magic\": 237, \"heavy\": 218, \"standard\": 218,"
		+ " \"light\": 218, \"slash\": 75, \"stab\": 160}, \"attributes\": [], \"weakness\": {\"element\": \"fire\","
		+ " \"severity\": 50}}]";

	private static BossRules rules;
	private static MonsterStatsProvider.Parsed data;

	@BeforeClass
	public static void load()
	{
		final Gson gson = new Gson();
		rules = BossRules.load(gson, null);
		data = MonsterStatsProvider.parse(gson, new StringReader(MONSTERS), rules);
	}

	private static BossRules.DamageModifierRules modifiers()
	{
		final BossRules.Boss boss = rules.forNpc(ARAXXOR);
		assertNotNull(boss);
		assertNotNull(boss.damageModifiers);
		return boss.damageModifiers;
	}

	private static List<BossRules.ModifierRule> applying(ModifierTracker tracker, int thrown, int lands)
	{
		return tracker.applying(INDEX, modifiers(), ARAXXOR, "MELEE", thrown, lands, false, (id, range) -> false);
	}

	// ---- the group ----

	@Test
	public void araxxorHisAraxytesTheirEggsAndHisCorpseAreOneGroup()
	{
		for (int id : new int[]{ARAXXOR, NpcID.ARAXXOR_DEAD, NpcID.ARAXXOR_MINION_MIRRORBACK,
			NpcID.ARAXXOR_MINION_EXPLODE, NpcID.ARAXXOR_MINION_VENOM, NpcID.ARAXXOR_MINION_EGG_MIRRORBACK,
			NpcID.ARAXXOR_MINION_EGG_EXPLODE, NpcID.ARAXXOR_MINION_EGG_VENOM})
		{
			assertEquals(NAME, EncounterGroup.of(id));
		}
		// The ids the log shows: 13671 Mirrorback, 13675 Acidic, 13673 Ruptura, 13669 the corpse.
		assertEquals(13671, NpcID.ARAXXOR_MINION_MIRRORBACK);
		assertEquals(13675, NpcID.ARAXXOR_MINION_VENOM);
		assertEquals(13673, NpcID.ARAXXOR_MINION_EXPLODE);
		assertEquals(13669, NpcID.ARAXXOR_DEAD);
		// Not the Morytania spider cave's araxytes, nor the superior.
		assertNull(EncounterGroup.of(NpcID.ARAXYTE_NORMAL));
		assertNull(EncounterGroup.of(NpcID.SUPERIOR_ARAXYTE));
	}

	@Test
	public void theAraxytesAreScoredAddsAndTheCorpseIsHisBeatenForm()
	{
		for (int id : new int[]{NpcID.ARAXXOR_MINION_MIRRORBACK, NpcID.ARAXXOR_MINION_EXPLODE,
			NpcID.ARAXXOR_MINION_VENOM})
		{
			assertFalse(EncounterGroup.isUnscored(id));
			assertFalse(EncounterGroup.lootIsTheKill(id));
			// An araxyte dying is not Araxxor's kill, and its fight is its own.
			assertFalse(EncounterGroup.otherFallEndsFight(id, ARAXXOR));
			assertFalse(EncounterGroup.addKeepsBossFight(id, ARAXXOR));
		}
		assertTrue(EncounterGroup.lootIsTheKill(ARAXXOR));
		assertTrue(EncounterGroup.isDefeated(NpcID.ARAXXOR_DEAD));
		assertTrue(EncounterGroup.isUnattackable(NpcID.ARAXXOR_DEAD));
		assertFalse(EncounterGroup.isDefeated(ARAXXOR));
		// His corpse despawning with a fight open on an araxyte left alive is not that araxyte's kill.
		assertFalse(EncounterGroup.otherFallEndsFight(NpcID.ARAXXOR_DEAD, NpcID.ARAXXOR_MINION_VENOM));
		// A boss, not a run of monsters (T-089).
		assertFalse(MonsterRooms.isMonster("Acidic Araxyte", NpcID.ARAXXOR_MINION_VENOM, false, rules));
	}

	// ---- the kill ----

	/**
	 * Eric's kill, by its fights: Araxxor [7487]–[7524], an Acidic araxyte to
	 * [7541] (with two axe hits on Araxxor that the booking took as splash),
	 * Araxxor to [7557] (an axe hit's 20% the Mirrorback takes), the Mirrorback
	 * to [7564], Araxxor to [7632], an Acidic to [7640], and Araxxor until he
	 * becomes his corpse at [7704]. The damage on him is each segment's from its
	 * RECONCILE: the weapon's, the thrall's and the rest.
	 */
	private static MonsterRunReplay theKill()
	{
		final MonsterRunReplay run = new MonsterRunReplay();
		run.fight(NAME, ARAXXOR, INDEX, 1020);
		for (int i = 0; i < 7; i++)
		{
			run.attack();
		}
		run.hit(241);
		run.fight("Acidic Araxyte", NpcID.ARAXXOR_MINION_VENOM, 42415, 58).attack().attack().attack().hit(58)
			.splash(NAME, INDEX, 23).splash(NAME, INDEX, 15);
		run.dies(42415);
		run.fight(NAME, ARAXXOR, INDEX, 1020).attack().hit(41).counted(INDEX, 4)
			.splash("Mirrorback Araxyte", 43446, 10);
		run.fight("Mirrorback Araxyte", NpcID.ARAXXOR_MINION_MIRRORBACK, 43446, 58).attack().attack().hit(36)
			.counted(43446, 2);
		run.dies(43446);
		run.fight(NAME, ARAXXOR, INDEX, 1020);
		for (int i = 0; i < 12; i++)
		{
			run.attack();
		}
		// The weapon's 326, the thrall's 24, burn's 10, and the Ruptura's explosion of 80 (see the PR).
		run.hit(326).counted(INDEX, 24 + 10 + 80);
		run.fight("Acidic Araxyte", NpcID.ARAXXOR_MINION_VENOM, 45114, 58).attack().attack().hit(58).counted(45114, 2);
		run.dies(45114);
		run.fight(NAME, ARAXXOR, INDEX, 1020);
		for (int i = 0; i < 8; i++)
		{
			run.attack();
		}
		// The weapon's 212, of it 17 a second Mirrorback took off it, the thrall's 12, and his enraged cleave hitting
		// himself, 47 over five splats.
		run.hit(195).splash("Mirrorback Araxyte", 46063, 17).counted(INDEX, 12 + 47);
		run.becomes(INDEX, NpcID.ARAXXOR_DEAD);
		return run;
	}

	@Test
	public void theKillIsOneRoomWithItsAraxytesScoredInIt()
	{
		final MonsterRunReplay run = theKill();
		assertEquals(1, run.fought().size());
		final Encounter room = run.room;
		assertEquals(NAME, room.getName());
		assertEquals(7, room.getFights().size());
		int adds = 0;
		for (Fight fight : room.getFights())
		{
			assertTrue(fight.isScored());
			adds += fight.getTargetId() == ARAXXOR ? 0 : 1;
		}
		assertEquals(3, adds);
		// Closed as he became his corpse: the kill, its last fight carrying the boundary.
		assertTrue(room.isEnded());
		assertTrue(room.isKilled());
		assertTrue(room.getFights().get(6).isClosedRoom());
		assertTrue(room.getFights().get(6).isTargetDied());
		// History rebuilds it as one room too.
		assertEquals(1, LedgerHistory.rooms(run.history, NAME).size());
	}

	@Test
	public void hisCountsCarryAcrossTheSegmentsSoTheKillsReconcileAddsUp()
	{
		final MonsterRunReplay run = theKill();
		// On that build every switch and every araxyte's death cleared them: the last fight's RECONCILE read 254 of his
		// 1,020. Carried, they are everything on him. The log's splats on him come to 1,020 exactly: this replay has all
		// but the thrall's 2 during the second Acidic fight, which the log can't say were counted against him.
		assertEquals(241 + 23 + 15 + 41 + 4 + 326 + 24 + 10 + 80 + 195 + 12 + 47, run.lastCounted);
		assertEquals(1018, run.lastCounted);
	}

	@Test
	public void anAddDyingKeepsWhatTheBossHasLeft()
	{
		final Encounter room = new Encounter(NAME, null, 0);
		final Fight onBoss = new Fight(NAME, ARAXXOR, INDEX, 1020, 0, null, 0);
		onBoss.recordAttackMade(false);
		room.add(onBoss);
		final Fight onAdd = new Fight("Acidic Araxyte", NpcID.ARAXXOR_MINION_VENOM, 2, 58, 0, null, 0);
		room.add(onAdd);
		assertTrue(PvmPerformancePlugin.roomGoesOnAtDeath(room, onAdd, NpcID.ARAXXOR_MINION_VENOM));
		// The boss's own death is the kill: it keeps nothing.
		assertFalse(PvmPerformancePlugin.roomGoesOnAtDeath(room, onBoss, ARAXXOR));
		// An ungrouped boss's neither.
		assertFalse(PvmPerformancePlugin.roomGoesOnAtDeath(room, new Fight("Vorkath", 8061, 3, 750, 0, null, 0), 8061));
	}

	// ---- the enrage ----

	@Test
	public void theEnrageOpensOnHisTransitionAndStays()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		assertTrue(applying(tracker, 8029, 8030).isEmpty());
		// NPC_ARAXXOR_01_ENRAGE_TRANSITION_01, at [8030] of the second attempt.
		tracker.onBossAnim(INDEX, NAME, modifiers(), 11488, 8030);
		assertEquals(1, applying(tracker, 8030, 8031).size());
		assertEquals("enraged", applying(tracker, 8030, 8031).get(0).name);
		// Still on long after: it lasts the rest of the fight.
		assertEquals(1, applying(tracker, 9000, 9001).size());
		// Never live (the Jagex rule): the overlay's figures go on as before.
		assertTrue(ModifierTracker.shownLive(applying(tracker, 8030, 8031)).isEmpty());
		// His ordinary melee doesn't open it, and the enraged cleave does when the transition was missed.
		final ModifierTracker missed = new ModifierTracker(null);
		missed.onBossAnim(INDEX, NAME, modifiers(), 11480, 7626);
		assertTrue(applying(missed, 7627, 7628).isEmpty());
		missed.onBossAnim(INDEX, NAME, modifiers(), 11487, 7649);
		assertEquals(1, applying(missed, 7650, 7651).size());
	}

	@Test
	public void theEnragedLevelsAreThePhasesTsOnes()
	{
		final ModifierTracker tracker = new ModifierTracker(null);
		tracker.onBossAnim(INDEX, NAME, modifiers(), 11488, 100);
		final int[] levels = ModifierTracker.levels(applying(tracker, 100, 101));
		assertEquals(35, levels[0]);
		assertEquals(28, levels[1]);
		assertEquals(31, levels[2]);
		final MonsterStatsProvider.MonsterStats base = data.versionsById.get(ARAXXOR).get(0);
		final MonsterStatsProvider.MonsterStats enraged = base.withLevels(levels[0], levels[1], levels[2]);
		// The wiki's infobox: Defence 135 → 170, Magic 190 → 218, Ranged 210 → 241.
		assertEquals(170, enraged.getDefenceLevel());
		assertEquals(218, enraged.getMagicLevel());
		assertEquals(241, enraged.getRangedLevel());
		assertEquals(base.getDefSlash(), enraged.getDefSlash());
		assertEquals(1020, enraged.getHitpoints());
		// No levels, no copy.
		assertTrue(base == base.withLevels(0, 0, 0));
	}

	/** By hand: the Zombie axe's slash against his +75, at the roll Eric's 0.883 implies, normal and enraged. */
	@Test
	public void theEnrageAgainstHandWorkedAccuracy()
	{
		final int normal = (135 + 9) * (75 + 64);
		final int enraged = (170 + 9) * (75 + 64);
		assertEquals(20016, normal);
		assertEquals(24881, enraged);
		// The attack roll the log's 0.883 implies: 1 - (D + 2) / (2 (A + 1)) = 0.883.
		final int attack = 85545;
		assertEquals(0.883, CombatCalc.hitChanceFrom(attack, normal), 0.0005);
		assertEquals(1 - 24883.0 / 171092, CombatCalc.hitChanceFrom(attack, enraged), 1e-12);
		assertEquals(0.855, CombatCalc.hitChanceFrom(attack, enraged), 0.0005);
		// A drain carries over (wiki): 30 off the enraged 170 rolls as 140.
		assertEquals((140 + 9) * (75 + 64), (170 - 30 + 9) * (75 + 64));
	}

	@Test
	public void araxxorNoLongerWarnsTheEnrageIsUntracked()
	{
		assertNull(rules.forNpc(ARAXXOR).unmodelled);
		assertNull(data.versionsById.get(ARAXXOR).get(0).getWarning());
	}
}
