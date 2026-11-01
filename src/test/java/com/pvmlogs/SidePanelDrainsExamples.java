package com.pvmlogs;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.gameval.NpcID;

/**
 * T-142: kills with T-141's per-stat and per-special drain records, for the Drains card's tests and renders.
 * Illustrative figures, shaped on Eric's Yama kills (2026-10-31) and T-141's cases. Each fight is a real {@link Fight}
 * with its record, read by {@link SidePanelModel#drain} as the panel reads a room.
 */
final class SidePanelDrainsExamples
{
	private SidePanelDrainsExamples()
	{
	}

	static DrainRecord.StatDrain stat(String stat, int base, int floor, int start, int end, int regen, double value)
	{
		final DrainRecord.StatDrain s = new DrainRecord.StatDrain();
		s.stat = stat;
		s.base = base;
		s.floor = floor;
		s.start = start;
		s.end = end;
		s.regenTicks = regen;
		s.value = value;
		return s;
	}

	static DrainRecord.SourceDrain source(String weapon, boolean own, int thrown, int landed, String stat, int drained,
		double value)
	{
		final DrainRecord.SourceDrain s = new DrainRecord.SourceDrain();
		s.weapon = weapon;
		s.own = own;
		s.thrown = thrown;
		s.landed = landed;
		if (drained > 0)
		{
			s.drained.put(stat, drained);
		}
		if (drained > 0 || value != 0)
		{
			s.valueByStat.put(stat, value);
		}
		s.value = value;
		return s;
	}

	/** A fight's record: its stats and sources, and the T-049 fields today's card reads, filled from them. */
	static DrainRecord record(List<DrainRecord.StatDrain> stats, List<DrainRecord.SourceDrain> sources)
	{
		final DrainRecord r = new DrainRecord();
		r.stats = stats;
		r.sources = sources;
		r.defenceStart = -1;
		r.defenceEnd = -1;
		r.magicStart = -1;
		r.magicEnd = -1;
		for (DrainRecord.StatDrain s : stats)
		{
			if (DrainRecord.StatDrain.DEFENCE.equals(s.stat))
			{
				r.defenceBase = s.base;
				r.defenceFloor = s.floor;
				r.defenceStart = s.start;
				r.defenceEnd = s.end;
			}
		}
		for (DrainRecord.SourceDrain s : sources)
		{
			if (s.own)
			{
				final int[] c = r.specs.computeIfAbsent(s.weapon, k -> new int[2]);
				c[0] += s.thrown;
				c[1] += s.landed;
			}
			else
			{
				r.partyDrains += s.thrown;
			}
		}
		return r;
	}

	/** The room's fights, one per record, at this boss; the drain value each record's stats sum to. */
	static Encounter room(String boss, int npcId, DrainRecord... records)
	{
		final Encounter room = new Encounter(boss, null, SidePanelExamples.at(14, 0));
		long t = SidePanelExamples.at(14, 0);
		for (DrainRecord r : records)
		{
			final Fight f = new Fight(boss, npcId, 1, 900, t, null, 0);
			f.recordDrain(r);
			double value = 0;
			for (DrainRecord.StatDrain s : r.stats == null ? new ArrayList<DrainRecord.StatDrain>() : r.stats)
			{
				value += s.value;
			}
			f.recordSpecOutcomes(3, 0, value, value);
			t += 60_000L;
			f.end(true, t);
			room.add(f);
		}
		return room;
	}

	/** The Fight tab after a kill at this room: today's kill fixture with the room's drains. */
	static SidePanelModel.Input kill(Encounter room, boolean magicUsesDefence)
	{
		final SidePanelModel.Drain d = SidePanelModel.drain(room);
		d.magicUsesDefence = magicUsesDefence;
		d.valueSeconds = d.valueDamage / 9;
		d.specLuckSeconds = 0.4;
		return SidePanelExamples.drainKill(room.getName(), d);
	}

	private static List<DrainRecord.StatDrain> stats(DrainRecord.StatDrain... s)
	{
		return new ArrayList<>(java.util.Arrays.asList(s));
	}

