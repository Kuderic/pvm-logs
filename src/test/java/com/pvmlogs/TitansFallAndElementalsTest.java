package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.TreeMap;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-121, from Eric's first Royal Titans kill (10:30 log, build 4f6ca3c, read in
 * place, nothing copied): Eldric's fall at [7428] cleared every count, so
 * Branda's kill at [7437] reconciled 57 of 600; and a Twinflame cast at [7242],
 * 3 ticks after the one at [7239], against the staff's 6.
 */
public class TitansFallAndElementalsTest
{
	private static final int BRANDA = 48293;
	private static final int ELDRIC = 48311;

	/** The room as it stood at [7428]: Branda's last fight ended at [7340], Eldric's open. */
	private static final class Room
	{
		final Encounter room = new Encounter("The Royal Titans", null, 0L);
		final Fight branda = new Fight("Branda the Fire Queen", NpcID.RT_FIRE_QUEEN, BRANDA, 600, 0L, null, 7331);
		final Fight eldric = new Fight("Eldric the Ice King", NpcID.RT_ICE_KING, ELDRIC, 600, 0L, null, 7393);

		Room()
		{
			branda.recordAttackMade(false);
			branda.recordDamageDealt(62, 1L, true);
			branda.end(false, 2L);
			room.add(branda);
			eldric.recordAttackMade(false);
			eldric.recordDamageDealt(161, 3L, true);
			room.add(eldric);
		}
	}

	@Test
	public void theFirstTitansFallKeepsTheOthersCount()
	{
		final Room r = new Room();
		// [7428]: Eldric changes into his beaten form. One of two: the room goes on, Branda's count with it.
		assertTrue(PvmPerformancePlugin.separatePartGoesOn(r.room, r.eldric, NpcID.RT_ICE_KING_INACTIVE, ELDRIC, true));
		// Asking doesn't count him down: the room is closed only by the second.
		assertFalse(r.room.wouldCloseOnDeathOf(NpcID.RT_ICE_KING_INACTIVE, ELDRIC, true));
		assertFalse(r.room.closesOnDeathOf(NpcID.RT_ICE_KING_INACTIVE, ELDRIC, true));
		// [7437]: Branda falls, the second. That closes the room, so nothing is kept.
		final Fight last = new Fight("Branda the Fire Queen", NpcID.RT_FIRE_QUEEN, BRANDA, 600, 4L, null, 7429);
		last.recordAttackMade(false);
		r.room.add(last);
		assertTrue(r.room.wouldCloseOnDeathOf(NpcID.RT_FIRE_QUEEN_INACTIVE, BRANDA, true));
		assertFalse(PvmPerformancePlugin.separatePartGoesOn(r.room, last, NpcID.RT_FIRE_QUEEN_INACTIVE, BRANDA, true));
		// Eldric again (the same index) doesn't make a second.
		assertFalse(r.room.wouldCloseOnDeathOf(NpcID.RT_ICE_KING_INACTIVE, ELDRIC, true));
	}

	@Test
	public void herCountCarriesSoHerKillReconcilesAtSixHundred()
	{
		// What closeTargetHealth reads at her kill, with the counts kept: every splat on her index since the room opened.
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(BRANDA, NpcID.RT_FIRE_QUEEN, 600, 7168);
		health.setTableMaxHp(ELDRIC, NpcID.RT_ICE_KING, 600, 7208);
		// Her five fights to [7340]: 215, 69, 90, 107, 62.
		for (int dealt : new int[]{215, 69, 90, 107, 62})
		{
			health.damage(BRANDA, NpcID.RT_FIRE_QUEEN, dealt, 7340);
		}
		health.damage(ELDRIC, NpcID.RT_ICE_KING, 600, 7427);
		assertEquals(543, health.damageCounted(BRANDA));
		// Eldric's fall: only his own count goes (finalizeFight's keepCounts with died).
		health.forget(ELDRIC);
		// [7432] and [7436]: 26 and 31.
		health.damage(BRANDA, NpcID.RT_FIRE_QUEEN, 26, 7432);
		health.damage(BRANDA, NpcID.RT_FIRE_QUEEN, 31, 7436);
		assertEquals(600, health.damageCounted(BRANDA));
		assertEquals(0, health.countedRemaining(BRANDA));
	}

	@Test
	public void anyOtherSeparateGroupKeepsItsPartnersCounts()
	{
		// A vanguard dying in the Chambers, with the others still up: the room goes on (no death rule closes it).
		final Encounter cox = new Encounter("Vanguards", null, 0L);
		final Fight ranged = new Fight("Vanguard", NpcID.RAIDS_VANGUARD_RANGED, 2, 100, 0L, null, 0);
		ranged.recordAttackMade(false);
		cox.add(ranged);
		assertTrue(PvmPerformancePlugin.separatePartGoesOn(cox, ranged, NpcID.RAIDS_VANGUARD_RANGED, 2, false));
		// One boss in parts (Araxxor) isn't a separate group: his death is the room's.
		final Encounter arax = new Encounter("Araxxor", null, 0L);
		final Fight boss = new Fight("Araxxor", NpcID.ARAXXOR, 3, 1020, 0L, null, 0);
		boss.recordAttackMade(false);
		arax.add(boss);
		assertFalse(PvmPerformancePlugin.separatePartGoesOn(arax, boss, NpcID.ARAXXOR_DEAD, 3, true));
		// A room already over keeps nothing.
		cox.end(5L);
		assertFalse(PvmPerformancePlugin.separatePartGoesOn(cox, ranged, NpcID.RAIDS_VANGUARD_RANGED, 2, false));
	}

