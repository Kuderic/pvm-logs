package com.pvmlogs;

import java.util.Collections;
import java.util.List;
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

	@Inject
	DamageModifiers(Client client, MonsterStatsProvider monsters, DebugLog debug)
	{
		this.client = client;
		this.monsters = monsters;
		this.tracker = new ModifierTracker(line -> debug.log("%s", line));
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
		return tracker.applying(npc.getIndex(), boss.damageModifiers, npc.getId(), style == null ? null : style.name(),
			client.getTickCount(), client.getTickCount() + Math.max(0, delayTicks), behind(npc), this::inside);
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

	// Whether the player stands within range of an npc of this id: its footprint widened by the range, in tiles.
	private boolean inside(int npcId, int range)
	{
		final Player me = client.getLocalPlayer();
		final WorldPoint at = me == null ? null : me.getWorldLocation();
		if (at == null || client.getTopLevelWorldView() == null)
		{
			return false;
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
				return true;
			}
		}
		return false;
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
