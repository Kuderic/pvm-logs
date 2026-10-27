package com.pvmlogs;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The search is the one part of gear switching that needs no Client: it takes
 * what a loadout is worth as an argument. These use a scorer where a higher
 * item id is simply better, so the arithmetic never enters into it.
 */
public class GearSearchTest
{
	// An empty loadout needs no ItemManager as long as nothing asks for a name,
	// and the scorer here asks only for ids.
	private static Loadout empty()
	{
		return Loadout.worn(null, null);
	}

	private static GearSearch.Score higherIdIsBetter()
	{
		return gear ->
		{
			double total = 0;
			for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
			{
				total += Math.max(0, gear.id(slot));
			}
			return total;
		};
	}

	private static List<GearSearch.Candidate> carrying(EquipmentInventorySlot slot, int itemId)
	{
		return Collections.singletonList(new GearSearch.Candidate(slot, itemId));
	}

	@Test
	public void wornIsReturnedByIdentityWhenNothingBeatsIt()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.HEAD, 500);
		// The search does not copy when it has nothing to say. Callers must
		// still compare by sameItems, not by reference — the worn loadout is
		// rebuilt every tick, so this identity does not survive one.
		assertSame(worn, GearSearch.best(worn, carrying(EquipmentInventorySlot.HEAD, 100),
			false, higherIdIsBetter()));
	}

	@Test
	public void aBetterItemIsSwappedIn()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.HEAD, 100);
		final Loadout best = GearSearch.best(worn, carrying(EquipmentInventorySlot.HEAD, 500),
			false, higherIdIsBetter());
		assertEquals(500, best.id(EquipmentInventorySlot.HEAD));
	}

	@Test
	public void severalSlotsImproveTogether()
	{
		final Loadout worn = empty()
			.with(EquipmentInventorySlot.HEAD, 100)
			.with(EquipmentInventorySlot.BODY, 100);
		final Loadout best = GearSearch.best(worn, Arrays.asList(
			new GearSearch.Candidate(EquipmentInventorySlot.HEAD, 500),
			new GearSearch.Candidate(EquipmentInventorySlot.BODY, 700)),
			false, higherIdIsBetter());
		assertEquals(500, best.id(EquipmentInventorySlot.HEAD));
		assertEquals(700, best.id(EquipmentInventorySlot.BODY));
	}

	@Test
	public void theWeaponIsNeverJudged()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.WEAPON, 100);
		// The whole point of pinning the weapon: a better one in the bag is not
		// a missed switch, because which weapon to bring is not being scored.
		assertSame(worn, GearSearch.best(worn, carrying(EquipmentInventorySlot.WEAPON, 900),
			false, higherIdIsBetter()));
	}

	@Test
	public void aTwoHanderLeavesNoShieldSlotToFill()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.WEAPON, 100);
		// Advice the game would not let the player take is not advice.
		assertSame(worn, GearSearch.best(worn, carrying(EquipmentInventorySlot.SHIELD, 900),
			true, higherIdIsBetter()));
	}

	@Test
	public void aShieldIsJudgedWhenTheWeaponIsOneHanded()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.WEAPON, 100);
		final Loadout best = GearSearch.best(worn, carrying(EquipmentInventorySlot.SHIELD, 900),
			false, higherIdIsBetter());
		assertEquals(900, best.id(EquipmentInventorySlot.SHIELD));
	}

	@Test
	public void anItemAlreadyWornInThatSlotIsNotASwitch()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.HEAD, 500);
		assertSame(worn, GearSearch.best(worn, carrying(EquipmentInventorySlot.HEAD, 500),
			false, higherIdIsBetter()));
	}

	// T-010: scored per tick, and items worn on purpose.

	private static final int TENTACLE = ItemID.ABYSSAL_TENTACLE;
	private static final int ELDER_MAUL = ItemID.ELDER_MAUL;
	private static final int BLOOD_FURY = ItemID.BLOOD_AMULET;
	private static final int TORTURE = ItemID.ZENYTE_AMULET_ENCHANTED;

	// Made-up figures in the shape CombatCalc gives: each item adds to the average hit, and the weapon sets the speed.
	private static final Map<Integer, Double> HIT = new HashMap<>();
	private static final Map<Integer, Integer> SPEED = new HashMap<>();

	static
	{
		HIT.put(TENTACLE, 15.0);
		SPEED.put(TENTACLE, 4);
		HIT.put(ELDER_MAUL, 20.0);
		SPEED.put(ELDER_MAUL, 6);
		HIT.put(BLOOD_FURY, 0.0);
		HIT.put(TORTURE, 0.4);
	}

	private static double hit(Loadout gear)
	{
		double total = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			total += HIT.getOrDefault(gear.id(slot), 0.0);
		}
		return total;
	}

	private static int speed(Loadout gear)
	{
		return SPEED.getOrDefault(gear.id(EquipmentInventorySlot.WEAPON), 4);
	}

	// What CombatCalc now passes the search: damage per tick.
	private static GearSearch.Score perTick()
	{
		return gear -> GearSearch.perTick(hit(gear), speed(gear));
	}

	private static final Loadout TENTACLE_AND_BLOOD_FURY = empty()
		.with(EquipmentInventorySlot.WEAPON, TENTACLE)
		.with(EquipmentInventorySlot.AMULET, BLOOD_FURY);

	@Test
	public void perTickRanksASlowHeavyHitterBelowAFasterWeaponThatOutdamagesIt()
	{
		// Per attack the maul wins, 20 to 15; per tick it loses, 3.33 to 3.75. Per tick is what a fight is made of.
		assertTrue(HIT.get(ELDER_MAUL) > HIT.get(TENTACLE));
		assertTrue(GearSearch.perTick(20.0, 6) < GearSearch.perTick(15.0, 4));
		assertEquals(0.0, GearSearch.switchCost(15.0, 4, 20.0, 6), 1e-9);
	}

	@Test
	public void aSlowerHarderHittingCarriedWeaponIsNotAMissedSwitch()
	{
		final Loadout worn = empty().with(EquipmentInventorySlot.WEAPON, TENTACLE);
		assertSame(worn, GearSearch.best(worn, carrying(EquipmentInventorySlot.WEAPON, ELDER_MAUL), false, perTick()));
	}

	@Test
	public void aBetterSameSpeedAmuletIsAMissedSwitchWithItsCost()
	{
		// The weapon stays pinned (Eric, 2026-10-26), so the brief's "better same-speed weapon" case is an amulet here.
		final Loadout best = GearSearch.best(TENTACLE_AND_BLOOD_FURY, carrying(EquipmentInventorySlot.AMULET, TORTURE),
			false, perTick());
		assertEquals(TORTURE, best.id(EquipmentInventorySlot.AMULET));
		assertEquals(0.4, GearSearch.switchCost(hit(TENTACLE_AND_BLOOD_FURY), 4, hit(best), speed(best)), 1e-9);
	}

	@Test
	public void bloodFuryWornWithTortureCarriedIsFlaggedWithItsCost()
	{
		// T-015 removed "Worn on purpose" (Eric: typing item names was too hard to use). Blood fury for sustain is a missed
		// switch again, and its cost is what shows the trade is small: 0.4 of a 15 hit here.
		final Loadout best = GearSearch.best(TENTACLE_AND_BLOOD_FURY, carrying(EquipmentInventorySlot.AMULET, TORTURE),
			false, perTick());
		assertNotSame(TENTACLE_AND_BLOOD_FURY, best);
		assertEquals(TORTURE, best.id(EquipmentInventorySlot.AMULET));
		assertEquals(0.4, GearSearch.switchCost(hit(TENTACLE_AND_BLOOD_FURY), 4, hit(best), speed(best)), 1e-9);
	}

	@Test
	public void aSwitchCostIsNothingWithoutBothFigures()
	{
		assertEquals(0.0, GearSearch.switchCost(-1, 4, 10.0, 4), 1e-9);
		assertEquals(0.0, GearSearch.switchCost(10.0, 4, -1, 4), 1e-9);
		assertEquals(-1.0, GearSearch.perTick(-1, 4), 1e-9);
	}

	@Test
	public void aSwitchCheckCarriesItsCostAndNothingWhenClean()
	{
		final Loadout best = TENTACLE_AND_BLOOD_FURY.with(EquipmentInventorySlot.AMULET, TORTURE);
		final CombatCalc.SwitchCheck missed = new CombatCalc.SwitchCheck(TENTACLE_AND_BLOOD_FURY, best, 15.0, 4, 15.4, 4);
		assertTrue(missed.isMissed());
		assertEquals(0.4, missed.cost(), 1e-9);
		assertTrue(!CombatCalc.SwitchCheck.NONE.isMissed());
		assertEquals(0.0, CombatCalc.SwitchCheck.NONE.cost(), 1e-9);
	}
}
