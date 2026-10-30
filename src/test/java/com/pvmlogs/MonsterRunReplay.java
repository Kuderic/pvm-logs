package com.pvmlogs;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/**
 * The plugin's room and fight lifecycle (T-089), driven event by event the way
 * {@code PvmPerformancePlugin} drives it: startFight's switch, openEncounterFor's
 * rooms and the run set aside, closeOnDespawn's and the loot's kills, and
 * trackMonsterRoom's pause and leaving. Each decision is the plugin's own static
 * rule ({@link PvmPerformancePlugin#keepsCountsOnSwitch},
 * {@link PvmPerformancePlugin#roomGoesOnAtDeath},
 * {@link PvmPerformancePlugin#opensRoom}, {@link PvmPerformancePlugin#lootEndsFight},
 * {@link MonsterRooms}, {@link Encounter}); only the glue between them is
 * written again here, as the plugin needs a client.
 *
 * <p>The counts each target has left are kept as the plugin's
 * {@code damageByIndex}: kept on a switch that keeps counts, the dead one's
 * dropped at its death, all of them dropped otherwise.
 */
final class MonsterRunReplay
{
	static final BossRules RULES = BossRules.load(new Gson(), null);

	final List<Encounter> rooms = new ArrayList<>();
	/** The saved fights, newest first, as the plugin keeps its history. */
	final List<Fight> history = new ArrayList<>();
	final Map<Integer, Integer> damageByIndex = new HashMap<>();
	final Map<Integer, String> names = new HashMap<>();
	final MonsterRooms monsterRooms = new MonsterRooms();
	Encounter room;
	Encounter setAside;
	Fight open;
	long now;
	boolean inRaid;
	/** Set by a log replay, which sets {@link #now} from each line's tick: no event moves the clock on its own. */
	boolean clocked;
	/** Fights opened on a monster the player's damage had already reached, and what they had taken by then. */
	int startedHurt;
	int hpCarried;
	WorldPoint at = new WorldPoint(1700, 10100, 0);
	WorldPoint was = at;

	/** The clock moves to {@code millis}. */
	MonsterRunReplay at(long millis)
	{
		now = Math.max(now, millis);
		return this;
	}

	/** A fight opens on this NPC: the player attacked or targeted it (startFight). */
	MonsterRunReplay fight(String name, int npcId, int index, int maxHp)
	{
		tick();
		names.put(index, name);
		final boolean monster = MonsterRooms.isMonster(name, npcId, inRaid, RULES);
		if (open != null && !open.isEnded())
		{
			finish(false, PvmPerformancePlugin.keepsCountsOnSwitch(room, open, npcId, monster));
		}
		if (damageByIndex.getOrDefault(index, 0) > 0)
		{
			startedHurt++;
			hpCarried += damageByIndex.get(index);
		}
		open = new Fight(name, npcId, index, maxHp, now, null, 0);
		if (monster)
		{
			open.groupAsMonster();
		}
		openEncounterFor(open);
		return this;
	}

	/** openEncounterFor, with its run set aside and taken up again. */
	private void openEncounterFor(Fight fight)
	{
		if (!PvmPerformancePlugin.opensRoom(room, fight))
		{
			room.add(fight);
			return;
		}
		if (MonsterRooms.resumesRun(setAside, fight))
		{
			end(room);
			room = setAside;
			setAside = null;
			room.add(fight);
			return;
		}
		if (!fight.isMonsterRoom())
		{
			end(setAside);
			setAside = null;
		}
		if (room != null)
		{
			if (setAside == null && MonsterRooms.setsRunAside(room, fight))
			{
				setAside = room;
			}
			else
			{
				end(room);
			}
		}
		room = new Encounter(fight.encounterName(), null, now);
		rooms.add(room);
		room.add(fight);
	}

	/** An attack thrown at the open fight's target, booked (recordAttackMade and the room's noteAttack). */
	MonsterRunReplay attack()
	{
		tick();
		open.recordAttackMade(false);
		room.noteAttack(now);
		return this;
	}

	/** One of the player's splats on the open fight's target. */
	MonsterRunReplay hit(int amount)
	{
		open.recordDamageDealt(amount, now, amount > 0);
		damage(open.getTargetIndex(), amount);
		return this;
	}

	/** The expectation of the attack just thrown (sampleExpected). */
	MonsterRunReplay expect(double average, double variance)
	{
		open.recordExpected(59, 0.84, average, variance);
		return this;
	}

	/** The attack's splash on another NPC (recordExtraTarget). */
	MonsterRunReplay splash(String name, int index, int amount)
	{
		names.put(index, name);
		open.recordExtraTarget(amount, now, true, amount > 0);
		damage(index, amount);
		return this;
	}

