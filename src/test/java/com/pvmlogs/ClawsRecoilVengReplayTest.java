package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Eric's 23:08 log (2026-10-26), from master 3ab7315 (T-015 in): five Vardorvis
 * kills with burning claws, their specials and a greater ghost thrall, then a
 * ring of recoil, vengeance and a blowpipe check for T-017 Part B (Eric's OK to
 * keep it, 2026-10-26 23:16).
 *
 * <p>What it holds Part A to: burning claws' third splat, a tick after the
 * first two, stays the player's with the thrall out. Master judged the 2 on
 * 1611 the thrall's (IN_COOLDOWN, p=0.89) although the experience paid for it
 * exactly: the special had been spent at its booking, so the tick after read
 * as inside the cooldown.
 *
 * <p>What it shows for Part B, not yet acted on:
 * <ul>
 * <li>2742: two 1s on two Strangled with no attack and no experience, while the
 * ring of recoil was worn (it is gone by 2801). Recoil splats are isMine(),
 * type 16, and opened a fight.</li>
 * <li>2803: 112 Magic experience and nothing else, the vengeance cast. 2804: a
 * 10, type 16, that no experience paid for, booked as the blow; the blow's own
 * 20 lands on 2805 and is the extra below.</li>
 * <li>The log has no line for the hits Eric took, so when the reflected
 * damage lands against them, and how much, cannot be read from it.</li>
 * </ul>
 */
public class ClawsRecoilVengReplayTest
{
	private static final String LOG = "/com/pvmlogs/replay/vardorvis-claws-recoil-veng-20261026-2308.log";

	private static ThrallReplay replay() throws IOException
	{
		// As logged: x1.55, accuracy 0.45 and a 47 max with Piety and the potions.
		return new ThrallReplay(1.55, 0.45, 47, 4).run(LOG);
	}

	@Test
	public void burningClawsThirdSplatWithTheThrallOutStaysThePlayers() throws IOException
	{
		final ThrallReplay r = replay();
		for (int tick : new int[]{299, 1611})
		{
			final List<ThrallReplay.Judged> on = r.judgedBetween(tick, tick);
			assertEquals(on.toString(), 1, on.size());
			assertFalse(on.toString(), on.get(0).thralls);
			assertEquals(on.toString(), ThrallTracker.PlayerSplat.LATE_SPLAT_DUE, on.get(0).player);
		}
	}

	@Test
	public void theOnlyExtraIsTheBlowThatVengeanceLandedAhead() throws IOException
	{
		assertEquals(Collections.singletonList("2805: 20"), replay().extras);
	}
}
