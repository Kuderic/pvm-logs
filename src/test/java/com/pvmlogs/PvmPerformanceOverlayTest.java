package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Font;
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
}
