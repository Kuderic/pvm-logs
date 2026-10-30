package com.pvmlogs;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;

/**
 * T-099: rooms with a boss and its adds for the Damage card's Boss · Adds · All switch, each fight played through the
 * plugin's own calls onto one timeline store, as {@link SidePanelTimelineExamples#plan} plays one. Illustrative
 * figures, shaped on Eric's Araxxor kill (2026-10-29: the crossbow on the araxytes, always max hits), the Sire and
 * Zulrah. Shared by {@link SidePanelDamageTargetTest} and {@link SidePanelPictures}.
 */
final class SidePanelTargetExamples
{
	private SidePanelTargetExamples()
	{
	}

	/** One fight in the room: its target and the stretches played on it, in turn. */
	static final class Leg
	{
		final String target;
		final int npcId;
		final int index;
		final SidePanelTimelineExamples.Stretch[] stretches;

		Leg(String target, int npcId, int index, SidePanelTimelineExamples.Stretch... stretches)
		{
			this.target = target;
			this.npcId = npcId;
			this.index = index;
			this.stretches = stretches;
		}
	}

	/** A room and its timelines: its fights in order, each with its own timeline in the one store. */
	static final class Room
	{
		final Encounter room;
		final FightTimeline.Store store;

		Room(Encounter room, FightTimeline.Store store)
		{
			this.room = room;
			this.store = store;
		}
	}

	/** The legs played one after another on one clock, each a fight of its own. */
	static Room room(String name, Leg... legs)
	{
		final int[] clock = {0};
		final FightTimeline.Store store = new FightTimeline.Store();
		store.setClock(() -> clock[0]);
		final Encounter room = new Encounter(name, null, SidePanelExamples.at(14, 0));
		int t = 0;
		for (Leg leg : legs)
		{
			final Fight f = new Fight(leg.target, leg.npcId, leg.index, 900, SidePanelExamples.at(14, 0) + t * 600L,
				null, 0);
			store.attach(f);
			final FightTimeline line = store.of(f);
			for (SidePanelTimelineExamples.Stretch st : leg.stretches)
			{
				for (int amount : st.damage)
				{
					clock[0] = t;
					f.recordAttackMade(false, 1.0, false);
					line.describeLatest(st.name, st.itemId, null, st.style, t);
					if (st.special)
					{
						line.markLatestSpecial();
					}
					f.recordExpected(st.maxHit, st.accuracy, st.expected, st.expected * st.expected / 3);
					f.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
					if (amount > 0)
					{
						f.recordDamageDealt(amount, 0L, true);
						line.attributeSplat(Integer.MIN_VALUE);
					}
					for (int k = 1; k < st.speed; k++)
					{
						clock[0] = ++t;
						f.recordTickSpent();
					}
					t++;
				}
				for (int k = 0; k < st.lostAfter; k++)
				{
					clock[0] = t++;
					f.recordTickLost(false);
				}
			}
			f.end(true, SidePanelExamples.at(14, 0) + t * 600L);
			room.add(f);
		}
		return new Room(room, store);
	}

	static SidePanelTimelineExamples.Stretch weapon(String name, int item, FightTimeline.Style style, int speed,
		int[] hits, double expected, int maxHit, double accuracy, int lostAfter)
	{
		return new SidePanelTimelineExamples.Stretch(name, item, style, false, speed, hits, expected, maxHit, accuracy,
			lostAfter);
	}

