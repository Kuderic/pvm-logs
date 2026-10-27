package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Font;
import java.util.Arrays;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

/**
 * What the overlay decides outside the rows: which fonts the roles get from the
 * player's interface font, how wide the panel is, and when it's before the
 * first attack.
 */
public class PvmPerformanceOverlayTest
{
	private static PvmPerformanceOverlay.FontRoles roles(Font body)
	{
		return PvmPerformanceOverlay.FontRoles.of(body);
	}

	@Test
	public void regularBodyEmphasisesInBoldAndDetailsInSmall()
	{
		final Font body = FontManager.getRunescapeFont();
		final PvmPerformanceOverlay.FontRoles f = roles(body);
		assertSame(body, f.get(LedgerRows.FontRole.BODY));
		assertSame(FontManager.getRunescapeBoldFont(), f.get(LedgerRows.FontRole.EMPHASIS));
		assertSame(FontManager.getRunescapeSmallFont(), f.get(LedgerRows.FontRole.DETAIL));
	}

	@Test
	public void boldBodyKeepsBoldAndDetailsInRegular()
	{
		final Font body = FontManager.getRunescapeBoldFont();
		final PvmPerformanceOverlay.FontRoles f = roles(body);
		assertSame(body, f.get(LedgerRows.FontRole.EMPHASIS));
		assertSame(FontManager.getRunescapeFont(), f.get(LedgerRows.FontRole.DETAIL));
	}

	@Test
	public void smallBodyIsSmallThroughout()
	{
		final Font body = FontManager.getRunescapeSmallFont();
		final PvmPerformanceOverlay.FontRoles f = roles(body);
		assertSame(body, f.get(LedgerRows.FontRole.BODY));
		assertSame(body, f.get(LedgerRows.FontRole.EMPHASIS));
		assertSame(body, f.get(LedgerRows.FontRole.DETAIL));
	}

	@Test
	public void anyOtherFontEmphasisesByBoldingItself()
	{
		final Font body = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
		final PvmPerformanceOverlay.FontRoles f = roles(body);
		assertTrue(f.get(LedgerRows.FontRole.EMPHASIS).isBold());
		assertEquals(body.getFamily(), f.get(LedgerRows.FontRole.EMPHASIS).getFamily());
		assertEquals(14, f.get(LedgerRows.FontRole.EMPHASIS).getSize());
		assertSame(body, f.get(LedgerRows.FontRole.DETAIL));
	}

	@Test
	public void aRunescapeFontAtAnotherSizeCountsAsAnyOther()
	{
		// RuneLite allows the built-in fonts at any size; the bold and small fonts only come at 16.
		final Font body = FontManager.getRunescapeFont().deriveFont(20f);
		final PvmPerformanceOverlay.FontRoles f = roles(body);
		assertEquals(20, f.get(LedgerRows.FontRole.EMPHASIS).getSize());
		assertTrue(f.get(LedgerRows.FontRole.EMPHASIS).isBold());
		assertSame(body, f.get(LedgerRows.FontRole.DETAIL));
	}

	@Test
	public void widthIsTheWidestRowPlusTwentyHeldTo129And400()
	{
		assertEquals(129, PvmPerformanceOverlay.width(0));
		assertEquals(129, PvmPerformanceOverlay.width(109));
		assertEquals(130, PvmPerformanceOverlay.width(110));
		assertEquals(220, PvmPerformanceOverlay.width(200));
		assertEquals(400, PvmPerformanceOverlay.width(380));
		assertEquals(400, PvmPerformanceOverlay.width(900));
	}

	// ---- before the first attack ----

	private static Fight fight()
	{
		return new Fight("Vardorvis", NpcIds.GOBLIN, 1, 100, 0L, null, 0);
	}

	@Test
	public void aNewRoomIsBeforeTheFirstAttackWhileTheLastOneIsStillOnShow()
	{
		final Encounter last = new Encounter("Duke Sucellus", null, 0L);
		final Fight old = fight();
		old.recordAttackMade(false);
		last.add(old);
		// The new fight's room has nothing in it yet, so getDisplayEncounter still returns the last room.
		final Fight now = fight();
		new Encounter("Vardorvis", null, 0L).add(now);
		assertTrue(PvmPerformanceOverlay.isBeforeFirstAttack(now, last));
	}

	@Test
	public void theFirstFightOfTheSessionIsBeforeTheFirstAttack()
	{
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		final Fight now = fight();
		room.add(now);
		assertTrue(PvmPerformanceOverlay.isBeforeFirstAttack(now, room));
		assertTrue(PvmPerformanceOverlay.isBeforeFirstAttack(now, null));
	}

	@Test
	public void oneAttackEndsIt()
	{
		final Encounter room = new Encounter("Vardorvis", null, 0L);
		final Fight now = fight();
		room.add(now);
		now.recordAttackMade(false);
		assertFalse(PvmPerformanceOverlay.isBeforeFirstAttack(now, room));
	}

