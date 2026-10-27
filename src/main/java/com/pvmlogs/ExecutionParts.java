package com.pvmlogs;

/**
 * Which of Execution's parts the overlay lists under it: uptime, stat boost,
 * prayer and gear. Public because the config proxy returns it, and a type it
 * cannot reach throws on every read.
 */
public enum ExecutionParts
{
	ALL("All"),
	/** Only the parts that cost real time, by the same rule that colours them. */
	COSTING("Costing only"),
	OFF("Off");

	private final String label;

	ExecutionParts(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
