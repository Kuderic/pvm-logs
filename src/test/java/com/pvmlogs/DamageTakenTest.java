package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.HitsplatID;
import net.runelite.api.Prayer;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

// T-018: splats on the local player - only hits are damage taken, damage over time sits beside it, the rest count nowhere.
public class DamageTakenTest
{
	private static Fight fight()
	{
		return new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
	}

	@Test
	public void aHealOnThePlayerIsNotDamageTaken()
	{
		final DamageTaken taken = new DamageTaken();
		final Fight f = fight();
		final SessionTotals trip = new SessionTotals(0L);
		assertEquals(HitsplatKind.HEAL, taken.onSplat(HitsplatID.HEAL, 20, 10, false, f, trip, 600L));
		assertEquals(0, f.getDamageTaken());
		assertEquals(0, f.getHitsTaken());
		assertEquals(0, f.getDotTaken());
		assertEquals(0, trip.getDamageTaken());
		assertEquals(0, taken.damageTakenOn(10));
		// And it does not keep the fight open.
		assertEquals(0L, f.getLastActivityMillis());
	}

	@Test
	public void aPrayerDrainIsNotDamage()
	{
		final DamageTaken taken = new DamageTaken();
		final Fight f = fight();
		for (int type : new int[]{HitsplatID.PRAYER_DRAIN, HitsplatID.CORRUPTION, HitsplatID.DISEASE,
			HitsplatID.SANITY_DRAIN, HitsplatID.DOOM, HitsplatID.DAMAGE_ME_CYAN})
		{
			assertEquals("type " + type, HitsplatKind.NO_HEALTH_EFFECT,
				taken.onSplat(type, 5, 10, false, f, null, 600L));
		}
		assertEquals(0, f.getDamageTaken());
		assertEquals(0, f.getDotTaken());
		assertEquals(0, f.getHitsTaken());
		assertEquals(0, taken.damageTakenOn(10));
		assertEquals(0L, f.getLastActivityMillis());
	}

	@Test
	public void venomGoesToDamageOverTimeNotDamageTaken()
	{
		final DamageTaken taken = new DamageTaken();
		final Fight f = fight();
		final SessionTotals trip = new SessionTotals(0L);
		assertEquals(HitsplatKind.DAMAGE_OVER_TIME, taken.onSplat(HitsplatID.VENOM, 6, 10, false, f, trip, 600L));
		taken.onSplat(HitsplatID.POISON, 4, 40, false, f, trip, 18600L);
		taken.onSplat(HitsplatID.BLEED, 3, 41, false, f, trip, 19200L);
		taken.onSplat(HitsplatID.BURN, 2, 42, false, f, trip, 19800L);
		assertEquals(15, f.getDotTaken());
		assertEquals(15, trip.getDotTaken());
		assertEquals(0, f.getDamageTaken());
		assertEquals(0, f.getHitsTaken());
		assertEquals(0, taken.damageTakenOn(10));
		// Damage over time keeps the fight open, as damage does (the brief).
		assertEquals(19800L, f.getLastActivityMillis());
	}

	@Test
	public void zerosAreHitsTakenButNoDamage()
	{
		final DamageTaken taken = new DamageTaken();
		final Fight f = fight();
		final SessionTotals trip = new SessionTotals(0L);
		assertEquals(HitsplatKind.HIT_TAKEN, taken.onSplat(HitsplatID.BLOCK_ME, 0, 10, true, f, trip, 600L));
		taken.onSplat(HitsplatID.DAMAGE_ME, 23, 14, true, f, trip, 3000L);
		taken.onSplat(HitsplatID.BLOCK_ME, 0, 18, true, f, trip, 5400L);
		assertEquals(3, f.getHitsTaken());
		assertEquals(23, f.getDamageTaken());
		assertEquals(23, f.getBiggestHitTaken());
		assertEquals(3, trip.getHitsTaken());
		assertEquals(23, trip.getDamageTaken());
		// A 0 still keeps the fight open: it is an attack.
		assertEquals(5400L, f.getLastActivityMillis());
	}

