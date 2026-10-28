package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * T-049: which splat is a special's (RuneLite's special attack counter's way:
 * the target, the weapon's hit delay, and the order splats arrive in on one
 * tick), party drains and their echoes, resets, and the fight's record.
 */
public class DrainTrackerTest
{
	private static final int BOSS = 7;
	private static final int OTHER = 9;
	private static final int BOSS_ID = 12345;

	private final List<String> lines = new ArrayList<>();
	private final DrainTracker tracker = new DrainTracker(lines::add);

	private static DrainRule.Target boss()
	{
		return new DrainRule.Target(200, 150, 100, 200, 200, 100, 0, 0, false, false, false, false);
	}

	private DrainTracker.Tracked bossState()
	{
		return tracker.tracked(BOSS);
	}

	/** A melee special's splat is the last of the player's on its tick: a thrall's comes first and is not it. */
	@Test
	public void aMeleeSpecialIsTheLastSplatOfItsTick()
	{
		tracker.ownThrow(100, SpecialWeapon.DRAGON_WARHAMMER, BOSS, BOSS_ID, "Boss", 101, boss(), false);
		tracker.ownSplat(BOSS, 101, 3);
		tracker.ownSplat(BOSS, 101, 34);
		tracker.tick(101);
		assertEquals(140, bossState().state.defence);
		assertTrue(lines.get(0), lines.get(0).startsWith("DRAIN Dragon Warhammer by me on Boss (npc 12345, index 7): "
			+ "thrown 100, landed 101, hit 34 (landed): defence 200 -> 140 of 200 (floor 0)"));
	}

	/** A missed hammer with the thrall's splat on the same tick: the hammer's 0 is last, so it missed. */
	@Test
	public void aThrallSplatIsNeverTheHammers()
	{
		tracker.ownThrow(100, SpecialWeapon.DRAGON_WARHAMMER, BOSS, BOSS_ID, "Boss", 101, boss(), false);
		tracker.ownSplat(BOSS, 101, 3);
		tracker.ownSplat(BOSS, 101, 0);
		tracker.tick(101);
		assertEquals(200, bossState().state.defence);
		assertTrue(lines.get(0), lines.get(0).contains("hit 0 (missed)"));
		assertFalse(bossState().events.get(0).landed);
	}

	/**
	 * A projectile special's splat is the first on its tick, three ticks on for a
	 * Dorgeshuun crossbow at distance 3; an earlier attack's late splat the tick
	 * before, and the thrall after it, are not taken.
	 */
	@Test
	public void aProjectileSpecialIsTheFirstSplatOnItsDueTick()
	{
		final int due = 100 + SpecialWeapon.DORGESHUUN_CROSSBOW.getHitDelay(3);
		assertEquals(103, due);
		tracker.ownThrow(100, SpecialWeapon.DORGESHUUN_CROSSBOW, BOSS, BOSS_ID, "Boss", due, boss(), false);
		tracker.ownSplat(BOSS, 102, 40);
		tracker.tick(102);
		assertNull(bossState());
		tracker.ownSplat(BOSS, 103, 25);
		tracker.ownSplat(BOSS, 103, 2);
		tracker.tick(103);
		assertEquals(175, bossState().state.defence);
	}

	/** A splat on something else on the due tick is not the special's. */
	@Test
	public void onlyTheTargetsSplatsCount()
	{
		tracker.ownThrow(100, SpecialWeapon.BANDOS_GODSWORD, BOSS, BOSS_ID, "Boss", 101, boss(), false);
		tracker.ownSplat(OTHER, 101, 60);
		tracker.tick(101);
		assertEquals(1, tracker.pendingCount());
		tracker.tick(102);
		assertEquals(0, tracker.pendingCount());
		assertEquals(200, bossState().state.defence);
		assertEquals(DrainTracker.NO_SPLAT, bossState().events.get(0).skip);
		assertTrue(lines.get(0), lines.get(0).startsWith("DRAIN SKIP Bandos Godsword by me on Boss (npc 12345, index 7): "
			+ "no matching splat on tick 101"));
	}

