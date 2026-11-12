package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-220 (Eric, 2026-11-12: "Scarab should not show as a boss"): the Path of Scabaras puzzle's scarabs (11697, 12 hp)
 * opened a fight and a room "Scarab" in his Tombs raids ({@code pvm-debug-20261111-231111.log}, [4964] and [10741], read
 * in place); his history held 9 such fights, none in records.json.
 */
public class RaidTrashTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);

	private static PvmPerformancePlugin.Swat swat(boolean inRaid, int id, int hp, boolean bossFightOpen)
	{
		return PvmPerformancePlugin.weakAddSwat(inRaid, id, false, hp, bossFightOpen);
	}

	@Test
	public void aTwelveHpScarabInTheTombsOpensNoFight()
	{
		assertTrue(EncounterGroup.isRaidTrash(true, NpcID.TOA_SCABARAS_SCARAB, 12));
		assertEquals(PvmPerformancePlugin.Swat.NO_FIGHT, swat(true, NpcID.TOA_SCABARAS_SCARAB, 12, false));
		// The 27 and 28 of older logs too.
		assertEquals(PvmPerformancePlugin.Swat.NO_FIGHT, swat(true, NpcID.TOA_SCABARAS_SCARAB, 28, false));
		// Swatted in a boss's fight, it stays in that fight, as T-180 has any weak monster.
		assertEquals(PvmPerformancePlugin.Swat.IN_FIGHT, swat(true, NpcID.TOA_SCABARAS_SCARAB, 12, true));
		// Outside a raid, not the raid's trash: what T-180 does with it (nothing, as it's no listed add or monster here).
		assertFalse(EncounterGroup.isRaidTrash(false, NpcID.TOA_SCABARAS_SCARAB, 12));
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(false, NpcID.TOA_SCABARAS_SCARAB, 12, false));
		// Stronger than 40: not trash.
		assertFalse(EncounterGroup.isRaidTrash(true, NpcID.TOA_SCABARAS_SCARAB, 41));
	}

	@Test
	public void aWeakMonsterInAGroupWorksAsT180HasIt()
	{
		// Kephri's eggs, a listed add: no fight with no boss fight open, in it with one.
		assertFalse(EncounterGroup.isRaidTrash(true, NpcID.KEPHRI_EGG_HATCH, 35));
		assertEquals(PvmPerformancePlugin.Swat.NO_FIGHT, swat(true, NpcID.KEPHRI_EGG_HATCH, 35, false));
		assertEquals(PvmPerformancePlugin.Swat.IN_FIGHT, swat(true, NpcID.KEPHRI_EGG_HATCH, 35, true));
		// Kephri's guardians scale with the raid (T-184): fights.
		assertFalse(EncounterGroup.isRaidTrash(true, NpcID.TOA_KEPHRI_GUARDIAN_MELEE, 40));
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(true, NpcID.TOA_KEPHRI_GUARDIAN_MELEE, 40, false));
		// The Scavenger beast, as before.
		assertEquals(PvmPerformancePlugin.Swat.NO_FIGHT, swat(true, NpcID.RAIDS_SCAVENGER_BEAST_A, 30, false));
	}

	@Test
	public void theBaboonsRoomIsUnchanged()
	{
		// A group: its 4-hp thrall baboon is no trash and still a fight, with a boss fight open or not.
		for (int id : new int[]{NpcID.TOA_PATH_APMEKEN_BABOON_THRALL, NpcID.TOA_PATH_APMEKEN_BABOON_MELEE_1,
			NpcID.TOA_PATH_APMEKEN_BABOON_CURSED})
		{
			assertEquals("Baboons", EncounterGroup.of(id));
			assertFalse(EncounterGroup.isRaidTrash(true, id, 4));
			assertEquals(PvmPerformancePlugin.Swat.NONE, swat(true, id, 4, false));
		}
	}

	@Test
	public void strongerMonstersOutsideTheGroupsAreLeftAsTheyAre()
	{
		// The ungrouped raid npcs with real hitpoints in Eric's logs and history are bosses or have their own rules: Ba-Ba,
		// the Great Olm's head, the Chambers' Ice demon. None is trash; each keeps its room.
		assertFalse(EncounterGroup.isRaidTrash(true, NpcID.TOA_BABA, 870));
		assertFalse(EncounterGroup.isRaidTrash(true, NpcID.OLM_HEAD, 800));
		assertFalse(EncounterGroup.isRaidTrash(true, 7584, 140));
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(true, NpcID.OLM_HEAD, 800, false));
		assertEquals(PvmPerformancePlugin.Swat.NONE, swat(true, 7584, 140, false));
		// Why no "other" for them: the Olm's head is in no group and no rule, and not a boss by name either, so a monster
		// test can't tell it from a puzzle monster.
		assertEquals(null, EncounterGroup.of(NpcID.OLM_HEAD));
		assertTrue(MonsterRooms.isMonster("Great Olm", NpcID.OLM_HEAD, false, RULES));
	}

	private static Fight fight(String name, int id, int hp, long start, RaidType raid, int damage)
	{
		final Fight f = new Fight(name, id, 1, hp, start, raid, 1);
		f.recordAttackMade(false, 0.9);
		f.recordExpected(Math.min(hp, 45), 0.6, Math.min(hp, 14), 180);
		f.recordAttackResolved(true, true, 0.97, 1.0, 0.01, 0);
		f.recordTickSpent();
		f.recordDamageDealt(damage, start + 2400L, true);
		f.end(true, start + 2400L);
		return f;
	}

	@Test
	public void anOldScarabFightDoesntShowAsABossInHistory()
	{
		final List<Fight> history = new ArrayList<>();
		// Newest first: a Kephri kill, the puzzle's scarab before it, Zebak before that.
		history.add(fight("Kephri", NpcID.TOA_KEPHRI_BOSS_WEAK, 1520, 600_000L, RaidType.TOMBS_OF_AMASCUT, 1520));
		history.add(fight("Scarab", NpcID.TOA_SCABARAS_SCARAB, 12, 300_000L, RaidType.TOMBS_OF_AMASCUT, 12));
		history.add(fight("Zebak", NpcID.TOA_ZEBAK, 1100, 100_000L, RaidType.TOMBS_OF_AMASCUT, 1100));
		final List<String> names = new ArrayList<>();
		for (SidePanelHistory.BossEntry e : SidePanelHistory.bosses(history, false, ""))
		{
			names.add(e.name);
		}
		assertTrue(names.toString(), names.contains("Kephri"));
		assertFalse(names.toString(), names.contains("Scarab"));
		assertTrue(names.toString(), names.contains("Zebak"));
		// Kept in the history itself, and out of the records (raid fights never go in: BossRecords.figures).
		assertEquals(3, history.size());
		final BossRecords records = new BossRecords(null, new Gson());
		records.noteHistory(history, 0);
		assertEquals(null, records.view(new LedgerHistory.Like("Scarab", 12, true)));
		// A scarab outside a raid would be a room as before: only a raid's.
		assertFalse(SidePanelHistory.isRaidTrash(SidePanelHistory.allRooms(java.util.Collections.singletonList(
			fight("Scarab", NpcID.TOA_SCABARAS_SCARAB, 12, 0L, null, 12))).get(0)));
	}
}