	@Test
	public void unprotectedCountsOnlyWithNoProtectionPrayerUp()
	{
		final DamageTaken taken = new DamageTaken();
		final Fight f = fight();
		final SessionTotals trip = new SessionTotals(0L);
		taken.onSplat(HitsplatID.DAMAGE_ME, 12, 10, true, f, trip, 600L);
		taken.onSplat(HitsplatID.DAMAGE_ME, 41, 14, false, f, trip, 3000L);
		taken.onSplat(HitsplatID.DAMAGE_MAX_ME, 30, 18, false, f, trip, 5400L);
		taken.onSplat(HitsplatID.DAMAGE_ME, 7, 22, true, f, trip, 7800L);
		assertEquals(90, f.getDamageTaken());
		assertEquals(71, f.getDamageTakenUnprotected());
		assertEquals(41, f.getBiggestHitTaken());
		assertEquals(71, trip.getDamageTakenUnprotected());
		assertEquals(41, trip.getBiggestHitTaken());
		// Venom with no prayer up is damage over time, not unprotected hit damage.
		taken.onSplat(HitsplatID.VENOM, 8, 30, false, f, trip, 9000L);
		assertEquals(71, f.getDamageTakenUnprotected());
	}

	@Test
	public void theProtectionPrayersAreTheThreeAndTheirDampenForms()
	{
		assertEquals(EnumSet.of(Prayer.PROTECT_FROM_MELEE, Prayer.PROTECT_FROM_MISSILES, Prayer.PROTECT_FROM_MAGIC,
			Prayer.RP_DAMPEN_MELEE, Prayer.RP_DAMPEN_RANGED, Prayer.RP_DAMPEN_MAGIC),
			EnumSet.copyOf(Arrays.asList(DamageTaken.PROTECTION)));
		assertEquals("melee", DamageTaken.protectionName(Prayer.PROTECT_FROM_MELEE));
		assertEquals("missiles", DamageTaken.protectionName(Prayer.PROTECT_FROM_MISSILES));
		assertEquals("dampen magic", DamageTaken.protectionName(Prayer.RP_DAMPEN_MAGIC));
	}

	@Test
	public void damageTakenOnIsTheTicksHitsOnly()
	{
		final DamageTaken taken = new DamageTaken();
		final Fight f = fight();
		taken.onSplat(HitsplatID.DAMAGE_ME, 20, 100, true, f, null, 0L);
		taken.onSplat(HitsplatID.DAMAGE_ME, 15, 100, false, f, null, 0L);
		taken.onSplat(HitsplatID.BLOCK_ME, 0, 100, false, f, null, 0L);
		taken.onSplat(HitsplatID.VENOM, 6, 100, false, f, null, 0L);
		taken.onSplat(HitsplatID.HEAL, 10, 100, false, f, null, 0L);
		taken.onSplat(HitsplatID.DAMAGE_ME, 9, 101, false, f, null, 0L);
		assertEquals(35, taken.damageTakenOn(100));
		assertEquals(Arrays.asList(20, 15, 0), taken.hitsTakenOn(100));
		assertEquals(9, taken.damageTakenOn(101));
		assertEquals(0, taken.damageTakenOn(99));
		assertEquals(Collections.emptyList(), taken.hitsTakenOn(99));
	}

	/** Recoil answers the boss's first swing too, which lands before any fight is open. */
	@Test
	public void damageTakenOnCountsHitsOutsideAFight()
	{
		final DamageTaken taken = new DamageTaken();
		final SessionTotals trip = new SessionTotals(0L);
		assertEquals(HitsplatKind.HIT_TAKEN, taken.onSplat(HitsplatID.DAMAGE_ME, 31, 50, false, null, trip, 0L));
		assertEquals(31, taken.damageTakenOn(50));
		assertEquals(0, trip.getDamageTaken());
		final Fight ended = fight();
		ended.end(true, 100L);
		taken.onSplat(HitsplatID.DAMAGE_ME, 12, 51, false, ended, trip, 200L);
		assertEquals(12, taken.damageTakenOn(51));
		assertEquals(0, ended.getDamageTaken());
	}

	@Test
	public void aFewTicksOfHistoryAreKept()
	{
		final DamageTaken taken = new DamageTaken();
		taken.onSplat(HitsplatID.DAMAGE_ME, 10, 100, false, null, null, 0L);
		taken.onSplat(HitsplatID.DAMAGE_ME, 11, 100 + DamageTaken.KEEP_TICKS, false, null, null, 0L);
		assertEquals(10, taken.damageTakenOn(100));
		taken.onSplat(HitsplatID.DAMAGE_ME, 12, 101 + DamageTaken.KEEP_TICKS, false, null, null, 0L);
		assertEquals(0, taken.damageTakenOn(100));
		assertEquals(11, taken.damageTakenOn(100 + DamageTaken.KEEP_TICKS));
		taken.clear();
		assertEquals(0, taken.damageTakenOn(101 + DamageTaken.KEEP_TICKS));
	}

