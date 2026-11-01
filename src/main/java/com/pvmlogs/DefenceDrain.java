package com.pvmlogs;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * The client side of the drain tracking (T-049): reads what the game shows and
 * hands it to {@link DrainTracker}, whose rules are {@link DrainRule}'s and whose
 * floors, immunities and resets are {@code boss-rules.json}'s ({@link BossRules.DrainRules}).
 *
 * <p>Everything here is the player's own and the party's own specials, which is
 * what RuneLite's special attack counter and Better Party Defence show live too:
 * nothing about the boss's attacks.
 */
@Singleton
class DefenceDrain
{
	private static final int MAX_SPEC_ENERGY = 1000;

	private static final Map<Integer, SpecialWeapon> BY_ITEM_ID = new HashMap<>();

	static
	{
		for (SpecialWeapon weapon : SpecialWeapon.values())
		{
			for (int id : weapon.getItemID())
			{
				BY_ITEM_ID.put(id, weapon);
			}
		}
	}

	private final Client client;
	private final MonsterStatsProvider monsters;
	private final PartyHitpoints partyHitpoints;
	private final PartyService party;
	private final DrainTracker tracker;

	// Seeded from the live value rather than left unknown, so the first special after a reset is measured against
	// something: at -1 it was swallowed.
	private int specEnergy = -1;
	// A special whose energy fell this tick, aimed once the tick's player update is in (the target is read then).
	private SpecialWeapon thrownWeapon;
	private int thrownTick = Integer.MIN_VALUE;
	private int hitpointsXpTick = Integer.MIN_VALUE;

	@Inject
	DefenceDrain(Client client, MonsterStatsProvider monsters, PartyHitpoints partyHitpoints, PartyService party,
		DebugLog debug)
	{
		this.client = client;
		this.monsters = monsters;
		this.partyHitpoints = partyHitpoints;
		this.party = party;
		this.tracker = new DrainTracker(line -> debug.log("%s", line));
	}

	/**
	 * Notices the player's own special going out, from the energy falling. The
	 * party message carries only what other members did, so a solo player's own
	 * specials have to be seen here or not at all. The weapon is read now; the
	 * target once the tick's player update has arrived ({@link #onGameTick}), as
	 * RuneLite's counter defers it.
	 */
	void onEnergyChanged()
	{
		final int energy = client.getVarpValue(VarPlayerID.SA_ENERGY);
		final int previous = specEnergy;
		specEnergy = energy;
		if (previous < 0)
		{
			// First reading since a reset. A full bar cannot have just been spent, so anything short of it is a special that
			// already went out.
			if (energy >= MAX_SPEC_ENERGY)
			{
				return;
			}
		}
		else if (energy >= previous)
		{
			return;
		}
		final SpecialWeapon weapon = BY_ITEM_ID.get(equippedWeaponId());
		if (weapon != null)
		{
			thrownWeapon = weapon;
			thrownTick = client.getTickCount();
		}
	}

	/** Hitpoints experience rose: on the tick of an elder maul special, that is its hit. */
	void onHitpointsXp()
	{
		hitpointsXpTick = client.getTickCount();
	}

	/** Once a tick: aims a special thrown this tick, and resolves the ones due. O(specials waiting). */
	void onGameTick()
	{
		final int now = client.getTickCount();
		if (tracker.hasCarried())
		{
			adoptReplacements(now);
		}
		if (thrownWeapon != null)
		{
			aim(thrownWeapon, thrownTick, now);
			thrownWeapon = null;
		}
		tracker.tick(now);
	}

