package com.pvmlogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T-128: a party member's Fight tab (Eric, 2026-10-31: "Clicking in to someone's damage contribution should display the
 * same panels as in the fight tab, just in the party tab for that person"). The sender's own Fight tab input, for the
 * room just ended, goes into a {@link LuckVsSkillRoom}; the receiver turns it back into a {@link SidePanelModel.Input}
 * and the Fight tab's own model and components draw it, so the two can't drift. Mid-fight, a member's view is what
 * their live {@link LuckVsSkillUpdate} can fill: the tiles, Damage and Execution as it stands.
 */
final class SidePanelMemberFight
{
	private SidePanelMemberFight()
	{
	}

	/** What a member's view says mid-fight about the rest. */
	static final String LATER_LIVE = "Their Luck curve, Where the time went, chart and ticks come when their room ends.";
	/** A whole raid: their rooms' Fight tabs, one at a time. */
	static final String RAID_NOTE = "A member's whole raid shows their summed tiles and Damage; pick This room for their "
		+ "room's full Fight tab.";

	// ---- the sender's side ----

	/** The room-end message for {@code room}: its Fight tab, as the sender's own panel would build it. */
	static LuckVsSkillRoom message(Encounter room, FightTimeline.View view, int raidNumber, BossRules rules)
	{
		return message(room, r -> r == room ? view : null, raidNumber, rules);
	}

	/**
	 * T-132: the same, with {@code views} giving each part of the room its timeline (the boss-only and adds-only rooms of
	 * the Damage card's switch, as {@code PvmPerformancePanel.read} gives them), so the member's card has the switch.
	 */
	static LuckVsSkillRoom message(Encounter room, java.util.function.Function<Encounter, FightTimeline.View> views,
		int raidNumber, BossRules rules)
	{
		final SidePanelModel.Input in = input(room, views.apply(room), rules, true);
		if (in.view != null && in.bossOnly != null && in.addsOnly != null)
		{
			in.bossOnly.view = views.apply(in.bossOnly.part);
			in.addsOnly.view = views.apply(in.addsOnly.part);
		}
		final LuckVsSkillRoom m = new LuckVsSkillRoom();
		m.r = room.getName();
		m.rs = room.getStartMillis();
		m.rn = room.getRaid() == null ? null : room.getRaid().getDisplayName();
		m.ri = room.getRaid() == null ? 0 : raidNumber;
		m.f = encode(in);
		return m;
	}