	@Test
	public void anUnscoredFightTakesTheHitButTheTripDoesNot()
	{
		final DamageTaken taken = new DamageTaken();
		// A Zulrah snakeling, unscored (T-084). (The Judge of Yama was the example until T-136 scored it.)
		final Fight judge = new Fight("Snakeling", NpcID.SNAKEBOSS_MINION_MELEE, 1, 1, 0L, null, 0);
		assertTrue(!judge.isScored());
		final SessionTotals trip = new SessionTotals(0L);
		taken.onSplat(HitsplatID.DAMAGE_ME, 25, 10, false, judge, trip, 600L);
		taken.onSplat(HitsplatID.BURN, 3, 11, false, judge, trip, 1200L);
		assertEquals(25, judge.getDamageTaken());
		assertEquals(3, judge.getDotTaken());
		assertEquals(0, trip.getDamageTaken());
		assertEquals(0, trip.getDotTaken());
		assertEquals(0, trip.getHitsTaken());
	}

	@Test
	public void anUnknownTypeIsLoggedAndCountedNowhere()
	{
		final DamageTaken taken = new DamageTaken();
		final Fight f = fight();
		assertEquals(HitsplatKind.UNCLASSIFIED, taken.onSplat(99, 4, 10, false, f, null, 600L));
		assertEquals(0, f.getDamageTaken());
		assertEquals(0, f.getDotTaken());
		assertEquals(0, taken.damageTakenOn(10));
	}

	/** Every type lands in exactly one kind, and only the red and blue ones are hits. */
	@Test
	public void everyTypeOnThePlayerHasAKind()
	{
		final Set<Integer> hits = new HashSet<>(Arrays.asList(HitsplatID.BLOCK_ME, HitsplatID.BLOCK_OTHER,
			HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_MAX_ME));
		final Set<Integer> dot = new HashSet<>(Arrays.asList(HitsplatID.POISON, HitsplatID.VENOM, HitsplatID.BURN,
			HitsplatID.BLEED));
		final EnumSet<HitsplatKind> playerKinds = EnumSet.of(HitsplatKind.HIT_TAKEN, HitsplatKind.DAMAGE_OVER_TIME,
			HitsplatKind.HEAL, HitsplatKind.NO_HEALTH_EFFECT, HitsplatKind.UNCLASSIFIED);
		for (int type = 0; type <= 200; type++)
		{
			final HitsplatKind kind = HitsplatKind.onPlayer(type);
			assertTrue("type " + type, playerKinds.contains(kind));
			assertEquals("type " + type, hits.contains(type), kind == HitsplatKind.HIT_TAKEN);
			assertEquals("type " + type, dot.contains(type), kind == HitsplatKind.DAMAGE_OVER_TIME);
			assertEquals("type " + type, type == HitsplatID.HEAL, kind == HitsplatKind.HEAL);
		}
		// Every coloured and poise type is something other than hitpoints on a player too.
		for (int type : new int[]{18, 19, 20, 21, 22, 23, 24, 25, 44, 45, 46, 47, 53, 54, 55})
		{
			assertEquals("type " + type, HitsplatKind.NO_HEALTH_EFFECT, HitsplatKind.onPlayer(type));
		}
		// The boss side is untouched: a hit type on an npc is still the player's or another player's.
		assertEquals(HitsplatKind.PLAYER, HitsplatKind.of(true, false, HitsplatID.DAMAGE_ME));
		assertFalse(HitsplatKind.HIT_TAKEN.damagesTarget());
	}

	@Test
	public void theTakenLineSaysWhatItSaw()
	{
		assertEquals("TAKEN 23 type 16 hit (damage), protect melee, hp 76, from npc 12223 #456 (target); "
				+ "target anim 10340 t-1",
			DamageTaken.line(23, HitsplatID.DAMAGE_ME, HitsplatKind.HIT_TAKEN, "melee", 76, "npc 12223 #456 (target)",
				"anim 10340 t-1", true));
		assertEquals("TAKEN 6 type 5 dot (venom), protect none, hp 50, from unknown; target nothing seen (no fight)",
			DamageTaken.line(6, HitsplatID.VENOM, HitsplatKind.DAMAGE_OVER_TIME, null, 50, "", "", false));
		assertEquals("TAKEN 3 type 99 unknown (type 99), protect none, hp 50, from unknown; target nothing seen",
			DamageTaken.line(3, 99, HitsplatKind.UNCLASSIFIED, null, 50, null, null, true));
	}