	// A boss whose actor was just replaced (Kephri's shield, a TFA boss): its stats move to the new actor. Only runs for
	// the few ticks something is waiting.
	private void adoptReplacements(int now)
	{
		if (client.getTopLevelWorldView() == null)
		{
			return;
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || tracker.tracked(npc.getIndex()) != null)
			{
				continue;
			}
			final BossRules.Boss boss = bossFor(npc.getId());
			final String key = carryKey(boss);
			if (key != null && tracker.isCarried(key))
			{
				tracker.adopt(key, npc.getIndex(), npc.getId(), npc.getName(), targetFor(npc), now,
					boss.defenceDrain.resetsOnNpc(npc.getId()));
			}
		}
	}

	private static String carryKey(BossRules.Boss boss)
	{
		return boss == null || boss.defenceDrain == null || !boss.defenceDrain.carryAcrossActors || boss.name == null
			? null : boss.name;
	}

	private void aim(SpecialWeapon weapon, int tick, int now)
	{
		final Player me = client.getLocalPlayer();
		final Actor at = me == null ? null : me.getInteracting();
		if (!(at instanceof NPC))
		{
			tracker.ownThrow(tick, weapon, -1, -1, null, tick, null, false);
			return;
		}
		final NPC npc = (NPC) at;
		final int due = tick + weapon.getHitDelay(distance(me, npc));
		tracker.ownThrow(tick, weapon, npc.getIndex(), npc.getId(), npc.getName(), due, targetFor(npc),
			hitpointsXpTick == tick || hitpointsXpTick == now);
	}

	private static int distance(Player me, NPC npc)
	{
		final WorldPoint from = me.getWorldLocation();
		final WorldArea area = npc.getWorldArea();
		return from == null || area == null ? 1 : area.distanceTo(from);
	}

	/**
	 * T-147: the draining specials whose animations are known, by weapon: RuneLite's gameval {@code AnimationID}. The
	 * others (elder maul, Emberlight, the sceptre, the Tonalztics, Seercull, the Eye of ayak, the anchor, the bone
	 * weapons) aren't named there, so they aren't seen: no id is guessed.
	 */
	static final Map<SpecialWeapon, java.util.Set<Integer>> SPECIAL_ANIMATIONS = new java.util.EnumMap<>(SpecialWeapon.class);

	static
	{
		SPECIAL_ANIMATIONS.put(SpecialWeapon.DRAGON_WARHAMMER,
			java.util.Collections.singleton(net.runelite.api.gameval.AnimationID.DRAGON_WARHAMMER_SA_PLAYER));
		SPECIAL_ANIMATIONS.put(SpecialWeapon.BANDOS_GODSWORD, new java.util.HashSet<>(java.util.Arrays.asList(
			net.runelite.api.gameval.AnimationID.BGS_SPECIAL_PLAYER,
			net.runelite.api.gameval.AnimationID.BGS_SPECIAL_ORNATE_PLAYER)));
		final java.util.Set<Integer> lights = java.util.Collections.singleton(
			net.runelite.api.gameval.AnimationID.DARK_SPEC_PLAYER);
		SPECIAL_ANIMATIONS.put(SpecialWeapon.DARKLIGHT, lights);
		SPECIAL_ANIMATIONS.put(SpecialWeapon.ARCLIGHT, lights);
	}

	/** T-147: the special a player's animation and weapon show, or null: a known special animation with its weapon. */
	static SpecialWeapon seenSpecial(int weaponItemId, int animation)
	{
		final SpecialWeapon weapon = BY_ITEM_ID.get(weaponItemId);
		final java.util.Set<Integer> animations = weapon == null ? null : SPECIAL_ANIMATIONS.get(weapon);
		return animations != null && animations.contains(animation) ? weapon : null;
	}

	/**
	 * T-147: another player's animation, already known to be aimed at the open fight's target (the plugin's
	 * AnimationChanged hook; never a scan). A draining special with its weapon in hand is handed to the tracker, its
	 * splat due by the weapon's hit delay from where they stand. Not the player's own, whose specials are seen from the
	 * energy falling.
	 */
	void onOtherPlayerAnimation(Player player, NPC target)
	{
		if (player == null || target == null || player == client.getLocalPlayer() || player.getPlayerComposition() == null)
		{
			return;
		}
		final SpecialWeapon weapon = seenSpecial(player.getPlayerComposition().getEquipmentId(
			net.runelite.api.kit.KitType.WEAPON), player.getAnimation());
		if (weapon == null)
		{
			return;
		}
		final int tick = client.getTickCount();
		tracker.seenThrow(tick, weapon, player.getId(), target.getIndex(), target.getId(), target.getName(),
			tick + weapon.getHitDelay(distance(player, target)), targetFor(target));
	}

	/** T-147: another player's splat on an npc ({@code isOthers()}), for a seen special due there now. */
	void onOthersHitsplat(NPC npc, int amount)
	{
		tracker.othersSplat(npc.getIndex(), client.getTickCount(), amount);
	}

	/** One of the player's own ({@code isMine()}) splats on an npc, before anything has judged it. */
	void onMyHitsplat(NPC npc, int amount)
	{
		tracker.ownSplat(npc.getIndex(), client.getTickCount(), amount);
	}

	/**
	 * A party member's special, from RuneLite's special attack counter. The
	 * player's own comes back as one of these too, and is dropped: it was counted
	 * when it landed.
	 */
	void onSpecialCounterUpdate(SpecialCounterUpdate event)
	{
		final PartyMember me = party == null ? null : party.getLocalMember();
		final Player self = client.getLocalPlayer();
		if (isEcho(event, me == null ? Long.MIN_VALUE : me.getMemberId(), self == null ? Integer.MIN_VALUE : self.getId())
			|| event.getWorld() != client.getWorld())
		{
			return;
		}
		final NPC npc = client.getTopLevelWorldView() == null ? null
			: client.getTopLevelWorldView().npcs().byIndex(event.getNpcIndex());
		if (npc == null)
		{
			return;
		}
		tracker.partyDrain(client.getTickCount(), event.getWeapon(), event.getHit(), npc.getIndex(), npc.getId(),
			npc.getName(), targetFor(npc));
	}

	/**
	 * Whether a party message is the player's own special come back: sent by this
	 * party member, or naming this player. RuneLite's own counter drops the same.
	 */
	static boolean isEcho(SpecialCounterUpdate event, long localMemberId, int localPlayerId)
	{
		return event.getMemberId() == localMemberId || event.getPlayerId() == localPlayerId;
	}

	/**
	 * An npc changed form. A boss whose rules restore its stats on entering that
	 * form starts again from its base; otherwise the drains carry over, against
	 * the new form's levels.
	 */
	void onNpcChanged(NPC npc)
	{
		final DrainTracker.Tracked tracked = tracker.tracked(npc.getIndex());
		if (tracked == null || tracked.npcId == npc.getId())
		{
			return;
		}
		final BossRules.DrainRules rules = rulesFor(npc.getId());
		final boolean restores = rules != null && rules.resetsOnNpc(npc.getId()) && !rules.resetsOnNpc(tracked.npcId);
		tracker.changed(npc.getIndex(), client.getTickCount(), npc.getId(), npc.getName(), targetFor(npc), restores);
	}

	/** A varbit changed: Sotetseg's maze restores his Defence. */
	void onVarbitChanged(int varbitId, int value)
	{
		final BossRules rules = monsters.rules();
		if (rules == null || !rules.drainVarbits().contains(varbitId))
		{
			return;
		}
		for (NPC npc : client.getTopLevelWorldView() == null ? java.util.Collections.<NPC>emptyList()
			: client.getTopLevelWorldView().npcs())
		{
			if (npc == null || tracker.tracked(npc.getIndex()) == null)
			{
				continue;
			}
			final BossRules.DrainRules r = rulesFor(npc.getId());
			if (r != null && r.resetsOnVarbit(varbitId, value))
			{
				tracker.reset(npc.getIndex(), client.getTickCount(), "varbit " + varbitId + " = " + value);
			}
		}
	}

	private BossRules.DrainRules rulesFor(int npcId)
	{
		final BossRules.Boss boss = bossFor(npcId);
		return boss == null ? null : boss.defenceDrain;
	}

	private BossRules.Boss bossFor(int npcId)
	{
		final BossRules rules = monsters.rules();
		return rules == null ? null : rules.forNpc(npcId);
	}

	/**
	 * What the drain tracking knows of an npc: its levels as the raid leaves them,
	 * its floors, and whether it is a demon or Tekton. Null without monster stats.
	 *
	 * <p>In the Tombs of Amascut the raid level raises the defence ROLL, not the
	 * Defence level (wiki, Tombs of Amascut), so drains and the floors there work on
	 * the unscaled level and {@link CombatCalc} scales what is left. In the Chambers
	 * the levels themselves scale with the party, and drains work on those.
	 */
	DrainRule.Target targetFor(NPC npc)
	{
		final MonsterStatsProvider.MonsterStats stats = monsters.get(npc.getId());
		if (stats == null)
		{
			return null;
		}
		final int defence = RaidScaling.tombsRaidLevel(client) > 0 ? stats.getDefenceLevel()
			: RaidScaling.defence(client, stats.getDefenceLevel(), stats.getName(), partyHitpoints.highest());
		final int magic = RaidScaling.magic(client, stats.getMagicLevel(), stats.getName(), partyHitpoints.highest());
		return target(stats, rulesFor(npc.getId()), defence, magic);
	}

	/** The target from its stats and rules, at these (raid-scaled) levels. Static for the tests. */
	static DrainRule.Target target(MonsterStatsProvider.MonsterStats stats, BossRules.DrainRules rules, int defence,
		int magic)
	{
		final boolean immune = stats.isDrainImmune() || rules != null && rules.immune;
		final int defenceFloor = rules == null ? 0 : rules.defenceFloor(defence);
		final int magicFloor = rules == null ? 0 : rules.magicFloor(magic);
		return new DrainRule.Target(defence, magic, stats.getDefMagic(), stats.getAttackLevel(),
			stats.getStrengthLevel(), stats.getRangedLevel(), defenceFloor, magicFloor, immune,
			stats.hasAttribute("demon"), rules != null && rules.tekton, rules != null && rules.godswordDoubled,
			rules == null ? BossRules.DrainRules.DEFAULT_REGEN_TICKS : rules.regenTicks());
	}

	/** The target's tracked stats for the live readout and the fight's record, or null where nothing is tracked. */
	DrainTracker.Tracked tracked(int npcIndex)
	{
		return tracker.tracked(npcIndex);
	}

	/** The fight's drain record (see {@link DrainTracker#record}), starting it from its base if nothing was thrown. */
	DrainRecord recordFor(NPC npc, int npcIndex, int fromTick)
	{
		if (npc != null && npc.getIndex() == npcIndex)
		{
			tracker.track(npcIndex, npc.getId(), npc.getName(), targetFor(npc));
		}
		return tracker.record(npcIndex, fromTick);
	}

	/** Levels of Defence taken off this npc, 0 where nothing is tracked. */
	int drainedFrom(int npcIndex)
	{
		final DrainTracker.Tracked t = tracker.tracked(npcIndex);
		return t == null ? 0 : t.target.defence - t.state.defence;
	}

	/** Levels of Magic taken off it. */
	int magicDrainedFrom(int npcIndex)
	{
		final DrainTracker.Tracked t = tracker.tracked(npcIndex);
		return t == null ? 0 : t.target.magic - t.state.magic;
	}

	/** Points of magic defence bonus taken off it (the Eye of ayak). */
	int magicDefenceDrainedFrom(int npcIndex)
	{
		final DrainTracker.Tracked t = tracker.tracked(npcIndex);
		return t == null ? 0 : t.target.magicDefence - t.state.magicDefence;
	}

	/**
	 * The drain dies with the NPC, as its stats do; a boss whose actor the game
	 * replaces mid-fight keeps them a few ticks for the next one (see
	 * {@link #onGameTick}).
	 */
	void forget(NPC npc)
	{
		tracker.forget(npc.getIndex(), carryKey(bossFor(npc.getId())), client.getTickCount());
	}

	/** The same target back under another index (T-088): its drain goes with it. */
	void move(int fromIndex, int toIndex)
	{
		tracker.move(fromIndex, toIndex);
	}

	void clear()
	{
		tracker.clear();
		thrownWeapon = null;
		specEnergy = -1;
	}

	private int equippedWeaponId()
	{
		final ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null)
		{
			return -1;
		}
		final Item item = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return item == null ? -1 : item.getId();
	}
}