	private void damage(int index, int amount)
	{
		damageByIndex.merge(index, amount, Integer::sum);
		if (amount > 0 && runOf(names.get(index)) != null)
		{
			monsterRooms.hit(index);
		}
	}

	/** The NPC despawns dead (closeOnDespawn). */
	MonsterRunReplay dies(int index)
	{
		tick();
		credit(index);
		monsterRooms.forget(index);
		if (open == null || open.isEnded() || open.getTargetIndex() != index)
		{
			damageByIndex.remove(index);
			return this;
		}
		finish(true, PvmPerformancePlugin.roomGoesOnAtDeath(room, open, open.getTargetId()));
		return this;
	}

	/** The open fight's target despawns alive: walked out of view (closeOnDespawn). */
	MonsterRunReplay despawns(int index)
	{
		tick();
		monsterRooms.forget(index);
		if (open != null && !open.isEnded() && open.getTargetIndex() == index)
		{
			finish(false, false);
		}
		return this;
	}

	/** The NPC drops loot (handleNpcLootReceived). */
	MonsterRunReplay loot(int npcId, int index)
	{
		tick();
		credit(index);
		if (PvmPerformancePlugin.lootEndsFight(open, npcId, index))
		{
			finish(true, open.isMonsterRoom() && PvmPerformancePlugin.roomGoesOnAtDeath(room, open, npcId));
		}
		if (room != null && room.closesOnLootFrom(npcId))
		{
			room.end(now);
			final List<Fight> held = room.getFights();
			held.get(held.size() - 1).closeRoom();
		}
		return this;
	}

	/** Time passes with nothing attacked (trackMonsterRoom's pause). */
	MonsterRunReplay waits(long millis)
	{
		now += millis;
		return checkRoom();
	}

	/** The player teleports away (trackMonsterRoom's leaving). */
	MonsterRunReplay teleports()
	{
		was = at;
		at = new WorldPoint(3200, 3200, 0);
		return checkRoom();
	}

	private MonsterRunReplay checkRoom()
	{
		final boolean left = MonsterRooms.leftArea(was, at);
		was = at;
		if (setAside != null && (left || MonsterRooms.pauseEnds(setAside, now)))
		{
			end(setAside);
			setAside = null;
		}
		if (room == null || room.isEnded() || !room.isMonsterRoom() || !room.isEngaged())
		{
			return this;
		}
		if (MonsterRooms.pauseEnds(room, now) || left)
		{
			if (open != null && !open.isEnded() && room.getFights().contains(open))
			{
				open.end(false, Math.max(open.getStartMillis(), Math.min(now, room.getLastAttackMillis())));
				keep(open);
				damageByIndex.clear();
			}
			end(room);
		}
		return this;
	}

	private Encounter runOf(String name)
	{
		for (Encounter r : new Encounter[]{room, setAside})
		{
			if (r != null && !r.isEnded() && r.isMonsterRoom() && MonsterRooms.sameKind(r.getFights().get(0), name))
			{
				return r;
			}
		}
		return null;
	}

	private void credit(int index)
	{
		final Encounter run = runOf(names.get(index));
		if (run == null || !monsterRooms.takeKill(index))
		{
			return;
		}
		final Fight onto = open != null && !open.isEnded() && open.getAttempts() > 0 && run.getFights().contains(open)
			? open : run.lastAttempted();
		if (onto != null)
		{
			onto.recordKill();
		}
	}

	private void finish(boolean died, boolean keepCounts)
	{
		final int index = open.getTargetIndex();
		open.end(died, now);
		if (!keepCounts)
		{
			damageByIndex.clear();
		}
		else if (died)
		{
			damageByIndex.remove(index);
		}
		keep(open);
	}

	private void keep(Fight fight)
	{
		if (fight.getAttempts() > 0)
		{
			history.add(0, fight);
		}
	}

	private void end(Encounter r)
	{
		if (r == null || r.isEnded())
		{
			return;
		}
		if (r.isMonsterRoom())
		{
			r.endMonsterRoom(now);
		}
		else
		{
			r.end(now);
		}
	}

	private void tick()
	{
		if (!clocked)
		{
			now += 600;
		}
	}

	/** The rooms that had anything thrown in them, as the overlay and History would show them. */
	List<Encounter> fought()
	{
		final List<Encounter> out = new ArrayList<>();
		for (Encounter r : rooms)
		{
			if (r.hasAttempts())
			{
				out.add(r);
			}
		}
		return out;
	}
}
