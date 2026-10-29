package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import org.junit.Test;

/**
 * The boss's name in its own colour at the head of the fight overlay (Eric,
 * 2026-10-28): themed per boss, the accent for a boss with none, white for
 * anything else; kept after the kill; a raid's rooms in the raid's.
 */
public class BossColoursTest
{
	private static List<LedgerRows.Row> rows(LedgerRows.State state)
	{
		return LedgerRows.build(LedgerExamples.kill(), state, new LedgerRows.Settings());
	}

	@Test
	public void eachBossHasItsColourAndOthersStayWhite()
	{
		assertEquals(0xFF66B2, BossColours.of("Vardorvis"));
		assertEquals(0x7CFC5A, BossColours.of("zulrah"));
		// Apostrophes and case don't matter: the hiscores spell some names differently from the game.
		assertEquals(BossColours.of("Kree'Arra"), BossColours.of("Kreearra"));
		assertEquals(BossColours.of("Phosani's Nightmare"), BossColours.of("The Nightmare"));
		// A boss with no colour of its own takes the accent; anything that isn't a boss, none.
		assertEquals(BossColours.ACCENT, BossColours.of("Mimic"));
		assertEquals(-1, BossColours.of("Goblin"));
		assertEquals(-1, BossColours.of((String) null));
		assertEquals(BossColours.of("Tombs of Amascut"), BossColours.of(RaidType.TOMBS_OF_AMASCUT));
		assertEquals(-1, BossColours.of((RaidType) null));
	}

	@Test
	public void everyHiscoresBossHasAColourAndNoneIsAColourTheOverlayGivesMeaningTo()
	{
		for (HiscoreSkill skill : HiscoreSkill.values())
		{
			if (skill.getType() != HiscoreSkillType.BOSS)
			{
				continue;
			}
			final int rgb = BossColours.of(skill.getName());
			assertTrue(skill.getName(), rgb >= 0);
			for (LedgerRows.Colour meaning : new LedgerRows.Colour[]{LedgerRows.Colour.NEUTRAL, LedgerRows.Colour.BETTER,
				LedgerRows.Colour.COST, LedgerRows.Colour.WARNING, LedgerRows.Colour.DEAD})
			{
				assertNotEquals(skill.getName() + " vs " + meaning, meaning.rgb, rgb);
			}
		}
	}

	@Test
	public void theTitleIsInTheBossColourRunningAndAfterTheKill()
	{
		final LedgerRows.Row running = rows(LedgerRows.State.fight("Vardorvis", false, false, false)).get(0);
		assertEquals(LedgerRows.Colour.BOSS, running.leftColour);
		assertEquals(0xFF66B2, running.leftRgb());
		// Eric: keep the boss's colour after the kill; "(dead)" says it in words.
		final LedgerRows.Row dead = rows(LedgerRows.State.fight("Vardorvis", true, true, false)).get(0);
		assertEquals("Vardorvis (dead)", dead.left);
		assertEquals(0xFF66B2, dead.leftRgb());
		final LedgerRows.Row pre = LedgerRows.build(null, LedgerRows.State.preAttack("Vardorvis"),
			new LedgerRows.Settings()).get(0);
		assertEquals(0xFF66B2, pre.leftRgb());
	}

	@Test
	public void somethingThatIsntABossIsWhiteThenRedAfterItsKillAsBefore()
	{
		final LedgerRows.Row running = rows(LedgerRows.State.fight("Goblin", false, false, false)).get(0);
		assertEquals(LedgerRows.Colour.NEUTRAL, running.leftColour);
		assertEquals(0xFFFFFF, running.leftRgb());
		final LedgerRows.Row dead = rows(LedgerRows.State.fight("Goblin", true, true, false)).get(0);
		assertEquals(LedgerRows.Colour.DEAD, dead.leftColour);
		assertEquals(LedgerRows.Colour.DEAD.rgb, dead.leftRgb());
	}

	@Test
	public void aRaidsRoomAndTheRaidTakeTheRaidsColour()
	{
		// The overlay gives a raid's room its raid's colour (PvmPerformanceOverlay.roomState).
		final Encounter room = new Encounter("Zebak", RaidType.TOMBS_OF_AMASCUT, 0L);
		final Fight zebak = new Fight("Zebak", NpcIds.GOBLIN, 1, 700, 0L, RaidType.TOMBS_OF_AMASCUT, 1);
		room.add(zebak);
		final LedgerRows.State state = PvmPerformanceOverlay.roomState(zebak, room, false, r -> null);
		assertEquals(BossColours.of("Tombs of Amascut"), rows(state).get(0).leftRgb());
		final LedgerRows.Row raid = rows(LedgerRows.State.raid("Theatre of Blood", 2, true, false)).get(0);
		assertEquals(BossColours.of("Theatre of Blood"), raid.leftRgb());
		// The trip has no boss.
		final LedgerRows.Row trip = rows(LedgerRows.State.trip(3, true, false)).get(0);
		assertEquals(0xFFFFFF, trip.leftRgb());
	}

	@Test
	public void afterTheKillOnlyTheWordDeadIsRed()
	{
		// Eric, 2026-10-28: "show only the '(dead)' word in red, so you get both signals".
		final LedgerRows.Row dead = rows(LedgerRows.State.fight("Vardorvis", true, true, false)).get(0);
		assertEquals("Vardorvis <col=ff7070>(dead)", dead.drawnLeft());
		// Running, ended without a kill, or cut to fit: drawn as it reads.
		assertEquals("Vardorvis", rows(LedgerRows.State.fight("Vardorvis", false, false, false)).get(0).drawnLeft());
		assertEquals("Vardorvis (ended)", rows(LedgerRows.State.fight("Vardorvis", true, false, false)).get(0).drawnLeft());
		assertEquals("Vardorvis (de\u2026", dead.withLeft("Vardorvis (de\u2026").drawnLeft());
		// Something that isn't a boss is all red after its kill, as before.
		assertEquals("Goblin (dead)", rows(LedgerRows.State.fight("Goblin", true, true, false)).get(0).drawnLeft());
	}

	@Test
	public void aCutNameKeepsItsColour()
	{
		final LedgerRows.Row title = rows(LedgerRows.State.fight("Vardorvis", false, false, false)).get(0);
		assertEquals(title.leftRgb(), title.withLeft("Vard…").leftRgb());
		assertEquals(title.leftRgb(), title.withRight("x").leftRgb());
	}
}
