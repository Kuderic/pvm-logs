package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-122: while the player is on one titan, the other's splats count on the other.
 * The two are parts of a SEPARATE group, not each other's adds, so T-108's room
 * splats passed them by: in Eric's log of 2026-10-30 22:28
 * ({@code pvm-debug-20261030-222815}, build 4205249), Eldric's burn ticked at
 * [1929], [1933], [1937] and [1941] while the fight was on Branda, unlogged and
 * uncounted, and his kill reconciled at 596 of 600.
 *
 * <p>As {@link BossSplatsOnAnAddTest}, these drive what the splat handler does,
 * in its order: {@link PvmPerformancePlugin#roomBossFight} picks the fight, the
 * kind's total goes on it, and {@link TargetHealth} takes the damage on the
 * part's index, which RECONCILE's "on the target" reads.
 */
public class PartnerPartSplatsTest
{
	private static final int BRANDA = NpcID.RT_FIRE_QUEEN;
	private static final int ELDRIC = NpcID.RT_ICE_KING;
	private static final int BRANDA_INDEX = 33704;
	private static final int ELDRIC_INDEX = 33732;
	private static final String ROOM = "The Royal Titans";

	@Test
	public void theOtherTitansBurnCountsOnHim()
	{
		final Encounter room = new Encounter(ROOM, null, 0L);
		// Eldric's stretch to [1925]: 192 of the player's and 3 of burn, 405 left.
		final Fight eldric = new Fight("Eldric the Ice King", ELDRIC, ELDRIC_INDEX, 600, 0L, null, 1880);
		eldric.recordAttackMade(false);
		eldric.recordDamageDealt(192, 1_000L, true);
		eldric.recordDotDamage(3, 74);
		room.add(eldric);
		eldric.end(false, 2_000L);
		// [1925]: on Branda.
		final Fight branda = new Fight("Branda the Fire Queen", BRANDA, BRANDA_INDEX, 600, 0L, null, 1925);
		branda.recordAttackMade(false);
		branda.recordDamageDealt(46, 3_000L, true);
		room.add(branda);
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(ELDRIC_INDEX, ELDRIC, 600, 1880);
		health.damage(ELDRIC_INDEX, ELDRIC, 195, 1925);
		assertEquals(405, health.remaining(ELDRIC_INDEX, 1925));

		final int brandaDealt = branda.getDamageDealt();
		final int brandaDot = branda.getDotDamage();
		for (int tick = 1929; tick <= 1941; tick += 4)
		{
			final Fight booked = PvmPerformancePlugin.roomBossFight(room, branda, ELDRIC_INDEX, ELDRIC);
			assertSame(eldric, booked);
			booked.recordDotDamage(1, 74);
			health.damage(ELDRIC_INDEX, ELDRIC, 1, tick);
		}
		// [1941]: his bar read 401, and the count now says so too.
		assertEquals(401, health.remaining(ELDRIC_INDEX, 1941));
		assertEquals(7, eldric.getDotDamage());
		// Branda's fight is as it was, and nothing on it is scored or expected from the burn.
		assertEquals(brandaDealt, branda.getDamageDealt());
		assertEquals(brandaDot, branda.getDotDamage());
		// The rest of the kill takes his last 401: 600 counted, where the log's KILL line counted 596.
		health.damage(ELDRIC_INDEX, ELDRIC, 401, 2060);
		assertEquals(600, health.damageCounted(ELDRIC_INDEX));
	}

	@Test
	public void bothWays()
	{
		final Encounter room = new Encounter(ROOM, null, 0L);
		final Fight branda = new Fight("Branda the Fire Queen", BRANDA, BRANDA_INDEX, 600, 0L, null, 1791);
		branda.recordAttackMade(false);
		room.add(branda);
		branda.end(false, 1L);
		final Fight eldric = new Fight("Eldric the Ice King", ELDRIC, ELDRIC_INDEX, 600, 0L, null, 1850);
		eldric.recordAttackMade(false);
		room.add(eldric);
		assertSame(branda, PvmPerformancePlugin.roomBossFight(room, eldric, BRANDA_INDEX, BRANDA));
	}

	@Test
	public void onlyAnotherPartOfASeparateGroup()
	{
		assertTrue(EncounterGroup.isPartnerPart(ELDRIC, BRANDA));
		assertTrue(EncounterGroup.isPartnerPart(BRANDA, ELDRIC));
		// Her beaten form isn't a part to count on, nor are the elementals (adds: T-108's case).
		assertFalse(EncounterGroup.isPartnerPart(NpcID.RT_FIRE_QUEEN_INACTIVE, ELDRIC));
		assertFalse(EncounterGroup.isPartnerPart(NpcID.RT_SUMMON_ELEMENTAL_FIRE, ELDRIC));
		assertFalse(EncounterGroup.isPartnerPart(ELDRIC, NpcID.RT_SUMMON_ELEMENTAL_ICE));
		// A ONE_BOSS group's parts are one boss, its fight already: unchanged.
		assertFalse(EncounterGroup.isPartnerPart(NpcID.SLAYER_KRAKEN_BOSS_WHIRLPOOL, NpcID.SLAYER_KRAKEN_BOSS));
		assertFalse(EncounterGroup.isPartnerPart(NpcID.ARAXXOR, NpcID.ARAXXOR_MINION_MIRRORBACK));
		// An unrelated npc in the scene.
		assertFalse(EncounterGroup.isPartnerPart(NpcID.GOBLIN, ELDRIC));
		assertFalse(EncounterGroup.isPartnerPart(ELDRIC, NpcID.GOBLIN));
	}

	@Test
	public void unrelatedNpcsAndPartsWithNoFightStayUncounted()
	{
		final Encounter room = new Encounter(ROOM, null, 0L);
		final Fight branda = new Fight("Branda the Fire Queen", BRANDA, BRANDA_INDEX, 600, 0L, null, 1791);
		branda.recordAttackMade(false);
		room.add(branda);
		// Eldric not yet fought in this room: nowhere to book him, as T-108 has it.
		assertNull(PvmPerformancePlugin.roomBossFight(room, branda, ELDRIC_INDEX, ELDRIC));
		// Anything else in the scene.
		assertNull(PvmPerformancePlugin.roomBossFight(room, branda, 40000, NpcID.GOBLIN));
		// A Kraken whirlpool while on the Kraken: the same boss, not this path.
		final Encounter kraken = new Encounter("Kraken", null, 0L);
		final Fight boss = new Fight("Kraken", NpcID.SLAYER_KRAKEN_BOSS, 500, 255, 0L, null, 0);
		boss.recordAttackMade(false);
		kraken.add(boss);
		assertNull(PvmPerformancePlugin.roomBossFight(kraken, boss, 501, NpcID.SLAYER_KRAKEN_BOSS_WHIRLPOOL));
	}
}
