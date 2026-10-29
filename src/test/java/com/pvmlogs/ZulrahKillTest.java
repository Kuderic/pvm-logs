package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-084: a Zulrah kill is one room and one fight across its three forms, the
 * snakelings unscored adds that never end it, its hitpoints carried, its loot
 * closing the room; a recoil on a one-hitpoint snakeling is a recoil; and its
 * dives are a proposed uptime allowance. Synthetic, in the shape of Eric's kill
 * of 2026-10-29 ([5059]-[5269] of the 06:12 log, nothing copied).
 */
public class ZulrahKillTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int SERPENTINE = NpcID.SNAKEBOSS_BOSS_RANGED;
	private static final int MAGMA = NpcID.SNAKEBOSS_BOSS_MELEE;
	private static final int TANZANITE = NpcID.SNAKEBOSS_BOSS_MAGIC;
	private static final int SNAKELING_MELEE = NpcID.SNAKEBOSS_MINION_MELEE;
	private static final int SNAKELING_MAGIC = NpcID.SNAKEBOSS_MINION_MAGIC;
	private static final int SNAKELING_DYING = NpcID.SNAKEBOSS_MINION_DYING;

	@Test
	public void theIdsAreTheGamevalsAndTheLogs()
	{
		// 2042-2044 and 2045/2046 are the ids the log shows; 2047 is the gamevals' dying snakeling.
		assertEquals(2042, SERPENTINE);
		assertEquals(2043, MAGMA);
		assertEquals(2044, TANZANITE);
		assertEquals(2045, SNAKELING_MELEE);
		assertEquals(2046, SNAKELING_MAGIC);
		assertEquals(2047, SNAKELING_DYING);
	}

	@Test
	public void zulrahIsOneGroupWithItsSnakelingsAsUnscoredAdds()
	{
		for (int id : new int[]{SERPENTINE, MAGMA, TANZANITE, SNAKELING_MELEE, SNAKELING_MAGIC, SNAKELING_DYING})
		{
			assertEquals("npc " + id, "Zulrah", EncounterGroup.of(id));
		}
		for (int form : new int[]{SERPENTINE, MAGMA, TANZANITE})
		{
			assertFalse(EncounterGroup.isUnscored(form));
			assertTrue("Zulrah's own drop is the kill", EncounterGroup.lootIsTheKill(form));
		}
		for (int add : new int[]{SNAKELING_MELEE, SNAKELING_MAGIC, SNAKELING_DYING})
		{
			assertTrue(EncounterGroup.isUnscored(add));
			assertFalse(EncounterGroup.lootIsTheKill(add));
			// A snakeling dying ends nothing but a fight on itself.
			assertFalse(EncounterGroup.otherFallEndsFight(add, SERPENTINE));
		}
		// Its forms are one boss.
		assertTrue(EncounterGroup.otherFallEndsFight(MAGMA, SERPENTINE));
	}

	@Test
	public void aSnakelingLeavesZulrahsFightOpen()
	{
		// The log's [5155]: a shot at a snakeling ended Zulrah's fight as "a different target was attacked".
		for (int form : new int[]{SERPENTINE, MAGMA, TANZANITE})
		{
			assertTrue(EncounterGroup.addKeepsBossFight(SNAKELING_MELEE, form));
			assertTrue(EncounterGroup.addKeepsBossFight(SNAKELING_MAGIC, form));
		}
		// Not the other way round, not between forms, and not for other bosses' adds.
		assertFalse(EncounterGroup.addKeepsBossFight(SERPENTINE, SNAKELING_MELEE));
		assertFalse(EncounterGroup.addKeepsBossFight(MAGMA, SERPENTINE));
		assertFalse(EncounterGroup.addKeepsBossFight(NpcID.ABYSSALSIRE_LUNG, NpcID.ABYSSALSIRE_SIRE_WANDERING));
		assertFalse(EncounterGroup.addKeepsBossFight(NpcID.SLAYER_KRAKEN_BOSS_TENTACLE, NpcID.SLAYER_KRAKEN_BOSS));
		assertFalse(EncounterGroup.addKeepsBossFight(NpcID.HESPORI_HEALER_ACTIVE, NpcID.HESPORI));
	}

	@Test
	public void theKillIsOneRoomAndOneFightWithItsHitpointsCarried()
	{
		// One fight on the boss, opened on its Serpentine form, through Magma and Tanzanite and back, as the plugin keeps it
		// now: the snakeling shot is a tick of time in it, nothing scored; the snakeling's splats are nobody's.
		final Fight zulrah = new Fight("Zulrah", SERPENTINE, 33278, 500, 0L, null, 0);
		assertEquals("Zulrah", zulrah.encounterName());
		assertTrue(zulrah.isScored());
		final Encounter room = new Encounter(zulrah.encounterName(), null, 0L);
		room.add(zulrah);
		int dealt = 0;
		final int[][] byForm = {{27, 27, 25, 14, 4}, {27, 7, 1}, {1, 9, 27}, {2, 29, 22, 3, 8, 1}};
		for (int[] form : byForm)
		{
			for (int hit : form)
			{
				zulrah.recordAttackMade(true, 1, false);
				zulrah.recordDamageDealt(hit, 0L, hit > 0);
				dealt += hit;
			}
			zulrah.recordTickLost(false);
		}
		// The shot at the snakeling on [5153]: a tick of time in Zulrah's fight, no attempt, no damage.
		final int attemptsBefore = zulrah.getAttempts();
		zulrah.recordTickSpent();
		assertEquals(attemptsBefore, zulrah.getAttempts());
		// Then on, through Magma and Tanzanite, to the kill.
		final int[][] after = {{3, 8, 10}, {0, 4, 31}, {40, 10, 6, 19, 26, 2}, {20, 31, 14, 33}};
		for (int[] form : after)
		{
			for (int hit : form)
			{
				zulrah.recordAttackMade(true, 1, false);
				zulrah.recordDamageDealt(hit, 0L, hit > 0);
				dealt += hit;
			}
		}
		// Hitpoints carried: everything dealt is against the one 500, not a fresh 500 each fight.
		assertEquals(dealt, zulrah.getDamageDealt());
		assertTrue(dealt <= 500);
		assertEquals(1, room.getFights().size());
		// The loot is Zulrah's in whatever form it dies, and closes the room.
		assertTrue(EncounterGroup.lootIsTheKill(TANZANITE));
		room.end(1L);
		assertTrue(room.isEnded());
	}

	@Test
	public void aRecoilOnAOneHitpointSnakelingIsARecoil()
	{
		// [5226]: a 13 taken, and the snakeling that threw it shows a 1 on [5227]. Recoil is 10% plus 1, so 2, capped at the
		// snakeling's one hitpoint. Before, it matched nothing and joined Zulrah's attack as a target with 16 expected.
		final Map<Integer, List<Integer>> hits = new HashMap<>();
		hits.put(5226, Collections.singletonList(13));
		final Reflect r = new Reflect();
		assertNull(new Reflect().claim(5227, 1, t -> hits.get(t), true));
		final Reflect.Match m = r.claim(5227, 1, t -> hits.get(t), true, 1);
		assertNotNull(m);
		assertEquals(Reflect.Source.RECOIL, m.source);
		assertEquals(13, m.hit);
		// [5148]-[5149]: a 6 taken recoils its 1 exactly, capped or not.
		hits.put(5148, Collections.singletonList(6));
		assertNotNull(new Reflect().claim(5149, 1, t -> hits.get(t), true, 1));
		// On something with more left, a 1 is not a 13's recoil.
		assertNull(new Reflect().claim(5227, 1, t -> hits.get(t), true, 400));
		assertTrue(Reflect.fits(2, 1, 1));
		assertFalse(Reflect.fits(2, 1, 5));
		assertFalse(Reflect.fits(1, 2, 2));
		assertTrue(Reflect.fits(3, 3, -1));
	}

	@Test
	public void theDiveIsAWindowAndAProposedAllowance()
	{
		final BossRules.Boss zulrah = RULES.forNpc(SERPENTINE);
		assertNotNull(zulrah.damageModifiers);
		assertTrue(RULES.forNpc(TANZANITE) == zulrah);
		final List<String> lines = new ArrayList<>();
		final ModifierTracker tracker = new ModifierTracker(lines::add);
		// The log's [5231] sink and [5234] emerge, the form changing to Tanzanite on the emerge.
		tracker.onBossAnim(33278, "Zulrah", zulrah.damageModifiers, 5072, 5231);
		assertEquals("MOD Zulrah: diving on at tick 5231 (anim 5072)", lines.get(0));
		assertTrue(tracker.isOpen(33278, "diving", 5233));
		tracker.onBossAnim(33278, "Zulrah", zulrah.damageModifiers, 5073, 5234);
		assertEquals("MOD Zulrah: diving off at tick 5234 (anim 5073, after 3 ticks)", lines.get(1));
		assertFalse(tracker.isOpen(33278, "diving", 5234));
		// No damage modifier rides on it.
		assertTrue(tracker.applying(33278, zulrah.damageModifiers, TANZANITE, "RANGED", 5232, true, (id, r) -> false)
			.isEmpty());

		final BossRules.UptimeAllowance dive = zulrah.uptimeAllowances.get(0);
		assertEquals("diving", dive.state);
		assertEquals(8, dive.maxTicks);
		assertEquals(6, dive.afterTicks);
		assertFalse(dive.fromCast());
		// Idle from the sink to past the emerge: 8 excused, covered to 6 after the last open tick, the rest lost.
		final MechanicAllowances mechanics = new MechanicAllowances(null);
		// Each tick shows the window, then asks about the idle tick a booking lag back, as the plugin does.
		int excused = 0;
		for (int t = 5231; t <= 5243; t++)
		{
			final int tick = t;
			mechanics.observe(tick, zulrah.uptimeAllowances,
				state -> tick < 5234 ? new int[]{5231, 5241} : null);
			if (tick - 2 >= 5232)
			{
				excused += mechanics.excuse(tick - 2) != null ? 1 : 0;
			}
		}
		assertEquals(8, excused);
		assertEquals(0, mechanics.stillLostTotal());
	}
}