	@Test
	public void theSummaryLineAddsUpTheFight()
	{
		final DamageTaken taken = new DamageTaken();
		final Fight f = fight();
		taken.onSplat(HitsplatID.DAMAGE_ME, 41, 10, false, f, null, 600L);
		taken.onSplat(HitsplatID.DAMAGE_ME, 12, 14, true, f, null, 3000L);
		taken.onSplat(HitsplatID.BLOCK_ME, 0, 18, true, f, null, 5400L);
		taken.onSplat(HitsplatID.BLEED, 5, 19, true, f, null, 6000L);
		assertEquals("TAKEN TOTAL Vardorvis (npc 3029): hits 3 for 53 (biggest 41, without protection 41), dot 5",
			DamageTaken.summary(f));
	}

	@Test
	public void theRoomAndTheRaidAddItUp()
	{
		final DamageTaken taken = new DamageTaken();
		final Fight a = fight();
		final Fight b = fight();
		taken.onSplat(HitsplatID.DAMAGE_ME, 41, 10, false, a, null, 600L);
		taken.onSplat(HitsplatID.VENOM, 6, 11, false, a, null, 600L);
		taken.onSplat(HitsplatID.DAMAGE_ME, 30, 20, true, b, null, 600L);
		taken.onSplat(HitsplatID.BLOCK_ME, 0, 24, true, b, null, 600L);
		final Encounter room = new Encounter("Kephri", RaidType.TOMBS_OF_AMASCUT, 0L);
		room.add(a);
		room.add(b);
		assertEquals(71, room.getDamageTaken());
		assertEquals(6, room.getDotTaken());
		assertEquals(3, room.getHitsTaken());
		assertEquals(41, room.getBiggestHitTaken());
		assertEquals(41, room.getDamageTakenUnprotected());
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		raid.add(room);
		final Fight c = fight();
		taken.onSplat(HitsplatID.DAMAGE_ME, 50, 30, false, c, null, 600L);
		final Encounter other = new Encounter("Zebak", RaidType.TOMBS_OF_AMASCUT, 0L);
		other.add(c);
		raid.add(other);
		assertEquals(121, raid.getDamageTaken());
		assertEquals(6, raid.getDotTaken());
		assertEquals(4, raid.getHitsTaken());
		assertEquals(50, raid.getBiggestHitTaken());
		assertEquals(91, raid.getDamageTakenUnprotected());
	}

	@Test
	public void theTripStartsOverOnReset()
	{
		final SessionTotals trip = new SessionTotals(0L);
		trip.recordHitTaken(30, true);
		trip.recordDotTaken(4);
		trip.reset(1L);
		assertEquals(0, trip.getDamageTaken());
		assertEquals(0, trip.getDotTaken());
		assertEquals(0, trip.getHitsTaken());
		assertEquals(0, trip.getBiggestHitTaken());
		assertEquals(0, trip.getDamageTakenUnprotected());
	}

	/** A fight saved before T-018 keeps its old damage taken and reads 0 for the rest. */
	@Test
	public void oldHistoryLoads()
	{
		final Fight old = new Gson().fromJson(
			"{\"targetName\":\"Vardorvis\",\"targetId\":12223,\"damageTaken\":312,\"attempts\":40}", Fight.class);
		assertEquals(312, old.getDamageTaken());
		assertEquals(0, old.getDotTaken());
		assertEquals(0, old.getHitsTaken());
		assertEquals(0, old.getBiggestHitTaken());
		assertEquals(0, old.getDamageTakenUnprotected());
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(old);
		assertEquals(312, room.getDamageTaken());
		assertEquals(0, room.getHitsTaken());
	}

	@Test
	public void aNewFightRoundTripsThroughHistory()
	{
		final DamageTaken taken = new DamageTaken();
		final Fight f = fight();
		taken.onSplat(HitsplatID.DAMAGE_ME, 41, 10, false, f, null, 600L);
		taken.onSplat(HitsplatID.POISON, 6, 11, false, f, null, 600L);
		final Gson gson = new Gson();
		final Fight back = gson.fromJson(gson.toJson(f), Fight.class);
		assertEquals(41, back.getDamageTaken());
		assertEquals(6, back.getDotTaken());
		assertEquals(1, back.getHitsTaken());
		assertEquals(41, back.getBiggestHitTaken());
		assertEquals(41, back.getDamageTakenUnprotected());
	}
}