	/** The Tonalztics' two glaives are the first two splats; one landing takes an eighth of the Magic once. */
	@Test
	public void tonalzticsCountsItsTwoGlaives()
	{
		tracker.ownThrow(100, SpecialWeapon.TONALZTICS_OF_RALOS, BOSS, BOSS_ID, "Boss", 102, boss(), false);
		tracker.ownSplat(BOSS, 102, 12);
		tracker.tick(102);
		assertEquals("waits for the second glaive", 1, tracker.pendingCount());
		tracker.ownSplat(BOSS, 102, 0);
		tracker.ownSplat(BOSS, 102, 3);
		tracker.tick(102);
		assertEquals(182, bossState().state.defence);
		assertEquals(1, bossState().events.get(0).hit);
	}

	/** The elder maul is resolved on its throw, from the hitpoints experience: its splat comes two ticks late. */
	@Test
	public void elderMaulFromItsExperience()
	{
		tracker.ownThrow(100, SpecialWeapon.ELDER_MAUL, BOSS, BOSS_ID, "Boss", 102, boss(), true);
		assertEquals(130, bossState().state.defence);
		tracker.ownThrow(110, SpecialWeapon.ELDER_MAUL, BOSS, BOSS_ID, "Boss", 112, boss(), false);
		assertEquals(130, bossState().state.defence);
		assertEquals(2, bossState().events.size());
	}

	@Test
	public void specialsThatDrainNothingAreLoggedAndLeft()
	{
		tracker.ownThrow(100, SpecialWeapon.BULWARK, BOSS, BOSS_ID, "Boss", 101, boss(), false);
		assertEquals(0, tracker.pendingCount());
		assertTrue(lines.get(0), lines.get(0).endsWith(DrainTracker.NOT_MODELLED));
		tracker.ownThrow(100, SpecialWeapon.DRAGON_WARHAMMER, -1, -1, null, 101, null, false);
		assertTrue(lines.get(1), lines.get(1).endsWith("no npc target"));
		tracker.ownThrow(100, SpecialWeapon.DRAGON_WARHAMMER, BOSS, BOSS_ID, "Boss", 101, null, false);
		assertTrue(lines.get(2), lines.get(2).endsWith("no stats for the target"));
		assertNull(bossState());
	}

	/** At an immune boss, and at a floor, nothing drains and the skip says why. */
	@Test
	public void immuneAndFloorSkips()
	{
		final DrainRule.Target verzik = new DrainRule.Target(150, 300, 100, 400, 400, 1, 0, 0, true, false, false,
			false);
		tracker.partyDrain(100, SpecialWeapon.DRAGON_WARHAMMER, 30, BOSS, BOSS_ID, "Verzik Vitur", verzik);
		assertEquals(DrainTracker.IMMUNE, bossState().events.get(0).skip);
		final DrainRule.Target nex = new DrainRule.Target(260, 230, 300, 315, 200, 100, 250, 0, false, false, false,
			false);
		tracker.partyDrain(100, SpecialWeapon.BANDOS_GODSWORD, 40, OTHER, 11278, "Nex", nex);
		tracker.partyDrain(101, SpecialWeapon.BANDOS_GODSWORD, 40, OTHER, 11278, "Nex", nex);
		final DrainTracker.Tracked n = tracker.tracked(OTHER);
		assertEquals(250, n.state.defence);
		assertTrue(n.events.get(0).floored);
		assertEquals(DrainTracker.AT_FLOOR, n.events.get(1).skip);
	}

	/** A party drain applies as it happened, and stacks with the player's own. */
	@Test
	public void partyDrainsStack()
	{
		tracker.partyDrain(100, SpecialWeapon.DRAGON_WARHAMMER, 25, BOSS, BOSS_ID, "Boss", boss());
		tracker.ownThrow(103, SpecialWeapon.DRAGON_WARHAMMER, BOSS, BOSS_ID, "Boss", 104, boss(), false);
		tracker.ownSplat(BOSS, 104, 40);
		tracker.tick(104);
		assertEquals(98, bossState().state.defence);
		assertTrue(lines.get(0), lines.get(0).contains("by party"));
	}

