package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * The Fight tab's Targets list counts the NPCs of a name, not its fights (Eric, 2026-10-29 21:27: "it says araxxor x3"):
 * Araxxor's room has a fight on him per stretch between his araxytes, all on the one NPC.
 */
public class SidePanelTargetsTest
{
	private static final int ARAXXOR_INDEX = 41940;

	@Test
	public void araxxorsThreeStretchesAreOneAraxxor()
	{
		final List<String> names = names(araxxorKill());
		assertEquals("Araxxor", names.get(0));
		assertEquals(1, SidePanelModel.targets(araxxorKill()).get(0).npcs);
	}

	@Test
	public void twoAraxytesOfAKindAreTwo()
	{
		final List<String> names = names(araxxorKill());
		assertEquals("Acidic Araxyte ×2", names.get(1));
		assertEquals("Mirrorback Araxyte", names.get(2));
	}

	@Test
	public void theSiresPhasesAreUnchanged()
	{
		final List<Fight> fights = new ArrayList<>();
		fights.add(fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_STASIS_AWAKE, 1));
		for (int i = 0; i < 4; i++)
		{
			fights.add(fight("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 10 + i));
		}
		fights.add(fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_WANDERING, 1));
		fights.add(fight("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_PUPPET, 1));
		final List<String> names = names(fights);
		// Three forms of the one Sire: phases, never ×.
		assertEquals("Abyssal Sire", names.get(0));
		assertEquals("Respiratory system ×4", names.get(1));
		final PanelSnapshot.FightTab t = tab(fights);
		assertEquals("3 phases · 3 attacks · 0:07", t.targets.rows.get(0).sub.text);
	}

	@Test
	public void theSameNpcFoughtTwiceIsOne()
	{
		final List<Fight> fights = new ArrayList<>();
		fights.add(fight("Abyssal demon", 7241, 7));
		fights.add(fight("Abyssal demon", 7241, 8));
		fights.add(fight("Abyssal demon", 7241, 7));
		fights.add(fight("Greater abyssal demon", 7410, 9));
		assertEquals("Abyssal demon ×2", names(fights).get(0));
		assertEquals("Greater abyssal demon", names(fights).get(1));
	}

	/** Araxxor, two Acidic araxytes, Araxxor, a Mirrorback araxyte, Araxxor: as T-091's room holds a kill. */
	private static List<Fight> araxxorKill()
	{
		final List<Fight> fights = new ArrayList<>();
		fights.add(fight("Araxxor", NpcID.ARAXXOR, ARAXXOR_INDEX));
		fights.add(fight("Acidic Araxyte", NpcID.ARAXXOR_MINION_VENOM, 41950));
		fights.add(fight("Acidic Araxyte", NpcID.ARAXXOR_MINION_VENOM, 41951));
		fights.add(fight("Araxxor", NpcID.ARAXXOR, ARAXXOR_INDEX));
		fights.add(fight("Mirrorback Araxyte", NpcID.ARAXXOR_MINION_MIRRORBACK, 41952));
		fights.add(fight("Araxxor", NpcID.ARAXXOR, ARAXXOR_INDEX));
		return fights;
	}

	private static Fight fight(String name, int id, int index)
	{
		final Fight f = new Fight(name, id, index, 500, 0L, null, 0);
		f.recordAttackMade(false, 1.0);
		f.recordExpected(40, 0.7, 14);
		f.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
		f.recordDamageDealt(12, 0L, true);
		f.recordTickSpent();
		f.recordTickSpent();
		f.recordTickSpent();
		return f;
	}

	private static PanelSnapshot.FightTab tab(List<Fight> fights)
	{
		final Encounter room = new Encounter(fights.get(0).getTargetName(), null, 0L);
		fights.forEach(room::add);
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		return SidePanelModel.fight(in);
	}

	private static List<String> names(List<Fight> fights)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.ListRow r : tab(fights).targets.rows)
		{
			out.add(r.name.text);
		}
		return out;
	}
}
