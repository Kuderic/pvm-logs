package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import net.runelite.api.Skill;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-136, from Eric's first Yama log (build 2d9270f, read in place, nothing
 * copied): four kills, each split at a Void Flare, which wasn't in Yama's
 * group, so attacking one ended his room and the flare's own one-kill room
 * took the kill message (kill 1 saved as a 16-tick kill); the Judge of Yama
 * unscored; a magic cast booked from its splat three ticks after its throw,
 * against a booking lag of two, losing a tick on every cast.
 */
public class YamaTest
{
	private static Fight fight(String name, int npcId, int index, int hp, long start)
	{
		final Fight f = new Fight(name, npcId, index, hp, start, null, 0);
		f.recordAttackMade(false);
		return f;
	}

	@Test
	public void aFlareAttackedMidKillKeepsTheRoom()
	{
		assertEquals("Yama", EncounterGroup.of(NpcID.YAMA_VOIDFLARE));
		assertEquals("Yama", EncounterGroup.of(NpcID.YAMA_JUDGE_OF_YAMA));
		assertEquals("Yama", EncounterGroup.of(NpcID.YAMA));
		final Encounter room = new Encounter("Yama", null, 0L);
		final Fight yama = fight("Yama", NpcID.YAMA, 60992, 2500, 0L);
		room.add(yama);
		// [7943]: Eric turned on a flare; it was a room of its own and Yama's room ended there.
		final Fight flare = fight("Void Flare", NpcID.YAMA_VOIDFLARE, 61001, 20, 1_000L);
		assertTrue(PvmPerformancePlugin.switchesWithinRoom(room, flare, NpcID.YAMA_VOIDFLARE));
		room.add(flare);
		final Fight judge = fight("Judge of Yama", NpcID.YAMA_JUDGE_OF_YAMA, 61002, 400, 2_000L);
		assertTrue(PvmPerformancePlugin.switchesWithinRoom(room, judge, NpcID.YAMA_JUDGE_OF_YAMA));
		room.add(judge);
		// The flare's death and the Judge's don't close it.
		assertFalse(room.closesOnDeathOf(NpcID.YAMA_VOIDFLARE, 61001, false));
		assertFalse(room.closesOnDeathOf(NpcID.YAMA_JUDGE_OF_YAMA, 61002, false));
		assertFalse(room.isEnded());
		// Yama drops no loot event (none in four kills), so his death closes it.
		assertTrue(room.closesOnDeathOf(NpcID.YAMA, 60992, false));
		// Back on Yama after the flare: still his room.
		final Fight again = fight("Yama", NpcID.YAMA, 60992, 2500, 3_000L);
		final Encounter open = new Encounter("Yama", null, 0L);
		open.add(yama);
		open.add(flare);
		assertTrue(PvmPerformancePlugin.switchesWithinRoom(open, again, NpcID.YAMA));
	}

	@Test
	public void theKillMessageWithTheFlareDeadLastIsYamas()
	{
		final Encounter room = new Encounter("Yama", null, 0L);
		final Fight yama = fight("Yama", NpcID.YAMA, 60992, 2500, 0L);
		yama.recordDamageDealt(229, 1L, true);
		room.add(yama);
		final Fight flare = fight("Void Flare", NpcID.YAMA_VOIDFLARE, 61001, 20, 9_000L);
		room.add(flare);
		flare.end(true, 10_000L);
		// [7968]: the kill count came with the flare the last fight to end on its target's death, Yama's still open.
		assertSame(yama, PvmPerformancePlugin.killMessageFight(flare, yama, 10_600L, yama));
		// With Yama's fight ended too, the flare dying last: still his.
		yama.end(true, 10_300L);
		assertSame(yama, PvmPerformancePlugin.killMessageFight(flare, null, 10_600L, yama));
		// The boss's own fight is picked as before; with no boss fight in the room, nothing changes.
		assertSame(yama, PvmPerformancePlugin.killMessageFight(yama, null, 10_600L, yama));
		assertSame(flare, PvmPerformancePlugin.killMessageFight(flare, null, 10_600L, null));
		// Nothing picked stays nothing: the room's boss doesn't take a message no fight would.
		assertEquals(null, PvmPerformancePlugin.killMessageFight(null, null, 10_600L, yama));
	}