	/** The player's own special comes back as a party message: by member or by player id, it is dropped. */
	@Test
	public void partyEchoesAreTheirOwn()
	{
		final SpecialCounterUpdate mine = new SpecialCounterUpdate(BOSS, SpecialWeapon.DRAGON_WARHAMMER, 30, 330, 42);
		mine.setMemberId(5L);
		assertTrue(DefenceDrain.isEcho(mine, 5L, 99));
		assertTrue(DefenceDrain.isEcho(mine, 6L, 42));
		assertFalse(DefenceDrain.isEcho(mine, 6L, 99));
	}

	@Test
	public void aResetRestoresTheBase()
	{
		tracker.partyDrain(100, SpecialWeapon.DRAGON_WARHAMMER, 25, BOSS, BOSS_ID, "Sotetseg", boss());
		tracker.reset(BOSS, 150, "varbit 6447 = 2");
		assertEquals(200, bossState().state.defence);
		assertTrue(bossState().events.get(1).isReset());
		assertTrue(lines.get(1), lines.get(1).startsWith("DRAIN RESET Sotetseg"));
		// Nothing to restore: no event.
		tracker.reset(BOSS, 160, "again");
		assertEquals(2, bossState().events.size());
	}

	/** The npc goes: its stats go with it, and a special still in the air at it is dropped and logged. */
	@Test
	public void forgettingDropsWhatIsInTheAir()
	{
		tracker.partyDrain(100, SpecialWeapon.DRAGON_WARHAMMER, 25, BOSS, BOSS_ID, "Boss", boss());
		tracker.ownThrow(103, SpecialWeapon.SEERCULL, BOSS, BOSS_ID, "Boss", 106, boss(), false);
		tracker.forget(BOSS);
		assertNull(bossState());
		assertEquals(0, tracker.pendingCount());
		assertTrue(lines.get(1), lines.get(1).endsWith(DrainTracker.TARGET_GONE));
	}

	/**
	 * The fight's record: the Defence when it opened (after a party hammer before
	 * it), at the end, and the specials during it by weapon, thrown and landed.
	 */
	@Test
	public void theFightsRecord()
	{
		tracker.partyDrain(90, SpecialWeapon.DRAGON_WARHAMMER, 25, BOSS, BOSS_ID, "Boss", boss());
		tracker.ownThrow(100, SpecialWeapon.DRAGON_WARHAMMER, BOSS, BOSS_ID, "Boss", 101, boss(), false);
		tracker.ownSplat(BOSS, 101, 0);
		tracker.tick(101);
		tracker.ownThrow(106, SpecialWeapon.DRAGON_WARHAMMER, BOSS, BOSS_ID, "Boss", 107, boss(), false);
		tracker.ownSplat(BOSS, 107, 31);
		tracker.tick(107);
		tracker.partyDrain(110, SpecialWeapon.BANDOS_GODSWORD, 20, BOSS, BOSS_ID, "Boss", boss());
		final DrainRecord r = tracker.record(BOSS, 95);
		assertNotNull(r);
		assertEquals(140, r.defenceStart);
		assertEquals(78, r.defenceEnd);
		assertEquals(200, r.defenceBase);
		assertEquals(0, r.defenceFloor);
		assertEquals(2, r.specs.get("DRAGON_WARHAMMER")[0]);
		assertEquals(1, r.specs.get("DRAGON_WARHAMMER")[1]);
		assertEquals(2, r.ownThrown());
		assertEquals(1, r.ownLanded());
		assertEquals(1, r.partyDrains);
		assertEquals(3, r.events.size());
		assertNull(tracker.record(OTHER, 0));
		// Nothing thrown during it: the record starts and ends where the target stands.
		final DrainRecord quiet = tracker.record(BOSS, 200);
		assertEquals(78, quiet.defenceStart);
		assertEquals(78, quiet.defenceEnd);
		assertEquals(0, quiet.ownThrown());
	}

