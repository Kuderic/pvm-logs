package com.pvmlogs;

/**
 * T-223 (Eric, 2026-11-26 01:55: "sync the redraw of the side panel to sync up with the player's attack"): when the
 * panel builds during a fight. On the tick an attack of the player's is booked (its damage and expected damage arrive
 * together then), at most once every {@link #BOOKING_GAP_TICKS} ticks: a booking sooner than that waits for the third
 * tick. Otherwise every {@link PvmPerformancePanel#UPDATE_TICKS} ticks, as before (T-046), so the clock and the idle strip
 * keep moving through gaps, eating and mechanics; but not while the player's next attack is due, by the rhythm of their
 * last two (a 6- or 7-tick weapon's), so it doesn't build a tick before the booking and then make the booking wait.
 *
 * <p>The booking is read from one counter a tick, the trip's attacks made ({@link SessionTotals#getAttacksMade}); a
 * change in it is a booking. Out of a fight, on a change of what's on show, or when forced: at once, as before. Client
 * thread.
 */
final class SidePanelCadence
{
	/** The fewest ticks between two builds that a booking starts. */
	static final int BOOKING_GAP_TICKS = 3;
	/** The longest gap between two attacks taken as a rhythm to wait for (a 7-tick weapon's); slower ones don't hold. */
	static final int RHYTHM_MAX_TICKS = 7;
	/** However the rhythm reads, a fight's panel builds again within this many ticks (4.8 s). */
	static final int HOLD_MAX_TICKS = 8;

	/** The tick of the last build; very old before the first. */
	private long builtAt = Long.MIN_VALUE / 2;
	/** The counter when the last build was made: a booking since then is one the panel hasn't shown. */
	private int bookingsAtBuild;
	/** The counter as last read, the tick it last changed, and the gap between its last two changes (0: not known). */
	private int seen;
	private long bookedAt = Long.MIN_VALUE / 2;
	private int rhythm;

	/**
	 * Reads this tick's counter (every tick, built or not: one int) and says whether the tick builds, before its key is
	 * compared. {@code fighting}: the same fight still running, its first attack made; out of one the rhythm is forgotten.
	 */
	boolean due(long tick, int bookings, boolean force, boolean fighting, boolean sameShape, boolean live)
	{
		if (bookings != seen)
		{
			if (fighting && bookedAt > Long.MIN_VALUE / 2)
			{
				rhythm = (int) Math.min(Integer.MAX_VALUE, tick - bookedAt);
			}
			seen = bookings;
			bookedAt = tick;
		}
		if (!fighting)
		{
			rhythm = 0;
		}
		return due(force, fighting, sameShape, live, tick - builtAt, bookings != bookingsAtBuild, tick - bookedAt, rhythm);
	}

	/** The tick built: what it showed is the counter as read. */
	void built(long tick)
	{
		builtAt = tick;
		bookingsAtBuild = seen;
	}

	/**
	 * The rule, alone. {@code booked}: an attack booked since the last build; {@code sinceBooking}: ticks since the
	 * last one; {@code rhythm}: the ticks between the last two (0: not known).
	 */
	static boolean due(boolean force, boolean fighting, boolean sameShape, boolean live, long sinceBuild, boolean booked,
		long sinceBooking, int rhythm)
	{
		if (force || !fighting || !sameShape)
		{
			return true;
		}
		if (!live)
		{
			return false;
		}
		if (booked)
		{
			return sinceBuild >= BOOKING_GAP_TICKS;
		}
		if (sinceBuild < PvmPerformancePanel.UPDATE_TICKS)
		{
			return false;
		}
		// The next attack is due by the player's rhythm: wait for it (a few ticks at most), not build a tick before it.
		final boolean nextDue = rhythm > 0 && rhythm <= RHYTHM_MAX_TICKS && sinceBooking < rhythm;
		return !nextDue || sinceBuild >= HOLD_MAX_TICKS;
	}
}
