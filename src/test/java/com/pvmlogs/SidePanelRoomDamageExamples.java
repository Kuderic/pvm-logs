package com.pvmlogs;

/** T-109: Araxxor kills with damage from his room (T-106), for the renders. Illustrative figures. */
final class SidePanelRoomDamageExamples
{
	private SidePanelRoomDamageExamples()
	{
	}

	/** An Araxxor kill with {@code room} damage from his room: Ruptura explosions and his own cleave. */
	static Encounter araxxor(int room, long start)
	{
		return SidePanelRoomDamageTest.araxxor(room, start);
	}
}