	@Test
	public void aNewAddInARoomUnderWayIsNotBeforeTheFirstAttack()
	{
		// Otherwise the panel would blink back to the loadout on every add in a grouped room.
		final Encounter room = new Encounter("Nylocas", null, 0L);
		final Fight first = fight();
		first.recordAttackMade(false);
		room.add(first);
		final Fight next = fight();
		room.add(next);
		assertFalse(PvmPerformanceOverlay.isBeforeFirstAttack(next, room));
	}

	@Test
	public void anEndedFightIsNeverBeforeTheFirstAttack()
	{
		final Fight done = fight();
		done.recordAttackMade(false);
		done.end(true, 1L);
		assertFalse(PvmPerformanceOverlay.isBeforeFirstAttack(done, null));
		assertFalse(PvmPerformanceOverlay.isBeforeFirstAttack(null, null));
	}

	// ---- built once a tick (T-016) ----

	@Test
	public void twoFramesInOneTickBuildOnce()
	{
		final PvmPerformanceOverlay.OncePerKey<String> lines = new PvmPerformanceOverlay.OncePerKey<>();
		final int[] built = {0};
		final java.util.function.Supplier<String> build = () -> "lines " + ++built[0];
		assertEquals("lines 1", lines.get(Arrays.asList(1000, "Vardorvis", 370), build));
		assertEquals("lines 1", lines.get(Arrays.asList(1000, "Vardorvis", 370), build));
		assertEquals(1, lines.builds());
		// A splat landing within the tick changes the key, and so does the next tick.
		assertEquals("lines 2", lines.get(Arrays.asList(1000, "Vardorvis", 388), build));
		assertEquals("lines 3", lines.get(Arrays.asList(1001, "Vardorvis", 388), build));
		assertEquals(3, lines.builds());
	}

	@Test
	public void nothingToShowIsKeptToo()
	{
		final PvmPerformanceOverlay.OncePerKey<String> lines = new PvmPerformanceOverlay.OncePerKey<>();
		assertNull(lines.get(Arrays.asList(1000), () -> null));
		assertNull(lines.get(Arrays.asList(1000), () -> "built again"));
		assertEquals(1, lines.builds());
	}

	// ---- the loadout's name (T-016) ----

	@Test
	public void theLoadoutIsNamedForTheWeaponAndTheSpellCast()
	{
		assertEquals("Abyssal tentacle", CombatCalc.attackName("Abyssal tentacle", null));
		assertEquals("Purging staff (Fire Surge)", CombatCalc.attackName("Purging staff", "Fire Surge"));
		assertEquals("Ice Barrage", CombatCalc.attackName(null, "Ice Barrage"));
		assertEquals("Unarmed", CombatCalc.attackName(null, null));
	}

	// ---- trip totals ----

	@Test
	public void tripKeepsThrallAndDotDamageUntilReset()
	{
		final SessionTotals trip = new SessionTotals(0L);
		trip.recordThrallDamage(80);
		trip.recordThrallDamage(4);
		trip.recordDotDamage(10);
		trip.recordDotDamage(-3);
		assertEquals(84, trip.getThrallDamage());
		assertEquals(10, trip.getDotDamage());
		// Kept out of damage dealt, as Fight keeps them.
		assertEquals(0, trip.getDamageDealt());
		trip.reset(1L);
		assertEquals(0, trip.getThrallDamage());
		assertEquals(0, trip.getDotDamage());
	}

	// ---- fitting the attack's name to a width the player saved (Eric, 2026-10-26) ----

	private static LedgerRows.Row name(String left, String shortLeft)
	{
		return new LedgerRows.Row(left, "5.5 DPS", LedgerRows.FontRole.EMPHASIS, LedgerRows.FontRole.EMPHASIS,
			LedgerRows.Colour.NEUTRAL, LedgerRows.Colour.NEUTRAL, shortLeft);
	}

	/** 7 px a character. */
	private static int sevens(String text)
	{
		return text.length() * 7;
	}

	@Test
	public void aNameThatFitsIsLeftAlone()
	{
		final LedgerRows.Row row = name("Abyssal tentacle", "Abyssal tentacle");
		// 112 + 49 + the 6 px gap + the 8 px border = 175.
		assertSame(row, PvmPerformanceOverlay.fit(row, PvmPerformanceOverlayTest::sevens, 49, 175));
	}

	@Test
	public void aNameTooWideForTheSavedWidthIsCutRatherThanWrapped()
	{
		final LedgerRows.Row row = name("Abyssal tentacle", "Abyssal tentacle");
		assertEquals("Abyssal tent\u2026", PvmPerformanceOverlay.fit(row, PvmPerformanceOverlayTest::sevens, 49, 154).left);
		// Only the name changes.
		assertEquals("5.5 DPS", PvmPerformanceOverlay.fit(row, PvmPerformanceOverlayTest::sevens, 49, 154).right);
	}