	private static List<DrainRecord.SourceDrain> sources(DrainRecord.SourceDrain... s)
	{
		return new ArrayList<>(java.util.Arrays.asList(s));
	}

	static final String DEF = DrainRecord.StatDrain.DEFENCE;
	static final String MAGIC = DrainRecord.StatDrain.MAGIC;
	static final String MDEF = DrainRecord.StatDrain.MAGIC_DEFENCE;

	/**
	 * Yama over two fights: the Emberlight lands 2 of 5 in the first (Defence 225 → 191, +30), and in the second his
	 * drained Defence adds 8 more with no special of that fight's; Soul Rend lands 3 of 3 there, 60 off his magic defence,
	 * the bonus his casts met +81 → +21 (phase 3), +27.
	 */
	static Encounter yama()
	{
		final DrainRecord.StatDrain md = stat(MDEF, 0, 0, 0, -60, 0, 27);
		md.bonusAtStart = 81;
		md.bonusAtEnd = 21;
		return room("Yama", NpcID.YAMA, record(stats(stat(DEF, 225, 145, 225, 191, 100, 30)),
			sources(source("EMBERLIGHT", true, 5, 2, DEF, 34, 30))),
			record(stats(stat(DEF, 225, 145, 191, 191, 100, 8), md), sources(source("EYE_OF_AYAK", true, 3, 3, MDEF, 60,
				27))));
	}

	/** General Graardor: a Dragon warhammer (1 of 2) and a Bandos godsword, both on his Defence. */
	static Encounter graardor()
	{
		return room("General Graardor", NpcID.GODWARS_BANDOS_AVATAR, record(stats(stat(DEF, 250, 0, 250, 113, 100, 147)),
			sources(source("DRAGON_WARHAMMER", true, 2, 1, DEF, 75, 96), source("BANDOS_GODSWORD", true, 1, 1, DEF, 62,
				51))));
	}

	/** The Abyssal Sire: the Emberlight alone. */
	static Encounter sire()
	{
		return room("Abyssal Sire", NpcID.ABYSSALSIRE_SIRE_PUPPET, record(stats(stat(DEF, 250, 0, 250, 174, 0, 42)),
			sources(source("EMBERLIGHT", true, 2, 2, DEF, 76, 42))));
	}

	/** Xarth at its floor: your two hammers (the second had nothing left) and a party member's. */
	static Encounter floorAndParty()
	{
		return room("Xarth", NpcID.GOBLIN, record(stats(stat(DEF, 170, 102, 170, 102, 0, 60)),
			sources(source("DRAGON_WARHAMMER", true, 2, 2, DEF, 34, 41), source("DRAGON_WARHAMMER", false, 1, 1, DEF, 34,
				19))));
	}

	/** Yama with the Emberlight landing and both Soul Rends unmatched (KC 356's, until T-143): landed 0 of 2, +0. */
	static Encounter soulRendsMissed()
	{
		return room("Yama", NpcID.YAMA, record(stats(stat(DEF, 225, 145, 225, 191, 100, 48)),
			sources(source("EMBERLIGHT", true, 2, 1, DEF, 34, 48), source("EYE_OF_AYAK", true, 2, 0, MDEF, 0, 0))));
	}

	/** Verzik-like: a boss whose magic rolls off its Defence, so the hammer's drain helps your magic too. */
	static Encounter magicOffDefence()
	{
		return room("Ice demon", NpcID.GOBLIN, record(stats(stat(DEF, 160, 0, 160, 112, 100, 55)),
			sources(source("DRAGON_WARHAMMER", true, 1, 1, DEF, 48, 55))));
	}

	/** A kill saved before T-141: a record without its per-stat fields. */
	static Encounter old()
	{
		final DrainRecord r = new DrainRecord();
		r.defenceBase = 250;
		r.defenceStart = 250;
		r.defenceEnd = 175;
		r.specs.put("DRAGON_WARHAMMER", new int[]{2, 1});
		return room("General Graardor", NpcID.GODWARS_BANDOS_AVATAR, r);
	}
}
