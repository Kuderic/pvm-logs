package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

// The player's shield, armour, charge and poise damage: kept by kind on the fight, still part of damage dealt.
public class NotHitpointsTest
{
	@Test
	public void itIsKeptByKindBesideDamageDealt()
	{
		final Fight f = new Fight("Verzik Vitur", 8370, 1, 2000, 0L, null, 0);
		assertNull(f.getNotHitpoints());
		assertEquals(0, f.notHitpointsTotal());
		f.recordNotHitpoints("shield", 12);
		f.recordDamageDealt(12, 1L, true);
		f.recordNotHitpoints("shield", 8);
		f.recordDamageDealt(8, 2L, true);
		f.recordNotHitpoints("poise", 5);
		f.recordDamageDealt(5, 3L, true);
		assertEquals(Integer.valueOf(20), f.getNotHitpoints().get("shield"));
		assertEquals(25, f.notHitpointsTotal());
		// The expected side scores these attacks, so damage dealt keeps them (T-011 item 3, Eric's call).
		assertEquals(25, f.getDamageDealt());
	}
}