	@Test
	public void theJudgeAndTheFlaresAreScoredAdds()
	{
		assertTrue(fight("Judge of Yama", NpcID.YAMA_JUDGE_OF_YAMA, 1, 400, 0L).isScored());
		assertTrue(fight("Judge of Yama", NpcID.LEAGUE6_JUDGE_OF_YAMA, 1, 400, 0L).isScored());
		assertTrue(fight("Void Flare", NpcID.YAMA_VOIDFLARE, 2, 20, 0L).isScored());
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.YAMA_JUDGE_OF_YAMA));
		assertFalse(EncounterGroup.lootIsTheKill(NpcID.YAMA_VOIDFLARE));
		assertTrue(EncounterGroup.lootIsTheKill(NpcID.YAMA));
	}

	@Test
	public void magicBookedThreeTicksLateDoesNotLoseATick()
	{
		// A 5-tick staff: thrown on 0, the next due on 5. The cast goes out on 5 (its Magic XP that tick) and its splat
		// books it on 8 (+3), past the lag of 2 the lost-tick check waits for: on 7 it was LOST, every cast.
		final int due = 5;
		final int lag = 2;
		final int xp = 5;
		assertTrue(PvmPerformancePlugin.attackOverdue(7, due, lag));
		assertTrue(PvmPerformancePlugin.throwProvenByXp(7, due, xp, 5));
		// A cast thrown the tick before it was due (a 4-tick cast, seen 31 times in 223) proves it too.
		assertTrue(PvmPerformancePlugin.throwProvenByXp(7, due, 4, 5));
		// The XP of the attack before this one proves nothing.
		assertFalse(PvmPerformancePlugin.throwProvenByXp(7, due, 0, 5));
	}

	@Test
	public void aRealGapStillLosesTicks()
	{
		final int due = 5;
		// No XP since the last throw: idle, as before.
		assertFalse(PvmPerformancePlugin.throwProvenByXp(7, due, Integer.MIN_VALUE, 5));
		// A cast on 5 that is never booked proves only its own 5 ticks: from 10 on, with no further XP, ticks are lost.
		assertTrue(PvmPerformancePlugin.throwProvenByXp(9, due, 5, 5));
		assertFalse(PvmPerformancePlugin.throwProvenByXp(10, due, 5, 5));
		// XP in the future (not yet seen) proves nothing.
		assertFalse(PvmPerformancePlugin.throwProvenByXp(7, due, 8, 5));
	}

	@Test
	public void theJudgeDespawningAtZeroDied()
	{
		// [7712] the Judge's count reached 0; [7717] it despawned without being flagged dead, held 11 ticks as out of view.
		assertTrue(PvmPerformancePlugin.diedOnDespawn(false, 0));
		assertTrue(PvmPerformancePlugin.diedOnDespawn(true, -1));
		assertFalse(PvmPerformancePlugin.diedOnDespawn(false, 120));
		// Not the fight's target: nothing known, so not dead.
		assertFalse(PvmPerformancePlugin.diedOnDespawn(false, -1));
	}

	@Test
	public void magicAloneDecodedToNothingIsASplash()
	{
		// [7633]: Magic +43, no Hitpoints, decoded 0-0 - a cast with no hitsplat.
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.MAGIC, 43);
		final XpLedger.Award splash = ledger.close(7633, NpcID.YAMA, 1.825, null);
		assertTrue(splash != null && splash.splash());
		// A cast that hit pays Hitpoints too, and is no splash.
		ledger.add(Skill.MAGIC, 120);
		ledger.add(Skill.HITPOINTS, 53);
		final XpLedger.Award hit = ledger.close(7640, NpcID.YAMA, 1.825, null);
		assertTrue(hit != null && !hit.splash());
	}

	@Test
	public void aSplatPaidByMagicIsACastWhateverIsInHand()
	{
		// [7694]: a Purging staff cast at the Judge landed three ticks after its Magic drop, the Emberlight back in hand.
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.MAGIC, 120);
		ledger.add(Skill.HITPOINTS, 53);
		ledger.close(10, NpcID.YAMA_JUDGE_OF_YAMA, 1.0, null);
		assertTrue(ledger.paidAsShot(40, 13));
		// A melee blow paid by Strength isn't.
		final XpLedger melee = new XpLedger();
		melee.add(Skill.STRENGTH, 160);
		melee.add(Skill.HITPOINTS, 53);
		melee.close(10, NpcID.YAMA_JUDGE_OF_YAMA, 1.0, null);
		assertFalse(melee.paidAsShot(40, 11));
		// Nothing paid for it: no.
		assertFalse(new XpLedger().paidAsShot(40, 11));
	}
}
