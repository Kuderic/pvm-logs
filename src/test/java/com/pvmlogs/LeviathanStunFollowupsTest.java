package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * T-078: the Leviathan's stun as the first T-066 log shows it. In that log's
 * shapes (rebuilt, no lines copied): the cast on 597 lands on 601; the bow's
 * free shot is thrown on 598, surfaces on 600 and lands 22 on 601; the player
 * runs behind; the wound shot is thrown on 608 and lands 44 on 610, closing the
 * stun. In the enrage, a cast on 732 lands on 738 as the pathfinder appears, and
 * shots of 25 and 27 land from the front.
 */
public class LeviathanStunFollowupsTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int LEVI = 12214;
	private static final int PATHFINDER = 12220;
	private static final int INDEX = 48528;
	private static final ModifierTracker.InsideCheck NOWHERE = (id, range) -> false;
	private static final ModifierTracker.InsideCheck IN_PATHFINDER = (id, range) -> id == PATHFINDER;

	private final List<String> lines = new ArrayList<>();
	private final ModifierTracker tracker = new ModifierTracker(lines::add);
	private final boolean[] pathfinderUp = {false};

	public LeviathanStunFollowupsTest()
	{
		tracker.setPresence(id -> id == PATHFINDER && pathfinderUp[0]);
	}

	private static BossRules.Boss levi()
	{
		return RULES.forNpc(LEVI);
	}

	private static BossRules.DamageModifierRules rules()
	{
		return levi().damageModifiers;
	}

	private List<String> names(int thrown, int lands, boolean behind, ModifierTracker.InsideCheck in)
	{
		final List<String> out = new ArrayList<>();
		for (BossRules.ModifierRule m : tracker.applying(INDEX, rules(), LEVI, "RANGED", thrown, lands, behind, in))
		{
			out.add(m.name);
		}
		return out;
	}

	private List<BossRules.ModifierRule> mods(int thrown, int lands, boolean behind, ModifierTracker.InsideCheck in)
	{
		return tracker.applying(INDEX, rules(), LEVI, "RANGED", thrown, lands, behind, in);
	}

	@Test
	public void theRulesAreInTheData()
	{
		BossRules.StateRule stunned = null;
		for (BossRules.StateRule s : rules().states)
		{
			if ("stunned".equals(s.name))
			{
				stunned = s;
			}
		}
		assertTrue(stunned != null && stunned.resetsAttackDelay);
		assertTrue(ModifierTracker.contains(stunned.endWhileNpc, PATHFINDER));
		for (Spell spell : Arrays.asList(Spell.SHADOW_RUSH, Spell.SHADOW_BURST, Spell.SHADOW_BLITZ, Spell.SHADOW_BARRAGE))
		{
			assertTrue(spell.name(), PvmPerformancePlugin.resetsAttackDelay(levi(), spell));
		}
		assertFalse(PvmPerformancePlugin.resetsAttackDelay(levi(), Spell.ICE_BARRAGE));
		assertFalse(PvmPerformancePlugin.resetsAttackDelay(RULES.forNpc(5889), Spell.SHADOW_RUSH));
	}

	@Test
	public void castFreeShotRunWoundShot()
	{
		// The cast on 597: booked, and the stun resets the attack delay, so the weapon is due on 598.
		final int due = 597 + PvmPerformancePlugin.bookedSpeed(true, false, 5, 5);
		assertEquals(598, due);
		tracker.onPlayerSpell(INDEX, "The Leviathan", rules(), Spell.SHADOW_RUSH.name(), 601);
		// The free shot's projectile set off on 600, with the last attack seen on 597: taken as mine now, refused before.
		assertFalse(PvmPerformancePlugin.tooSoonToBeMine(600, 597,
			PvmPerformancePlugin.speedSinceLastAttack(597, 597, 4)));
		assertTrue(PvmPerformancePlugin.tooSoonToBeMine(600, 597, 4));
		// Any other attack after another keeps the fastest speed.
		assertEquals(4, PvmPerformancePlugin.speedSinceLastAttack(597, 600, 4));
		assertEquals(4, PvmPerformancePlugin.speedSinceLastAttack(Integer.MIN_VALUE, 600, 4));
		// Thrown on 598, before the stun starts, it lands free of it: the 22 on 601 is no breach of a cap of 10.
		assertTrue(names(598, 601, false, NOWHERE).isEmpty());
		assertEquals(44, ModifierTracker.max(mods(598, 601, false, NOWHERE), 44));
		// One thrown after the stun has started is capped from the front, as before.
		assertEquals(Collections.singletonList("stunned, from the front"), names(602, 605, false, NOWHERE));
		assertEquals(10, ModifierTracker.max(mods(602, 605, false, NOWHERE), 44));
		// Booked on 600 as thrown on 598 at the bow's 4: due 602. Nothing is thrown while the player runs behind it, so
		// 604 on reads as lost, as any idle tick does.
		final int lag = PvmPerformancePlugin.pendingBookingLag(true, false);
		assertFalse(PvmPerformancePlugin.attackOverdue(599, due, lag));
		assertFalse(PvmPerformancePlugin.attackOverdue(603, 598 + 4, lag));
		assertTrue(PvmPerformancePlugin.attackOverdue(604, 598 + 4, lag));

		// The wound shot, thrown on 608 from behind, landing on 610. The figures worked out on its throw's tick carry the
		// wound: it cannot miss, and hits at least 28 of a 44 max.
		final List<String> atThrow = names(608, 610, true, NOWHERE);
		assertEquals(Collections.singletonList("into the wound"), atThrow);
		assertEquals(28, ModifierTracker.damage(mods(608, 610, true, NOWHERE), 0, 44, true));
		assertEquals(1.0, ModifierTracker.accuracy(mods(608, 610, true, NOWHERE), 0.74), 1e-12);
		// Its own splat closes the stun on 610, before the booking that tick: read then, the shot has no window at all.
		tracker.onModifiersLanded(INDEX, "The Leviathan", rules(), atThrow, 610);
		assertTrue(names(610, 612, true, NOWHERE).isEmpty());
		assertTrue(lines.get(lines.size() - 1), lines.get(lines.size() - 1).startsWith(
			"MOD The Leviathan: stunned off at tick 610 (a hit with into the wound"));
		// So a projectile is scored from its throw's tick, where that tick has figures.
		assertEquals(608, PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, true, true, 608, 610));
		// Without them, the tick it left on, as before; a switch since the throw still wins; melee and casts unchanged.
		assertEquals(610, PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, true, false, 608, 610));
		assertEquals(607, PvmPerformancePlugin.scoringTick(607, true, true, 608, 610));
		assertEquals(611, PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, false, true, 610, 611));
	}

	@Test
	public void aStunThatRunsOut()
	{
		tracker.onPlayerSpell(INDEX, "The Leviathan", rules(), Spell.SHADOW_RUSH.name(), 601);
		// Thrown inside it, landing inside: capped. Landing after it: not.
		assertEquals(10, ModifierTracker.max(mods(612, 615, false, NOWHERE), 44));
		assertTrue(names(614, 617, false, NOWHERE).isEmpty());
		tracker.tick(616);
		tracker.tick(617);
		assertEquals(2, lines.size());
		assertEquals("MOD The Leviathan: stunned off at tick 616 (ran out after 15 ticks)", lines.get(1));
	}

	@Test
	public void theEnrageEndsTheStun()
	{
		// Cast on 732, before the enrage: the stun is set to start on 738.
		tracker.onPlayerSpell(INDEX, "The Leviathan", rules(), Spell.SHADOW_RUSH.name(), 738);
		assertEquals("MOD The Leviathan: stunned on at tick 738 (your SHADOW_RUSH, for 15 ticks)", lines.get(0));
		// The pathfinder appears with the enrage on 737: the stun never starts.
		pathfinderUp[0] = true;
		tracker.tick(737);
		assertEquals("MOD The Leviathan: stunned off at tick 737 (npc 12220 is here, after 0 ticks)", lines.get(1));
		// The shots of 25 and 27, thrown on 743 and 747 from the front inside the pathfinder: no cap, its minimum of 8.
		for (int thrown : new int[]{743, 747})
		{
			final List<BossRules.ModifierRule> in = mods(thrown, thrown + 2, false, IN_PATHFINDER);
			assertEquals(Collections.singletonList("pathfinder"), names(thrown, thrown + 2, false, IN_PATHFINDER));
			assertEquals(44, ModifierTracker.max(in, 44));
			assertEquals(8, ModifierTracker.damage(in, 0, 44, true));
		}
		// Nothing more is logged for it, not even when it would have run out.
		tracker.tick(753);
		assertEquals(2, lines.size());
		// A cast while the pathfinder is up opens nothing.
		tracker.onPlayerSpell(INDEX, "The Leviathan", rules(), Spell.SHADOW_RUSH.name(), 790);
		assertEquals("MOD The Leviathan: stunned not on at tick 790 (your SHADOW_RUSH, but npc 12220 is here)",
			lines.get(2));
		assertTrue(names(791, 793, false, NOWHERE).isEmpty());
	}

	@Test
	public void aStunUnderWayEndsWhenThePathfinderAppears()
	{
		tracker.onPlayerSpell(INDEX, "The Leviathan", rules(), Spell.SHADOW_RUSH.name(), 700);
		assertEquals(10, ModifierTracker.max(mods(702, 705, false, NOWHERE), 44));
		pathfinderUp[0] = true;
		tracker.tick(706);
		assertEquals("MOD The Leviathan: stunned off at tick 706 (npc 12220 is here, after 6 ticks)", lines.get(1));
		assertTrue(names(706, 709, false, NOWHERE).isEmpty());
		// Thrown before it ended, landing after: not capped either, as any attack landing after a window.
		assertTrue(names(704, 707, false, NOWHERE).isEmpty());
	}

	@Test
	public void aShotThatDroppedAClickIsNotScoredFromTheThrowsRow()
	{
		// Eric's 05:01 log, [313]-[317] (shape only): Shadow Rush clicked on 313 never cast; the bow's shot thrown on 314
		// dropped the click when it surfaced on 316. The row of 314 was worked out with the click in hand (max 14), so the
		// shot is scored from the figures read once it was gone, as the call site passes it: not as a projectile's throw.
		assertEquals(316, PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, false, true, 314, 316));
		assertEquals(314, PvmPerformancePlugin.scoringTick(Integer.MIN_VALUE, true, true, 314, 316));
	}

	@Test
	public void aClickThatDidNotCastLeavesTheWeaponsSpeed()
	{
		// The tick the bow's shot was thrown on recorded the spell's 5, the click still in hand; the drop says it was the
		// bow's, at its own 4.
		assertEquals(4, PvmPerformancePlugin.bookedSpeed(false, true, 5, 4));
		// Otherwise the speed recorded on the tick it was held, as before; the live one where none was recorded.
		assertEquals(5, PvmPerformancePlugin.bookedSpeed(false, false, 5, 4));
		assertEquals(4, PvmPerformancePlugin.bookedSpeed(false, false, null, 4));
	}
}
