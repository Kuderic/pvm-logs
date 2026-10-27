package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-017 Part B: recoil and vengeance matched to the hits they answer, from the
 * OSRS Wiki's formulas (read 2026-10-27), and booked as their own damage.
 */
public class ReflectTest
{
	private final Map<Integer, List<Integer>> hits = new HashMap<>();

	private List<Integer> hitsOn(int tick)
	{
		return hits.getOrDefault(tick, Collections.emptyList());
	}

	@Test
	public void recoilIsATenthRoundedDownPlusOne()
	{
		assertEquals(0, Reflect.recoilOf(0));
		assertEquals(1, Reflect.recoilOf(1));
		assertEquals(1, Reflect.recoilOf(9));
		assertEquals(2, Reflect.recoilOf(10));
		assertEquals(5, Reflect.recoilOf(45));
	}

	@Test
	public void vengeanceIsThreeQuartersRoundedDownAtLeastOne()
	{
		assertEquals(0, Reflect.vengeanceOf(0));
		assertEquals(1, Reflect.vengeanceOf(1));
		assertEquals(10, Reflect.vengeanceOf(14));
		// The wiki's example: 81 reflects 60.
		assertEquals(60, Reflect.vengeanceOf(81));
	}

	@Test
	public void theRecoilRings()
	{
		assertTrue(Reflect.isRecoilRing(ItemID.RING_OF_RECOIL));
		assertTrue(Reflect.isRecoilRing(ItemID.ZENYTE_RING_ENCHANTED_RECOIL));
		assertTrue(Reflect.isRecoilRing(ItemID.NZONE_ZENYTE_RING_ENCHANTED_RECOIL));
		// An uncharged ring of suffering recoils nothing.
		assertFalse(Reflect.isRecoilRing(ItemID.ZENYTE_RING_ENCHANTED));
		assertFalse(Reflect.isRecoilRing(-1));
	}

	@Test
	public void aRecoilAnswersAHitOnItsTickOrTheOneBefore()
	{
		final Reflect r = new Reflect();
		hits.put(100, Collections.singletonList(25));
		final Reflect.Match m = r.claim(100, 3, this::hitsOn, true);
		assertEquals(Reflect.Source.RECOIL, m.source);
		assertEquals(25, m.hit);
		assertEquals(100, m.hitTick);
		hits.put(200, Collections.singletonList(25));
		assertEquals(200, r.claim(201, 3, this::hitsOn, true).hitTick);
		// Two ticks on is too late.
		hits.put(300, Collections.singletonList(25));
		assertNull(r.claim(302, 3, this::hitsOn, true));
	}

	@Test
	public void eachHitIsAnsweredOnceAndTwoHitsTwice()
	{
		final Reflect r = new Reflect();
		hits.put(100, Arrays.asList(12, 15));
		assertTrue(r.claim(100, 2, this::hitsOn, true) != null);
		assertTrue(r.claim(100, 2, this::hitsOn, true) != null);
		// A third 2 is nobody's recoil: the player's own hit.
		assertNull(r.claim(100, 2, this::hitsOn, true));
	}

	@Test
	public void noRecoilWithoutTheRingAndNoneForAZeroOrAMiss()
	{
		final Reflect r = new Reflect();
		hits.put(100, Arrays.asList(0, 25));
		assertNull(r.claim(100, 3, this::hitsOn, false));
		assertNull(r.claim(100, 0, this::hitsOn, true));
		// The 0 taken recoils nothing, so a 1 is not its recoil.
		assertNull(r.claim(100, 1, this::hitsOn, true));
	}

	@Test
	public void vengeanceAnswersOneHitWhenItsReboundWentOff()
	{
		final Reflect r = new Reflect();
		hits.put(2804, Collections.singletonList(14));
		// Not up: a 10 is the player's.
		assertNull(r.claim(2804, 10, this::hitsOn, false));
		r.vengeanceFired(2804);
		final Reflect.Match m = r.claim(2804, 10, this::hitsOn, false);
		assertEquals(Reflect.Source.VENGEANCE, m.source);
		assertEquals(14, m.hit);
		// Once.
		assertNull(r.claim(2804, 10, this::hitsOn, false));
	}

	@Test
	public void theVarbitAndTheWordsMarkOneRebound()
	{
		final Reflect r = new Reflect();
		hits.put(100, Collections.singletonList(40));
		r.vengeanceFired(100);
		r.vengeanceFired(101);
		assertEquals(Reflect.Source.VENGEANCE, r.claim(101, 30, this::hitsOn, false).source);
		assertNull(r.claim(101, 30, this::hitsOn, false));
	}

	@Test
	public void theReboundAndItsSplatMayBeATickApart()
	{
		final Reflect r = new Reflect();
		hits.put(100, Collections.singletonList(40));
		r.vengeanceFired(100);
		assertEquals(Reflect.Source.VENGEANCE, r.claim(101, 30, this::hitsOn, false).source);
		final Reflect late = new Reflect();
		late.vengeanceFired(100);
		assertNull(late.claim(102, 30, this::hitsOn, false));
	}

	@Test
	public void vengeanceIsAskedBeforeRecoil()
	{
		final Reflect r = new Reflect();
		// A 9 taken: recoil sends 1, vengeance 6.
		hits.put(100, Collections.singletonList(9));
		r.vengeanceFired(100);
		assertEquals(Reflect.Source.VENGEANCE, r.claim(100, 6, this::hitsOn, true).source);
		assertEquals(Reflect.Source.RECOIL, r.claim(100, 1, this::hitsOn, true).source);
	}

	@Test
	public void reflectedDamageIsItsOwnAtEveryWidth()
	{
		final Fight a = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		a.recordReflectDamage(10);
		a.recordReflectDamage(3);
		a.recordDamageDealt(20, 600L, true);
		assertEquals(13, a.getReflectDamage());
		// Not weapon damage.
		assertEquals(20, a.getDamageDealt());
		final Fight b = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		b.recordReflectDamage(2);
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		room.add(a);
		room.add(b);
		assertEquals(15, room.getReflectDamage());
		final Raid raid = new Raid("Tombs of Amascut", 1, 0L);
		raid.add(room);
		assertEquals(15, raid.getReflectDamage());
		final SessionTotals trip = new SessionTotals(0L);
		trip.recordReflectDamage(10);
		assertEquals(10, trip.getReflectDamage());
		trip.reset(0L);
		assertEquals(0, trip.getReflectDamage());
	}

	@Test
	public void aFightSavedBeforeLoadsWithNone()
	{
		final Fight old = new Gson().fromJson("{\"targetName\":\"Vardorvis\",\"damageDealt\":700,\"thrallDamage\":20}",
			Fight.class);
		assertEquals(0, old.getReflectDamage());
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		f.recordReflectDamage(7);
		final Gson gson = new Gson();
		assertEquals(7, gson.fromJson(gson.toJson(f), Fight.class).getReflectDamage());
	}

	@Test
	public void theCsvRowEndsWithIt()
	{
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		f.recordReflectDamage(13);
		f.end(true, 1200L);
		final String row = PvmPerformancePlugin.csvRow(f).trim();
		assertEquals("13", row.substring(row.lastIndexOf(',') + 1));
	}
}
