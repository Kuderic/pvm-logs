package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * T-124: what the party's Luck vs Skill players have shared, for the side
 * panel's Party card (T-125). Per member: the latest live figures, the last
 * room's summary, the damage series of each room (one point per live update:
 * scored ticks, damage, expected) and, in a raid, each room's summary.
 *
 * <p>Fed from RuneLite's websocket thread and read from Swing's, so every
 * method is synchronized and {@link #view} hands out copies. Never saved:
 * cleared on leaving or changing party, a member dropped as they leave. A
 * member who never sent an update (no Luck vs Skill) isn't on it.
 */
final class PartyBoard
{
	/** The dev loopback's member (T-124), when the party server echoes your own update back. */
	static final String ECHO_NAME = "You (echo)";
	/** The dev loopback's member when the server doesn't echo: your own update, fed locally. */
	static final String LOCAL_NAME = "You (local copy)";
	/** Points kept per room: an update every 5 ticks, so an hour. */
	static final int MAX_POINTS = 720;
	/** Rooms a member's series and raid summaries are kept for. */
	static final int MAX_ROOMS = 16;

	/** One live update's cumulative figures, for the damage over time chart. */
	static final class Point
	{
		final int scoredTicks;
		final int damage;
		final double expected;
		/**
		 * T-128: the room's clock in ticks at the update ({@link LuckVsSkillUpdate#ct}), the party chart's one x axis:
		 * scored ticks leave out idle time and unscored adds, so a line drawn by them ran ahead of yours. 0 from a sender
		 * that didn't send it.
		 */
		final int clockTicks;

		Point(int scoredTicks, int damage, double expected)
		{
			this(scoredTicks, damage, expected, 0);
		}

		Point(int scoredTicks, int damage, double expected, int clockTicks)
		{
			this.scoredTicks = scoredTicks;
			this.damage = damage;
			this.expected = expected;
			this.clockTicks = clockTicks;
		}

		/** The x the chart draws it at: the room's clock, or the scored ticks from a sender without it. */
		int x()
		{
			return clockTicks > 0 ? clockTicks : scoredTicks;
		}
	}

	/** A member as the side panel reads it: a copy, safe to keep. */
	static final class View
	{
		final long memberId;
		/** The party's display name for them, or the loopback's name; "Party member" when the party hasn't said yet. */
		final String name;
		/** The latest live figures; null before any. */
		final LuckVsSkillUpdate live;
		/** The last room's summary; null before any. */
		final LuckVsSkillUpdate lastRoom;
		/** The room their latest update was about ({@link LuckVsSkillUpdate#roomKey}); null before any. */
		final String room;
		/** Each room's series, oldest room first. */
		final Map<String, List<Point>> series;
		/** The current (or last) raid's room summaries, in the order they ended. */
		final List<LuckVsSkillUpdate> raidRooms;
		final long updatedMillis;
		/** T-128: each room's Fight tab, as they sent it when it ended ({@link LuckVsSkillRoom}), by room key. */
		final Map<String, LuckVsSkillRoom> fights;

		View(long memberId, String name, LuckVsSkillUpdate live, LuckVsSkillUpdate lastRoom, String room,
			Map<String, List<Point>> series, List<LuckVsSkillUpdate> raidRooms, long updatedMillis)
		{
			this(memberId, name, live, lastRoom, room, series, raidRooms, updatedMillis,
				Collections.<String, LuckVsSkillRoom>emptyMap());
		}

		View(long memberId, String name, LuckVsSkillUpdate live, LuckVsSkillUpdate lastRoom, String room,
			Map<String, List<Point>> series, List<LuckVsSkillUpdate> raidRooms, long updatedMillis,
			Map<String, LuckVsSkillRoom> fights)
		{
			this.fights = fights;
			this.memberId = memberId;
			this.name = name;
			this.live = live;
			this.lastRoom = lastRoom;
			this.room = room;
			this.series = series;
			this.raidRooms = raidRooms;
			this.updatedMillis = updatedMillis;
		}
	}

	private static final class Member
	{
		final long id;
		String nameOverride;
		LuckVsSkillUpdate live;
		LuckVsSkillUpdate lastRoom;
		String room;
		final LinkedHashMap<String, List<Point>> series = new LinkedHashMap<>();
		String raidName;
		int raid;
		final LinkedHashMap<String, LuckVsSkillUpdate> raidRooms = new LinkedHashMap<>();
		/** T-128: their rooms' Fight tabs. */
		final LinkedHashMap<String, LuckVsSkillRoom> fights = new LinkedHashMap<>();
		long updatedMillis;

		Member(long id)
		{
			this.id = id;
		}
	}

	private final Map<Long, Member> members = new LinkedHashMap<>();
	private long revision;

	/**
	 * An update from {@code memberId}. {@code nameOverride} names the loopback's
	 * member; null for a real one, whose name the party gives.
	 */
	synchronized void accept(long memberId, LuckVsSkillUpdate u, String nameOverride, long now)
	{
		if (u == null || u.r == null)
		{
			return;
		}
		final Member m = members.computeIfAbsent(memberId, Member::new);
		m.nameOverride = nameOverride;
		m.updatedMillis = now;
		m.room = u.roomKey();
		final List<Point> points = m.series.computeIfAbsent(m.room, k -> new ArrayList<>());
		final Point last = points.isEmpty() ? null : points.get(points.size() - 1);
		if (last == null || last.scoredTicks != u.st || last.damage != u.d || last.clockTicks != u.ct)
		{
			points.add(new Point(u.st, u.d, u.e, u.ct));
			if (points.size() > MAX_POINTS)
			{
				points.remove(0);
			}
		}
		trim(m.series);
		if (u.isLive())
		{
			m.live = u;
		}
		else
		{
			m.lastRoom = u;
			m.live = u.roomKey().equals(m.live == null ? null : m.live.roomKey()) ? null : m.live;
		}
		if (u.rn != null)
		{
			if (m.raid != u.ri || !u.rn.equals(m.raidName))
			{
				m.raidRooms.clear();
				m.raid = u.ri;
				m.raidName = u.rn;
			}
			if (!u.isLive())
			{
				m.raidRooms.put(u.roomKey(), u);
				trim(m.raidRooms);
			}
		}
		revision++;
	}

	/**
	 * T-128: a room's Fight tab from {@code memberId}, sent once as it ended. Kept by its room key, as the series are,
	 * for the last {@link #MAX_ROOMS} rooms.
	 */
	synchronized void acceptRoom(long memberId, LuckVsSkillRoom room, String nameOverride, long now)
	{
		if (room == null || room.r == null || room.f == null)
		{
			return;
		}
		final Member m = members.computeIfAbsent(memberId, Member::new);
		m.nameOverride = nameOverride;
		m.updatedMillis = Math.max(m.updatedMillis, now);
		m.fights.put(room.roomKey(), room);
		trim(m.fights);
		revision++;
	}

	private static <V> void trim(LinkedHashMap<String, V> map)
	{
		for (Iterator<String> it = map.keySet().iterator(); map.size() > MAX_ROOMS && it.hasNext(); )
		{
			it.next();
			it.remove();
		}
	}

	/** A member left the party. */
	synchronized void part(long memberId)
	{
		if (members.remove(memberId) != null)
		{
			revision++;
		}
	}

	/** Left or changed party, or the plugin stopped. */
	synchronized void clear()
	{
		if (!members.isEmpty())
		{
			members.clear();
			revision++;
		}
	}

	synchronized boolean isEmpty()
	{
		return members.isEmpty();
	}

	/** Bumped on every change, so the panel can tell whether to rebuild. */
	synchronized long revision()
	{
		return revision;
	}

	/** Every member, in the order they first sent, named by {@code names} unless the loopback named them. */
	synchronized List<View> view(LongFunction<String> names)
	{
		final List<View> out = new ArrayList<>();
		for (Member m : members.values())
		{
			String name = m.nameOverride;
			if (name == null && names != null)
			{
				name = names.apply(m.id);
			}
			final Map<String, List<Point>> series = new LinkedHashMap<>();
			m.series.forEach((room, points) -> series.put(room, Collections.unmodifiableList(new ArrayList<>(points))));
			out.add(new View(m.id, name == null ? "Party member" : name, m.live, m.lastRoom, m.room,
				Collections.unmodifiableMap(series), Collections.unmodifiableList(new ArrayList<>(m.raidRooms.values())),
				m.updatedMillis, Collections.unmodifiableMap(new LinkedHashMap<>(m.fights))));
		}
		return Collections.unmodifiableList(out);
	}
}