	/**
	 * Araxxor: the Noxious halberd on him (5 ticks), and the Zaryte crossbow on two Acidic and a Mirrorback araxyte
	 * between, every bolt a max hit on their low Defence.
	 */
	static Room araxxor()
	{
		final int index = 41940;
		return room("Araxxor",
			new Leg("Araxxor", NpcID.ARAXXOR, index, weapon("Noxious halberd", ItemID.NOXIOUS_HALBERD,
				FightTimeline.Style.MELEE, 5, SidePanelTimelineExamples.hits(18, 42, 0, 31, 55, 18, 0, 47), 27.5, 61, 0.62,
				3)),
			new Leg("Acidic Araxyte", NpcID.ARAXXOR_MINION_VENOM, 41950, weapon("Zaryte crossbow", ItemID.ZARYTE_XBOW,
				FightTimeline.Style.RANGED, 5, new int[]{48, 48, 48}, 38, 48, 0.95, 1)),
			new Leg("Acidic Araxyte", NpcID.ARAXXOR_MINION_VENOM, 41951, weapon("Zaryte crossbow", ItemID.ZARYTE_XBOW,
				FightTimeline.Style.RANGED, 5, new int[]{48, 48}, 38, 48, 0.95, 2)),
			new Leg("Araxxor", NpcID.ARAXXOR, index, weapon("Noxious halberd", ItemID.NOXIOUS_HALBERD,
				FightTimeline.Style.MELEE, 5, SidePanelTimelineExamples.hits(16, 38, 51, 0, 29, 44, 0), 27.5, 61, 0.62, 2),
				weapon("Zaryte crossbow", ItemID.ZARYTE_XBOW, FightTimeline.Style.RANGED, 5,
					SidePanelTimelineExamples.hits(4, 33, 0, 41), 24, 52, 0.55, 2)),
			new Leg("Mirrorback Araxyte", NpcID.ARAXXOR_MINION_MIRRORBACK, 41952, weapon("Zaryte crossbow",
				ItemID.ZARYTE_XBOW, FightTimeline.Style.RANGED, 5, new int[]{48, 48, 48}, 38, 48, 0.95, 1)),
			new Leg("Araxxor", NpcID.ARAXXOR, index, weapon("Noxious halberd", ItemID.NOXIOUS_HALBERD,
				FightTimeline.Style.MELEE, 5, SidePanelTimelineExamples.hits(14, 45, 0, 36, 52, 21), 27.5, 61, 0.62, 0)));
	}

	/** The Sire: Emberlight on him in his forms, the respiratory systems (not scored) between. */
	static Room sire()
	{
		final SidePanelTimelineExamples.Stretch lungs = weapon("Emberlight", ItemID.EMBERLIGHT,
			FightTimeline.Style.MELEE, 4, new int[]{24, 26}, 20, 46, 0.85, 1);
		return room("Abyssal Sire",
			new Leg("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_STASIS_AWAKE, 1, weapon("Emberlight", ItemID.EMBERLIGHT,
				FightTimeline.Style.MELEE, 4, SidePanelTimelineExamples.hits(10, 30, 0, 24, 41, 17), 18.2, 46, 0.8, 2)),
			new Leg("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 10, lungs),
			new Leg("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 11, lungs),
			new Leg("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 12, lungs),
			new Leg("Respiratory system", NpcID.ABYSSALSIRE_LUNG, 13, lungs),
			new Leg("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_PUPPET, 1, weapon("Emberlight", ItemID.EMBERLIGHT,
				FightTimeline.Style.MELEE, 4, SidePanelTimelineExamples.hits(24, 30, 24, 0, 41, 17, 33), 18.2, 46, 0.8, 0)));
	}

	/** Zulrah: the Twisted bow on its green and blue forms, and a snakeling (not scored) swatted between. */
	static Room zulrah()
	{
		return room("Zulrah",
			new Leg("Zulrah", NpcID.SNAKEBOSS_BOSS_RANGED, 3, weapon("Twisted bow", ItemID.TWISTED_BOW,
				FightTimeline.Style.RANGED, 5, SidePanelTimelineExamples.hits(12, 41, 0, 50, 38), 31.5, 58, 0.72, 2)),
			new Leg("Snakeling", NpcID.SNAKEBOSS_MINION_MAGIC, 20, weapon("Twisted bow", ItemID.TWISTED_BOW,
				FightTimeline.Style.RANGED, 5, new int[]{1}, 1, 1, 1, 1)),
			new Leg("Zulrah", NpcID.SNAKEBOSS_BOSS_MAGIC, 3, weapon("Twisted bow", ItemID.TWISTED_BOW,
				FightTimeline.Style.RANGED, 5, SidePanelTimelineExamples.hits(12, 47, 30, 0, 55), 33, 58, 0.74, 0)));
	}

	/** The room read as the panel reads a kill: its own view, and each side's of the switch. */
	static SidePanelModel.Input input(Room r)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, r.room);
		in.ended = true;
		in.killed = true;
		in.endMillis = SidePanelExamples.at(14, 6);
		in.view = FightTimeline.room(r.room, r.store);
		if (in.bossOnly != null)
		{
			in.bossOnly.view = FightTimeline.room(in.bossOnly.part, r.store);
			in.addsOnly.view = FightTimeline.room(in.addsOnly.part, r.store);
		}
		return in;
	}

	/** An ungrouped boss: the Leviathan's weapons kill, which has no adds. */
	static SidePanelModel.Input ungrouped()
	{
		return SidePanelExamples.leviathanWeapons();
	}

	/** The fights of a room, for tests. */
	static List<Fight> fights(Room r)
	{
		return new ArrayList<>(r.room.getFights());
	}
}