	@Test
	public void theKillCountGoesToTheRoomsKillNotTheFirstTitan()
	{
		// [7428] Eldric's fight ended at his fall; [7437] the kill count came as Branda's fight was still open.
		final Room r = new Room();
		r.eldric.end(true, 10_000L);
		final Fight brandaLast = new Fight("Branda the Fire Queen", NpcID.RT_FIRE_QUEEN, BRANDA, 600, 10_100L, null, 7429);
		brandaLast.recordAttackMade(false);
		assertSame(brandaLast, PvmPerformancePlugin.killMessageFight(r.eldric, brandaLast, 15_400L));
		// Once hers has closed the room, it's hers.
		brandaLast.end(true, 15_400L);
		brandaLast.closeRoom();
		assertSame(brandaLast, PvmPerformancePlugin.killMessageFight(brandaLast, null, 15_500L));
		// A boss that stands alone still takes it on its death.
		final Fight vard = new Fight("Vardorvis", NpcID.VARDORVIS, 1, 700, 0L, null, 0);
		vard.recordAttackMade(false);
		vard.end(true, 1_000L);
		assertSame(vard, PvmPerformancePlugin.killMessageFight(vard, null, 2_000L));
		// With nothing open, the last fight to end on its target's death takes it, grouped or not.
		assertSame(r.eldric, PvmPerformancePlugin.killMessageFight(r.eldric, null, 15_400L));
		assertNull(PvmPerformancePlugin.killMessageFight(r.eldric, null, 10_000L + PvmPerformancePlugin.KILL_MESSAGE_MILLIS
			+ 1));
	}

	@Test
	public void aGroupedBossKeepsAKillMessageThatComesBeforeItsLoot()
	{
		// The Abyssal Sire's room closes on its loot (T-055): between the death and the drop its fight has ended on the
		// death, the room is still open, and no fight is open. A kill message in that gap is the Sire's.
		final Encounter room = new Encounter("Abyssal Sire", null, 0L);
		final Fight sire = new Fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_APOCALYPSE, 7, 400, 0L, null, 0);
		sire.recordAttackMade(false);
		sire.recordDamageDealt(400, 1L, true);
		room.add(sire);
		sire.end(true, 20_000L);
		assertTrue(sire.getGroupName() != null);
		assertFalse(sire.isClosedRoom());
		assertSame(sire, PvmPerformancePlugin.killMessageFight(sire, null, 21_200L));
		// A fight on a spawn opened after the death and still open takes it instead: the death was an add's for all we know.
		final Fight spawn = new Fight("Spawn", NpcID.ABYSSALSIRE_SPAWN, 8, 10, 20_500L, null, 0);
		spawn.recordAttackMade(false);
		assertSame(spawn, PvmPerformancePlugin.killMessageFight(sire, spawn, 21_200L));
		// A fight that ended without its target dying never takes it.
		final Fight left = new Fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_APOCALYPSE, 7, 400, 0L, null, 0);
		left.recordAttackMade(false);
		left.end(false, 20_000L);
		assertNull(PvmPerformancePlugin.killMessageFight(left, null, 21_200L));
	}

	@Test
	public void eachElementalKilledBringsTheNextCastATickSooner()
	{
		assertTrue(PvmPerformancePlugin.HASTENS_ON_KILL.contains(NpcID.RT_SUMMON_ELEMENTAL_ICE));
		assertTrue(PvmPerformancePlugin.HASTENS_ON_KILL.contains(NpcID.RT_SUMMON_ELEMENTAL_FIRE));
		assertFalse(PvmPerformancePlugin.HASTENS_ON_KILL.contains(NpcID.RT_FIRE_QUEEN));
		// [7239] a Fire Wave from the staff (speed 6), seen at [7241] as its splats killed three ice elementals.
		final TreeMap<Integer, Integer> kills = new TreeMap<>();
		kills.put(7241, 3);
		final int due = PvmPerformancePlugin.dueAfter(false, 7239, 7239, CombatCalc.TWINFLAME_CAST_TICKS)
			- PvmPerformancePlugin.hasteSince(kills, 7239);
		assertEquals(7242, due);
		// So the cast at [7242], its XP on that tick, is no contradiction: the weapon was free.
		assertFalse(7242 < due);
		// And its projectile, set off on [7244] with the last attack seen on [7241], could be mine.
		assertFalse(PvmPerformancePlugin.tooSoonToBeMine(7244, 7241,
			Math.max(1, 4 - PvmPerformancePlugin.hasteSince(kills, 7241))));
		// Kills before the attack went out are another attack's.
		assertEquals(0, PvmPerformancePlugin.hasteSince(kills, 7242));
		assertEquals(0, PvmPerformancePlugin.hasteSince(kills, Integer.MIN_VALUE));
		// The staff's speed stays 6 (T-086): elsewhere every Twinflame-to-Twinflame gap in Eric's logs is 6.
		assertEquals(6, CombatCalc.TWINFLAME_CAST_TICKS);
	}
}