	/** A record for a target nothing was thrown at, started from what the glue knows of it. */
	@Test
	public void aTargetCanBeTrackedBeforeAnySpecial()
	{
		assertNull(tracker.track(BOSS, BOSS_ID, "Boss", null));
		assertNotNull(tracker.track(BOSS, BOSS_ID, "Boss", boss()));
		final DrainRecord r = tracker.record(BOSS, 0);
		assertEquals(200, r.defenceStart);
		assertEquals(200, r.defenceEnd);
	}

	/** Old events drop off past the cap, so a long session cannot grow the list without bound. */
	@Test
	public void eventsAreCapped()
	{
		for (int i = 0; i < DrainTracker.MAX_EVENTS + 10; i++)
		{
			tracker.partyDrain(i, SpecialWeapon.ARCLIGHT, 0, BOSS, BOSS_ID, "Boss", boss());
		}
		assertEquals(DrainTracker.MAX_EVENTS, bossState().events.size());
	}

	/** A splat a tick late is some later attack's, not the special's: it is given up on, as RuneLite's counter does. */
	@Test
	public void aLateSplatIsNotTheSpecials()
	{
		tracker.ownThrow(100, SpecialWeapon.DRAGON_WARHAMMER, BOSS, BOSS_ID, "Boss", 101, boss(), false);
		tracker.tick(101);
		assertEquals(1, tracker.pendingCount());
		tracker.ownSplat(BOSS, 102, 40);
		tracker.tick(102);
		assertEquals(0, tracker.pendingCount());
		assertEquals(200, bossState().state.defence);
		assertTrue(lines.get(0), lines.get(0).endsWith("no matching splat on tick 101"));
	}

	private static DrainRule.Target regenerating(int every)
	{
		return new DrainRule.Target(200, 150, 100, 200, 200, 100, 0, 0, false, false, false, false, every);
	}

	/** Drained levels come back one at a time, from the first drain, every regenTicks; one event holds the run. */
	@Test
	public void drainedLevelsRegenerate()
	{
		tracker.ownThrow(100, SpecialWeapon.DRAGON_WARHAMMER, BOSS, BOSS_ID, "Boss", 101, regenerating(10), false);
		tracker.ownSplat(BOSS, 101, 34);
		tracker.tick(101);
		assertEquals(140, bossState().state.defence);
		for (int tick = 102; tick <= 130; tick++)
		{
			tracker.tick(tick);
		}
		// Ticks 111, 121: two levels.
		assertEquals(142, bossState().state.defence);
		final List<DrainEvent> events = bossState().events;
		assertEquals(2, events.size());
		assertTrue(events.get(1).isRegen());
		assertEquals(2, events.get(1).hit);
		assertEquals(111, events.get(1).thrownTick);
		assertEquals(121, events.get(1).landedTick);
		assertEquals(142, events.get(1).defenceAfter);
		assertTrue(lines.get(lines.size() - 1), lines.get(lines.size() - 1).startsWith(
			"DRAIN REGEN Boss (npc 12345, index 7): tick 121, defence 141 -> 142 of 200"));
		// Regeneration is not a special: the record counts one thrown.
		assertEquals(1, tracker.record(BOSS, 0).ownThrown());
		assertEquals(142, tracker.record(BOSS, 0).defenceEnd);
	}

	/** Back at its base, regeneration stops changing anything; the Eye of ayak's bonus never comes back. */
	@Test
	public void regenerationStopsAtTheBase()
	{
		tracker.ownThrow(100, SpecialWeapon.EYE_OF_AYAK, BOSS, BOSS_ID, "Boss", 105, regenerating(5), false);
		tracker.ownSplat(BOSS, 105, 40);
		tracker.tick(105);
		assertEquals(60, bossState().state.magicDefence);
		for (int tick = 106; tick <= 200; tick++)
		{
			tracker.tick(tick);
		}
		assertEquals(60, bossState().state.magicDefence);
		assertEquals(1, bossState().events.size());
	}

