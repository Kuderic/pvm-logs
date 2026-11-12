package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * T-213 (Eric, 2026-11-11: "When a boss respawns the side panel flashes a new screen for like a brief moment before going
 * to the live fight display"): a fight opened on a known throw is under way from it, not from its booking. Eric's Hydra log
 * ({@code pvm-debug-20261111-121905.log}, build {@code faf4f9e}, read in place): {@code xp award tick 2026} then
 * {@code fight START} on the same tick, the bolt booked on 2028; between, both views showed "no attacks yet".
 */
public class ThrowKnownTest
{
	private static final int HYDRA = 8615;

	private static Encounter room(Fight fight)
	{
		final Encounter room = new Encounter("Alchemical Hydra", null, 1_000L);
		room.add(fight);
		return room;
	}

	private static Fight hydra()
	{
		return new Fight("Alchemical Hydra", HYDRA, 64804, 1100, 1_000L, null, 0);
	}

	@Test
	public void aFightOpenedOnTheThrowsXpIsLiveBeforeItsBooking()
	{
		final Fight fight = hydra();
		final Encounter room = room(fight);
		// Opened by the XP on 2026: the throw is known, nothing booked.
		fight.noteThrowKnown(2026);
		assertEquals(0, fight.getAttempts());
		assertTrue(fight.isThrowKnown());
		assertTrue(PvmPerformanceOverlay.isUnderway(room));
		assertFalse(PvmPerformanceOverlay.isBeforeFirstAttack(fight, room));
		// The first throw known counts; the booking changes nothing.
		fight.noteThrowKnown(2031);
		assertEquals(2026, fight.getFirstThrowTick());
		fight.recordAttackMade(false, 1.0, false);
		assertFalse(PvmPerformanceOverlay.isBeforeFirstAttack(fight, room));
	}

	@Test
	public void oneOpenedOnAProjectileIsToo()
	{
		// The projectile handler notes the throw on the tick it set off ("projectile 27 taken as mine"), as the energy fall
		// and a cast seen do: one note on the fight, whatever showed it.
		final Fight fight = hydra();
		final Encounter room = room(fight);
		fight.noteThrowKnown(3236);
		assertFalse(PvmPerformanceOverlay.isBeforeFirstAttack(fight, room));
	}

	@Test
	public void oneOpenedByTargetingWaitsForTheThrowNotTheBooking()
	{
		final Fight fight = hydra();
		final Encounter room = room(fight);
		// Targeted, nothing thrown: the pre-attack screen, the loadout and the last fight.
		assertFalse(fight.isThrowKnown());
		assertFalse(PvmPerformanceOverlay.isUnderway(room));
		assertTrue(PvmPerformanceOverlay.isBeforeFirstAttack(fight, room));
		// Shown against the last room, the kill, as the plugin shows it until this one begins: still the pre-attack screen.
		final Fight killed = hydra();
		killed.recordAttackMade(false, 1.0, false);
		assertTrue(PvmPerformanceOverlay.isBeforeFirstAttack(fight, room(killed)));
		// The throw: live, before its booking.
		fight.noteThrowKnown(2026);
		assertFalse(PvmPerformanceOverlay.isBeforeFirstAttack(fight, room));
	}

	@Test
	public void anAddInARoomUnderWayIsntBeforeItsFirstAttack()
	{
		// As before (T-022): the Wardens' next phase opens in a room the Obelisk began, so it doesn't blink to the title.
		final Fight obelisk = hydra();
		obelisk.recordAttackMade(false, 1.0, false);
		final Encounter room = room(obelisk);
		final Fight warden = new Fight("Tumeken's Warden", 11757, 2, 320, 2_000L, null, 0);
		room.add(warden);
		assertFalse(PvmPerformanceOverlay.isBeforeFirstAttack(warden, room));
		// An ended fight is never before its first attack.
		final Fight looked = hydra();
		looked.end(false, 3_000L);
		assertFalse(PvmPerformanceOverlay.isBeforeFirstAttack(looked, room(looked)));
	}

	@Test
	public void theLiveCardWithNothingBookedHasNoNanOrNull()
	{
		final Fight fight = hydra();
		final Encounter room = room(fight);
		fight.noteThrowKnown(2026);
		// The bolt's two ticks in the air: spent, nothing booked.
		fight.recordTickSpent(true);
		fight.recordTickSpent(true);
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(in);
		assertNotNull(tab.card);
		final List<String> bad = new ArrayList<>();
		scan(tab, "tab", bad, new IdentityHashMap<>());
		assertTrue(bad.toString(), bad.isEmpty());
		// The live card's empty forms: the clock from the throw, damage 0, DPS 0.0 (as between a booking and its splat
		// today), no exp, no Skill, luck "no data".
		assertEquals("0:01", tab.header.clock.text);
		assertEquals("0.0", tab.card.dps);
		assertEquals(null, tab.card.expDps);
		assertEquals(null, tab.card.skill);
		assertEquals("no data", tab.card.luckWord.text);
		// The overlay's live rows too.
		final LedgerRows.State state = PvmPerformanceOverlay.roomState(fight, room, false, r -> null);
		for (LedgerRows.Row r : LedgerRows.build(LedgerRows.of(room), state, new LedgerRows.Settings()))
		{
			assertFalse(r.left + " | " + r.right, text(r.left).contains("NaN") || text(r.right).contains("NaN")
				|| "null".equals(r.left) || "null".equals(r.right) || text(r.right).contains("null"));
		}
	}

	private static String text(String s)
	{
		return s == null ? "" : s;
	}

	/** Every string the panel would show, walked from the snapshot: none NaN, none "null". */
	private static void scan(Object o, String path, List<String> bad, Map<Object, Boolean> seen)
	{
		if (o == null || seen.put(o, true) != null)
		{
			return;
		}
		if (o instanceof String)
		{
			final String s = (String) o;
			if (s.contains("NaN") || s.contains("null") || s.contains("Infinity"))
			{
				bad.add(path + " = " + s);
			}
			return;
		}
		if (o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum
			|| o.getClass().isArray() && o.getClass().getComponentType().isPrimitive())
		{
			return;
		}
		if (o instanceof Collection)
		{
			int i = 0;
			for (Object e : (Collection<?>) o)
			{
				scan(e, path + "[" + i++ + "]", bad, seen);
			}
			return;
		}
		if (o instanceof Map)
		{
			for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet())
			{
				scan(e.getValue(), path + "." + e.getKey(), bad, seen);
			}
			return;
		}
		if (o.getClass().isArray())
		{
			final Object[] a = (Object[]) o;
			for (int i = 0; i < a.length; i++)
			{
				scan(a[i], path + "[" + i + "]", bad, seen);
			}
			return;
		}
		if (!o.getClass().getName().startsWith("com.pvmlogs"))
		{
			return;
		}
		for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
		{
			for (Field f : c.getDeclaredFields())
			{
				if (Modifier.isStatic(f.getModifiers()))
				{
					continue;
				}
				f.setAccessible(true);
				try
				{
					scan(f.get(o), path + "." + f.getName(), bad, seen);
				}
				catch (IllegalAccessException e)
				{
					throw new AssertionError(e);
				}
			}
		}
	}
}
