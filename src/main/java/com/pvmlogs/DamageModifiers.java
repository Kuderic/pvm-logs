package com.pvmlogs;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * The client side of the boss damage modifiers (T-057): reads what the game
 * shows into {@link ModifierTracker}, and tells {@link CombatCalc} which of a
 * boss's {@code damageModifiers} an attack going out now will land with.
 *
 * <p>The windows are judged at the tick the attack is expected to LAND, the
 * player's position at the tick it goes out: an attack thrown just before a stun
 * ends and landing after it is not capped; a shot launched from inside the
 * pathfinder keeps its bonus wherever the player has run to by the time it
 * lands.
 */
@Singleton
class DamageModifiers
{
	private final Client client;
	private final MonsterStatsProvider monsters;
	private final ModifierTracker tracker;
	private final Consumer<String> log;

	@Inject
	DamageModifiers(Client client, MonsterStatsProvider monsters, DebugLog debug)
	{
		this.client = client;
		this.monsters = monsters;
		this.log = line -> debug.log("%s", line);
		this.tracker = new ModifierTracker(log);
		this.tracker.setPresence(this::present);
	}

	/** Whether an npc of this id is in the scene (T-078: the Leviathan's pathfinder, which ends its stun). */
	private boolean present(int npcId)
	{
		if (client.getTopLevelWorldView() == null)
		{
			return false;
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc != null && npc.getId() == npcId)
			{
				return true;
			}
		}
		return false;
	}

	private BossRules.Boss bossFor(int npcId)
	{
		final BossRules rules = monsters.rules();
		final BossRules.Boss boss = rules == null ? null : rules.forNpc(npcId);
		return boss == null || boss.damageModifiers == null ? null : boss;
	}

	/** Whether a damage-modifier state is open on this npc now (T-106: Araxxor enraged, for naming his own cleave). */
	boolean isOpen(NPC npc, String state)
	{
		return npc != null && bossFor(npc.getId()) != null && tracker.isOpen(npc.getIndex(), state, client.getTickCount());
	}

	/** Whether this npc has any damage modifiers: the hooks below do nothing for one that has none. */
	boolean hasModifiers(NPC npc)
	{
		return npc != null && bossFor(npc.getId()) != null;
	}

	/** The boss started an animation. */
	void onNpcAnimation(NPC npc)
	{
		final BossRules.Boss boss = npc == null ? null : bossFor(npc.getId());
		if (boss != null && npc.getAnimation() >= 0)
		{
			tracker.onBossAnim(npc.getIndex(), boss.name, boss.damageModifiers, npc.getAnimation(),
				client.getTickCount());
		}
	}

	/** The boss changed form (the Sire stepping out). */
	void onNpcChanged(NPC npc)
	{
		final BossRules.Boss boss = npc == null ? null : bossFor(npc.getId());
		if (boss != null)
		{
			tracker.onNpcId(npc.getIndex(), boss.name, boss.damageModifiers, npc.getId(), client.getTickCount());
		}
	}

	/** The player cast this spell at the npc, landing on {@code landTick}. */
	void onPlayerCast(NPC npc, Spell spell, int landTick)
	{
		final BossRules.Boss boss = npc == null || spell == null ? null : bossFor(npc.getId());
		if (boss != null)
		{
			tracker.onPlayerSpell(npc.getIndex(), boss.name, boss.damageModifiers, spell.name(), landTick);
		}
	}

	/**
	 * One of the player's splats landed on the npc: the modifiers it landed with
	 * (style aside, from where the player stands now) start and end windows - the
	 * Leviathan's wound breaks the stun and sets off its special.
	 */
	void onMyHitsplat(NPC npc)
	{
		final BossRules.Boss boss = npc == null ? null : bossFor(npc.getId());
		if (boss == null)
		{
			return;
		}
		final int now = client.getTickCount();
		final List<BossRules.ModifierRule> landed = tracker.applying(npc.getIndex(), boss.damageModifiers, npc.getId(),
			null, now, behind(npc), this::inside);
		final List<String> names = new java.util.ArrayList<>(landed.size());
		for (BossRules.ModifierRule m : landed)
		{
			names.add(m.name);
		}
		tracker.onModifiersLanded(npc.getIndex(), boss.name, boss.damageModifiers, names, now);
	}

	/** The window of the boss's state open or set to open now, as {start, end}; null for none (T-074). */
	int[] window(NPC npc, String state)
	{
		return npc == null ? null : tracker.window(npc.getIndex(), state, client.getTickCount());
	}

	/** The boss's uptime allowances (T-074); empty for one with none. */
	List<BossRules.UptimeAllowance> uptimeAllowances(NPC npc)
	{
		final BossRules rules = npc == null ? null : monsters.rules();
		final BossRules.Boss boss = rules == null ? null : rules.forNpc(npc.getId());
		return boss == null || boss.uptimeAllowances == null ? Collections.emptyList() : boss.uptimeAllowances;
	}

	/** A damage-over-time splat of this kind landed on the npc (T-087: the Maggot King's burn stacks). */
	void onBossDot(NPC npc, String kind, int amount)
	{
		final BossRules.Boss boss = npc == null ? null : bossFor(npc.getId());
		if (boss != null && boss.damageModifiers.stacks != null)
		{
			tracker.onBossDot(npc.getIndex(), boss.name, boss.damageModifiers, kind, amount, client.getTickCount());
		}
	}

	/**
	 * The change to the npc's magic defence bonus an attack going out now takes
	 * (T-087): each modifier it lands with that counts a stack the boss carries,
	 * times that many. The stacks are the ones on it now, as the attack is rolled
	 * when it goes out.
	 */
	int magicDefenceChange(NPC npc, CombatCalc.Style style, int delayTicks)
	{
		final BossRules.Boss boss = npc == null ? null : bossFor(npc.getId());
		if (boss == null)
		{
			return 0;
		}
		int change = 0;
		for (BossRules.ModifierRule m : forAttack(npc, style, delayTicks))
		{
			if (m.stacks != null && m.magicDefencePerStack != null)
			{
				change += m.magicDefencePerStack
					* tracker.stacks(npc.getIndex(), m.stacks, boss.damageModifiers, client.getTickCount());
			}
		}
		return change;
	}

	void onGameTick()
	{
		tracker.tick(client.getTickCount());
	}

	void forget(NPC npc)
	{
		if (npc != null)
		{
			tracker.forget(npc.getIndex());
		}
	}

	void clear()
	{
		tracker.clear();
		insideSeen.clear();
		meleeSeen.clear();
	}

	/**
	 * The modifiers an attack going out now at this npc lands with, by style,
	 * landing {@code delayTicks} from now. Empty for a boss without any.
	 */
	List<BossRules.ModifierRule> forAttack(NPC npc, CombatCalc.Style style, int delayTicks)
	{
		final BossRules.Boss boss = npc == null ? null : bossFor(npc.getId());
		if (boss == null)
		{
			return Collections.emptyList();
		}
		final int distance = hasMeleeRule(boss.damageModifiers) ? meleeDistance(npc) : NO_DISTANCE;
		final Boolean inMelee = distance == NO_DISTANCE ? null : distance == IN_MELEE;
		final List<BossRules.ModifierRule> mods = tracker.applying(npc.getIndex(), boss.damageModifiers, npc.getId(),
			style == null ? null : style.name(), client.getTickCount(), client.getTickCount() + Math.max(0, delayTicks),
			behind(npc), this::inside, inMelee);
		if (distance != NO_DISTANCE)
		{
			meleeSeen.put(client.getTickCount(), new MeleeSeen(npc.getIndex(), npc.getName(), distance, style, mods));
			meleeSeen.keySet().removeIf(t -> client.getTickCount() - t > MELEE_SEEN_TICKS);
		}
		return live ? ModifierTracker.shownLive(mods) : mods;
	}

	// What the player's melee distance from a boss with an outOfMelee rule was on each recent tick, and the modifiers it
	// gave (T-120), for the MOD line at the attack's booking, which trails its throw.
	private static final int MELEE_SEEN_TICKS = 10;
	private static final int NO_DISTANCE = Integer.MIN_VALUE;
	// Cardinally next to the footprint: the one way to be in its melee distance.
	static final int IN_MELEE = 1;
	// Next to it on a diagonal: a tile away, out of its melee distance.
	static final int DIAGONAL = -1;
	private final Map<Integer, MeleeSeen> meleeSeen = new HashMap<>();

	private static final class MeleeSeen
	{
		final int npcIndex;
		final String npcName;
		final int distance;
		final CombatCalc.Style style;
		final List<BossRules.ModifierRule> mods;

		MeleeSeen(int npcIndex, String npcName, int distance, CombatCalc.Style style, List<BossRules.ModifierRule> mods)
		{
			this.npcIndex = npcIndex;
			this.npcName = npcName;
			this.distance = distance;
			this.style = style;
			this.mods = mods;
		}
	}

	private static boolean hasMeleeRule(BossRules.DamageModifierRules rules)
	{
		if (rules == null || rules.modifiers == null)
		{
			return false;
		}
		for (BossRules.ModifierRule m : rules.modifiers)
		{
			if (m.outOfMelee != null)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The MOD line for the player's ranged attack at this npc thrown on {@code tick}
	 * (T-120), from the figures worked out that tick; null for any other attack, or
	 * a boss without an outOfMelee rule.
	 */
	String meleeDistanceLine(int npcIndex, int tick)
	{
		final MeleeSeen seen = meleeSeen.get(tick);
		if (seen == null || seen.npcIndex != npcIndex || seen.style != CombatCalc.Style.RANGED)
		{
			return null;
		}
		final StringBuilder boosts = new StringBuilder();
		for (BossRules.ModifierRule m : seen.mods)
		{
			if (m.attackRoll != null && m.attackRoll.length == 2
				&& (m.attackTypes == null || m.attackTypes.contains("RANGED")))
			{
				boosts.append(boosts.length() == 0 ? "" : ", ").append(m.name).append(" x")
					.append(m.attackRoll[0] / Math.max(1, m.attackRoll[1]));
			}
		}
		return String.format("MOD %s: ranged attack thrown on tick %d, the player %s: %s", seen.npcName, tick,
			describeDistance(seen.distance), boosts.length() == 0 ? "no accuracy boost" : "ranged accuracy " + boosts);
	}

	static String describeDistance(int distance)
	{
		if (distance == IN_MELEE)
		{
			return "next to its footprint (in melee distance)";
		}
		if (distance == DIAGONAL)
		{
			return "diagonal to its footprint (out of melee distance)";
		}
		if (distance == 0)
		{
			return "under it (out of melee distance)";
		}
		return distance + " tiles from its footprint (out of melee distance)";
	}

	// The player's distance from the npc's footprint, as meleeDistance(...) has it; NO_DISTANCE when either isn't known.
	private int meleeDistance(NPC npc)
	{
		final Player me = client.getLocalPlayer();
		final WorldPoint at = me == null ? null : me.getWorldLocation();
		final WorldArea area = npc.getWorldArea();
		if (at == null || area == null || at.getPlane() != area.getPlane())
		{
			return NO_DISTANCE;
		}
		return meleeDistance(area.getX(), area.getY(), area.getWidth(), area.getHeight(), at.getX(), at.getY());
	}

	/**
	 * How far a tile is from a footprint (T-120), in tiles on the larger axis:
	 * {@link #IN_MELEE} on a tile cardinally next to it, {@link #DIAGONAL} on one
	 * diagonally next to it, 0 under it, else the distance (2 and up). The Royal
	 * Titans' boost holds "whenever she is out of melee distance" (OSRS Wiki,
	 * Branda the Fire Queen): read here as the player not on a tile from which the
	 * titan could melee them, cardinally next to its footprint, as an NPC's melee
	 * reaches. That is a reading, not a source.
	 */
	static int meleeDistance(int x, int y, int width, int height, int px, int py)
	{
		final int dx = px < x ? x - px : px > x + width - 1 ? px - (x + width - 1) : 0;
		final int dy = py < y ? y - py : py > y + height - 1 ? py - (y + height - 1) : 0;
		if (dx == 0 && dy == 0)
		{
			return 0;
		}
		if (dx + dy == 1)
		{
			return IN_MELEE;
		}
		if (dx == 1 && dy == 1)
		{
			return DIAGONAL;
		}
		return Math.max(dx, dy);
	}

	/**
	 * While set, the modifiers are the ones the live figures may show (T-087):
	 * those marked {@code hiddenLive} are left out. The attack itself is still
	 * scored with them, from the figures worked out with this off.
	 */
	private boolean live;

	void setLive(boolean live)
	{
		this.live = live;
	}

	/** Whether an attack going out now at this npc lands with a modifier the live figures must not show. */
	boolean hiddenApply(NPC npc, CombatCalc.Style style, int delayTicks)
	{
		final boolean was = live;
		live = false;
		try
		{
			return ModifierTracker.shownLive(forAttack(npc, style, delayTicks)).size()
				!= forAttack(npc, style, delayTicks).size();
		}
		finally
		{
			live = was;
		}
	}

	/** The modifiers' names an attack at this npc goes out with now, for the ATTACK line. */
	String describe(NPC npc, CombatCalc.Style style, int delayTicks)
	{
		return ModifierTracker.names(forAttack(npc, style, delayTicks));
	}

	// Whether the player stands behind the npc: on the far side of the line through its centre square to its facing.
	private boolean behind(NPC npc)
	{
		final Player me = client.getLocalPlayer();
		final WorldPoint at = me == null ? null : me.getWorldLocation();
		final WorldArea area = npc.getWorldArea();
		if (at == null || area == null)
		{
			return false;
		}
		return isBehind(area.getX(), area.getY(), area.getWidth(), area.getHeight(), npc.getOrientation(), at.getX(),
			at.getY());
	}

	/**
	 * Whether a tile is behind an npc of this footprint facing this way. The
	 * client's orientation runs 0 south, 512 west, 1024 north, 1536 east, out of
	 * 2048; behind is the half-plane away from the facing, past the centre.
	 * The wiki gives no geometry for the Leviathan's wound beyond "run around to
	 * attack the gaping wound on its backside": the half-plane is a guess, for a
	 * log to settle.
	 */
	static boolean isBehind(int x, int y, int width, int height, int orientation, int px, int py)
	{
		final double angle = (orientation & 2047) * 2 * Math.PI / 2048;
		final double fx = -Math.sin(angle);
		final double fy = -Math.cos(angle);
		final double cx = x + (width - 1) / 2.0;
		final double cy = y + (height - 1) / 2.0;
		return fx * (px - cx) + fy * (py - cy) < 0;
	}

	/**
	 * Whether the player is inside the range of an npc of this id for an attack
	 * going out now: inside on this tick and the tick before (T-079). Judged on one
	 * tick, 9 of the 52 enrages in Eric's logs of 2026-10-28/29 had a first
	 * "pathfinder" shot that missed or hit under its 20% minimum: the player
	 * stepping in, or the pathfinder just spawned, on the tick the shot went out.
	 * The extra tick is a guess from that, which the MOD lines' tiles will settle.
	 */
	private boolean inside(int npcId, int range)
	{
		final int now = client.getTickCount();
		final long key = ((long) npcId << 8) | (range & 0xFF);
		final int[] seen = insideSeen.computeIfAbsent(key, k -> new int[]{NOT_INSIDE, Integer.MIN_VALUE});
		if (seen[1] != now)
		{
			final String where = insideNow(npcId, range);
			final boolean in = where != null;
			final boolean was = seen[0] != NOT_INSIDE;
			seen[0] = since(seen[0], seen[1], in, now);
			if (in != was)
			{
				log.accept(String.format("MOD %s npc %d (range %d) at tick %d: %s", in ? "inside" : "outside", npcId, range,
					now, in ? where : describeOutside(npcId)));
			}
			seen[1] = now;
		}
		return dwelt(seen[0], now);
	}

	/**
	 * The tick the player has been inside since, after asking on {@code now}:
	 * {@link #NOT_INSIDE} when outside, kept when inside and last asked on the tick
	 * before, else {@code now}. A tick not asked about is not known to have been
	 * inside (it is asked on every tick a fight is shown).
	 */
	static int since(int since, int lastAsked, boolean in, int now)
	{
		if (!in)
		{
			return NOT_INSIDE;
		}
		return since != NOT_INSIDE && lastAsked == now - 1 ? since : now;
	}

	/** Whether the player, inside since {@code since} ({@link #NOT_INSIDE} for not), counts as inside on {@code now}. */
	static boolean dwelt(int since, int now)
	{
		return since != NOT_INSIDE && since <= now - 1;
	}

	static final int NOT_INSIDE = Integer.MIN_VALUE;
	/** Per npc id and range: the tick the player has been inside since (or NOT_INSIDE), and the tick last asked. */
	private final Map<Long, int[]> insideSeen = new HashMap<>();

	// Where the player stands against the npc's footprint if within range of one of this id, for the MOD line; null if not.
	private String insideNow(int npcId, int range)
	{
		final Player me = client.getLocalPlayer();
		final WorldPoint at = me == null ? null : me.getWorldLocation();
		if (at == null || client.getTopLevelWorldView() == null)
		{
			return null;
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getId() != npcId)
			{
				continue;
			}
			final WorldArea area = npc.getWorldArea();
			if (area != null && withinRange(area.getX(), area.getY(), area.getWidth(), area.getHeight(), area.getPlane(),
				at.getX(), at.getY(), at.getPlane(), range))
			{
				return tiles(at, area);
			}
		}
		return null;
	}

	// The player's tile and the npc's footprint, for the MOD line when the player has stepped out.
	private String describeOutside(int npcId)
	{
		final Player me = client.getLocalPlayer();
		final WorldPoint at = me == null ? null : me.getWorldLocation();
		if (at == null || client.getTopLevelWorldView() == null)
		{
			return "you not placed";
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc != null && npc.getId() == npcId && npc.getWorldArea() != null)
			{
				return tiles(at, npc.getWorldArea());
			}
		}
		return String.format("you at %d,%d, none in the scene", at.getX(), at.getY());
	}

	private static String tiles(WorldPoint at, WorldArea area)
	{
		return String.format("you at %d,%d, it at %d,%d size %dx%d", at.getX(), at.getY(), area.getX(), area.getY(),
			area.getWidth(), area.getHeight());
	}

	/** Whether a tile is within {@code range} tiles (Chebyshev) of an area on the same plane. */
	static boolean withinRange(int x, int y, int width, int height, int plane, int px, int py, int pplane, int range)
	{
		if (plane != pplane)
		{
			return false;
		}
		final int dx = Math.max(0, Math.max(x - px, px - (x + width - 1)));
		final int dy = Math.max(0, Math.max(y - py, py - (y + height - 1)));
		return Math.max(dx, dy) <= range;
	}
}