	/**
	 * A room's Fight tab input, as the sender's own panel builds it ({@code PvmPerformancePanel.read}): the room's figures,
	 * the boss rules' names, its timeline. {@code ended}: after the room. The player's own row on the Party tab opens it.
	 */
	static SidePanelModel.Input input(Encounter room, FightTimeline.View view, BossRules rules, boolean ended)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		in.ended = ended;
		in.killed = ended && room.isKilled();
		in.warning = room.getStatsWarning();
		in.avoidableAttacks = ended ? SidePanelModel.avoidableAttacks(rules, room.getFights())
			: Collections.<String>emptySet();
		in.version = rules == null ? null : SidePanelModel.versionName(rules, room.getFights(), in.maxHp);
		if (view != null && view.states.length > 0)
		{
			in.view = view;
			in.weaponRows = SidePanelModel.weaponRows(view);
		}
		else
		{
			in.charts = false;
		}
		return in;
	}

	/** The Fight tab's input, as far as a member's view needs it. */
	static LuckVsSkillRoom.Fight encode(SidePanelModel.Input in)
	{
		final LuckVsSkillRoom.Fight f = new LuckVsSkillRoom.Fight();
		f.s = values(in.scope);
		f.n = in.name;
		f.sn = in.scoredName;
		f.run = in.run;
		f.kills = in.kills;
		f.died = in.died;
		f.killed = in.killed;
		f.rd = in.roomDamage;
		f.rds = in.roomDamageSources.isEmpty() ? null : new ArrayList<>(in.roomDamageSources);
		f.hp = in.maxHp;
		f.solo = in.solo;
		f.unscored = in.unscored;
		f.ht = in.hitsTaken;
		f.mht = in.missedHitsTaken;
		f.raid = in.raidType == null ? null : in.raidType.name();
		f.tba = in.takenByAttack.isEmpty() ? null : new LinkedHashMap<>(in.takenByAttack);
		f.avoid = in.avoidableAttacks.isEmpty() ? null : new ArrayList<>(in.avoidableAttacks);
		f.sth = in.scoredThrall;
		f.sdt = in.scoredDot;
		f.sdk = in.scoredDotByKind.isEmpty() ? null : new LinkedHashMap<>(in.scoredDotByKind);
		f.srf = in.scoredReflect;
		f.th = in.thrall;
		f.dt = in.dot;
		f.dk = in.dotByKind.isEmpty() ? null : new LinkedHashMap<>(in.dotByKind);
		f.rf = in.reflect;
		f.warn = in.warning;
		f.ver = in.version;
		f.dr = drain(in.drain);
		if (in.view != null && in.view.states.length > 0)
		{
			f.w = weapons(in.weaponRows != null ? in.weaponRows : SidePanelModel.weaponRows(in.view));
			f.c = chart(in.view);
		}
		// T-132: the Damage card's switch, where the room has adds and their own timelines came with it.
		if (!in.run && in.bossOnly != null && in.addsOnly != null && in.bossOnly.view != null && in.addsOnly.view != null)
		{
			f.b = part(in.bossOnly);
			f.a = part(in.addsOnly);
		}
		return f;
	}

	/** T-132: one side of the switch: its figures, its other damage, its weapon rows and chart. */
	private static LuckVsSkillRoom.Fight part(SidePanelModel.Input p)
	{
		final LuckVsSkillRoom.Fight f = new LuckVsSkillRoom.Fight();
		f.s = values(p.scope);
		f.n = p.name;
		f.sn = p.scoredName;
		f.unscored = p.unscored;
		f.ns = p.noneScored;
		f.sth = p.scoredThrall;
		f.sdt = p.scoredDot;
		f.sdk = p.scoredDotByKind.isEmpty() ? null : new LinkedHashMap<>(p.scoredDotByKind);
		f.srf = p.scoredReflect;
		if (!p.noneScored && p.view != null && p.view.states.length > 0)
		{
			f.w = weapons(SidePanelModel.weaponRows(p.view));
			f.c = chart(p.view);
		}
		return f;
	}

	static LuckVsSkillRoom.Values values(LedgerRows.Scope s)
	{
		final LuckVsSkillRoom.Values v = new LuckVsSkillRoom.Values();
		v.ct = s.combatTicks();
		v.st = s.scoredCombatTicks();
		v.d = s.damage();
		v.e = box(s.expectedDamage());
		v.th = s.thrallDamage();
		v.rf = s.reflectDamage();
		v.so = s.scoredOtherDamage();
		v.dv = box(s.damageVariance());
		v.dt = s.dotDamage();
		v.dk = s.dotDamageByKind().isEmpty() ? null : new LinkedHashMap<>(s.dotDamageByKind());
		v.he = s.healed();
		v.tl = s.ticksLost();
		v.tls = box(s.ticksLostShare());
		v.ef = box(s.efficiency());
		v.bs = box(s.boostShare());
		v.bc = box(s.boostCostShare());
		v.sc = box(s.switchCostShare());
		v.is = box(s.idealSetupDamage());
		v.am = s.attacksMade();
		v.ap = s.attacksPrayed();
		v.as = s.attacksSwitched();
		v.ad = s.attacksDrained();
		v.tk = s.damageTaken();
		v.ot = s.dotTaken();
		v.ok = s.dotTakenByKind().isEmpty() ? null : new LinkedHashMap<>(s.dotTakenByKind());
		v.bh = s.biggestHitTaken();
		v.up = s.damageTakenUnprotected();
		v.hi = s.hits();
		v.eh = box(s.expectedHits());
		v.le = s.ticksLostEating();
		v.tm = s.ticksMechanics();
		v.ae = box(s.avoidableEatingTicks());
		v.ta = s.damageTakenAvoidable();
		v.ds = s.defenceStart();
		v.de = s.defenceEnd();
		v.ms = s.magicStart();
		v.me = s.magicEnd();
		v.dr = box(s.drainValueDamage());
		v.sl = box(s.specLuckDamage());
		// luckSeconds(damage) is a rate over the scored ticks, the expected damage and the other damage
		// (Encounter.luckSeconds): read back from two points, which pins both, so the receiver's is the sender's.
		final double one = s.luckSeconds(1000);
		if (!Double.isNaN(one) && one != 0 && s.scoredCombatTicks() > 0)
		{
			// seconds(d) = ticks·0.6·d / (expected + other): expected + other = ticks·0.6·1000 / one.
			v.lse = box(s.scoredCombatTicks() * LedgerRows.SECONDS_PER_TICK * 1000 / one);
		}
		return v;
	}

	private static LuckVsSkillRoom.Drain drain(SidePanelModel.Drain d)
	{
		if (d == null)
		{
			return null;
		}
		final LuckVsSkillRoom.Drain w = new LuckVsSkillRoom.Drain();
		w.ds = d.defenceStart;
		w.de = d.defenceEnd;
		w.db = d.defenceBase;
		w.df = d.defenceFloor;
		w.ms = d.magicStart;
		w.me = d.magicEnd;
		w.sp = d.specs.isEmpty() ? null : new LinkedHashMap<>(d.specs);
		w.pd = d.partyDrains;
		w.vd = box(d.valueDamage);
		w.vs = box(d.valueSeconds);
		w.ld = box(d.specLuckDamage);
		w.lsec = box(d.specLuckSeconds);
		return w;
	}

	private static List<LuckVsSkillRoom.Weapon> weapons(List<SidePanelModel.WeaponRow> rows)
	{
		final List<LuckVsSkillRoom.Weapon> out = new ArrayList<>(rows.size());
		for (SidePanelModel.WeaponRow r : rows)
		{
			final LuckVsSkillRoom.Weapon w = new LuckVsSkillRoom.Weapon();
			w.n = r.name;
			w.x = r.special;
			w.a = r.attacks;
			w.d = r.damage;
			w.e = round(r.expected);
			w.ek = r.expectedKnown;
			w.t = r.ticks;
			w.l = r.landed;
			w.le = round(r.landedExpected);
			w.lk = r.landedKnown;
			w.mh = r.maxHit;
			w.b = r.biggest;
			w.i = r.itemId;
			w.sp = r.spell == null ? 0 : r.spell.getVarbitValue();
			w.st = r.style.name();
			out.add(w);
		}
		return out;
	}

	/** The chart and strip: the strip's runs (attacks folded into cooldown, restored from their ticks), the attacks, points. */
	static LuckVsSkillRoom.Chart chart(FightTimeline.View view)
	{
		final int n = view.states.length;
		final LuckVsSkillRoom.Chart c = new LuckVsSkillRoom.Chart();
		c.n = n;
		final StringBuilder marks = new StringBuilder();
		final Set<Integer> attackTicks = new HashSet<>();
		for (FightTimeline.Attack a : view.attacks)
		{
			final int i = a.tick - view.startTick;
			if (i < 0 || i >= n)
			{
				continue;
			}
			if (marks.length() > 0)
			{
				marks.append(',');
			}
			marks.append(i).append(':').append(a.style.ordinal());
			if (a.special)
			{
				marks.append(":x");
			}
			attackTicks.add(i);
		}
		c.a = marks.toString();
		final StringBuilder runs = new StringBuilder();
		int i = 0;
		while (i < n)
		{
			final byte s = fold(view.states[i], attackTicks.contains(i));
			int j = i + 1;
			while (j < n && fold(view.states[j], attackTicks.contains(j)) == s)
			{
				j++;
			}
			if (runs.length() > 0)
			{
				runs.append(',');
			}
			runs.append(s).append('x').append(j - i);
			i = j;
		}
		c.s = runs.toString();
		final int points = (n - 1) / LuckVsSkillRoom.Chart.STEP + 1 + ((n - 1) % LuckVsSkillRoom.Chart.STEP == 0 ? 0 : 1);
		c.pt = new int[points];
		c.pd = new int[points];
		c.pe = new double[points];
		c.pv = new double[points];
		int k = 0;
		for (int t = 0; t < n; t += LuckVsSkillRoom.Chart.STEP)
		{
			point(c, k++, t, view);
		}
		if ((n - 1) % LuckVsSkillRoom.Chart.STEP != 0)
		{
			point(c, k, n - 1, view);
		}
		return c;
	}

	private static void point(LuckVsSkillRoom.Chart c, int k, int t, FightTimeline.View view)
	{
		c.pt[k] = t;
		c.pd[k] = Math.round(view.damage[t]);
		c.pe[k] = round(view.expected[t]);
		c.pv[k] = round(view.variance[t]);
	}

	/** An attack's tick in the strip folds into cooldown; its state comes back from the attack's mark. */
	private static byte fold(byte state, boolean attack)
	{
		return attack && (state == FightTimeline.ATTACK || state == FightTimeline.SPECIAL) ? FightTimeline.COOLDOWN : state;
	}

	// ---- the receiver's side ----

	/** The Fight tab's input from a member's room-end message: the same model and components draw it. */
	static SidePanelModel.Input decode(LuckVsSkillRoom.Fight f)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.kind = SidePanelModel.Kind.ROOM;
		in.ended = true;
		// Not a saved kill: History's "saved kill" line isn't theirs.
		in.saved = false;
		in.scope = scope(f.s == null ? new LuckVsSkillRoom.Values() : f.s);
		in.name = f.n == null ? "" : f.n;
		in.scoredName = f.sn == null ? in.name : f.sn;
		in.run = f.run;
		in.kills = f.kills;
		in.died = f.died;
		in.killed = f.killed;
		in.roomDamage = f.rd;
		in.roomDamageSources = f.rds == null ? Collections.<String>emptyList() : f.rds;
		in.maxHp = f.hp;
		in.solo = f.solo;
		in.unscored = f.unscored;
		in.hitsTaken = f.ht;
		in.missedHitsTaken = f.mht;
		in.raidType = raid(f.raid);
		in.inRaid = in.raidType != null;
		in.takenByAttack = f.tba == null ? Collections.<String, Integer>emptyMap() : f.tba;
		in.avoidableAttacks = f.avoid == null ? Collections.<String>emptySet() : new HashSet<>(f.avoid);
		in.scoredThrall = f.sth;
		in.scoredDot = f.sdt;
		in.scoredDotByKind = f.sdk == null ? Collections.<String, Integer>emptyMap() : f.sdk;
		in.scoredReflect = f.srf;
		in.thrall = f.th;
		in.dot = f.dt;
		in.dotByKind = f.dk == null ? Collections.<String, Integer>emptyMap() : f.dk;
		in.reflect = f.rf;
		in.warning = f.warn;
		in.version = f.ver;
		in.drain = drain(f.dr);
		// A party member's own panels: they're sending, so they're in a party (T-132).
		in.inParty = true;
		if (f.c != null && f.c.n > 0)
		{
			in.view = view(f.c);
			in.weaponRows = weaponRows(f.w);
		}
		else
		{
			in.charts = false;
		}
		// T-132: the Damage card's switch, from a sender that sent both sides.
		if (f.b != null && f.a != null && in.view != null)
		{
			in.bossOnly = side(f.b, in.name);
			in.addsOnly = side(f.a, in.name);
		}
		return in;
	}

	/** T-132: one side of the switch read back: as {@code SidePanelModel.part} makes it from the room's fights. */
	private static SidePanelModel.Input side(LuckVsSkillRoom.Fight f, String room)
	{
		final SidePanelModel.Input p = new SidePanelModel.Input();
		p.kind = SidePanelModel.Kind.ROOM;
		p.side = true;
		p.scope = scope(f.s == null ? new LuckVsSkillRoom.Values() : f.s);
		p.name = f.n == null ? room : f.n;
		p.scoredName = f.sn == null ? "" : f.sn;
		p.unscored = f.unscored;
		p.noneScored = f.ns;
		p.scoredThrall = f.sth;
		p.scoredDot = f.sdt;
		p.scoredDotByKind = f.sdk == null ? Collections.<String, Integer>emptyMap() : f.sdk;
		p.scoredReflect = f.srf;
		if (f.c != null && f.c.n > 0)
		{
			p.view = view(f.c);
			p.weaponRows = weaponRows(f.w);
		}
		return p;
	}

	/** Mid-fight: the input a member's live update can fill. The Fight tab draws it live, without its chart or strip. */
	static SidePanelModel.Input live(LuckVsSkillUpdate u, java.util.function.IntFunction<String> itemNames)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.kind = SidePanelModel.Kind.ROOM;
		in.ended = false;
		in.name = u.r == null ? "" : u.r;
		in.scoredName = in.name;
		in.scope = scope(liveValues(u));
		in.hitsTaken = u.th;
		in.inParty = true;
		in.charts = false;
		in.later = null;
		in.weaponRows = liveRows(u, itemNames);
		return in;
	}

	/**
	 * A live update's figures as a scope's: what it sends, and what follows from it. Uptime and Execution's parts come
	 * back exactly as {@code LedgerRows.Numbers} has them on the sender's side; the spread of damage isn't sent live, so
	 * Luck is in seconds until the room ends.
	 */
	static LuckVsSkillRoom.Values liveValues(LuckVsSkillUpdate u)
	{
		final LuckVsSkillRoom.Values v = new LuckVsSkillRoom.Values();
		v.ct = u.ct;
		v.st = u.st;
		v.d = u.d;
		// T-134: their other damage, so their DPS tile counts it as the meter does.
		v.so = Math.max(0, u.o);
		v.e = u.e < 0 ? null : u.e;
		v.dv = -1d;
		v.am = u.a;
		v.tk = u.tk;
		v.ds = v.de = v.ms = v.me = -1;
		v.ta = -1;
		v.ae = -1d;
		if (u.u >= 0)
		{
			// Uptime is 1 − idle share: Numbers reads it from the lost share, with no eating split live.
			v.tl = u.il;
			v.le = 0;
			v.tls = 1 - u.u;
		}
		else
		{
			v.tls = -1d;
		}
		if (u.x >= 0 && u.u > 0)
		{
			v.ef = Math.min(1, u.x / u.u);
		}
		else
		{
			v.ef = -1d;
		}
		if (u.bs >= 0 && u.gs >= 0 && u.ps >= 0 && v.ef >= 0)
		{
			// Numbers splits the setup's loss into boost, gear and the prayer's remainder; the shares kept come back as
			// 1 − each, and the ideal from what they cost.
			final double boost = 1 - u.bs;
			final double gear = 1 - u.gs;
			final double prayer = 1 - u.ps;
			v.bs = 1d;
			v.bc = boost;
			v.sc = gear;
			final double cost = Math.max(0, u.bd) + Math.max(0, u.gd) + Math.max(0, u.pd);
			v.is = boost + gear + prayer > 0 && cost > 0 ? cost / (boost + gear + prayer) : -1d;
		}
		else
		{
			v.bs = -1d;
		}
		return v;
	}

	private static List<SidePanelModel.WeaponRow> liveRows(LuckVsSkillUpdate u, java.util.function.IntFunction<String> names)
	{
		final List<SidePanelModel.WeaponRow> out = new ArrayList<>();
		for (LuckVsSkillUpdate.Row r : u.rows())
		{
			final SidePanelModel.WeaponRow w = new SidePanelModel.WeaponRow();
			w.name = SidePanelParty.attackName(r, names).replace(" spec", "");
			w.special = r.x;
			w.attacks = r.a;
			w.landed = r.l;
			w.landedKnown = 0;
			w.damage = r.d;
			w.expected = Math.max(0, r.e);
			w.expectedKnown = r.e > 0;
			w.itemId = r.i;
			w.spell = r.s > 0 ? Spell.forVarbit(r.s) : null;
			out.add(w);
		}
		return out;
	}

	/** The values as a {@code LedgerRows.Scope}: each method its value, the defaults where nothing was sent. */
	static LedgerRows.Scope scope(LuckVsSkillRoom.Values v)
	{
		return new LedgerRows.Scope()
		{
			public int combatTicks()
			{
				return v.ct;
			}

			public int scoredCombatTicks()
			{
				return v.st;
			}

			public int damage()
			{
				return v.d;
			}

			public double expectedDamage()
			{
				return unbox(v.e, 0);
			}

			public int thrallDamage()
			{
				return v.th;
			}

			public int reflectDamage()
			{
				return v.rf;
			}

			public int scoredOtherDamage()
			{
				return v.so;
			}

			public double damageVariance()
			{
				return unbox(v.dv, -1);
			}

			public int dotDamage()
			{
				return v.dt;
			}

			public Map<String, Integer> dotDamageByKind()
			{
				return v.dk == null ? Collections.<String, Integer>emptyMap() : v.dk;
			}

			public int healed()
			{
				return v.he;
			}

			public int ticksLost()
			{
				return v.tl;
			}

			public double ticksLostShare()
			{
				return unbox(v.tls, -1);
			}

			public double efficiency()
			{
				return unbox(v.ef, -1);
			}

			public double boostShare()
			{
				return unbox(v.bs, -1);
			}

			public double boostCostShare()
			{
				return unbox(v.bc, 0);
			}

			public double switchCostShare()
			{
				return unbox(v.sc, 0);
			}

			public double idealSetupDamage()
			{
				return unbox(v.is, -1);
			}

			public int attacksMade()
			{
				return v.am;
			}

			public int attacksPrayed()
			{
				return v.ap;
			}

			public int attacksSwitched()
			{
				return v.as;
			}

			public int attacksDrained()
			{
				return v.ad;
			}

			public int damageTaken()
			{
				return v.tk;
			}

			public int dotTaken()
			{
				return v.ot;
			}

			public Map<String, Integer> dotTakenByKind()
			{
				return v.ok == null ? Collections.<String, Integer>emptyMap() : v.ok;
			}

			public int biggestHitTaken()
			{
				return v.bh;
			}

			public int damageTakenUnprotected()
			{
				return v.up;
			}

			public int hits()
			{
				return v.hi;
			}

			public double expectedHits()
			{
				return unbox(v.eh, 0);
			}

			public int ticksLostEating()
			{
				return v.le;
			}

			public int ticksMechanics()
			{
				return v.tm;
			}

			public double avoidableEatingTicks()
			{
				return unbox(v.ae, -1);
			}

			public int damageTakenAvoidable()
			{
				return v.ta;
			}

			public int defenceStart()
			{
				return v.ds;
			}

			public int defenceEnd()
			{
				return v.de;
			}

			public int magicStart()
			{
				return v.ms;
			}

			public int magicEnd()
			{
				return v.me;
			}

			public double drainValueDamage()
			{
				return unbox(v.dr, Double.NaN);
			}

			public double specLuckDamage()
			{
				return unbox(v.sl, Double.NaN);
			}

			public double luckSeconds(double damage)
			{
				final double rate = unbox(v.lse, Double.NaN);
				return Double.isNaN(damage) || Double.isNaN(rate) || rate <= 0 || v.st <= 0 ? Double.NaN
					: v.st * LedgerRows.SECONDS_PER_TICK * damage / rate;
			}
		};
	}

	private static SidePanelModel.Drain drain(LuckVsSkillRoom.Drain w)
	{
		if (w == null)
		{
			return null;
		}
		final SidePanelModel.Drain d = new SidePanelModel.Drain();
		d.defenceStart = w.ds;
		d.defenceEnd = w.de;
		d.defenceBase = w.db;
		d.defenceFloor = w.df;
		d.magicStart = w.ms;
		d.magicEnd = w.me;
		if (w.sp != null)
		{
			d.specs = new LinkedHashMap<>(w.sp);
		}
		d.partyDrains = w.pd;
		d.valueDamage = unbox(w.vd, Double.NaN);
		d.valueSeconds = unbox(w.vs, Double.NaN);
		d.specLuckDamage = unbox(w.ld, Double.NaN);
		d.specLuckSeconds = unbox(w.lsec, Double.NaN);
		return d;
	}

	private static List<SidePanelModel.WeaponRow> weaponRows(List<LuckVsSkillRoom.Weapon> ws)
	{
		final List<SidePanelModel.WeaponRow> out = new ArrayList<>();
		if (ws == null)
		{
			return out;
		}
		for (LuckVsSkillRoom.Weapon w : ws)
		{
			final SidePanelModel.WeaponRow r = new SidePanelModel.WeaponRow();
			r.name = w.n;
			r.special = w.x;
			r.attacks = w.a;
			r.damage = w.d;
			r.expected = w.e;
			r.expectedKnown = w.ek;
			r.ticks = w.t;
			r.landed = w.l;
			r.landedExpected = w.le;
			r.landedKnown = w.lk;
			r.maxHit = w.mh;
			r.biggest = w.b;
			r.itemId = w.i;
			r.spell = w.sp > 0 ? Spell.forVarbit(w.sp) : null;
			r.style = style(w.st);
			out.add(r);
		}
		return out;
	}

	/** The chart's view: the strip from its runs and the attacks' ticks, the series between the points, linearly. */
	static FightTimeline.View view(LuckVsSkillRoom.Chart c)
	{
		final int n = c.n;
		final byte[] states = new byte[n];
		int i = 0;
		if (c.s != null && !c.s.isEmpty())
		{
			for (String run : c.s.split(","))
			{
				final int x = run.indexOf('x');
				final byte s = Byte.parseByte(run.substring(0, x));
				final int count = Integer.parseInt(run.substring(x + 1));
				for (int k = 0; k < count && i < n; k++)
				{
					states[i++] = s;
				}
			}
		}
		final List<FightTimeline.Attack> attacks = new ArrayList<>();
		if (c.a != null && !c.a.isEmpty())
		{
			for (String mark : c.a.split(","))
			{
				final String[] p = mark.split(":");
				final int tick = Integer.parseInt(p[0]);
				final FightTimeline.Attack a = new FightTimeline.Attack(tick);
				final int style = Integer.parseInt(p[1]);
				a.style = style >= 0 && style < FightTimeline.Style.values().length ? FightTimeline.Style.values()[style]
					: FightTimeline.Style.UNKNOWN;
				a.special = p.length > 2;
				attacks.add(a);
				if (tick >= 0 && tick < n && states[tick] == FightTimeline.COOLDOWN)
				{
					states[tick] = a.special ? FightTimeline.SPECIAL : FightTimeline.ATTACK;
				}
			}
		}
		final float[] damage = new float[n];
		final float[] expected = new float[n];
		final float[] variance = new float[n];
		final int points = c.pt == null ? 0 : c.pt.length;
		for (int k = 0; k < points; k++)
		{
			final int from = c.pt[k];
			final int to = k + 1 < points ? c.pt[k + 1] : from;
			for (int t = from; t <= to && t < n; t++)
			{
				final double f = to == from ? 0 : (double) (t - from) / (to - from);
				damage[t] = (float) lerp(c.pd[k], k + 1 < points ? c.pd[k + 1] : c.pd[k], f);
				expected[t] = (float) lerp(c.pe[k], k + 1 < points ? c.pe[k + 1] : c.pe[k], f);
				variance[t] = (float) lerp(c.pv[k], k + 1 < points ? c.pv[k + 1] : c.pv[k], f);
			}
		}
		return new FightTimeline.View(0, states, damage, expected, variance, attacks);
	}

	// ---- helpers ----

	private static double lerp(double a, double b, double f)
	{
		return a + (b - a) * f;
	}

	private static RaidType raid(String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			return RaidType.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private static FightTimeline.Style style(String name)
	{
		if (name == null)
		{
			return FightTimeline.Style.UNKNOWN;
		}
		try
		{
			return FightTimeline.Style.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return FightTimeline.Style.UNKNOWN;
		}
	}

	/**
	 * A double as Gson can write it: null for NaN or infinite, otherwise exactly. Rounded, the setup's split (the loss
	 * less the boost's and gear's shares) left a hair of prayer the Execution card floors to 99%: the member's view is
	 * the sender's to the digit, so the scope's figures go whole.
	 */
	static Double box(double v)
	{
		return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
	}

	private static double unbox(Double v, double otherwise)
	{
		return v == null ? otherwise : v;
	}

	private static double round(double v)
	{
		if (Double.isNaN(v) || Double.isInfinite(v))
		{
			return 0;
		}
		return v == 0 ? 0 : new java.math.BigDecimal(v).round(new java.math.MathContext(7)).doubleValue();
	}

	/**
	 * T-128, T-130 review: party members' Fight panels for the Party tab, every member's (not only the one selected), so a
	 * click shows theirs at once and never the compact breakdown first. Once their room has ended, the Fight tab's own
	 * model on their room message, built once per message; mid-fight, what their live update can fill (a few cards, built
	 * each time). Without either (their room's message still on its way, or a sender from before T-128), none, and the
	 * compact breakdown shows. Their name stands where the Fight tab says "you" and "yours". Client thread.
	 */
	static final class Panels
	{
		private final java.util.Map<LuckVsSkillRoom, PanelSnapshot.FightTab> built = new java.util.IdentityHashMap<>();
		private int builds;

		void attach(SidePanelParty.Member m, java.util.Map<String, LuckVsSkillRoom> fights,
			java.util.function.IntFunction<String> names)
		{
			if (m.figures.isLive())
			{
				final SidePanelModel.Input in = live(m.figures, names);
				in.whose = m.name;
				m.fight = SidePanelModel.fight(in);
				m.fightLive = true;
				return;
			}
			final LuckVsSkillRoom sent = fights == null ? null : fights.get(m.figures.roomKey());
			if (sent == null || sent.f == null)
			{
				return;
			}
			if (built.size() > 64)
			{
				built.clear();
			}
			m.fight = built.computeIfAbsent(sent, r ->
			{
				builds++;
				final SidePanelModel.Input in = decode(r.f);
				in.whose = m.name;
				return SidePanelModel.fight(in);
			});
		}

		/** For tests: how many room messages were made into panels. */
		int builds()
		{
			return builds;
		}
	}
}