	@Test
	public void aStaffAndSpellFallsBackToTheSpellBeforeItsCut()
	{
		final LedgerRows.Row row = name("Harmonised nightmare staff (Fire Surge)", "Fire Surge");
		assertEquals("Fire Surge", PvmPerformanceOverlay.fit(row, PvmPerformanceOverlayTest::sevens, 49, 175).left);
		assertEquals("Fire\u2026", PvmPerformanceOverlay.fit(row, PvmPerformanceOverlayTest::sevens, 49, 98).left);
	}

	@Test
	public void aRowWithNoShortFormIsLeftToRuneLite()
	{
		final LedgerRows.Row row = new LedgerRows.Row(" thrall dmg", "80", LedgerRows.FontRole.DETAIL,
			LedgerRows.FontRole.DETAIL, LedgerRows.Colour.NEUTRAL, LedgerRows.Colour.NEUTRAL);
		assertSame(row, PvmPerformanceOverlay.fit(row, PvmPerformanceOverlayTest::sevens, 14, 60));
	}

	@Test
	public void damageExpectedFallsBackToDamageRatherThanRunIntoItsFigures()
	{
		// One word, so RuneLite can't wrap it: it would draw over "688/778".
		final LedgerRows.Row row = new LedgerRows.Row("Damage/expected", "688/778", LedgerRows.FontRole.EMPHASIS,
			LedgerRows.FontRole.EMPHASIS, LedgerRows.Colour.NEUTRAL, LedgerRows.Colour.COST, "Damage");
		// 105 + 49 + 6 + 8 = 168 fits; one pixel less and it reads "Damage".
		assertSame(row, PvmPerformanceOverlay.fit(row, PvmPerformanceOverlayTest::sevens, 49, 168));
		assertEquals("Damage", PvmPerformanceOverlay.fit(row, PvmPerformanceOverlayTest::sevens, 49, 167).left);
	}

	@Test
	public void withNoSavedWidthTheCapIsTheLimit()
	{
		assertEquals(PvmPerformanceOverlay.MAX_WIDTH, PvmPerformanceOverlay.savedWidth(null));
		assertEquals(PvmPerformanceOverlay.MAX_WIDTH, PvmPerformanceOverlay.savedWidth(new java.awt.Dimension(0, 0)));
		assertEquals(180, PvmPerformanceOverlay.savedWidth(new java.awt.Dimension(180, 0)));
	}

	// ---- T-030: the spec's two rows are no wider than the one they replace ----

	@Test
	public void theSpecsTwoRowsFitWhereTheOldOneDid()
	{
		final java.awt.Graphics2D g = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
			.createGraphics();
		for (Font body : Arrays.asList(FontManager.getRunescapeFont(), FontManager.getRunescapeBoldFont(),
			FontManager.getRunescapeSmallFont()))
		{
			final java.awt.FontMetrics m = g.getFontMetrics(PvmPerformanceOverlay.FontRoles.of(body).detail);
			final int old = m.stringWidth(" spec") + m.stringWidth("50 max \u00b7 71% \u00b7 17.8 dmg");
			assertTrue(body.getName(), m.stringWidth(" spec") + m.stringWidth("48 max \u00b7 71%") <= old);
			assertTrue(body.getName(), m.stringWidth(" spec damage") + m.stringWidth("26.0 (8.2 burn)") <= old);
		}
	}

	// ---- T-029: Luck's band gives up its seconds on a narrow panel ----

	@Test
	public void luckDropsItsSecondsWhenThePanelIsTooNarrow()
	{
		final LedgerRows.Row luck = new LedgerRows.Row("Luck", "bottom 20% (14 s slower)", LedgerRows.FontRole.EMPHASIS,
			LedgerRows.FontRole.EMPHASIS, LedgerRows.Colour.NEUTRAL, LedgerRows.Colour.COST, null, "bottom 20%");
		// 4 + 24 characters at 7 px, the 6 px gap and the 8 px border: 210.
		assertSame(luck, PvmPerformanceOverlay.fit(luck, PvmPerformanceOverlayTest::sevens, PvmPerformanceOverlayTest::sevens,
			210));
		final LedgerRows.Row narrow = PvmPerformanceOverlay.fit(luck, PvmPerformanceOverlayTest::sevens,
			PvmPerformanceOverlayTest::sevens, 209);
		assertEquals("bottom 20%", narrow.right);
		assertEquals("Luck", narrow.left);
		assertEquals(LedgerRows.Colour.COST, narrow.rightColour);
	}

	@Test
	public void theLuckBandFitsWhereDamageExpectedDoes()
	{
		final java.awt.Graphics2D g = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
			.createGraphics();
		for (Font body : Arrays.asList(FontManager.getRunescapeFont(), FontManager.getRunescapeBoldFont(),
			FontManager.getRunescapeSmallFont()))
		{
			final java.awt.FontMetrics m = g.getFontMetrics(PvmPerformanceOverlay.FontRoles.of(body).emphasis);
			final int damage = m.stringWidth("Damage/expected") + m.stringWidth("688/778");
			// The short form always fits where Damage/expected does; the full one may need the short form.
			assertTrue(body.getName(), m.stringWidth("Luck") + m.stringWidth("bottom 20%") <= damage);
		}
	}
}
