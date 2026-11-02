package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * T-147: draining specials by players outside the RuneLite party, seen from their animation and weapon on the fight's
 * target, drained by their rule once their splat lands, once (a party member's counter wins), and kept as their own
 * source. Eric's 11-01 raids: his teammates' warhammers on Olm's left claw never reached the tracker.
 */
public class SeenSpecialsTest
{
	private static final int CLAW = 7;
	private static final int CLAW_ID = 7555;
	private static final int SLOT = 412;

	private final List<String> lines = new ArrayList<>();
	private final DrainTracker tracker = new DrainTracker(lines::add);

	/** Olm's left claw as a party of 3 leaves it: Defence 178, no floor. */
	private static DrainRule.Target claw()
	{
		return new DrainRule.Target(178, 178, 200, 250, 250, 250, 0, 0, false, false, false, false, 100);
	}

	/** Tekton (209 for a party of 3): a missed warhammer still takes 5%. */
	private static DrainRule.Target tekton()
	{
		return new DrainRule.Target(209, 209, 0, 1, 1, 1, 0, 0, false, false, true, false, 15);
	}

	private void ticks(int from, int to)
	{
		for (int t = from; t <= to; t++)
		{
			tracker.tick(t);
		}
	}

	private DrainEvent last()
	{
		final List<DrainEvent> events = tracker.tracked(CLAW).events;
		return events.get(events.size() - 1);
	}

	@Test
	public void anotherPlayersHammerThatLands()
	{
		tracker.seenThrow(100, SpecialWeapon.DRAGON_WARHAMMER, SLOT, CLAW, CLAW_ID, "Great Olm (Left claw)", 101, claw());
		tracker.othersSplat(CLAW, 101, 40);
		ticks(100, 103);
		// Waiting for a party counter to send it.
		assertNull(tracker.tracked(CLAW));
		assertEquals(1, tracker.seenCount());
		tracker.tick(104);
		assertEquals(0, tracker.seenCount());
		assertEquals(125, tracker.tracked(CLAW).state.defence);
		final DrainEvent e = last();
		assertTrue(e.seen);
		assertFalse(e.own);
		assertTrue(e.landed);
		assertTrue(lines.toString(), lines.get(0).startsWith("SEEN Dragon Warhammer special by player slot 412 (outside the"
			+ " party) at Great Olm (Left claw) (npc 7555, index 7): thrown 100, its splat due 101"));
		assertTrue(lines.toString(), lines.get(1).contains("splat 40 on tick 101 of [40] (landed)"));
		assertTrue(lines.toString(), lines.get(2).startsWith("DRAIN Dragon Warhammer by a player outside the party on Great"
			+ " Olm (Left claw)"));
		// Its own source in the record.
		final DrainRecord r = tracker.record(CLAW, 0);
		assertEquals(1, r.sources.size());
		assertTrue(r.sources.get(0).seen);
		assertEquals(Integer.valueOf(53), r.sources.get(0).drained.get(DrainRecord.StatDrain.DEFENCE));
		assertEquals("DRAGON_WARHAMMER (seen)", DrainRecord.sourceKey("DRAGON_WARHAMMER", false, true));
	}

	@Test
	public void oneThatMisses()
	{
		tracker.seenThrow(100, SpecialWeapon.DRAGON_WARHAMMER, SLOT, CLAW, CLAW_ID, "Great Olm (Left claw)", 101, claw());
		tracker.othersSplat(CLAW, 101, 0);
		ticks(100, 104);
		assertEquals(178, tracker.tracked(CLAW).state.defence);
		assertFalse(last().landed);
		assertTrue(last().seen);
		// At Tekton a miss still takes 5% (wiki, Dragon warhammer).
		tracker.seenThrow(200, SpecialWeapon.DRAGON_WARHAMMER, SLOT, 9, 7541, "Tekton", 201, tekton());
		tracker.othersSplat(9, 201, 0);
		ticks(200, 204);
		assertEquals(199, tracker.tracked(9).state.defence);
	}

	/** The claw with no regeneration, for a test whose throws are ticks apart. */
	private static DrainRule.Target clawHeld()
	{
		return new DrainRule.Target(178, 178, 200, 250, 250, 250, 0, 0, false, false, false, false, 0);
	}

	@Test
	public void twoOthersSplatsOnItsTick()
	{
		// Both hit: a warhammer's drain is set by its landing, so it landed whichever was its.
		tracker.seenThrow(100, SpecialWeapon.DRAGON_WARHAMMER, SLOT, CLAW, CLAW_ID, "Great Olm (Left claw)", 101, clawHeld());
		tracker.othersSplat(CLAW, 101, 30);
		tracker.othersSplat(CLAW, 101, 45);
		ticks(100, 104);
		assertEquals(125, tracker.tracked(CLAW).state.defence);
		// One hit, one missed: which was the hammer's can't be told. Nothing drained, and the line says so.
		tracker.seenThrow(200, SpecialWeapon.DRAGON_WARHAMMER, SLOT, CLAW, CLAW_ID, "Great Olm (Left claw)", 201, clawHeld());
		tracker.othersSplat(CLAW, 201, 0);
		tracker.othersSplat(CLAW, 201, 45);
		ticks(200, 204);
		assertEquals(125, tracker.tracked(CLAW).state.defence);
		assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.contains("not drained, 2 others' splats [0, 45]"
			+ " that don't say which on tick 201")));
		// A godsword drains by its damage, so two hits don't say how much: nothing.
		assertNull(DrainTracker.seenOutcome(SpecialWeapon.BANDOS_GODSWORD, Arrays.asList(30, 45)));
		assertEquals(30, DrainTracker.seenOutcome(SpecialWeapon.BANDOS_GODSWORD, Arrays.asList(30))[0]);
	}

	@Test
	public void noOthersSplatIsNothing()
	{
		tracker.seenThrow(100, SpecialWeapon.DRAGON_WARHAMMER, SLOT, CLAW, CLAW_ID, "Great Olm (Left claw)", 101, claw());
		ticks(100, 104);
		assertNull(tracker.tracked(CLAW));
		assertEquals(0, tracker.seenCount());
		assertTrue(lines.toString(), lines.get(lines.size() - 1).contains("not drained, no others' splat on tick 101"));
	}

	@Test
	public void aPartyMembersCounterAlsoSendsIt()
	{
		tracker.seenThrow(100, SpecialWeapon.DRAGON_WARHAMMER, SLOT, CLAW, CLAW_ID, "Great Olm (Left claw)", 101, claw());
		tracker.othersSplat(CLAW, 101, 40);
		ticks(100, 102);
		tracker.partyDrain(102, SpecialWeapon.DRAGON_WARHAMMER, 40, CLAW, CLAW_ID, "Great Olm (Left claw)", claw());
		ticks(103, 110);
		// Once, by the counter.
		assertEquals(1, tracker.tracked(CLAW).events.size());
		assertFalse(last().seen);
		assertEquals(125, tracker.tracked(CLAW).state.defence);
		assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.contains("a party member's counter sent it, so it's"
			+ " theirs")));
		// Another weapon's counter message doesn't take it.
		tracker.seenThrow(200, SpecialWeapon.DRAGON_WARHAMMER, SLOT, CLAW, CLAW_ID, "Great Olm (Left claw)", 201, claw());
		tracker.othersSplat(CLAW, 201, 40);
		tracker.partyDrain(201, SpecialWeapon.BANDOS_GODSWORD, 20, CLAW, CLAW_ID, "Great Olm (Left claw)", claw());
		ticks(200, 205);
		assertTrue(last().seen);
	}

	@Test
	public void anAnimationWithAnotherWeaponIsNothing()
	{
		assertEquals(SpecialWeapon.DRAGON_WARHAMMER, DefenceDrain.seenSpecial(ItemID.DRAGON_WARHAMMER,
			AnimationID.DRAGON_WARHAMMER_SA_PLAYER));
		assertEquals(SpecialWeapon.BANDOS_GODSWORD, DefenceDrain.seenSpecial(ItemID.BGS, AnimationID.BGS_SPECIAL_PLAYER));
		assertEquals(SpecialWeapon.BANDOS_GODSWORD, DefenceDrain.seenSpecial(ItemID.BGS,
			AnimationID.BGS_SPECIAL_ORNATE_PLAYER));
		// The hammer's animation with a godsword in hand, or a whip: nothing.
		assertNull(DefenceDrain.seenSpecial(ItemID.BGS, AnimationID.DRAGON_WARHAMMER_SA_PLAYER));
		assertNull(DefenceDrain.seenSpecial(ItemID.ABYSSAL_WHIP, AnimationID.DRAGON_WARHAMMER_SA_PLAYER));
		// The hammer in hand with its ordinary attack's animation: nothing.
		assertNull(DefenceDrain.seenSpecial(ItemID.DRAGON_WARHAMMER, AnimationID.BGS_SPECIAL_PLAYER));
	}

	@Test
	public void theCardLabelsItSeen()
	{
		final SidePanelModel.StatPart p = new SidePanelModel.StatPart();
		final SidePanelModel.SourcePart party = p.source("DRAGON_WARHAMMER", false);
		final SidePanelModel.SourcePart seen = p.source("DRAGON_WARHAMMER", false, true);
		assertTrue(party != seen);
		assertTrue(seen.seen);
		assertEquals(2, p.sources.size());
	}
}
