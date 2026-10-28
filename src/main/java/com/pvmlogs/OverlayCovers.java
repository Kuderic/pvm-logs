package com.pvmlogs;

/**
 * What the fight overlay adds up (T-040): Arodab's "Overlay: whole trip" and
 * "Inside a raid, show" as one choice. Public because the config proxy returns
 * it, and a type it cannot reach throws on every read.
 */
public enum OverlayCovers
{
	/** The fight on show and its room: a boss's adds and phases, or a raid's room. */
	FIGHT("This fight"),
	/** The raid so far; outside a raid, the same as {@link #FIGHT}. */
	RAID("Whole raid"),
	/** Every scored fight since the trip started. */
	TRIP("Whole trip");

	private final String label;

	OverlayCovers(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
