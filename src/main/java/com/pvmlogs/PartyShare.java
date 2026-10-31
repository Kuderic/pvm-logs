package com.pvmlogs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-124: building the party update from a room, and the rules around sending
 * it. Pure: the plugin hands it the room and its fights' timelines.
 */
final class PartyShare
{
	private PartyShare()
	{
	}

	/** At most one live update every this many ticks (T-046's rate; RuneLite asks plugins not to strain its party servers). */
	static final int INTERVAL_TICKS = 5;

	/**
	 * The hidden system property that turns on the dev loopback: your own
	 * update shown back as a member, so one client can test the round trip.
	 * Only {@code PvmPerformancePluginTest}'s launcher sets it.
	 */
	static final String ECHO_PROPERTY = "luckvsskill.partyEcho";

	/** After this many updates sent with no echo back from the server, the loopback feeds a local copy instead. */
	static final int ECHO_WAIT = 3;

	/** Whether anything goes to the party: the setting on, and in a party. Off, nothing is sent; others' still show. */
	static boolean mayShare(boolean setting, boolean inParty)
	{
		return setting && inParty;
	}

	/** Whether a received update goes on the board: anyone's but your own echo, which only the loopback keeps. */
	static boolean keep(boolean own, boolean loopback)
	{
		return !own || loopback;
	}

	/** Whether a live update is due on {@code tick}, the last having gone on {@code lastSent}. */
	static boolean due(int tick, int lastSent)
	{
		return lastSent == Integer.MIN_VALUE || tick - lastSent >= INTERVAL_TICKS || tick < lastSent;
	}

	/**
	 * Whether the dev loopback is on: the property set, and never on a build
	 * from the Plugin Hub ({@link BuildInfo}'s {@code release} line), whatever
	 * the property says.
	 */
	static boolean echoEnabled(String property, String build)
	{
		return "true".equalsIgnoreCase(property == null ? null : property.trim())
			&& (build == null || !build.startsWith("release "));
	}

	/**
	 * The update for a room: {@link LuckVsSkillUpdate#LIVE} while it runs (with
	 * Execution as it stands, without avoidable eating), {@link
	 * LuckVsSkillUpdate#ROOM_END} as it ends. {@code attacks} are its fights'
	 * timeline attacks, empty with Charts off.
	 */
	static LuckVsSkillUpdate build(int kind, Encounter room, Iterable<FightTimeline.Attack> attacks, int raidNumber)
	{
		final LedgerRows.Scope scope = LedgerRows.of(room);
		final LedgerRows.Numbers n = new LedgerRows.Numbers(scope);
		final LuckVsSkillUpdate u = new LuckVsSkillUpdate();
		u.k = kind;
		u.r = room.getName();
		u.rs = room.getStartMillis();
		u.rn = room.getRaid() == null ? null : room.getRaid().getDisplayName();
		u.ri = room.getRaid() == null ? 0 : raidNumber;
		u.d = n.damage;
		// T-134: the player's other damage, so the Party meter shows all of it.
		u.o = n.other;
		u.e = LuckVsSkillUpdate.wire(n.expected);
		u.st = Math.max(0, scope.scoredCombatTicks());
		u.a = Math.max(0, scope.attacksMade());
		u.ct = Math.max(0, scope.combatTicks());
		u.kill = kind == LuckVsSkillUpdate.ROOM_END && room.isKilled();
		u.p = LuckVsSkillUpdate.wire(n.percentile);
		u.ls = u.p < 0 || Double.isNaN(n.luck) ? 0 : LuckVsSkillUpdate.wire(n.luck);
		u.x = LuckVsSkillUpdate.wire(kind == LuckVsSkillUpdate.LIVE ? n.executionLive : n.execution);
		u.u = LuckVsSkillUpdate.wire(n.uptime);
		u.il = Math.max(0, scope.ticksLost());
		u.bd = LuckVsSkillUpdate.wire(n.boostDamage);
		u.gd = LuckVsSkillUpdate.wire(n.gearDamage);
		u.pd = LuckVsSkillUpdate.wire(n.prayerDamage);
		u.bs = share(n.boostShare);
		u.gs = share(n.gearShare);
		u.ps = share(n.prayerShare);
		u.tk = Math.max(0, scope.damageTaken());
		u.th = Math.max(0, room.getHitsTaken());
		u.w = rows(attacks);
		return u;
	}

	// A share is 0 to 1; anything else (a scope with no attack it was read for) is unknown.
	private static double share(double value)
	{
		return value >= 0 && value <= 1 ? LuckVsSkillUpdate.wire(value) : -1;
	}

	/**
	 * The attack rows, as the Damage card groups them: by weapon, spell and
	 * special; biggest damage first; at most {@link LuckVsSkillUpdate#MAX_ROWS},
	 * the rest summed into one with item and spell -1.
	 */
	static List<LuckVsSkillUpdate.Row> rows(Iterable<FightTimeline.Attack> attacks)
	{
		final Map<String, LuckVsSkillUpdate.Row> byKey = new LinkedHashMap<>();
		if (attacks != null)
		{
			for (FightTimeline.Attack a : attacks)
			{
				final int spell = a.spell == null ? 0 : a.spell.getVarbitValue();
				final LuckVsSkillUpdate.Row row = byKey.computeIfAbsent(a.itemId + "/" + spell + "/" + a.special, k ->
				{
					final LuckVsSkillUpdate.Row r = new LuckVsSkillUpdate.Row();
					r.i = a.itemId;
					r.s = spell;
					r.x = a.special;
					return r;
				});
				add(row, a);
			}
		}
		final List<LuckVsSkillUpdate.Row> all = new ArrayList<>(byKey.values());
		all.sort(Comparator.comparingInt((LuckVsSkillUpdate.Row r) -> r.d).reversed());
		final List<LuckVsSkillUpdate.Row> out = new ArrayList<>();
		LuckVsSkillUpdate.Row rest = null;
		for (LuckVsSkillUpdate.Row r : all)
		{
			if (out.size() < LuckVsSkillUpdate.MAX_ROWS - 1 || all.size() <= LuckVsSkillUpdate.MAX_ROWS)
			{
				out.add(r);
				continue;
			}
			if (rest == null)
			{
				rest = new LuckVsSkillUpdate.Row();
				out.add(rest);
			}
			rest.a += r.a;
			rest.l += r.l;
			rest.d += r.d;
			rest.e += r.e;
		}
		for (LuckVsSkillUpdate.Row r : out)
		{
			r.e = LuckVsSkillUpdate.wire(r.e);
		}
		return out;
	}

	private static void add(LuckVsSkillUpdate.Row row, FightTimeline.Attack a)
	{
		row.a++;
		if (a.damage > 0)
		{
			row.l++;
		}
		row.d += a.damage;
		if (a.mean > 0)
		{
			row.e += a.mean;
		}
	}
}
