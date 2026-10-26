package com.pvmlogs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

// The boss health bar is only ours when its npc varp names the target. RuneLite's opponent info compares the varp with
// the npc's composition id, and so does the plugin.
public class HealthBarTest
{
	@Test
	public void theVarpNamesTheTargetByItsId()
	{
		assertTrue(PvmPerformancePlugin.healthBarNames(12223, 12223, 12223));
		// A transformed npc: either its composition's id or the id it wears now.
		assertTrue(PvmPerformancePlugin.healthBarNames(2042, 2042, 2043));
		assertTrue(PvmPerformancePlugin.healthBarNames(2043, 2042, 2043));
	}

	@Test
	public void anotherNpcsBarOrNoBarIsNotOurs()
	{
		assertFalse(PvmPerformancePlugin.healthBarNames(12214, 12223, 12223));
		assertFalse(PvmPerformancePlugin.healthBarNames(-1, 12223, 12223));
		assertFalse(PvmPerformancePlugin.healthBarNames(-1, -1, -1));
	}
}
