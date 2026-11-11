package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.runelite.client.util.Text;

/**
 * T-209: which party members were in each room's fight. Eric, 2026-11-10: "Is there a way to tell which players are in
 * the fight with me?" after a duo Yama kill in a party of three, whose meter split "others" between both members not
 * running Luck vs Skill, though only one was there.
 *
 * <p>Two kinds of proof, either enough:
 * <ul>
 * <li><b>Seen:</b> while a fight is open, every {@link PartyShare#INTERVAL_TICKS} ticks (with the party update, never
 * a tick), the scene's players are matched to the party's members by name. In an instance anywhere in it (whoever is
 * in it is in the fight's instance); in the open world within {@link #OPEN_WORLD_TILES} of the fight's target.</li>
 * <li><b>Sharing:</b> a member running the plugin sent an update for a room of the same name since this room began:
 * the stronger proof, since it's their own fight.</li>
 * </ul>
 * Names are matched as RuneLite's party and the scene both give them, through {@link Text#toJagexName} (tags off; a
 * non-breaking space, underscore or hyphen read as a space; trimmed) and lower case, as Jagex names are. A member's
 * name as the party has it is read again at each scan, so a name change shows at the next one.
 *
 * <p>Kept with the room (by identity, weakly: a room no one holds goes). Only member ids are kept, and the log line
 * gives counts, never names.
 */
final class PartySeen
{
	/**
	 * The open world's reach around the target. A fight's attacks reach 10 tiles at most (a spell, a crossbow on
	 * long-range), and a member moving about the boss is a few tiles further; past that a player is passing by. Bosses
	 * fought in the open world (the wilderness's, Corporeal's cave, the God Wars rooms) are within this of their target
	 * for the whole fight.
	 */
	static final int OPEN_WORLD_TILES = 15;
	/** How long after a room ends a member's last update for it still counts (the room-end message comes after). */
	static final long LATE_MILLIS = 60_000;

	private static final class Seen
	{
		final Set<Long> byScene = new HashSet<>();
		final Set<Long> bySharing = new HashSet<>();
	}

	private final Map<Encounter, Seen> rooms = new WeakHashMap<>();
	// Members' updates from the websocket thread, waiting for the client thread to match them to a room.
	private final List<Object[]> shared = new ArrayList<>();

	/** A name as both sides are matched: Jagex's form, lower case; null for none. */
	static String key(String name)
	{
		if (name == null)
		{
			return null;
		}
		final String jagex = Text.toJagexName(Text.removeTags(name));
		return jagex == null || jagex.isEmpty() ? null : jagex.toLowerCase(Locale.ENGLISH);
	}

	/** The party's members but you, by {@link #key}: their ids. */
	static Map<String, Long> byKey(Map<Long, String> membersById, long localId)
	{
		final Map<String, Long> out = new HashMap<>();
		for (Map.Entry<Long, String> e : membersById.entrySet())
		{
			final String k = key(e.getValue());
			if (e.getKey() != localId && k != null)
			{
				out.put(k, e.getKey());
			}
		}
		return out;
	}

	/** Whether a player this far from the target (in tiles) is at the fight: anywhere in an instance. */
	static boolean inReach(boolean instance, int dx, int dy, int plane, int targetPlane)
	{
		return instance || plane == targetPlane && Math.max(Math.abs(dx), Math.abs(dy)) <= OPEN_WORLD_TILES;
	}

	/** A member was seen at the room's fight. */
	synchronized void seen(Encounter room, long memberId)
	{
		if (room != null)
		{
			rooms.computeIfAbsent(room, r -> new Seen()).byScene.add(memberId);
		}
	}

	/** A member's update arrived, for the room they name, now (any thread). */
	synchronized void shared(long memberId, String roomName, long millis)
	{
		if (roomName != null)
		{
			shared.add(new Object[]{memberId, roomName, millis});
		}
	}

	/**
	 * Matches the updates waiting to {@code room}: one naming it, sent from its start to a minute after its end (or now,
	 * while it runs), proves that member was in it. The rest wait no longer than {@link #LATE_MILLIS}.
	 */
	synchronized void matchShared(Encounter room, long now)
	{
		if (room == null)
		{
			return;
		}
		final long end = room.isEnded() ? room.getEndMillis() + LATE_MILLIS : Long.MAX_VALUE;
		shared.removeIf(u ->
		{
			final long millis = (Long) u[2];
			if (room.getName().equals(u[1]) && millis >= room.getStartMillis() && millis <= end)
			{
				rooms.computeIfAbsent(room, r -> new Seen()).bySharing.add((Long) u[0]);
				return true;
			}
			return now - millis > LATE_MILLIS;
		});
	}

	/** The members in the room's fight, seen or sharing; null when none was looked for (not in a party). */
	synchronized Set<Long> inFight(Encounter room)
	{
		final Seen s = room == null ? null : rooms.get(room);
		if (s == null)
		{
			return null;
		}
		final Set<Long> all = new HashSet<>(s.byScene);
		all.addAll(s.bySharing);
		return Collections.unmodifiableSet(all);
	}

	/** Marks the room as looked at, so a fight in a party where no member was seen reads "0 of N", not unknown. */
	synchronized void looked(Encounter room)
	{
		if (room != null)
		{
			rooms.computeIfAbsent(room, r -> new Seen());
		}
	}

	/** How many were proved by their own updates (for the log). */
	synchronized int sharing(Encounter room)
	{
		final Seen s = room == null ? null : rooms.get(room);
		return s == null ? 0 : s.bySharing.size();
	}

	/** The log line at the fight's end: counts only, never a name. */
	static String line(String room, int inFight, int members, int sharing)
	{
		return String.format(Locale.ENGLISH, "PARTY SEEN %s: %d of %d members in the fight (%d of them by their own "
			+ "updates)", room, inFight, members, sharing);
	}

	synchronized void clear()
	{
		rooms.clear();
		shared.clear();
	}
}
