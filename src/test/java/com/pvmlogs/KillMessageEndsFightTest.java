package com.pvmlogs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * T-174: the Wardens' phase 3 ran 99 ticks past its kill. At [84909] of Eric's 11-04 log (build {@code 8e328d6}) the
 * kill message came for Tumeken's Warden (npc 11762, index 57956) and, on the same tick, the warden left view with its
 * bar at 0: held as gone alive (T-088), its fight ran to an idle close at [85008]. The same at [27395] of the 11-03 log.
 */
public class KillMessageEndsFightTest
{
	private static final int WARDEN = 57956;

	@Test
	public void leavingViewOnTheKillMessagesTickIsTheDeath()
	{
		assertTrue(PvmPerformancePlugin.killedByMessage(84909, WARDEN, WARDEN, 84909));
		assertTrue("or the tick after it", PvmPerformancePlugin.killedByMessage(84909, WARDEN, WARDEN, 84910));
		// And so the despawn is a death, whatever the dead flag or the count says.
		assertTrue(PvmPerformancePlugin.diedOnDespawn(
			PvmPerformancePlugin.killedByMessage(84909, WARDEN, WARDEN, 84909), 1000));
	}

	@Test
	public void withNoKillMessageABossOutOfViewIsStillHeld()
	{
		assertFalse("no message", PvmPerformancePlugin.killedByMessage(Integer.MIN_VALUE, -1, WARDEN, 84909));
		assertFalse("another npc's", PvmPerformancePlugin.killedByMessage(84909, WARDEN, WARDEN + 1, 84909));
		assertFalse("long after it", PvmPerformancePlugin.killedByMessage(84909, WARDEN, WARDEN, 84911));
		assertFalse("before it", PvmPerformancePlugin.killedByMessage(84909, WARDEN, WARDEN, 84908));
		// Gone alive, not flagged dead and with hitpoints counted left: T-088's hold, as before.
		assertFalse(PvmPerformancePlugin.diedOnDespawn(
			PvmPerformancePlugin.killedByMessage(Integer.MIN_VALUE, -1, WARDEN, 84909), 120));
	}
}