	/** A form change carries the drains over (Zulrah), unless the rules restore them on the new form. */
	@Test
	public void aFormChangeCarriesOrRestores()
	{
		tracker.ownThrow(100, SpecialWeapon.DRAGON_WARHAMMER, BOSS, BOSS_ID, "Boss", 101, boss(), false);
		tracker.ownSplat(BOSS, 101, 34);
		tracker.tick(101);
		final DrainRule.Target enraged = new DrainRule.Target(235, 178, 100, 200, 200, 100, 0, 0, false, false, false,
			false);
		tracker.changed(BOSS, 120, BOSS_ID + 1, "Boss", enraged, false);
		assertEquals(140, bossState().state.defence);
		assertEquals(BOSS_ID + 1, bossState().npcId);
		assertEquals(235, bossState().target.defence);
		tracker.changed(BOSS, 130, BOSS_ID + 2, "Boss", boss(), true);
		assertEquals(200, bossState().state.defence);
		assertTrue(lines.get(lines.size() - 1), lines.get(lines.size() - 1).startsWith(
			"DRAIN RESET Boss (npc 12347, index 7): became npc 12347; defence 140 -> 200"));
		// The drain and the reset: a form change that carries the drains is not an event.
		assertEquals(2, bossState().events.size());
	}

	/** Kephri's shield replaces her actor: the drains move to the new one, and her last phase restores them. */
	@Test
	public void aReplacedActorKeepsItsDrains()
	{
		tracker.ownThrow(100, SpecialWeapon.DRAGON_WARHAMMER, BOSS, BOSS_ID, "Kephri", 101, boss(), false);
		tracker.ownSplat(BOSS, 101, 34);
		tracker.tick(101);
		tracker.forget(BOSS, "Kephri", 110);
		assertNull(tracker.tracked(BOSS));
		assertTrue(tracker.hasCarried());
		tracker.adopt("Kephri", OTHER, BOSS_ID + 1, "Kephri", boss(), 110, false);
		assertEquals(140, tracker.tracked(OTHER).state.defence);
		assertFalse(tracker.hasCarried());
		// The fight opened on the first actor still finds them.
		assertEquals(140, tracker.record(BOSS, 0).defenceEnd);
		assertEquals(1, tracker.record(BOSS, 0).ownThrown());
		// Her last phase, on yet another actor.
		tracker.forget(OTHER, "Kephri", 150);
		tracker.adopt("Kephri", 11, BOSS_ID + 2, "Kephri", boss(), 151, true);
		assertEquals(200, tracker.tracked(11).state.defence);
		assertTrue(lines.get(lines.size() - 1), lines.get(lines.size() - 1).contains("-> index 11 (npc 12347), restored"));
	}

	/** Waiting drains go after a few ticks, so a later kill of the same boss starts from its base. */
	@Test
	public void carriedDrainsExpire()
	{
		tracker.ownThrow(100, SpecialWeapon.DRAGON_WARHAMMER, BOSS, BOSS_ID, "Kephri", 101, boss(), false);
		tracker.ownSplat(BOSS, 101, 34);
		tracker.tick(101);
		tracker.forget(BOSS, "Kephri", 110);
		tracker.tick(110 + DrainTracker.CARRY_TICKS);
		assertTrue(tracker.hasCarried());
		tracker.tick(111 + DrainTracker.CARRY_TICKS);
		assertFalse(tracker.hasCarried());
		tracker.adopt("Kephri", OTHER, BOSS_ID, "Kephri", boss(), 130, false);
		assertNull(tracker.tracked(OTHER));
	}

	/** An undrained boss leaves nothing behind for the next actor. */
	@Test
	public void onlyDrainedStatsAreCarried()
	{
		tracker.track(BOSS, BOSS_ID, "Kephri", boss());
		tracker.forget(BOSS, "Kephri", 110);
		assertFalse(tracker.hasCarried());
	}
}
