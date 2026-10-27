package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

// T-026: a buff kept from its chat messages, with its duration as the fallback for a fade the client never showed.
public class TimedBuffTest
{
	@Test
	public void assumedDownUntilSeen()
	{
		final TimedBuff mark = new TimedBuff("MARK");
		assertFalse(mark.isUp());
		assertEquals("down (assumed down: not seen since the plugin started)", mark.describe());
		assertNull(mark.tick(1000));
		assertNull(mark.reset("logged out"));
		assertEquals("down (assumed down: logged out)", mark.describe());
	}

	@Test
	public void castRecastWarnFade()
	{
		final TimedBuff mark = new TimedBuff("MARK");
		assertEquals("MARK up: cast on tick 10 (x), lasts 297 ticks, to tick 307", mark.cast(10, 297, "x"));
		assertTrue(mark.isUp());
		assertEquals("UP (cast on tick 10, x, ends tick 307)", mark.describe());
		assertEquals("MARK refreshed: cast on tick 200, lasts 297 ticks, to tick 497", mark.cast(200, 297, ""));
		assertEquals(200, mark.castTick());
		assertEquals("MARK ending: warning on tick 480, ends tick 490 (estimated 497)", mark.warned(480, 10));
		assertEquals(490, mark.endTick());
		assertEquals("MARK down: faded on tick 490 (expected 490)", mark.faded(490));
		assertFalse(mark.isUp());
		assertEquals(-1, mark.endTick());
		assertEquals("down (faded on tick 490)", mark.describe());
	}

	@Test
	public void theDurationEndsItWhenNoFadeIsSeen()
	{
		final TimedBuff charge = new TimedBuff("CHARGE");
		charge.cast(0, 700, "");
		assertNull(charge.tick(700));
		assertNull(charge.tick(700 + TimedBuff.GRACE_TICKS));
		assertEquals("CHARGE down: no fade message 4 ticks after its expected end (tick 700), taken as expired",
			charge.tick(701 + TimedBuff.GRACE_TICKS));
		assertFalse(charge.isUp());
		assertNull(charge.tick(800));
	}

	/** A warning with no cast seen (the plugin started mid-buff): it was up after all, until then. */
	@Test
	public void aWarningAloneSaysItWasUp()
	{
		final TimedBuff mark = new TimedBuff("MARK");
		assertEquals("MARK up: its end warning on tick 50 with no cast seen; ends tick 60", mark.warned(50, 10));
		assertTrue(mark.isUp());
		assertEquals("MARK down: faded on tick 60 (expected 60)", mark.faded(60));
	}

	@Test
	public void aFadeWhenAlreadyDownSaysSo()
	{
		assertEquals("MARK down: faded on tick 5, already down", new TimedBuff("MARK").faded(5));
	}

	@Test
	public void aResetWhileUpIsLogged()
	{
		final TimedBuff mark = new TimedBuff("MARK");
		mark.cast(1, 297, "");
		assertEquals("MARK down: assumed down: hopped", mark.reset("hopped"));
		assertFalse(mark.isUp());
	}
}
