package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

// T-018: what the fight's target did, for the TAKEN line.
public class TargetActionsTest
{
	@Test
	public void itListsTheLastFewTicksOldestFirst()
	{
		final TargetActions seen = new TargetActions();
		seen.noteProjectile(97, 5, 2520, 1000, "", "me", 100);
		seen.noteAnimation(98, 5, 10340);
		assertEquals("proj 2520 at me t-3 lands t+0, anim 10340 t-2", seen.describe(100, 5));
	}

	@Test
	public void aProjectileSeenEveryFrameIsListedOnce()
	{
		final TargetActions seen = new TargetActions();
		for (int frame = 0; frame < 10; frame++)
		{
			seen.noteProjectile(97 + frame / 5, 5, 2520, 1000, "", "me", 100);
		}
		seen.noteProjectile(98, 5, 2521, 1030, "npc 12224 #7", "me", 101);
		assertEquals("proj 2520 at me t-3 lands t+0, proj 2521 from npc 12224 #7 at me t-2 lands t+1",
			seen.describe(100, 5));
	}

	@Test
	public void idleIsNotAnAnimationAndOldThingsDropOff()
	{
		final TargetActions seen = new TargetActions();
		seen.noteAnimation(90, 5, 10340);
		seen.noteAnimation(91, 5, -1);
		seen.noteAnimation(100, 5, 10341);
		assertEquals("anim 10341 t+0", seen.describe(100, 5));
		assertEquals("", seen.describe(100 + TargetActions.KEEP_TICKS + 1, 5));
	}

	@Test
	public void theLastTargetsActionsStayWithIt()
	{
		final TargetActions seen = new TargetActions();
		seen.noteAnimation(99, 5, 10340);
		seen.noteAnimation(100, 9, 8000);
		assertEquals("anim 8000 t+0", seen.describe(100, 9));
		seen.clear();
		assertEquals("", seen.describe(100, 9));
	}
}
