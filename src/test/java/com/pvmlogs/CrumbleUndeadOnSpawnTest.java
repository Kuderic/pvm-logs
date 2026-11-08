package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * T-181: at Vorkath, the bolt after a Crumble Undead on his Zombified Spawn was named Crumble Undead and scored with its
 * figures. Eric's log of 2026-11-06 11:38 (build {@code 7eca1b6}, read in place): [37098] the cast at the spawn, booked at
 * [37100] as an add's ("nothing scored"), and never spent, so the spell stayed in hand for its 8-tick window; the bolt
 * thrown at [37105] read "named by the throw's spell Crumble Undead", max 18, chances 0.11, against the bolt's 63 and
 * 0.84. 23 bolts in the session, 15 of them named so.
 */
public class CrumbleUndeadOnSpawnTest
{
	/** What the loadout reads on each tick (CombatCalc's spell for the timeline): the cast in hand, or nothing. */
	private static Map<Integer, Spell> readEachTick(CombatCalc.ManualCast hand, int from, int to)
	{
		final Map<Integer, Spell> byTick = new HashMap<>();
		for (int tick = from; tick <= to; tick++)
		{
			byTick.put(tick, hand.active(tick));
		}
		return byTick;
	}

	@Test
	public void theBoltAfterACastAtTheSpawnIsTheBolt()
	{
		final CombatCalc.ManualCast hand = new CombatCalc.ManualCast();
		hand.record(Spell.CRUMBLE_UNDEAD, 37098);
		// [37100]: booked at the spawn, an add swatted in passing: it spends the click, as a booking on the boss does.
		assertTrue(PvmPerformancePlugin.spendsTheCast(true, false));
		assertTrue(hand.attackThrown(false, 37100));
		final Map<Integer, Spell> byTick = readEachTick(hand, 37101, 37107);
		// The bolt thrown at [37105] (and the tick before it, read across a switch): no spell, so named and scored as the bolt.
		assertNull(CombatCalc.spellAtThrow(byTick, 37105, null));
		assertNull(CombatCalc.spellAtThrow(byTick, 37104, null));
		assertEquals("Dragon hunter crossbow", CombatCalc.attackName("Dragon hunter crossbow",
			spellName(CombatCalc.spellAtThrow(byTick, 37104, null))));
	}

	@Test
	public void beforeTheFixTheCastStayedInHandForTheBolt()
	{
		// Unspent, the window ran to [37106]: the bolt's tick read Crumble Undead.
		final CombatCalc.ManualCast hand = new CombatCalc.ManualCast();
		hand.record(Spell.CRUMBLE_UNDEAD, 37098);
		assertEquals(Spell.CRUMBLE_UNDEAD, readEachTick(hand, 37101, 37107).get(37105));
	}

	@Test
	public void aSwitchOnTheTickBetweenChangesNothing()
	{
		// The figures read from the tick before a switch at [37105] (T-065): with the cast spent, that tick is the bolt's too.
		final CombatCalc.ManualCast hand = new CombatCalc.ManualCast();
		hand.record(Spell.CRUMBLE_UNDEAD, 37098);
		hand.attackThrown(false, 37100);
		assertNull(readEachTick(hand, 37101, 37107).get(37104));
	}

	@Test
	public void aCrumbleUndeadAtVorkathHimselfIsStillItself()
	{
		final CombatCalc.ManualCast hand = new CombatCalc.ManualCast();
		hand.record(Spell.CRUMBLE_UNDEAD, 37098);
		// Its throw's tick reads the spell, which names and scores it; its booking spends it after.
		final Map<Integer, Spell> byTick = readEachTick(hand, 37098, 37099);
		assertEquals(Spell.CRUMBLE_UNDEAD, CombatCalc.spellAtThrow(byTick, 37098, null));
		assertEquals("Crumble Undead", CombatCalc.attackName("Dragon hunter crossbow",
			spellName(CombatCalc.spellAtThrow(byTick, 37098, null))));
		assertTrue(hand.attackThrown(false, 37100));
	}

	@Test
	public void aMeleeBlowSpendsNoCast()
	{
		assertFalse(PvmPerformancePlugin.spendsTheCast(false, false));
		assertTrue(PvmPerformancePlugin.spendsTheCast(false, true));
	}

	private static String spellName(Spell spell)
	{
		return spell == null ? null : spell.getDisplayName();
	}
}
