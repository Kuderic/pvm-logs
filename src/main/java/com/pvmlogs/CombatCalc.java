package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.NPCComposition;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.game.NPCManager;
import net.runelite.client.util.Text;

/**
 * Max hit, hit chance and average damage for the player's live loadout against a
 * given NPC. Anything needing the target's stats returns -1 when they are not
 * known. Formulae and prayer multipliers follow the LlemonDuck dps-calculator
 * (BSD-2), (c) Paul Norton.
 */
@Slf4j
class CombatCalc
{
	enum Style
	{
		MELEE,
		RANGED,
		MAGIC
	}

	private final Client client;
	private final ItemManager itemManager;
	private final MonsterStatsProvider monsters;
	private final GearBonusCalc gearBonuses;
	private final PvmPerformanceConfig config;
	private final NPCManager npcManager;
	private final DefenceDrain drain;
	// The boss's damage modifiers an attack lands with (T-057).
	private final DamageModifiers modifiers;
	private final PartyHitpoints partyHitpoints;
	private final TargetHealth health;

	// A manual cast counts for a few ticks past the click, so it survives the gap between casts while the player keeps
	// going.
	/** A game tick, in seconds. */
	private static final double TICK_SECONDS = 0.6;

	private static final int MANUAL_CAST_TICKS = 8;
	/** The twinflame staff's cast speed (T-086). */
	static final int TWINFLAME_CAST_TICKS = 6;
	// Clicks that may be outstanding at once. Two covers a click made while the previous cast is still in the air; the
	// third is slack.
	private static final int MAX_PENDING_CASTS = 3;
	// Slot index back to the slot, for reading the slot an inventory item goes in off its equipment stats. Not every index
	// is a real slot.
	/**
	 * The claws' hitsplat shape when the first accuracy roll connects: a max
	 * hit, then half, then a quarter twice, which is the doubled max the wiki
	 * describes spread over four hitsplats.
	 */
	private static final double[] CLAW_SHAPE = {1.0, 0.5, 0.25, 0.25};
	/**
	 * The claws' fourth-roll outcome, which is the one that does not follow the
	 * shape above: 125% of the max concentrated onto a single hitsplat.
	 */
	private static final double[] CLAW_LAST_RESORT = {1.25};

	/**
	 * The crimson kisten's four accuracy rolls, the floor of each outcome's
	 * damage as a share of the max hit, and the width of every band.
	 */
	private static final int KISTEN_ROLLS = 4;
	private static final double[] KISTEN_FLOORS = {0.70, 0.90, 1.10, 1.30};
	private static final double KISTEN_BAND = 0.40;

	/**
	 * Burning barrage, by which accuracy roll connected first. The total rolls
	 * between the floor and the ceiling as a share of the normal max hit, and is
	 * split over three hitsplats by the shape.
	 */
	private static final double[] BURNING_CLAW_FLOORS = {0.75, 0.50, 0.25};
	private static final double[] BURNING_CLAW_CEILINGS = {1.75, 1.50, 1.25};
	private static final double[][] BURNING_CLAW_SHAPES = {
		{0.25, 0.25, 0.50},
		{0.50, 0.50, 0.00},
		{0.00, 0.00, 1.00},
	};
	/**
	 * Wiki (Burning claws, read 2026-10-27): each hitsplat of a Burning barrage
	 * rolls its own burn, at 15% when the first accuracy roll connected, 30% the
	 * second, 45% the third; the all-miss hits can't burn.
	 */
	private static final double[] BURNING_CLAW_BURN_CHANCE = {0.15, 0.30, 0.45};
	/** Wiki (Burn): a burn deals 10 over 40 ticks; the Arkan blade's strong burn is the same 10. */
	private static final int BURN_DAMAGE = 10;
	/** Wiki (Scorching bow, read 2026-10-27): Scorching shackles burns a demon for 5 in all. */
	private static final int SCORCHING_BURN = 5;

	/**
	 * Verzik's first phase, in all three difficulties. The seated form counts:
	 * it is the same phase and the same cap, and the fight opens on it.
	 */
	private static final Set<Integer> VERZIK_FIRST_PHASE = Collections.unmodifiableSet(
		new HashSet<>(Arrays.asList(
			NpcID.VERZIK_INITIAL, NpcID.VERZIK_PHASE1,
			NpcID.VERZIK_INITIAL_STORY, NpcID.VERZIK_PHASE1_STORY,
			NpcID.VERZIK_INITIAL_HARD, NpcID.VERZIK_PHASE1_HARD,
			NpcID.VERZIK_INITIAL_BASE, NpcID.VERZIK_INITIAL_QUICKSTART,
			NpcID.VERZIK_INITIAL_HARD_BASE, NpcID.VERZIK_INITIAL_HARD_QUICKSTART)));

	private static final Map<Integer, EquipmentInventorySlot> SLOT_BY_INDEX = new HashMap<>();

	static
	{
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			SLOT_BY_INDEX.put(slot.getSlotIdx(), slot);
		}
	}

	// The weapon the combat tab heading was last seen describing, so a heading that has not caught up with a swap can be
	// told from one that has.
	private int headingWeapon = Integer.MIN_VALUE;
	private String headingText;
	private final ManualCast manualCast = new ManualCast();
	// Whether the attack currently being described was proven by a hitsplat.
	private boolean meleeProvenAttack;
	// Which setup the level and prayer reads answer for, as two switches that work apart: the prayer read is the one up or
	// the one intended for the style, and the levels are the real ones or the base plus the full boost expected here.
	// REAL is what the player had; IDEAL is the intended prayer at full boost; PRAYER_HELD is the intended prayer at the
	// real boost, for a prayer flicked within the tick; BOOSTED is the prayer as it was at full boost, which is what prices
	// the missing boost on its own.
	private static final int INTENDED_PRAYER = 1;
	private static final int FULL_BOOST = 2;
	private static final int REAL = 0;
	private static final int PRAYER_HELD = INTENDED_PRAYER;
	private static final int BOOSTED = FULL_BOOST;
	private static final int IDEAL = INTENDED_PRAYER | FULL_BOOST;
	private static final int MODES = 4;
	private int mode = REAL;
	// The index of the NPC the figures are being computed against, so the defence read can account for what has been
	// drained off it.
	private int targetIndex = -1;
	// The NPC being fought, for the health bar the ruby bolt is a share of.
	private NPC targetNpc;
	// T-050: the target's levels to work the figures out at, in place of what the drain tracker says is left; null for the
	// tracker's. Set only for the length of one figuresAt call.
	private DrainRule.Target levelsBase;
	private DrainRule.State levelsAt;
	// The enemy a miss has armed the gauntlets against, or -1. Per enemy because the effect is spent on the next attack
	// against that same one.
	private final ConflictionCharge charge = new ConflictionCharge();
	private BlowpipeDart cachedDart;
	private ItemEquipmentStats cachedDartStats;

	@Inject
	CombatCalc(Client client, ItemManager itemManager, MonsterStatsProvider monsters,
		GearBonusCalc gearBonuses, PvmPerformanceConfig config, NPCManager npcManager,
		DefenceDrain drain, PartyHitpoints partyHitpoints, TargetHealth health, DamageModifiers modifiers)
	{
		this.modifiers = modifiers;
		this.health = health;
		this.client = client;
		this.itemManager = itemManager;
		this.monsters = monsters;
		this.gearBonuses = gearBonuses;
		this.config = config;
		this.npcManager = npcManager;
		this.drain = drain;
		this.partyHitpoints = partyHitpoints;
	}

	/** The gear multipliers for the current loadout against this target. */
	private GearBonus computeGearBonus(int npcId)
	{
		return gearBonuses.compute(gear(), attackStyle(), monsters.get(npcId), npcId, castSpell(),
			slayerVerdict(npcId).onTask);
	}

	// What the on-task verdict was worked out from: the slayer varps, the boss id and the npc. Recomputed only when one of
	// them moves, since the task name comes from the game's tables.
	private String slayerKey;
	private SlayerTasks.Verdict slayerVerdictMemo;

	/**
	 * Whether this npc is the player's slayer task, for the slayer helmet and
	 * black mask (T-015). Read from the game as RuneLite's Slayer plugin does;
	 * when it cannot be told, assumed on task, as the old setting's default was.
	 */
	SlayerTasks.Verdict slayerVerdict(int npcId)
	{
		final int count = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		final int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		final int bossId = client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID);
		final int area = client.getVarpValue(VarPlayerID.SLAYER_AREA);
		// Who gave the task: Krystilia's and Konar's crazy archaeologist tasks do not take the Deranged archaeologist
		// (T-090).
		final int master = client.getVarbitValue(VarbitID.SLAYER_MASTER);
		final String key = count + "/" + taskId + "/" + bossId + "/" + area + "/" + master + "/" + npcId + "/"
			+ slayerWatch.learned().size();
		if (!key.equals(slayerKey) || slayerVerdictMemo == null)
		{
			slayerKey = key;
			final NPCComposition npc = npcId < 0 ? null : client.getNpcDefinition(npcId);
			final String taskName = count > 0 ? slayerTaskName(taskId, bossId) : null;
			final String areaName = count > 0 && area > 0 ? slayerAreaName(area) : null;
			slayerWatch.noteTask(taskName, SlayerTasks.inWilderness(master, areaName), count);
			slayerVerdictMemo = SlayerTasks.verdict(count, taskName, npc == null ? null : npc.getName(), area, master,
				areaName, slayerWatch.learned());
		}
		return slayerVerdictMemo;
	}

	// The game's check on the slayer table (T-090): what it learns lasts the session, which is this calc's life.
	private final SlayerTasks.Watch slayerWatch = new SlayerTasks.Watch();

	/**
	 * Varp SLAYER_COUNT changed. The task is read first, so a count that falls to none on the task's last kill still has
	 * the task's name to be matched against.
	 *
	 * @return a TASK MISMATCH line for the debug log, or null
	 */
	String onSlayerCountChanged(int tick)
	{
		final int count = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		if (count > 0)
		{
			// Still on the task: read it now, in case nothing has asked for a verdict since it was given.
			final int area = client.getVarpValue(VarPlayerID.SLAYER_AREA);
			final String areaName = area > 0 ? slayerAreaName(area) : null;
			final String taskName = slayerTaskName(client.getVarpValue(VarPlayerID.SLAYER_TARGET),
				client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID));
			final boolean wilderness = SlayerTasks.inWilderness(client.getVarbitValue(VarbitID.SLAYER_MASTER), areaName);
			final String line = slayerWatch.onCount(count, tick);
			slayerWatch.noteTask(taskName, wilderness, count);
			return line;
		}
		return slayerWatch.onCount(count, tick);
	}

	/**
	 * An NPC despawned dead.
	 *
	 * @param mine whether it was the target of the player's fight
	 * @return a TASK MISMATCH line for the debug log, or null
	 */
	String onNpcDiedForSlayer(NPC npc, boolean mine, int tick)
	{
		final NPCComposition def = npc.getTransformedComposition();
		final String name = def != null ? def.getName() : npc.getName();
		return slayerWatch.onDeath(npc.getId(), name, mine, tick);
	}

	/** @return a TASK MISMATCH line for the debug log, or null */
	String onSlayerTick(int tick)
	{
		return slayerWatch.onTick(tick);
	}

	// The name of Konar's area from the game's tables, as RuneLite's Slayer plugin reads it. Null when they cannot be
	// read.
	private String slayerAreaName(int area)
	{
		try
		{
			final List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID,
				0, area);
			return rows.isEmpty() ? null
				: (String) client.getDBTableField(rows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0];
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	// The task's name from the game's tables, as RuneLite's Slayer plugin reads it; task 98 is "bosses", whose boss comes
	// from its own sublist. Null when the tables cannot be read.
	private String slayerTaskName(int taskId, int bossId)
	{
		try
		{
			final int row;
			if (taskId == 98)
			{
				final List<Integer> bosses = client.getDBRowsByValue(DBTableID.SlayerTaskSublist.ID,
					DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, bossId);
				if (bosses.isEmpty())
				{
					return null;
				}
				row = (Integer) client.getDBTableField(bosses.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
			}
			else
			{
				final List<Integer> tasks = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0,
					taskId);
				if (tasks.isEmpty())
				{
					return null;
				}
				row = tasks.get(0);
			}
			return (String) client.getDBTableField(row, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	// Held for the tick. Equipment, varbits and the combat option cannot change within one, and an attack tick asks for
	// the gear multipliers a dozen times. The ideal figures share it safely: they change levels, not gear.
	private int memoTick = Integer.MIN_VALUE;
	private AttackStyle memoStyle;
	private String memoWeaponName;
	private boolean memoWeaponNameSet;
	private final int[] memoAttackBonus = new int[AttackType.values().length];
	private final boolean[] memoAttackBonusSet = new boolean[AttackType.values().length];
	private final int[] memoEquipmentBonus = new int[2];
	private final boolean[] memoEquipmentBonusSet = new boolean[2];
	private final int[] memoBaseMaxHit = new int[MODES];
	private final boolean[] memoBaseMaxHitSet = new boolean[MODES];
	private int memoGearNpc = Integer.MIN_VALUE;
	private GearBonus memoGear;
	private Loadout memoLoadout;
	// The magic formula's parts for a target, per mode, as the base max hit is: levels and prayers differ by mode.
	private final MagicDamage[] memoMagic = new MagicDamage[MODES];
	private final int[] memoMagicNpc = new int[MODES];
	private final List<String>[] memoMagicWhy = newReasonSlots();

	@SuppressWarnings("unchecked")
	private static List<String>[] newReasonSlots()
	{
		return (List<String>[]) new List<?>[MODES];
	}
	// The gear search's answer, which outlives the tick memo: the answer names item ids, and a switch only moves one
	// between the hand and the bag, so it keeps describing what the player should be wearing while they wear it.
	private int memoBestGearNpc = Integer.MIN_VALUE;
	private Loadout memoBestGear;
	// What the answer was worked out under: the weapon because it is pinned, the style because which attack bonus counts
	// turns on it, and the signature because the search can only offer what is there.
	private int memoBestGearWeapon = Integer.MIN_VALUE;
	private AttackStyle memoBestGearStyle;
	private long memoBestGearAvailable = Long.MIN_VALUE;
	// Every equippable item on the player, worn and carried together, and whether a container has said anything moved
	// since it was last counted.
	private long availableSignature;
	private boolean availableStale = true;

	/**
	 * Drops everything held once its tick has passed, and when the worn items
	 * change: a weapon swapped part way through a tick left every figure already
	 * worked out answering for the weapon before it. The gear search's answer is
	 * NOT dropped here - see bestGear.
	 */
	void invalidateGear()
	{
		memoTick = Integer.MIN_VALUE;
		availableStale = true;
	}

	/**
	 * Something moved in the inventory. Only a note that the count is worth
	 * taking again - it waits until the search asks, because this fires on every
	 * bite and sip.
	 */
	void invalidateInventory()
	{
		availableStale = true;
	}

	/**
	 * How many of what the player could put on, worn and carried together. A
	 * switch leaves it alone, which is the point: an item on the arm is one no
	 * longer in the bag.
	 */
	private long availableEquippableSignature()
	{
		if (!availableStale)
		{
			return availableSignature;
		}
		availableStale = false;
		// Order-independent, so items shuffled between slots or containers are not a change. Cheap next to the search it
		// avoids.
		long signature = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			final int id = gear().id(slot);
			if (id >= 0)
			{
				signature += (long) id * 2654435761L;
			}
		}
		final ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				// Food and potions fall out here, and a dose drunk changes the item's id, so counting ids alone would throw the
				// answer away.
				if (equipmentStats(item.getId()) != null)
				{
					signature += (long) item.getId() * 2654435761L;
				}
			}
		}
		availableSignature = signature;
		return signature;
	}

	private void expireMemo()
	{
		final int tick = client.getTickCount();
		if (tick == memoTick)
		{
			return;
		}
		memoTick = tick;
		memoStyle = null;
		memoWeaponNameSet = false;
		memoGearNpc = Integer.MIN_VALUE;
		memoGear = null;
		memoLoadout = null;
		Arrays.fill(memoAttackBonusSet, false);
		Arrays.fill(memoEquipmentBonusSet, false);
		Arrays.fill(memoBaseMaxHitSet, false);
		Arrays.fill(memoMagic, null);
	}

	// The gear every figure is worked out for. One object rather than a container read per question, so asking what
	// another loadout would do is the same calculation with a different argument.
	private Loadout gear()
	{
		expireMemo();
		if (memoLoadout == null)
		{
			memoLoadout = Loadout.worn(itemManager, client.getItemContainer(InventoryID.WORN));
		}
		return memoLoadout;
	}

	/**
	 * What is worn, slot by slot, with each item's id (T-056): so a log can say
	 * which variant of a piece a bonus missed. Empty slots are left out.
	 */
	static String describe(Loadout gear)
	{
		if (gear == null)
		{
			return "nothing known";
		}
		final StringBuilder b = new StringBuilder();
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			final int id = gear.id(slot);
			if (id < 0)
			{
				continue;
			}
			if (b.length() > 0)
			{
				b.append(", ");
			}
			final String name = gear.name(slot);
			b.append(slot.name().toLowerCase(Locale.ROOT)).append(' ').append(name == null ? "?" : name)
				.append(" #").append(id);
		}
		return b.length() == 0 ? "nothing worn" : b.toString();
	}

	/** The worn gear as one line, for the fight-start log and the saved log's header. */
	String describeWorn()
	{
		return "GEAR worn: " + describe(gear());
	}

	/** The gear worn now, described only when asked: what a MAX EXCEEDED line names. */
	java.util.function.Supplier<String> wornNow()
	{
		final Loadout snapshot = gear();
		return () -> describe(snapshot);
	}

	/** Whether the ordinary attack's max is one hitsplat's: not a scythe, dark bow or other multi-hit weapon (T-056). */
	boolean maxIsOneSplat()
	{
		return ordinaryShape() == OrdinaryShape.ONE;
	}

	// Points every figure at a different loadout. Everything derived from the gear goes with it, or the swap is half
	// applied - the bonuses and the max hit are memoised per tick.
	private void useLoadout(Loadout gear)
	{
		memoLoadout = gear;
		memoStyle = null;
		memoWeaponNameSet = false;
		memoGearNpc = Integer.MIN_VALUE;
		memoGear = null;
		Arrays.fill(memoAttackBonusSet, false);
		Arrays.fill(memoEquipmentBonusSet, false);
		Arrays.fill(memoBaseMaxHitSet, false);
		Arrays.fill(memoMagic, null);
	}

	// Expected damage for a loadout that is not being worn. Restores what was there afterwards, memo included, so a search
	// leaves no trace.
	private double averageHitWith(Loadout gear, int npcId, int as)
	{
		final Loadout worn = memoLoadout;
		final int was = mode;
		try
		{
			useLoadout(gear);
			mode = as;
			return averageHit(npcId);
		}
		finally
		{
			mode = was;
			useLoadout(worn);
		}
	}

	/**
	 * How far the attack going out now reaches, in tiles (T-097: the Giant
	 * Mole's chase). From the wiki's Attack range: "almost all melee weapons
	 * have an attack range of 1", halberds 2; "all combat offensive and curse
	 * spells have an attack range of 10", powered staves mostly 7 (6 to 8);
	 * shortbows and crossbows 7, longbows 10, thrown weapons 4 (darts 3,
	 * blowpipes 5), chinchompas 9; long-range "usually gives 2 tiles greater
	 * range ... up to a 10 tile limit".
	 */
	int attackReach()
	{
		final AttackStyle style = attackStyle();
		return reachOf(style.getAttackType(), weaponCategory(), style.getCombatStyle(),
			poweredStaffMaxHit() > 0 && activeManualCast() == null);
	}

	/** The reach of an attack of this type, weapon and style (T-097); see {@link #attackReach}. */
	static int reachOf(AttackType type, WeaponCategory category, CombatStyle style, boolean poweredStaff)
	{
		final int base;
		if (type == AttackType.MAGIC)
		{
			base = poweredStaff ? 7 : 10;
		}
		else if (type == AttackType.RANGED)
		{
			base = category == WeaponCategory.CHINCHOMPAS ? 9 : category == WeaponCategory.THROWN ? 4 : 7;
		}
		else
		{
			return category == WeaponCategory.POLEARM ? 2 : 1;
		}
		return style == CombatStyle.LONGRANGE ? Math.min(10, base + 2) : base;
	}

	private AttackStyle attackStyle()
	{
		expireMemo();
		if (memoStyle == null)
		{
			memoStyle = resolveAttackStyle();
		}
		return memoStyle;
	}

	private String weaponName()
	{
		expireMemo();
		if (!memoWeaponNameSet)
		{
			memoWeaponName = computeWeaponName();
			memoWeaponNameSet = true;
		}
		return memoWeaponName;
	}

	private int attackBonus(AttackType type)
	{
		expireMemo();
		final int slot = type.ordinal();
		if (!memoAttackBonusSet[slot])
		{
			memoAttackBonus[slot] = computeAttackBonus(type);
			memoAttackBonusSet[slot] = true;
		}
		return memoAttackBonus[slot];
	}

	private int equipmentBonus(boolean melee)
	{
		expireMemo();
		final int slot = melee ? 0 : 1;
		if (!memoEquipmentBonusSet[slot])
		{
			memoEquipmentBonus[slot] = computeEquipmentBonus(melee);
			memoEquipmentBonusSet[slot] = true;
		}
		return memoEquipmentBonus[slot];
	}

	/**
	 * Held per mode rather than once: the levels and prayers behind it are
	 * substituted while working out what an attack should have been.
	 */
	private int baseMaxHit()
	{
		expireMemo();
		final int slot = mode;
		if (!memoBaseMaxHitSet[slot])
		{
			memoBaseMaxHit[slot] = computeBaseMaxHit();
			memoBaseMaxHitSet[slot] = true;
		}
		return memoBaseMaxHit[slot];
	}

	private GearBonus gearBonus(int npcId)
	{
		expireMemo();
		if (memoGear == null || memoGearNpc != npcId)
		{
			memoGear = computeGearBonus(npcId);
			memoGearNpc = npcId;
		}
		return memoGear;
	}

	/** Expected hit chance (0..1), or -1 with no data. A boss's modifier can replace it (T-057: "cannot miss"). */
	double hitChance(int npcId)
	{
		return ModifierTracker.accuracy(bossModifiers(npcId), hitChance(npcId, null));
	}

	/**
	 * Whether an attack going out now lands with a boss modifier the live figures
	 * must not show (T-087: the Maggot King's punish).
	 */
	boolean hiddenModifiersApply(int npcId)
	{
		final NPC npc = targetNpc;
		return modifiers != null && npc != null && npc.getId() == npcId && modifiers.hasModifiers(npc)
			&& modifiers.hiddenApply(npc, weaponStyle(), landsInTicks(npc));
	}

	/** While set, the figures leave out the modifiers marked {@code hiddenLive}: for what is shown live (T-087). */
	void setLiveFigures(boolean live)
	{
		if (modifiers != null)
		{
			modifiers.setLive(live);
		}
	}

	/** The change to the target's magic defence bonus its modifiers make for an attack going out now (T-087). */
	private int bossMagicDefenceChange(int npcId)
	{
		final NPC npc = targetNpc;
		if (modifiers == null || npc == null || npc.getId() != npcId || !modifiers.hasModifiers(npc))
		{
			return 0;
		}
		return modifiers.magicDefenceChange(npc, weaponStyle(), landsInTicks(npc));
	}

	/**
	 * The boss's damage modifiers an ordinary attack going out now lands with
	 * (T-057): the Sire's halving while it walks, the Leviathan's stun cap, its
	 * wound, its specials and the pathfinder. Empty for any other target, and for
	 * a target other than the fight's own.
	 */
	private List<BossRules.ModifierRule> bossModifiers(int npcId)
	{
		final NPC npc = targetNpc;
		if (modifiers == null || npc == null || npc.getId() != npcId || !modifiers.hasModifiers(npc))
		{
			return Collections.emptyList();
		}
		return modifiers.forAttack(npc, weaponStyle(), landsInTicks(npc));
	}

	// Ticks from the attack going out to its hitsplat, as the client sees it: melee the next tick, ranged and magic by
	// the wiki's hit delay for the distance, plus the tick the client lags the server (as magicHitDelay measures it).
	private int landsInTicks(NPC npc)
	{
		final Player me = client.getLocalPlayer();
		final WorldArea area = npc.getWorldArea();
		final int distance = me == null || area == null || me.getWorldLocation() == null ? 1
			: Math.max(0, area.distanceTo(me.getWorldLocation()));
		switch (weaponStyle())
		{
			case MAGIC:
				return 2 + (1 + distance) / 3;
			case RANGED:
				return 2 + (3 + distance) / 6;
			default:
				return 1;
		}
	}

	/** The modifiers, for the ATTACK line: their names, or "none". */
	String describeBossModifiers(int npcId)
	{
		return ModifierTracker.names(bossModifiers(npcId));
	}

	/**
	 * Hit chance with a special attack's roll modifier folded in.
	 *
	 * <p>The modifier multiplies the ATTACK ROLL, not the finished chance, and is
	 * applied to the FINISHED roll with integer arithmetic rather than folded in
	 * with the gear multiplier - that is the order the game works in, and it is
	 * exact where a double multiply can land a point either side. Scaling the
	 * chance instead would put a godsword above 100% against anything it already
	 * hits two thirds of the time.
	 */
	double hitChance(int npcId, SpecialAttack spec)
	{
		final MonsterStatsProvider.MonsterStats npc = attackStats(npcId);
		if (npc == null)
		{
			return -1;
		}
		final AttackStyle style = attackStyle();
		// Weaken rolls as a stab whatever style is selected (T-050).
		final AttackType type = spec != null && spec.rollsAgainst() != null && style.getAttackType().isMelee()
			? spec.rollsAgainst() : style.getAttackType();
		final double gear = gearBonus(npcId).getAccuracy();
		// A boss's modifiers on the defence roll (T-087: the Maggot King's punish, 85% off it for a crush).
		final List<BossRules.ModifierRule> mods = bossModifiers(npcId);
		if (type.isMelee())
		{
			return meleeHitChance(style, type, npc, gear, spec, mods);
		}
		if (type == AttackType.RANGED)
		{
			return rangedHitChance(style, npc, gear, spec, mods);
		}
		return magicHitChance(style, npc, gear, npcId, spec, mods);
	}

	/**
	 * The monster data an attack going out now is judged against (T-087): the
	 * version a boss's modifier names while it applies (the Maggot King "Nearby"
	 * while it slams), else the version in use.
	 */
	private MonsterStatsProvider.MonsterStats attackStats(int npcId)
	{
		final List<BossRules.ModifierRule> mods = bossModifiers(npcId);
		final String version = ModifierTracker.version(mods);
		MonsterStatsProvider.MonsterStats stats = version == null ? null : monsters.get(npcId, version);
		if (stats == null)
		{
			stats = monsters.get(npcId);
		}
		// And the levels a phase adds (T-091: Araxxor enraged), under the drains, which come off the result.
		final int[] levels = ModifierTracker.levels(mods);
		return stats == null ? null : stats.withLevels(levels[0], levels[1], levels[2]);
	}

	/**
	 * How much of this fight's damage has landed on the target, pushed in by the
	 * plugin once a tick. Counted per npc rather than taken from the fight,
	 * since an AoE attack puts damage on several and only this one's counts
	 * towards what this one has left.
	 */
	private int targetDamageTaken;

	void noteTargetDamageTaken(int damage)
	{
		this.targetDamageTaken = Math.max(0, damage);
	}

	/**
	 * Full hitpoints for a target: the wiki data first, since RuneLite's own
	 * table has nothing for any Chambers monster, and RuneLite's as the fallback
	 * for anything the wiki data misses. 0 when neither knows.
	 */
	private int targetMaxHitpoints(int npcId)
	{
		final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
		if (npc != null && npc.getHitpoints() > 0)
		{
			return npc.getHitpoints();
		}
		final Integer known = npcManager.getHealth(npcId);
		return known == null ? 0 : known;
	}

	/**
	 * The most this attack could actually deal: what the target has LEFT.
	 *
	 * <p>Overkill, and it is not small over a trip. An attack expecting 25
	 * against something on 3 hitpoints deals 3, and the difference was counted
	 * as damage the setup failed to produce - once per kill, which over 323
	 * Hueycoatls came to about half an average hit each and several thousand
	 * damage of phantom underperformance.
	 *
	 * <p>Applied inside {@code averageHit}, so the ideal setup is capped by the
	 * same number as the real one and efficiency does not fall on every killing
	 * blow.
	 *
	 * <p>COUNTED, not read off the health bar. The bar is a ratio out of a scale
	 * of about thirty and is only drawn some of the time, and taking a remaining
	 * figure from it read 3 where 27 was left, then no cap at all four attacks
	 * later. Full hitpoints minus the damage this fight has put on this npc is
	 * exact while the player is the only one hitting it.
	 *
	 * <p>In a group it overestimates what is left, because other people's damage
	 * is invisible - which errs towards no cap, and no cap is the side to err
	 * on: a figure that is a little high is easier to live with than one that
	 * collapses to a fifth of itself for an attack.
	 */
	private int overkillCap(int npcId)
	{
		// The boss health bar where the game is drawing one for this target, else every splat on it counted, heals
		// included - see TargetHealth. Both beat the count below, which saw only the player's and the thrall's damage. The
		// count carries a boss rule's margin where it is known to run low (T-020, the Abyssal Sire).
		final int known = targetIndex < 0 ? -1 : health.capRemaining(targetIndex, client.getTickCount());
		if (known > 0)
		{
			return known;
		}
		final int max = targetMaxHitpoints(npcId);
		if (max <= 0)
		{
			return Integer.MAX_VALUE;
		}
		final int remaining = max - targetDamageTaken;
		// At or past zero the count has lost the target - something else killed it, or damage arrived that was not ours -
		// and a cap of nothing would zero the expected damage. Not knowing caps nothing.
		return remaining > 0 ? remaining : Integer.MAX_VALUE;
	}

	/**
	 * Void applied where the game applies it: to the EFFECTIVE LEVEL, before the
	 * attack roll or the max hit is worked out from it, and truncated there.
	 *
	 * <p>It used to be a multiplier on the finished figure, which is the same
	 * boost and a different number - the two round in different places and land
	 * a point apart. A blowpipe in elite void read a max of 33 where the game
	 * gives 34.
	 *
	 * <p>11/10 everywhere, except the elite set's ranged damage at 9/8 and the
	 * mage helm's accuracy at 29/20. Magic DAMAGE is not a level scaling at all
	 * - the elite set's 5% is a damage bonus and stays with the others.
	 */
	private int voidScaled(int effectiveLevel, AttackType type, boolean damage)
	{
		final int tier = GearBonusCalc.voidTier(type, gear());
		if (tier == GearBonusCalc.VOID_NONE)
		{
			return effectiveLevel;
		}
		if (type == AttackType.MAGIC)
		{
			return damage ? effectiveLevel : effectiveLevel * 29 / 20;
		}
		if (type == AttackType.RANGED && damage && tier == GearBonusCalc.VOID_ELITE)
		{
			return effectiveLevel * 9 / 8;
		}
		return effectiveLevel * 11 / 10;
	}

	/** How much of a hit the target keeps, for the few that shrug most of it off. */
	private double mitigation(int npcId)
	{
		return RaidScaling.damageTaken(npcId, attackStyle().getAttackType())
			* corporealBeastMitigation(npcId)
			* guardianMultiplier(npcId);
	}

	/**
	 * The Chambers' guardians, which a pickaxe chips at and nothing else
	 * touches. Mod Ash gives the modifier as
	 * {@code (50 + Mining level + pickaxe's level req) / 150}, applied to the
	 * damage already rolled - so it lands here with the other target-specific
	 * shares of a hit rather than in the max hit's own arithmetic, and Void and
	 * the rest are inside the figure it scales.
	 *
	 * <p>Not exact, and deliberately so: the game truncates EACH rolled hit,
	 * where scaling the average truncates once. That flatters the average by up
	 * to half a point, which is smaller than the party scaling either side of
	 * it and is the same approximation every other multiplier here makes.
	 *
	 * <p>The base Mining level, not the boosted one. The wiki notes the dragon
	 * pickaxe special gives no useful speed increase, which is what a boost of
	 * three worth 2% would look like either way, so this follows the wiki's own
	 * calculator rather than guessing at the live level.
	 */
	private double guardianMultiplier(int npcId)
	{
		if (!EncounterGroup.isGuardian(npcId))
		{
			return 1;
		}
		// Everything else is reduced to zero, the dragon warhammer's special excepted - and that one lands its drain before
		// the damage is zeroed, so it is a defence effect rather than a hit and is not undone by this.
		if (!attackStyle().getAttackType().isMelee() || weaponCategory() != WeaponCategory.PICKAXE)
		{
			return 0;
		}
		return (50.0 + client.getRealSkillLevel(Skill.MINING) + pickaxeLevelRequirement(weaponName())) / 150.0;
	}

	/**
	 * The level requirement the guardian formula reads off a pickaxe. Dragon and
	 * everything above it share 61 - the crystal pickaxe included, which Mod Ash
	 * confirmed rather than taking its own 71 - so the table names only what
	 * sits below that and the default carries the rest, variants and all.
	 */
	static int pickaxeLevelRequirement(String weaponName)
	{
		final String name = weaponName == null ? "" : weaponName.toLowerCase(Locale.ROOT);
		if (name.contains("bronze") || name.contains("iron"))
		{
			return 1;
		}
		if (name.contains("steel"))
		{
			return 6;
		}
		if (name.contains("black"))
		{
			return 11;
		}
		if (name.contains("mithril"))
		{
			return 21;
		}
		if (name.contains("adamant"))
		{
			return 31;
		}
		if (name.contains("rune") || name.contains("gilded"))
		{
			return 41;
		}
		return 61;
	}

	/**
	 * The Corporeal Beast halves everything but a corpbane weapon on stab.
	 *
	 * <p>Wiki: "50% damage reduction against any melee and ranged weapon that is
	 * not a Corpbane weapon. These weapons must also be on the stab attack
	 * style." Magic is exempt outright, as are recoil and Retribution, neither
	 * of which this counts as the player's attack anyway.
	 *
	 * <p>Ranged therefore always halves: there is no ranged weapon that attacks
	 * on stab, so the exemption cannot be reached from it. That is the rule as
	 * written rather than an assumption about ranged.
	 */
	private double corporealBeastMitigation(int npcId)
	{
		if (npcId != NpcID.CORP_BEAST)
		{
			return 1.0;
		}
		final AttackType type = attackStyle().getAttackType();
		if (type == AttackType.MAGIC)
		{
			return 1.0;
		}
		return type == AttackType.STAB && isCorpbaneWeapon(weaponName()) ? 1.0 : 0.5;
	}

	/**
	 * Wiki (Corpbane weapons): every spear, every halberd, Osmumten's fang, the
	 * thunder khopesh and King's barrage. Matched on name because the spears and
	 * halberds run to thirty-odd items across every metal, and a list of ids
	 * would be a table where a missing entry silently halves the figure.
	 */
	static boolean isCorpbaneWeapon(String weaponName)
	{
		if (weaponName == null)
		{
			return false;
		}
		final String name = weaponName.toLowerCase(Locale.ROOT);
		return name.contains("spear")
			|| name.contains("halberd")
			|| name.contains("osmumten's fang")
			|| name.contains("thunder khopesh");
	}

	double averageHit(int npcId)
	{
		final DamageMoments moments = averageHitMoments(npcId);
		return moments == null ? -1 : moments.mean;
	}

	/**
	 * The variance of one ordinary attack's damage (T-028), from the same
	 * distribution {@link #averageHit} averages; -1 where that has no figure.
	 */
	double averageHitVariance(int npcId)
	{
		final DamageMoments moments = averageHitMoments(npcId);
		return moments == null ? -1 : moments.variance();
	}

	/** One ordinary attack's damage as moments: {@link #averageHit}'s working, with the second moment beside it. */
	DamageMoments averageHitMoments(int npcId)
	{
		final double accuracy = hitChance(npcId);
		// A boss's damage modifiers (T-057) walk every roll of every hitsplat, so the game's rounding is kept per hit.
		final List<BossRules.ModifierRule> mods = bossModifiers(npcId);
		if (!mods.isEmpty())
		{
			final int first = (int) (unmitigatedMaxHit(npcId) * mitigation(npcId));
			if (accuracy < 0 || first <= 0)
			{
				return null;
			}
			final int[] maxima = weaponStyle() == Style.MAGIC ? new int[]{first}
				: shapeMaxima(ordinaryShape(), first, hitsPerAttack(npcId));
			return ModifierTracker.moments(mods, maxima, accuracy, Math.min(damageCap(npcId), overkillCap(npcId)));
		}
		if (weaponStyle() == Style.MAGIC)
		{
			// The magic formula walks every roll (T-026): Mark of Darkness and Ahrim's act on the rolled hit, sunfire runes
			// raise the bottom of it, and the twinflame staff adds a second splat, none of which a max over two can say.
			final MagicDamage magic = magicModel(npcId);
			if (accuracy < 0 || magic.preHitRoll() <= 0)
			{
				return null;
			}
			// T-028: the variance from the same walk, the miss as the accuracy roll in front of it.
			return magic.onHit(mitigation(npcId), damageCap(npcId), overkillCap(npcId)).landing(accuracy);
		}
		// Averages, not best cases: a keris crit or an ahrim's proc raises the max hit but only lifts sustained damage by a
		// few percent.
		final double averageMax = (baseMaxHit() * gearBonus(npcId).getExpectedDamage()
			+ colossalBladeBonus(npcId) + elementalWeaknessBonus(npcId)) * mitigation(npcId);
		if (accuracy < 0 || averageMax <= 0)
		{
			return null;
		}
		final int cap = Math.min(damageCap(npcId), overkillCap(npcId));
		// A scythe swing is several hits, each rolling its own accuracy and damage, so one attack is expected to deal their
		// sum. Each max is half the one before ROUNDED DOWN, which is why this walks them rather than multiplying by 1.75: a
		// 51 max is 51, 25, 12, which is 88 and not 89.25.
		final DamageMoments ordinary = shapeMoments(ordinaryShape(), averageMax, accuracy, hitsPerAttack(npcId), cap);

		final EnchantedBolt bolt = loadedBolt(npcId);
		if (bolt == null)
		{
			return ordinary;
		}
		final double procChance = bolt.chance(hasKandarinHardDiary());
		switch (bolt)
		{
			case RUBY:
				// Fires regardless of the accuracy roll and replaces the hit entirely, so the two outcomes are weighted rather than
				// scaled.
				return rubyMoments(procChance, EnchantedBolt.rubyDamage(targetCurrentHp(npcId)), ordinary);
			case DIAMOND:
				// Also ignores accuracy, but rolls damage normally against a raised max, so a proc always lands.
				return DamageMoments.uniform(1.15 * averageMax).weighted(procChance)
					.or(ordinary.weighted(1.0 - procChance));
			default:
				// Onyx leeches from damage actually dealt, so it needs a hit first.
				return onyxMoments(procChance, ordinary);
		}
	}

	/**
	 * How an ordinary attack's hitsplats roll (T-032), from the OSRS Wiki (Multi-hit
	 * weapons and each weapon's page, read 2026-10-27). Before this, every weapon
	 * but the scythe was one roll from 0 to the max: a dark bow expected half its
	 * two arrows and the charged tonalztics two thirds of their two throws, so both
	 * read as luck; the dual macuahuitl, whose second hit needs its first, expected
	 * too much.
	 */
	enum OrdinaryShape
	{
		/** One hit from 0 to the max: nearly everything. */
		ONE,
		/** The scythe: up to three hits by the target's size, each half the one before, rounded down. */
		SCYTHE,
		/** Two independently rolled hits splitting the max, ⌊M/2⌋ and the rest: Torag's hammers, the blades, temotli, tecpatls. */
		SPLIT,
		/** The dual macuahuitl: the max split ⌊M/2⌋ then the rest, the second's accuracy roll failing whenever the first did. */
		MACUAHUITL,
		/**
		 * The dark bow: two arrows, each its own accuracy roll ("roll independent
		 * accuracy checks") and the full max. The full max per arrow is read from
		 * the special, which rolls each arrow "up to the player's maximum Ranged
		 * hit" and which this table already had at 1.5 per arrow; the pages read
		 * give no split for the ordinary shot.
		 */
		TWO_FULL,
		/** The charged tonalztics of Ralos: two throws, each its own accuracy roll, each 0 to 75% of the max. */
		TWO_THREE_QUARTERS,
		/** The uncharged tonalztics: one throw, 0 to 75% of the max. */
		THREE_QUARTERS
	}

	/** The ordinary attack's shape for the weapon in hand and the style it attacks with. */
	OrdinaryShape ordinaryShape()
	{
		final int weapon = weaponItemId();
		final AttackType type = attackStyle().getAttackType();
		if (type.isMelee())
		{
			if (isScytheEquipped())
			{
				return OrdinaryShape.SCYTHE;
			}
			if (AttackSplats.isMacuahuitl(weapon))
			{
				return OrdinaryShape.MACUAHUITL;
			}
			if (AttackSplats.splitsMaxInTwo(weapon))
			{
				return OrdinaryShape.SPLIT;
			}
			return OrdinaryShape.ONE;
		}
		if (type == AttackType.RANGED)
		{
			if (SpecialAttack.forItem(weapon) == SpecialAttack.DARK_BOW)
			{
				return OrdinaryShape.TWO_FULL;
			}
			if (weapon == ItemID.TONALZTICS_OF_RALOS_CHARGED)
			{
				return OrdinaryShape.TWO_THREE_QUARTERS;
			}
			if (weapon == ItemID.TONALZTICS_OF_RALOS_UNCHARGED)
			{
				return OrdinaryShape.THREE_QUARTERS;
			}
		}
		return OrdinaryShape.ONE;
	}

	/**
	 * One ordinary attack's damage as moments, by its shape. The max may carry a
	 * fraction (a chance-based bonus folded in as its average), kept wherever the
	 * roll is uncapped as {@link #ordinaryMoments} keeps it; each hitsplat is held
	 * to the cap on its own, as the scythe's always have been.
	 *
	 * @param scytheHits the scythe's hits on this target; read by {@link OrdinaryShape#SCYTHE} alone
	 */
	static DamageMoments shapeMoments(OrdinaryShape shape, double averageMax, double accuracy, int scytheHits, int cap)
	{
		switch (shape)
		{
			case SCYTHE:
				return ordinaryMoments(averageMax, accuracy, scytheHits, cap);
			case SPLIT:
			{
				final double first = Math.floor(averageMax / 2.0);
				return roll(first, cap).landing(accuracy).plus(roll(averageMax - first, cap).landing(accuracy));
			}
			case MACUAHUITL:
			{
				// The second lands only if the first did and its own roll passes: B1 (Y1 + B2 Y2).
				final double first = Math.floor(averageMax / 2.0);
				return roll(first, cap).plus(roll(averageMax - first, cap).landing(accuracy)).landing(accuracy);
			}
			case TWO_FULL:
			{
				final DamageMoments arrow = roll(averageMax, cap).landing(accuracy);
				return arrow.plus(arrow);
			}
			case TWO_THREE_QUARTERS:
			{
				final DamageMoments thrown = roll(averageMax * 0.75, cap).landing(accuracy);
				return thrown.plus(thrown);
			}
			case THREE_QUARTERS:
				return roll(averageMax * 0.75, cap).landing(accuracy);
			default:
				return ordinaryMoments(averageMax, accuracy, 1, cap);
		}
	}

	// One hitsplat's damage roll from 0 to this max: keeping its fraction uncapped, whole under a cap.
	private static DamageMoments roll(double max, int cap)
	{
		return cap == Integer.MAX_VALUE ? DamageMoments.uniform(max) : DamageMoments.capped((int) max, cap);
	}

	/**
	 * The most each of an ordinary attack's hitsplats can deal, by its shape,
	 * from the first's max: what {@link #maxHit} adds up.
	 */
	static int[] shapeMaxima(OrdinaryShape shape, int max, int scytheHits)
	{
		switch (shape)
		{
			case SCYTHE:
			{
				final int[] maxima = new int[Math.max(1, scytheHits)];
				int hit = max;
				for (int i = 0; i < maxima.length; i++)
				{
					maxima[i] = hit;
					hit = nextScytheMax(hit);
				}
				return maxima;
			}
			case SPLIT:
			case MACUAHUITL:
				return new int[]{max / 2, max - max / 2};
			case TWO_FULL:
				return new int[]{max, max};
			case TWO_THREE_QUARTERS:
				return new int[]{(int) (max * 0.75), (int) (max * 0.75)};
			case THREE_QUARTERS:
				return new int[]{(int) (max * 0.75)};
			default:
				return new int[]{max};
		}
	}

	/**
	 * The chance one ordinary attack lands anything at this accuracy: two
	 * independent rolls land with 1 − (1 − a)², the dual macuahuitl only with
	 * its first.
	 */
	static double shapeLandChance(OrdinaryShape shape, double accuracy, int scytheHits)
	{
		switch (shape)
		{
			case SCYTHE:
				return landChance(accuracy, scytheHits);
			case SPLIT:
			case TWO_FULL:
			case TWO_THREE_QUARTERS:
				return landChance(accuracy, 2);
			default:
				return landChance(accuracy, 1);
		}
	}

	/**
	 * Ordinary hits, each with its own accuracy and damage roll, the max halving
	 * (rounded down) from one to the next: one hit for most weapons, a scythe's
	 * two or three. Uncapped, the roll keeps the max's fraction, as the average
	 * always has.
	 */
	static DamageMoments ordinaryMoments(double averageMax, double accuracy, int hits, int cap)
	{
		DamageMoments total = DamageMoments.ZERO;
		double max = averageMax;
		for (int hit = 0; hit < hits; hit++)
		{
			final DamageMoments roll = cap == Integer.MAX_VALUE
				? DamageMoments.uniform(max)
				: DamageMoments.capped((int) max, cap);
			total = total.plus(roll.landing(accuracy));
			max = Math.floor(max / 2.0);
		}
		return total;
	}

	/** A ruby bolt: with the proc's chance its fixed hit replaces the attack, else the attack as it was. */
	static DamageMoments rubyMoments(double procChance, int rubyDamage, DamageMoments ordinary)
	{
		return DamageMoments.fixed(rubyDamage).weighted(procChance).or(ordinary.weighted(1.0 - procChance));
	}

	/** An onyx bolt: 20% more on a proc, which needs the hit, so the whole attack is scaled with that chance. */
	static DamageMoments onyxMoments(double procChance, DamageMoments ordinary)
	{
		return new DamageMoments(ordinary.mean * (1.0 + 0.20 * procChance),
			ordinary.times(1.20).second * procChance + ordinary.second * (1.0 - procChance));
	}

	/**
	 * The enchanted bolt loaded for this attack, or null when none applies -
	 * not ranged, not a crossbow, or onyx against undead.
	 */
	private EnchantedBolt loadedBolt(int npcId)
	{
		final AttackStyle style = attackStyle();
		if (style.getAttackType() != AttackType.RANGED)
		{
			return null;
		}
		final WeaponCategory category = weaponCategory();
		if (category != WeaponCategory.CROSSBOW)
		{
			return null;
		}
		final int ammo = equippedItemId(EquipmentInventorySlot.AMMO);
		final EnchantedBolt bolt = ammo < 0
			? null : EnchantedBolt.forAmmoName(itemManager.getItemComposition(ammo).getName());
		if (bolt == EnchantedBolt.ONYX)
		{
			final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
			if (npc != null && npc.hasAttribute("undead"))
			{
				return null;
			}
		}
		return bolt;
	}

	private boolean hasKandarinHardDiary()
	{
		return client.getVarbitValue(VarbitID.KANDARIN_DIARY_HARD_COMPLETE) == 1;
	}

	/**
	 * The target's health now, which the ruby bolt effect is a share of. Falls
	 * back to full health whenever it cannot be read for THIS target.
	 *
	 * <p>The figure has to be confirmed to belong to the npc being asked about.
	 * The fight's target is not always what this is asked for - a room with
	 * several NPCs, a target switched mid-fight, the opening attack recomputed
	 * against the fight's target, and every candidate the gear search evaluates
	 * all reach here. Taking a figure unchecked scaled one NPC's max health by
	 * another one's bar. Both the id and the index must agree: the id alone
	 * cannot separate two of the same NPC standing together.
	 *
	 * <p>Exact hitpoints from {@link TargetHealth} - the boss health bar's, or every
	 * splat counted - where it has them. Otherwise the overhead health bar, which is
	 * quantised to its own scale of about thirty: the right bucket and not the
	 * right number, a point or two of ruby damage on a high-health boss.
	 */
	private int targetCurrentHp(int npcId)
	{
		final NPC npc = targetNpc;
		final boolean ours = npc != null && healthBarBelongsTo(npc.getId(), npc.getIndex(), npcId, targetIndex);
		return currentHp(ours ? health.remaining(targetIndex, client.getTickCount()) : -1,
			targetMaxHitpoints(npcId), ours ? npc.getHealthRatio() : -1, ours ? npc.getHealthScale() : -1);
	}

	/**
	 * Hitpoints left from the best there is: exact ({@code known}, -1 when not
	 * known), then the overhead bar's ratio of full, then full. 0 when even full
	 * is not known. Static so it stays under test without a Client.
	 */
	static int currentHp(int known, int maxHp, int ratio, int scale)
	{
		if (maxHp <= 0)
		{
			return known >= 0 ? known : 0;
		}
		if (known >= 0)
		{
			return Math.min(known, maxHp);
		}
		if (ratio < 0 || scale <= 0)
		{
			return maxHp;
		}
		return Math.max(0, maxHp * ratio / scale);
	}

	/**
	 * Whether a health bar read off the interacting NPC describes the target
	 * being asked about. Split out static so the guard stays under test without
	 * a Client, like {@link #conflictionArmedAgainst}.
	 *
	 * <p>The index is only checked once there is one to check: no fight is open
	 * between kills and on the opening attack, and refusing the bar then would
	 * read every target at full health.
	 */
	static boolean healthBarBelongsTo(int barNpcId, int barIndex, int npcId, int targetIndex)
	{
		return barNpcId == npcId && (targetIndex < 0 || barIndex == targetIndex);
	}

	/**
	 * Defence level as the raid leaves it. The monster data carries raid level 0
	 * stats, so unscaled figures read too high - and in the flattering
	 * direction, the harder the invocations the better the player looks.
	 */
	private int defenceLevel(MonsterStatsProvider.MonsterStats npc)
	{
		// What the target has left, not what it started with: warhammers and godswords take real levels off it. In the Tombs
		// the raid level scales the defence roll, so the drain comes off the unscaled level and what is left is scaled
		// (T-049; DefenceDrain.targetFor tracks the unscaled level there).
		final int raidLevel = RaidScaling.tombsRaidLevel(client);
		if (raidLevel > 0)
		{
			return RaidScaling.tombs(Math.max(0, npc.getDefenceLevel() - defenceDrained()), raidLevel);
		}
		return Math.max(0, raidScaled(npc) - defenceDrained());
	}

	/**
	 * The NPC the expected figures are being asked about: its index for the
	 * drain lookup, and the NPC itself for the health bar the ruby bolt needs.
	 *
	 * <p>The NPC is held rather than found again from
	 * {@code getLocalPlayer().getInteracting()}, which is what the health bar
	 * used to be read off. Interaction lags a target switch by a tick, so on the
	 * tick of a switch it still named the NPC just left - and the guard, quite
	 * correctly, refused a bar belonging to something else and fell back to full
	 * health. That showed in game as the ruby figure reading full health for one
	 * tick after every switch before correcting itself. This is the fight's
	 * target as the plugin already knows it, which is true from the tick the
	 * fight opens.
	 */
	void setTarget(NPC npc)
	{
		this.targetNpc = npc;
		this.targetIndex = npc == null ? -1 : npc.getIndex();
	}

	/**
	 * The level the target defends magic with, as drains leave it (T-049): its
	 * Magic, less what a Seercull, an Accursed sceptre or a godsword's leftover took;
	 * or, for the few that roll magic defence off Defence (Ice demon, Verzik, by
	 * boss-rules.json), its drained Defence.
	 */
	private int magicDefenceLevel(MonsterStatsProvider.MonsterStats npc)
	{
		if (npc.isMagicUsesDefence())
		{
			return defenceLevel(npc);
		}
		final int magic = RaidScaling.magic(client, npc.getMagicLevel(), npc.getName(), partyHitpoints.highest());
		return Math.max(0, magic - magicDrained());
	}

	// What has been drained off the target: the tracker's, or, inside figuresAt, the state's (T-050).
	private int defenceDrained()
	{
		return levelsAt != null ? Math.max(0, levelsBase.defence - levelsAt.defence) : drain.drainedFrom(targetIndex);
	}

	private int magicDrained()
	{
		return levelsAt != null ? Math.max(0, levelsBase.magic - levelsAt.magic) : drain.magicDrainedFrom(targetIndex);
	}

	private int magicDefenceDrained()
	{
		return levelsAt != null ? Math.max(0, levelsBase.magicDefence - levelsAt.magicDefence)
			: drain.magicDefenceDrainedFrom(targetIndex);
	}

	/** The target's magic defence bonus, less what an Eye of ayak took (T-049). */
	private int magicDefenceBonus(MonsterStatsProvider.MonsterStats npc)
	{
		final int taken = magicDefenceDrained();
		return taken <= 0 ? npc.getDefMagic() : Math.max(0, npc.getDefMagic() - taken);
	}

	// ToA: 2% per five raid levels, additively. Defence level only - magic does not scale, nor do defence bonuses.
	// Chambers scaling is in RaidScaling.
	private int raidScaled(MonsterStatsProvider.MonsterStats npc)
	{
		return RaidScaling.defence(client, npc.getDefenceLevel(), npc.getName(), partyHitpoints.highest());
	}

	private double meleeHitChance(AttackStyle style, AttackType type,
		MonsterStatsProvider.MonsterStats npc, double gear, SpecialAttack spec, List<BossRules.ModifierRule> mods)
	{
		final int effAtk = voidScaled((int) Math.floor(boostedLevel(Skill.ATTACK) * meleeAccuracyPrayer())
			+ style.attackLevelBonus() + 8, type, false);
		final int attRoll = scaled(attackRoll(effAtk, attackBonus(type), gear), spec);
		final int defBonus = type == AttackType.STAB ? npc.getDefStab()
			: type == AttackType.SLASH ? npc.getDefSlash() : npc.getDefCrush();
		final int defRoll = ModifierTracker.defenceRoll(mods, type.name(), (defenceLevel(npc) + 9) * (defBonus + 64));
		return meleeHitChanceFrom(attRoll, defRoll);
	}

	/**
	 * Magic rolls against the target's magic level, not its defence, and starts
	 * from +9 rather than +8. The 30/70 blend is the player rule; a monster's
	 * magic defence is its Magic stat and its magic defence bonus (wiki).
	 */
	private double magicHitChance(AttackStyle style, MonsterStatsProvider.MonsterStats npc, double gear,
		int npcId, SpecialAttack spec, List<BossRules.ModifierRule> mods)
	{
		final int effMagic = voidScaled((int) Math.floor(boostedLevel(Skill.MAGIC) * magicAccuracyPrayer())
			+ style.attackLevelBonus() + 9, AttackType.MAGIC, false);
		// Elemental weakness is worth as much accuracy as it is damage, a point each, and multiplies the roll as the gear
		// effects do.
		final int attRoll = scaled(attackRoll(effMagic, attackBonus(AttackType.MAGIC),
			gear * (1.0 + elementalWeakness(npcId) / 100.0)), spec);
		// Burn stacks on the Maggot King take 20 each off its magic defence bonus (T-087).
		final int bonus = Math.max(0, magicDefenceBonus(npc) + bossMagicDefenceChange(npcId));
		final int defRoll = ModifierTracker.defenceRoll(mods, AttackType.MAGIC.name(),
			(magicDefenceLevel(npc) + 9) * (bonus + 64));
		if (!hasConflictionGauntlets())
		{
			return hitChanceFrom(attRoll, defRoll);
		}
		// The real chance for the attack about to be thrown, not the long-run average: the gauntlets roll twice after a miss,
		// so every attack is at one of two known chances. Armed against an index rather than a flag, and both sides must name
		// a real enemy - no fight is -1 and unarmed is -1, and comparing them bare made the two nothings match.
		final boolean armed = conflictionArmedAgainst(charge.armedIndex(), targetIndex);
		return armed
			? 1.0 - sharedDefenceMissChance(attRoll, defRoll)
			: hitChanceFrom(attRoll, defRoll);
	}

	/**
	 * How a magic attack of mine ended, which says whether the next against
	 * that enemy rolls twice. A miss arms, a hit spends, and misses do not
	 * stack.
	 */
	void noteMagicResolved(int npcIndex, boolean missed)
	{
		charge.resolved(npcIndex, missed);
	}

	/**
	 * Which enemy the doubled roll is held against, apart so the rules can be
	 * tested without a Client. There are two, and both have been wrong once.
	 */
	static final class ConflictionCharge
	{
		private int armedIndex = -1;

		int armedIndex()
		{
			return armedIndex;
		}

		void resolved(int npcIndex, boolean missed)
		{
			if (npcIndex < 0)
			{
				// Nothing to hold it against.
				armedIndex = -1;
			}
			else if (missed)
			{
				// The last miss is what it is held against; a second on the same enemy is the same charge, since the effect does
				// not stack.
				armedIndex = npcIndex;
			}
			else if (armedIndex == npcIndex)
			{
				// Only a hit on the enemy it is held against spends it.
				armedIndex = -1;
			}
		}
	}

	/**
	 * Whether the doubled roll applies to the attack about to be thrown: armed
	 * by a miss, and against the enemy that miss was against. The wiki says
	 * "against the same enemy", so switching target drops it.
	 */
	static boolean conflictionArmedAgainst(int armedIndex, int targetIndex)
	{
		return armedIndex >= 0 && armedIndex == targetIndex;
	}

	/**
	 * Whether the gauntlets can work at all: worn, and no two-hander, which
	 * disables the effect outright.
	 */
	static boolean conflictionGauntletsWork(int glovesItemId, boolean twoHandedWeapon)
	{
		return glovesItemId == ItemID.CONFLICTION_GAUNTLETS && !twoHandedWeapon;
	}

	/** Forgets any charge held, because the fight it belonged to is over. */
	void forgetConflictionCharge()
	{
		charge.resolved(-1, false);
	}

	/** Whether the gauntlets are in play at all, so the caller can skip the rest. */
	boolean usesConflictionGauntlets()
	{
		return hasConflictionGauntlets() && attackStyle().getAttackType() == AttackType.MAGIC;
	}

	// Worn, and not disabled by a two-hander.
	private boolean hasConflictionGauntlets()
	{
		final ItemEquipmentStats weapon = weaponStats();
		return conflictionGauntletsWork(equippedItemId(EquipmentInventorySlot.GLOVES),
			weapon != null && weapon.isTwoHanded());
	}

	private double rangedHitChance(AttackStyle style, MonsterStatsProvider.MonsterStats npc,
		double gear, SpecialAttack spec, List<BossRules.ModifierRule> mods)
	{
		final int effRanged = voidScaled((int) Math.floor(boostedLevel(Skill.RANGED) * rangedAccuracyPrayer())
			+ style.attackLevelBonus() + 8, AttackType.RANGED, false);
		final int attRoll = scaled(attackRoll(effRanged, attackBonus(AttackType.RANGED), gear), spec);
		final int defRoll = ModifierTracker.defenceRoll(mods, AttackType.RANGED.name(),
			(defenceLevel(npc) + 9) * (npc.getDefRanged() + 64));
		return hitChanceFrom(attRoll, defRoll);
	}

	/**
	 * The attack roll, floored at zero: a bonus below -64 turns the term
	 * negative, and hitChanceFrom then returns a negative chance, which callers
	 * read as "no figure". Casting from melee gear reaches it easily.
	 */
	/** The special's scaling of a finished attack roll, or the roll unchanged. */
	private static int scaled(int attackRoll, SpecialAttack spec)
	{
		return spec == null ? attackRoll : spec.scaleAttackRoll(attackRoll);
	}

	static int attackRoll(int effectiveLevel, int equipmentBonus, double gearMultiplier)
	{
		return Math.max(0, (int) (effectiveLevel * (equipmentBonus + 64) * gearMultiplier));
	}

	static double hitChanceFrom(int attRoll, int defRoll)
	{
		if (attRoll > defRoll)
		{
			return 1.0 - (defRoll + 2.0) / (2.0 * (attRoll + 1.0));
		}
		return attRoll / (2.0 * (defRoll + 1.0));
	}

	// Melee hit chance, with the fang's second accuracy roll. Inside ToA the defence roll is re-rolled too, making the
	// attempts independent; outside, both attack rolls are compared against one defence roll.
	private double meleeHitChanceFrom(int attRoll, int defRoll)
	{
		if (!isFangEquipped())
		{
			return hitChanceFrom(attRoll, defRoll);
		}
		if (gearBonuses.inTombsOfAmascut())
		{
			final double miss = 1.0 - hitChanceFrom(attRoll, defRoll);
			return 1.0 - miss * miss;
		}
		return 1.0 - sharedDefenceMissChance(attRoll, defRoll);
	}

	// Chance two attack rolls both fail against the same defence roll. For a fixed d one attack misses at
	// (d+1)/(attRoll+1), so two miss at the square; averaging that over d gives the closed forms, the sum of squares
	// becoming the (d+2)(2d+3)/6 term. Past attRoll every d misses outright. Checked against brute-force enumeration.
	private static double sharedDefenceMissChance(int attRoll, int defRoll)
	{
		final double a = attRoll;
		final double d = defRoll;
		if (defRoll <= attRoll)
		{
			return (d + 2.0) * (2.0 * d + 3.0) / (6.0 * (a + 1.0) * (a + 1.0));
		}
		final double capped = (a + 2.0) * (2.0 * a + 3.0) / (6.0 * (a + 1.0));
		return (capped + (d - a)) / (d + 1.0);
	}

	private boolean isFangEquipped()
	{
		final int weapon = weaponItemId();
		return weapon == ItemID.OSMUMTENS_FANG || weapon == ItemID.OSMUMTENS_FANG_ORNAMENT
			|| weapon == ItemID.BR_OSMUMTENS_FANG;
	}

	private double meleeAccuracyPrayer()
	{
		if (intendedPrayer())
		{
			return prayerGoal().getAttackMultiplier();
		}
		if (prayerActive(Prayer.RP_INTENSIFY))
		{
			return 1.50;
		}
		if (prayerActive(Prayer.RP_DECIMATE))
		{
			return 1.30;
		}
		if (prayerActive(Prayer.RP_ANCIENT_STRENGTH))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.ZEAL))
		{
			return 1.25;
		}
		if (prayerActive(Prayer.PIETY))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.CHIVALRY))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.INCREDIBLE_REFLEXES))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.IMPROVED_REFLEXES))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.CLARITY_OF_THOUGHT))
		{
			return 1.05;
		}
		return 1.0;
	}

	// The selected combat option: the weapon's category paired with the option index, never null. Category from the name
	// on the combat tab, falling back to the varbit id - the name wins because the ids have drifted, and a powered staff
	// reporting 24 made tridents resolve as melee weapons.
	private WeaponCategory weaponCategory()
	{
		final String heading = combatTabCategory();
		final int weapon = weaponItemId();
		// The combat tab lags the equipment by a tick, so on a swap the heading still names the old weapon, and pairing it
		// with the new one's bonuses halved accuracy for a tick. A heading that has not changed while the weapon has is
		// stale, and is dropped for the weapon's own attack bonus.
		final boolean stale = weapon != headingWeapon && heading != null && heading.equals(headingText);
		if (!stale)
		{
			headingWeapon = weapon;
			headingText = heading;
		}
		final WeaponCategory named = stale ? null : WeaponCategory.forName(heading);
		if (named != null)
		{
			return named;
		}

		final String weaponName = weaponName();
		if (weaponName != null)
		{
			final String lower = weaponName.toLowerCase(Locale.ROOT);
			if (lower.contains("blowpipe") || lower.contains("dart") || lower.contains("knife") || lower.contains("thrownaxe") || lower.contains("chinchompa") || lower.contains("toktz-xil-ul"))
			{
				return WeaponCategory.THROWN;
			}
			if (lower.contains("crossbow") || lower.contains("c'bow"))
			{
				return WeaponCategory.CROSSBOW;
			}
			if (lower.contains("bow") || lower.contains("seercull"))
			{
				return WeaponCategory.BOW;
			}
		}

		return stale ? null
			: WeaponCategory.forVarbit(client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY));
	}

	// The combat tab's category heading, e.g. "Category: Whip", or null. The text is null-checked as well as the widget:
	// an interface can exist before its text is set, and Text.removeTags throws on null.
	String categoryName()
	{
		final WeaponCategory category = weaponCategory();
		return category == null ? "fallback:" + attackStyle().getAttackType() : category.name();
	}

	private String combatTabCategory()
	{
		final Widget widget = client.getWidget(InterfaceID.CombatInterface.CATEGORY);
		if (widget == null || widget.getText() == null)
		{
			return null;
		}
		return Text.removeTags(widget.getText());
	}

	/** The spell currently set to autocast, or null if none is. */
	private Spell autocastSpell()
	{
		return Spell.forVarbit(client.getVarbitValue(VarbitID.AUTOCAST_SPELL));
	}

	/**
	 * Notes a spell clicked onto an NPC. A manual cast overrides the weapon, so
	 * a trident held while barraging reports the barrage.
	 */
	void recordManualCast(Spell spell)
	{
		manualCast.record(spell, client.getTickCount());
	}

	/** The spell a manual cast named in the last few ticks, or null (T-057: the shadow spell that stuns). */
	Spell recentManualCast()
	{
		return activeManualCast();
	}

	/** The tick the manual cast in hand was clicked, for its trace line (T-066); meaningless while there is none. */
	int recentManualCastTick()
	{
		return manualCast.tick;
	}

	/**
	 * Forgets the manual cast in hand (T-066): a projectile the weapon fired is
	 * never a spell that has none, so whatever clicks are still counted were
	 * superseded by the weapon's attack.
	 */
	void dropManualCast()
	{
		manualCast.drop();
		// Every figure worked out this tick was the spell's, not only the style (T-078 follow-up).
		memoTick = Integer.MIN_VALUE;
	}

	private Spell activeManualCast()
	{
		return manualCast.active(client.getTickCount());
	}

	// How the attack about to be described was proven. A hitsplat can only be a melee blow, and a melee blow is never a
	// cast whatever is queued.
	void noteAttackKind(boolean fromProjectile)
	{
		if (meleeProvenAttack != fromProjectile)
		{
			return; // already set the way this attack needs it
		}
		meleeProvenAttack = !fromProjectile;
		memoStyle = null; // the style turns on this
	}

	// Spends the manual cast once one has gone out under it. The timer alone cannot end it: it has to outlast the click-
	// to-cast delay, and a window that wide goes on claiming the weapon's own attacks afterwards.
	void noteAttackThrown()
	{
		if (manualCast.attackThrown(meleeProvenAttack, client.getTickCount()))
		{
			memoTick = Integer.MIN_VALUE;
		}
	}

	/**
	 * The spell clicked onto an NPC and the clicks not yet seen leaving as casts,
	 * apart from the client so the order of clicks, casts and the weapon's next
	 * attack can be tested (T-066).
	 */
	static final class ManualCast
	{
		private Spell spell;
		private int tick = Integer.MIN_VALUE;
		// Clicks made but not yet seen leaving as a cast.
		private int pending;

		void record(Spell clicked, int now)
		{
			// A click arriving after the previous one has lapsed starts the count over rather than adding to a cast that never
			// happened.
			if (active(now) == null)
			{
				pending = 0;
			}
			spell = clicked;
			tick = now;
			// Capped: spamming the spell icon queues clicks the game will never turn into casts, and each would have to be
			// spent before the weapon is read.
			pending = Math.min(MAX_PENDING_CASTS, pending + 1);
		}

		/**
		 * The manually cast spell, if cast recently enough to still be what the
		 * player is doing. Casting takes 5 ticks, so the window is slightly wider
		 * and lapses back to the weapon once they stop clicking.
		 */
		Spell active(int now)
		{
			return spell == null || now - tick > MANUAL_CAST_TICKS ? null : spell;
		}

		/** @return whether the cast in hand was spent */
		boolean attackThrown(boolean meleeProven, int now)
		{
			if (meleeProven || active(now) == null)
			{
				pending = 0;
				return false;
			}
			// One cast leaves per click and the two are several ticks apart, so more than one click can be outstanding at once.
			if (--pending <= 0)
			{
				pending = 0;
				spell = null;
				return true;
			}
			return false;
		}

		void drop()
		{
			spell = null;
			pending = 0;
		}
	}

	/**
	 * What the loadout attacks with, as the overlay, the side panel and the fight
	 * timeline name it: the spell alone when one is cast, e.g. "Fire Surge", the
	 * weapon otherwise (T-071, Eric: "Bow of Faerdhinen (Shadow Rush) becomes
	 * Shadow Rush"; T-016 had "Purging staff (Fire Surge)"). A powered staff's own
	 * attack names no spell, so it is the staff; a manual cast names its spell
	 * whatever is held. "Unarmed" with nothing in hand. The debug log names the
	 * weapon in its own lines.
	 */
	String attackName()
	{
		final String weapon = weaponName();
		final AttackStyle style = attackStyle();
		final Spell spell = style.getAttackType() == AttackType.MAGIC && !"Powered staff".equals(style.getDisplayName())
			? activeSpell() : null;
		return attackName(weapon, spell == null ? null : spell.getDisplayName());
	}

	/** See {@link #attackName()}: either may be null. */
	static String attackName(String weapon, String spell)
	{
		if (spell != null)
		{
			return spell;
		}
		return weapon == null ? "Unarmed" : weapon;
	}

	/** The attack in hand's style, for the fight timeline (T-035). */
	FightTimeline.Style timelineStyle()
	{
		switch (weaponStyle())
		{
			case RANGED:
				return FightTimeline.Style.RANGED;
			case MAGIC:
				return FightTimeline.Style.MAGIC;
			default:
				return FightTimeline.Style.MELEE;
		}
	}

	/** The spell the attack in hand casts, or null: a powered staff's own attack, or not magic (T-035). */
	Spell timelineSpell()
	{
		return weaponStyle() == Style.MAGIC ? castSpell() : null;
	}

	/** The spell being cast, manual taking priority over autocast. */
	String activeSpellName()
	{
		final Spell spell = activeSpell();
		return spell == null ? "none" : spell.getDisplayName();
	}

	boolean castLandsWithoutProjectile()
	{
		final Spell spell = activeSpell();
		return spell != null && spell.landsWithoutProjectile()
			&& attackStyle().getAttackType() == AttackType.MAGIC;
	}

	private Spell activeSpell()
	{
		final Spell manual = activeManualCast();
		return manual != null ? manual : autocastSpell();
	}

	private AttackStyle resolveAttackStyle()
	{
		final int varp = client.getVarpValue(VarPlayerID.COM_MODE);
		// A spell clicked onto an NPC is a magic attack whatever is held, so it is settled before the weapon. A manual cast
		// is accurate whatever the combat option says.
		if (!meleeProvenAttack && activeManualCast() != null)
		{
			return new AttackStyle(varp, "Manual cast", AttackType.MAGIC, CombatStyle.ACCURATE);
		}
		// A staff recognised by name attacks with magic whatever the category table claims - its ids have gone stale, and
		// trusting it sent tridents down the melee path.
		if (poweredStaffMaxHit() > 0)
		{
			return new AttackStyle(varp, "Powered staff", AttackType.MAGIC,
				varp == 3 ? CombatStyle.LONGRANGE : CombatStyle.ACCURATE);
		}
		final WeaponCategory category = weaponCategory();
		if (category != null)
		{
			final AttackStyle style = category.styleFor(varp);
			if (style != null)
			{
				return style;
			}
		}
		return fallbackStyle(varp);
	}

	/**
	 * Best guess for a category with no table entry: the attack type from the
	 * weapon's dominant attack bonus, the style from the layout most share.
	 */
	private AttackStyle fallbackStyle(int varp)
	{
		final AttackType type = dominantAttackType();
		final CombatStyle style;
		if (type == AttackType.RANGED)
		{
			style = varp == 0 ? CombatStyle.ACCURATE
				: varp == 1 ? CombatStyle.RAPID
				: varp == 3 ? CombatStyle.LONGRANGE : CombatStyle.DEFENSIVE;
		}
		else if (type == AttackType.MAGIC)
		{
			style = varp == 3 ? CombatStyle.LONGRANGE : CombatStyle.ACCURATE;
		}
		else
		{
			style = varp == 0 ? CombatStyle.ACCURATE
				: varp == 1 ? CombatStyle.AGGRESSIVE
				: varp == 2 ? CombatStyle.CONTROLLED : CombatStyle.DEFENSIVE;
		}
		return new AttackStyle(varp, "Unknown", type, style);
	}

	/** The attack type the equipped weapon is strongest in. */
	private AttackType dominantAttackType()
	{
		final ItemEquipmentStats w = weaponStats();
		if (w == null)
		{
			return AttackType.CRUSH; // unarmed punches are crush
		}
		final int melee = Math.max(w.getAstab(), Math.max(w.getAslash(), w.getAcrush()));
		if (w.getArange() > melee && w.getArange() >= w.getAmagic())
		{
			return AttackType.RANGED;
		}
		if (w.getAmagic() > melee && w.getAmagic() > w.getArange())
		{
			return AttackType.MAGIC;
		}
		if (w.getAstab() >= w.getAslash() && w.getAstab() >= w.getAcrush())
		{
			return AttackType.STAB;
		}
		return w.getAslash() >= w.getAcrush() ? AttackType.SLASH : AttackType.CRUSH;
	}

	// The dart in the blowpipe, as last seen (T-015): from its Check message or darts used on it, remembered per account by
	// the plugin. Dragon until seen, as the old setting's default was.
	private BlowpipeDart loadedDart = BlowpipeDart.DRAGON;
	private String loadedDartSource = "assumed, not seen yet";

	/** The dart the player was seen loading or checking, and how it was seen. */
	void setBlowpipeDart(BlowpipeDart dart, String source)
	{
		if (dart != null)
		{
			loadedDart = dart;
			loadedDartSource = source;
		}
	}

	BlowpipeDart getLoadedDart()
	{
		return loadedDart;
	}

	/**
	 * The dart loaded in the blowpipe, or null when one is not held. The game
	 * has no varbit for it, so it is the one last seen; see setBlowpipeDart.
	 */
	private ItemEquipmentStats blowpipeDart()
	{
		final String weapon = weaponName();
		if (weapon == null || !weapon.toLowerCase().contains("blowpipe"))
		{
			return null;
		}
		final BlowpipeDart dart = loadedDart;
		if (dart == null || dart == BlowpipeDart.NONE)
		{
			return null;
		}
		// Held onto rather than looked up again: this is reached several times a tick, and the answer only changes when the
		// dart seen does.
		if (dart != cachedDart)
		{
			final ItemStats stats = itemManager.getItemStats(dart.getItemId());
			cachedDartStats = stats == null ? null : stats.getEquipment();
			cachedDart = dart;
		}
		return cachedDartStats;
	}

	// The equipment stats of one item, or null for an empty slot or an item carrying none.
	private ItemEquipmentStats equipmentStats(int itemId)
	{
		if (itemId < 0)
		{
			return null;
		}
		final ItemStats stats = itemManager.getItemStats(itemId);
		return stats == null ? null : stats.getEquipment();
	}

	/** Sum of the worn gear's attack bonus for the type being rolled. */
	private int computeAttackBonus(AttackType type)
	{
		final Loadout gear = gear();
		final boolean skipAmmo = !ammoSlotFeedsWeapon();
		int total = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			if (skipAmmo && slot == EquipmentInventorySlot.AMMO)
			{
				continue;
			}
			final ItemEquipmentStats e = equipmentStats(gear.id(slot));
			if (e != null)
			{
				switch (type)
				{
					case STAB:
						total += e.getAstab();
						break;
					case SLASH:
						total += e.getAslash();
						break;
					case CRUSH:
						total += e.getAcrush();
						break;
					case RANGED:
						total += e.getArange();
						break;
					default:
						total += e.getAmagic();
						break;
				}
			}
		}
		if (type == AttackType.MAGIC && usingWeaponsOwnAttack())
		{
			// The shadow multiplies the magic accuracy of everything else worn on the same terms as the damage: its own spell
			// only.
			total *= gearBonuses.shadowMultiplier(gear);
		}
		if (type == AttackType.RANGED)
		{
			final ItemEquipmentStats dart = blowpipeDart();
			if (dart != null)
			{
				total += dart.getArange();
			}
		}
		return total;
	}

	private ItemEquipmentStats weaponStats()
	{
		return equipmentStats(gear().id(EquipmentInventorySlot.WEAPON));
	}

	/** Ticks between attacks, which is the cadence tick loss measures against. */
	/**
	 * Damage a second this loadout is expected to do against this target, or -1
	 * when there is no figure.
	 *
	 * <p>Built from the ATTACK SPEED, not from a clock: average hit over the
	 * weapon's own cooldown. That is what makes it comparable between two setups
	 * that swing at different speeds, which is the whole reason it is shown - a
	 * bigger average hit on a slower weapon is not obviously better and this is
	 * the figure that settles it. It also owes nothing to the fight timer, which
	 * the measured dps does.
	 *
	 * <p>Ordinary attacks only. A special is not sustained, so folding one in
	 * would describe a rotation nobody can keep up.
	 */
	double expectedDps(int npcId)
	{
		final double average = averageHit(npcId);
		if (average < 0)
		{
			return -1;
		}
		return dpsFrom(average, attackSpeedTicks());
	}

	/**
	 * Average hit over the weapon's cooldown. Split out static so the arithmetic
	 * stays under test without a Client, like fangMaxHit and attackRoll.
	 */
	static double dpsFrom(double averageHit, int attackSpeedTicks)
	{
		return attackSpeedTicks <= 0 || averageHit < 0
			? -1
			: averageHit / (attackSpeedTicks * TICK_SECONDS);
	}

	int attackSpeedTicks()
	{
		final ItemEquipmentStats w = weaponStats();
		final int speed = w == null ? 4 : w.getAspeed();
		final int ticks = speed <= 0 ? 4 : speed;
		final AttackStyle style = attackStyle();
		if (style.getAttackType() == AttackType.MAGIC)
		{
			return castSpeedTicks(ticks);
		}
		// Rapid fires a tick sooner; it is the only style that alters attack speed.
		return style.getCombatStyle() == CombatStyle.RAPID ? Math.max(1, ticks - 1) : ticks;
	}

	/**
	 * Casting runs on its own clock: the weapon's speed applies only to powered
	 * staves and salamanders. Everything else casts in 5 ticks, or 4 for the
	 * harmonised staff on the standard spellbook, or 6 for the twinflame staff.
	 */
	private int castSpeedTicks(int weaponTicks)
	{
		final WeaponCategory category = weaponCategory();
		// A manual cast runs on the spell's clock even from a powered staff, so a trident being barraged off is 5 ticks.
		// Recognising the staff by name matters here too: by category alone an unmapped one got the spell clock.
		final boolean ownAttack = poweredStaffMaxHit() > 0
			|| category == WeaponCategory.POWERED_STAFF || category == WeaponCategory.SALAMANDER;
		if (activeManualCast() == null && ownAttack)
		{
			return weaponTicks;
		}
		return spellClockTicks(weaponItemId(), activeSpell());
	}

	/** A spell's cast speed from a staff that does not attack on its own: 5, but for the staves that change it. */
	static int spellClockTicks(int weaponId, Spell spell)
	{
		if (weaponId == ItemID.NIGHTMARE_STAFF_HARMONISED && spell != null && spell.getSpellbook() == Spellbook.STANDARD)
		{
			return 4;
		}
		// The twinflame staff has "an attack and cast speed of 6" (wiki, Twinflame staff). T-086: booked at 5, Eric's casts at
		// the Maggot King went out every 6 ticks (72 of 85 gaps between them, 2026-10-29), each leaving a tick read as lost.
		if (weaponId == ItemID.TWINFLAME_STAFF)
		{
			return TWINFLAME_CAST_TICKS;
		}
		return 5;
	}

	/**
	 * Max hit against this target, or 0 if unavailable. The target matters
	 * because salve, dragon hunter, demonbane and the rest only apply against
	 * what they are meant for; pass -1 for the figure before those.
	 */
	int maxHit(int npcId)
	{
		// What actually lands, which against Olm's hands is a third of it when the style is the wrong one for that hand.
		final int first = (int) (unmitigatedMaxHit(npcId) * mitigation(npcId));
		final int cap = damageCap(npcId);
		// The most ONE ATTACK can deal, which for a scythe is all its hits: a 51 max against a 3x3 is 51 + 25 + 12, since the
		// halving rounds down at every step. The cap is per hit, being a limit on what one blow may land.
		// The other multi-hit weapons too (T-032): a dark bow's two arrows, the split hits, the tonalztics' throws.
		int total = 0;
		// A boss's modifiers (T-057): the cap while stunned, the halving, the doubling for magic into the wound.
		final List<BossRules.ModifierRule> mods = bossModifiers(npcId);
		final int[] maxima = shapeMaxima(ordinaryShape(), first, hitsPerAttack(npcId));
		for (int hit = 0; hit < maxima.length; hit++)
		{
			total += Math.min(cap, mods.isEmpty() ? maxima[hit] : ModifierTracker.max(mods, maxima[hit], hit));
		}
		if (weaponStyle() == Style.MAGIC)
		{
			// The twinflame staff's second spell, 40% of the first.
			total += Math.min(cap, (int) (magicModel(npcId).secondMaxHit() * mitigation(npcId)));
		}
		return total;
	}

	/**
	 * The max of the hit after this one in a scythe swing: half, rounded down.
	 * Wiki: base 47 gives 47-23-11, base 48 gives 48-24-12.
	 */
	private String scytheBreakdown(int npcId)
	{
		final int hits = hitsPerAttack(npcId);
		if (hits < 2)
		{
			return "";
		}
		final StringBuilder parts = new StringBuilder(" (");
		final int cap = damageCap(npcId);
		int max = (int) (unmitigatedMaxHit(npcId) * mitigation(npcId));
		for (int hit = 0; hit < hits; hit++)
		{
			parts.append(hit == 0 ? "" : "+").append(Math.min(cap, max));
			max = nextScytheMax(max);
		}
		return parts.append(')').toString();
	}

	static int nextScytheMax(int max)
	{
		return max / 2;
	}

	/**
	 * The chance an attack lands at all, which is not its accuracy once one
	 * attack is several hits: the measured side counts the swing once however
	 * many connect, so this has to be the chance that any did.
	 */
	double landChance(int npcId)
	{
		final double accuracy = hitChance(npcId);
		if (accuracy < 0)
		{
			return accuracy;
		}
		return attackLandChance(accuracy, npcId);
	}

	/** {@link #landChance(int)} at a given accuracy: the weapon's ordinary shape decides how its rolls combine (T-032). */
	double attackLandChance(double accuracy, int npcId)
	{
		return accuracy < 0 ? accuracy : shapeLandChance(ordinaryShape(), accuracy, hitsPerAttack(npcId));
	}

	static double landChance(double accuracy, int hits)
	{
		return accuracy < 0 ? accuracy : 1.0 - Math.pow(1.0 - accuracy, hits);
	}

	/**
	 * How many times one attack lands here: a scythe hits twice against a 2x2
	 * and three times against 3x3 or larger, each rolling its own accuracy and
	 * strength. Only the SAME target - the arc beside it is the AoE gap.
	 */
	int hitsPerAttack(int npcId)
	{
		if (!isScytheEquipped() || !attackStyle().getAttackType().isMelee())
		{
			return 1;
		}
		return scytheHits(targetSize(npcId));
	}

	/**
	 * The hitsplats one attack by this weapon puts on this target (T-017): the
	 * weapon's own count or its special's, the target's size for a scythe, the
	 * spell for the twinflame staff. What attributes splats to the attack; the
	 * expected damage is worked out elsewhere.
	 */
	AttackSplats splatsPerAttack(int npcId, boolean special, int weaponId)
	{
		return AttackSplats.of(weaponId, special, targetSize(npcId), activeSpell(), inMulticombat());
	}

	/** Whether the player stands in a multicombat area, which Dinh's bulwark's special hits twice in (T-032). */
	private boolean inMulticombat()
	{
		return client.getVarbitValue(VarbitID.MULTIWAY_INDICATOR) == 1;
	}

	/** An item's name for the debug log. */
	String itemLabel(int itemId)
	{
		return itemId < 0 ? "Unarmed" : itemManager.getItemComposition(itemId).getName();
	}

	/**
	 * A scythe swing against a target this many tiles across: three hits from
	 * 3x3 up, two against a 2x2, one against anything smaller.
	 */
	static int scytheHits(int size)
	{
		return size >= 3 ? 3 : size == 2 ? 2 : 1;
	}

	/**
	 * How many tiles across the target is, asked of the GAME rather than of the
	 * wiki data. The client knows this exactly and for every monster, where the
	 * data knows it only for the ids it happens to list and is sometimes plainly
	 * wrong about them - the Chambers guardian is recorded as size 0.
	 *
	 * <p>It decides how many times a scythe hits, so getting it from a dataset
	 * that can be silent cost the extra hits on every monster the data misses:
	 * a scythe on Olm's hands, which are 5 tiles across and take three hits,
	 * was reading as one.
	 *
	 * <p>The live NPC first, since only it can answer for a monster that changes
	 * form; then the id's own definition; and the data last, which is now only
	 * reached with no client to ask.
	 */
	private int targetSize(int npcId)
	{
		if (targetNpc != null && targetNpc.getId() == npcId)
		{
			final NPCComposition composition = targetNpc.getTransformedComposition();
			if (composition != null && composition.getSize() > 0)
			{
				return composition.getSize();
			}
		}
		if (npcId >= 0)
		{
			final NPCComposition definition = client.getNpcDefinition(npcId);
			if (definition != null && definition.getSize() > 0)
			{
				return definition.getSize();
			}
		}
		final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
		return npc == null ? 1 : npc.getSize();
	}

	private boolean isScytheEquipped()
	{
		return isScythe(weaponName());
	}

	/** Whether this weapon name is a scythe of vitur, in any of its forms. */
	static boolean isScythe(String weaponName)
	{
		return weaponName != null && weaponName.toLowerCase(Locale.ROOT).contains("scythe of vitur");
	}

	private int damageCap(int npcId)
	{
		if (npcId == NpcID.HUEY_TAIL || npcId == NpcID.HUEY_TAIL_BROKEN)
		{
			return crushIsHighestAttackBonus() ? 9 : 4;
		}
		return verzikFirstPhaseCap(npcId);
	}

	/**
	 * Verzik's first phase caps every hitsplat but the Dawnbringer's special:
	 * ten for melee, three for ranged and magic. The cap is per hitsplat, which
	 * is what {@link #cappedAverage} already models.
	 *
	 * <p>The Dawnbringer exemption is NOT written here, and deliberately: the
	 * phase exempts Pulsate, not the staff, so an exemption at this level would
	 * uncap the Dawnbringer's ordinary attacks too. It lives on the special
	 * instead, as {@code SpecialAttack.ignoresDamageCap}, which is the only
	 * caller allowed to skip this. Every other special IS capped here - a
	 * godsword on the first phase is held to ten a hitsplat like anything else.
	 */
	private int verzikFirstPhaseCap(int npcId)
	{
		if (!VERZIK_FIRST_PHASE.contains(npcId))
		{
			return Integer.MAX_VALUE;
		}
		return attackStyle().getAttackType().isMelee() ? 10 : 3;
	}

	private boolean crushIsHighestAttackBonus()
	{
		final int crush = attackBonus(AttackType.CRUSH);
		return crush >= attackBonus(AttackType.STAB)
			&& crush >= attackBonus(AttackType.SLASH)
			&& crush >= attackBonus(AttackType.RANGED)
			&& crush >= attackBonus(AttackType.MAGIC);
	}

	/**
	 * The average of a roll from nought to {@code trueMax}, then capped. Every
	 * roll above the cap collapses onto it, so the average sits far closer to
	 * the cap than to half of it.
	 */
	/**
	 * The same average for a roll that cannot fall below {@code min}, which is
	 * the Voidwaker's shape and nothing else's.
	 *
	 * <p>Built out of the closed form below rather than walking the roll: the
	 * average over [min, max] is the whole sum less the part beneath min, so the
	 * tested arithmetic is reused on both ends and the hot path keeps its closed
	 * form. With min at zero this is that function exactly.
	 */
	static double cappedAverage(int min, int trueMax, int cap)
	{
		if (trueMax <= 0 || trueMax < min)
		{
			return 0;
		}
		if (min <= 0)
		{
			return cappedAverage(trueMax, cap);
		}
		final double whole = (trueMax + 1) * cappedAverage(trueMax, cap);
		final double beneath = min * cappedAverage(min - 1, cap);
		return (whole - beneath) / (trueMax - min + 1);
	}

	static double cappedAverage(int trueMax, int cap)
	{
		if (trueMax <= 0)
		{
			return 0;
		}
		if (cap >= trueMax)
		{
			return trueMax / 2.0;
		}
		final double atOrBelow = cap * (cap + 1) / 2.0;
		final double collapsedOnto = (double) (trueMax - cap) * cap;
		return (atOrBelow + collapsedOnto) / (trueMax + 1);
	}

	private int unmitigatedMaxHit(int npcId)
	{
		return fangNarrowed(unnarrowedMaxHit(npcId));
	}

	// Osmumten's fang rolls damage between 15% and 85% of the max hit rather than from zero, so the reachable max is the
	// max less the raised minimum.
	private int fangNarrowed(int hit)
	{
		return isFangEquipped() && attackStyle().getAttackType().isMelee()
			? fangMaxHit(hit)
			: hit;
	}

	/**
	 * The top of the fang's narrowed roll: the true max less the 15% it raises
	 * the bottom to. Split out so the off-by-one against a plain 0.85 stays
	 * under test without a Client.
	 */
	static int fangMaxHit(int trueMaxHit)
	{
		return trueMaxHit - trueMaxHit * 15 / 100;
	}

	/** The max hit before the fang narrows its roll, which is what its spec reaches. */
	private int unnarrowedMaxHit(int npcId)
	{
		if (weaponStyle() == Style.MAGIC)
		{
			return magicModel(npcId).maxHit();
		}
		final int hit = (int) (baseMaxHit() * gearBonus(npcId).getDamage())
			+ colossalBladeBonus(npcId) + elementalWeaknessBonus(npcId);
		final EnchantedBolt bolt = loadedBolt(npcId);
		if (bolt == null)
		{
			return hit;
		}
		switch (bolt)
		{
			case RUBY:
				// The bolt's hit replaces the weapon's, and only beats it on targets with a lot of health left.
				return Math.max(hit, EnchantedBolt.rubyDamage(targetCurrentHp(npcId)));
			case DIAMOND:
				return (int) (hit * 1.15);
			default:
				return (int) (hit * 1.20);
		}
	}

	/**
	 * The colossal blade adds 2 damage per tile of the target's size, up to 5.
	 * A flat addition, so it lands after the gear multipliers.
	 */
	private int colossalBladeBonus(int npcId)
	{
		if (weaponItemId() != ItemID.GIANTS_FOUNDRY_COLOSSAL_BLADE)
		{
			return 0;
		}
		final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
		return npc == null ? 0 : 2 * Math.min(npc.getSize(), 5);
	}

	/** The equipped weapon's special attack, or null if it has none that hits. */
	SpecialAttack specialAttack()
	{
		return SpecialAttack.forItem(weaponItemId());
	}

	// The most one special attack activation can deal against this target, or 0 when the weapon has no damaging spec.
	// Multi-hit specs are totalled.
	int specialAttackMaxHit(int npcId)
	{
		// Mitigated like an ordinary hit: a special on the wrong hand is no more exempt from Olm's mitigation than anything
		// else is.
		return (int) (unmitigatedSpecialAttackMaxHit(npcId) * mitigation(npcId));
	}

	private int unmitigatedSpecialAttackMaxHit(int npcId)
	{
		final int weapon = weaponItemId();
		if (isFangEquipped())
		{
			// Eviscerate rolls to the true max instead of the narrowed 85%. Its other half is accuracy, which is not damage and
			// not counted here.
			return unnarrowedMaxHit(npcId);
		}
		if (weapon == ItemID.ABYSSAL_BLUDGEON)
		{
			final int missingPrayer = Math.max(0,
				client.getRealSkillLevel(Skill.PRAYER) - client.getBoostedSkillLevel(Skill.PRAYER));
			return (int) (unmitigatedMaxHit(npcId) * (1.0 + 0.005 * missingPrayer));
		}
		if (weapon == ItemID.NIGHTMARE_STAFF_VOLATILE || weapon == ItemID.DEADMAN_BLIGHTED_VOLATILE_STAFF
			|| weapon == ItemID.BR_NIGHTMARE_STAFF_VOLATILE || weapon == ItemID.DEADMAN_NIGHTMARE_STAFF_VOLATILE)
		{
			final int magic = boostedLevel(Skill.MAGIC);
			// The spec is its own attack, not a spell, so no ancient bonus applies.
			return applyMagicDamage(Math.min(58, 58 * magic / 99 + 1), null);
		}
		if (weapon == ItemID.NIGHTMARE_STAFF_ELDRITCH)
		{
			final int magic = boostedLevel(Skill.MAGIC);
			// Wiki: min(floor(44 * magic / 99 + 1), 44), then the staff's own magic damage - the same shape as the volatile
			// staff above, which is why it sits beside it.
			return applyMagicDamage(Math.min(44, 44 * magic / 99 + 1), null);
		}
		if (weapon == ItemID.VERZIK_SPECIAL_WEAPON)
		{
			// Flat, and deliberately not run through applyMagicDamage: the wiki is explicit that magic damage gear, prayer and
			// the magic level all leave Pulsate alone.
			return SpecialAttack.DAWNBRINGER.fixedMax();
		}
		final SpecialAttack flat = SpecialAttack.forItem(weapon);
		if (flat != null && flat.hasFixedDamage())
		{
			// The dragonfire shields, which roll a flat range off the wielder's Defence rather than off any attack style or
			// gear.
			return flat.fixedMax();
		}
		final SpecialAttack spec = specialAttack();
		// The unmitigated hit: the caller applies mitigation, and taking it from maxHit() as well would apply Olm's third
		// twice over.
		if (spec == SpecialAttack.DARK_BOW)
		{
			// Two arrows at the special's own multiplier, a dragon arrow held to 48 (T-032).
			final boolean dragon = firesDragonArrows();
			final int arrow = (int) (unmitigatedMaxHit(npcId)
				* (dragon ? DARK_BOW_DRAGON_MULTIPLIER : DARK_BOW_OTHER_MULTIPLIER));
			return 2 * (dragon ? Math.min(DARK_BOW_DRAGON_CAP, arrow) : arrow);
		}
		if (spec == SpecialAttack.DINHS_BULWARK && inMulticombat())
		{
			return 2 * spec.maxTotal(unmitigatedMaxHit(npcId));
		}
		return spec == null ? 0 : spec.maxTotal(unmitigatedMaxHit(npcId));
	}

	/**
	 * The chance one activation of the special lands at least one hitsplat.
	 *
	 * <p>Not the ordinary hit chance: most specials modify the attack roll, the
	 * Voidwaker and the Dawnbringer cannot miss at all, and the claws roll up to
	 * four times, so missing needs every roll to fail. The abyssal dagger is the
	 * other way about - its two rolls are linked, so both hits land or neither
	 * does, and a second hit adds nothing to the chance of landing.
	 */
	double specialAttackLandChance(int npcId)
	{
		final SpecialAttack spec = specialAttack();
		if (spec == null)
		{
			return attackLandChance(hitChance(npcId), npcId);
		}
		if (spec.alwaysHits())
		{
			return 1.0;
		}
		final double accuracy = hitChance(npcId, spec);
		if (accuracy < 0)
		{
			return -1;
		}
		if (spec.sharedAccuracyRoll())
		{
			return accuracy;
		}
		if (spec == SpecialAttack.DARK_BOW)
		{
			// Each arrow deals its minimum on a miss (T-032), so an activation always puts damage down.
			return 1.0;
		}
		if (spec == SpecialAttack.DINHS_BULWARK)
		{
			// The target is hit twice in multicombat, once outside it (T-032).
			return landChance(accuracy, inMulticombat() ? 2 : 1);
		}
		// One roll per hitsplat either way. A cascade stops at its first success and the others all roll independently, but
		// "at least one connected" is the same arithmetic for both, so the count is all this needs.
		return landChance(accuracy, spec.hits());
	}

	/**
	 * What one activation of the equipped special is expected to deal here.
	 *
	 * <p>This is the special's counterpart to {@link #averageHit}, and the two
	 * are not interchangeable: a special has its own accuracy, its own per-hit
	 * maxima and, for the claws and the Voidwaker, its own shape of damage roll.
	 * Booking a special with the ordinary figure understates a godsword and
	 * badly understates anything capped - a Dawnbringer on Verzik's first phase
	 * expects three where it deals fifty, because the cap belongs to her
	 * ordinary hitsplats and not to the special that is exempt from it.
	 */
	double specialAttackAverageHit(int npcId)
	{
		final DamageMoments moments = specialAttackMoments(npcId);
		return moments == null ? -1 : moments.mean;
	}

	/** The variance of one special's damage (T-028), from {@link #specialAttackAverageHit}'s distribution; -1 without one. */
	double specialAttackVariance(int npcId)
	{
		final DamageMoments moments = specialAttackMoments(npcId);
		return moments == null ? -1 : moments.variance();
	}

	// T-050: figuresAt's slots.
	static final int AT_MEAN = 0;
	static final int AT_VARIANCE = 1;
	static final int AT_SPEC_MEAN = 2;
	static final int AT_SPEC_VARIANCE = 3;
	static final int AT_SPEC_LAND = 4;
	static final int AT_SPEC_ACCURACY = 5;
	static final int AT_SPEC_MAX = 6;
	static final int AT_CAP = 7;
	static final int AT_WIDTH = 8;

	/**
	 * The expected figures with the target's Defence, Magic and magic defence
	 * bonus at {@code at} rather than where the drain tracker says they are
	 * (T-050): the ordinary attack's mean and variance, the special's mean,
	 * variance, land chance, per-roll accuracy and max, and the per-hitsplat cap.
	 * Nothing else changes, so at the tracker's own state these are the figures
	 * {@link #averageHitMoments} and {@link #specialAttackMoments} give. A slot is
	 * -1 where that figure is unknown; null with no stats for the target.
	 *
	 * @param base the levels the drains are measured from (the tracker's target)
	 */
	double[] figuresAt(int npcId, DrainRule.Target base, DrainRule.State at)
	{
		if (monsters.get(npcId) == null || base == null || at == null)
		{
			return null;
		}
		levelsBase = base;
		levelsAt = at;
		try
		{
			final double[] out = new double[AT_WIDTH];
			final DamageMoments ordinary = averageHitMoments(npcId);
			final DamageMoments special = specialAttackMoments(npcId);
			final SpecialAttack spec = specialAttack();
			out[AT_MEAN] = ordinary == null ? -1 : ordinary.mean;
			out[AT_VARIANCE] = ordinary == null ? -1 : ordinary.variance();
			out[AT_SPEC_MEAN] = special == null ? -1 : special.mean;
			out[AT_SPEC_VARIANCE] = special == null ? -1 : special.variance();
			out[AT_SPEC_LAND] = specialAttackLandChance(npcId);
			out[AT_SPEC_ACCURACY] = spec == null ? hitChance(npcId) : spec.alwaysHits() ? 1.0 : hitChance(npcId, spec);
			final int specMax = specialAttackMaxHit(npcId);
			out[AT_SPEC_MAX] = specMax > 0 ? specMax : maxHit(npcId);
			out[AT_CAP] = Math.min(damageCap(npcId), overkillCap(npcId));
			return out;
		}
		finally
		{
			levelsBase = null;
			levelsAt = null;
		}
	}

	/** One special's damage as moments: {@link #specialAttackAverageHit}'s working, with the second moment beside it. */
	DamageMoments specialAttackMoments(int npcId)
	{
		final SpecialAttack spec = specialAttack();
		if (spec == null)
		{
			// No special that changes the hit, so activating one deals what an ordinary attack deals.
			return averageHitMoments(npcId);
		}
		final double accuracy = spec.alwaysHits() ? 1.0 : hitChance(npcId, spec);
		if (accuracy < 0)
		{
			return null;
		}
		// Pulsate alone: fixed damage that no gear, prayer or mitigation touches, and the only hit Verzik's first phase lets
		// past its cap.
		if (spec.hasFixedDamage())
		{
			final int flatMax = spec.fixedMax();
			return DamageMoments.capped((int) (flatMax * spec.minFraction()), flatMax,
				spec.ignoresDamageCap() ? Integer.MAX_VALUE : damageCap(npcId));
		}
		// Chance-based gear is worth less over time than it is at the top of the roll, which is the whole reason averageHit
		// has its own multiplier. The ratio carries that across without restating how a max hit is built.
		final GearBonus bonus = gearBonus(npcId);
		final double expectedShare = bonus.getDamage() > 0
			? bonus.getExpectedDamage() / bonus.getDamage()
			: 1.0;
		final double scale = mitigation(npcId) * expectedShare;
		// Every other special is capped like an ordinary hit: the phase exempts the Dawnbringer's, not specials in general,
		// so a godsword there is still held to ten a hitsplat.
		final int cap = damageCap(npcId);
		if (spec.cascadingAccuracy())
		{
			final int normalMax = (int) (unmitigatedMaxHit(npcId) * scale);
			return spec == SpecialAttack.BURNING_CLAWS
				? burningClawsMoments(normalMax, accuracy, cap)
				: clawsMoments(normalMax, accuracy, cap);
		}
		if (spec.binomialAccuracy())
		{
			return crimsonKistenMoments((int) (unmitigatedMaxHit(npcId) * scale), accuracy, cap);
		}
		// A special with no damage multipliers of its own - the fang, the bludgeon, the volatile staff - has already had its
		// max worked out.
		final int base = spec.hasDamageMultipliers()
			? unmitigatedMaxHit(npcId)
			: unmitigatedSpecialAttackMaxHit(npcId);
		// The specials whose hits are not one roll each of the table's maxima (T-032).
		if (spec == SpecialAttack.DARK_BOW)
		{
			final boolean dragon = firesDragonArrows();
			return darkBowSpecMoments((int) (base * scale), accuracy, dragon, cap);
		}
		final int[] maxima = spec.hitMaxima((int) (base * scale));
		if (spec.splitsMaxInTwo())
		{
			return specMoments(new int[]{maxima[0] / 2, maxima[0] - maxima[0] / 2}, accuracy, 0, 0, cap, false);
		}
		if (spec.splitsAcrossSuccesses())
		{
			return DamageMoments.capped(maxima[0], cap).landing(landChance(accuracy, spec.hits()));
		}
		if (spec == SpecialAttack.ANCIENT_GODSWORD || spec == SpecialAttack.SARADOMIN_SWORD)
		{
			return withExtraSplat(maxima[0], accuracy, cap, spec);
		}
		if (spec == SpecialAttack.DINHS_BULWARK && inMulticombat())
		{
			return specMoments(new int[]{maxima[0], maxima[0]}, accuracy, 0, 0, cap, false);
		}
		return specMoments(maxima, accuracy, spec.minFraction(), spec.flatMinimum(), cap, spec.sharedAccuracyRoll());
	}

	/** Saradomin's Lightning's extra Magic damage, "1-16" (wiki, Saradomin sword, read 2026-10-27; T-037). */
	static final int LIGHTNING_MIN = 1;
	static final int LIGHTNING_MAX = 16;

	/**
	 * A special whose hit brings a second splat of the player's own when it
	 * lands (T-037; wiki, read 2026-10-27), with the hit's damage roll and that
	 * splat both behind the one accuracy roll:
	 * <ul>
	 * <li>Blood Sacrifice (ancient godsword): 25, eight ticks later, "unless the
	 * target has moved 5 or more tiles away", which is assumed not to happen;</li>
	 * <li>Saradomin's Lightning (Saradomin sword): 1 to 16 Magic damage on the
	 * same attack; "If the melee attack misses, then the Magic attack will also
	 * fail". The wiki doesn't say whether it rolls its own accuracy, so it is
	 * taken to land whenever the melee hit does.</li>
	 * </ul>
	 * Each splat is held to the per-hitsplat cap on its own.
	 */
	static DamageMoments withExtraSplat(int meleeMax, double accuracy, int cap, SpecialAttack spec)
	{
		final DamageMoments extra = spec == SpecialAttack.ANCIENT_GODSWORD
			? DamageMoments.fixed(Math.min(cap, AttackSplats.BLOOD_SACRIFICE_DAMAGE))
			: DamageMoments.capped(LIGHTNING_MIN, LIGHTNING_MAX, cap);
		return DamageMoments.capped(0, meleeMax, cap).plus(extra).landing(accuracy);
	}

	// Dragon arrows in the quiver, seeking ones included: Descent of Dragons rather than of Darkness (wiki, Dark bow).
	private boolean firesDragonArrows()
	{
		final int ammo = equippedItemId(EquipmentInventorySlot.AMMO);
		return ammo == ItemID.DRAGON_ARROW || ammo == ItemID.DRAGON_ARROW_P || ammo == ItemID.DRAGON_ARROW_P_
			|| ammo == ItemID.DRAGON_ARROW_P__ || ammo == ItemID.SEEKING_DRAGON_ARROW;
	}

	/** Descent of Dragons' and of Darkness's damage multipliers, minimums and Dragons' per-arrow cap (wiki, Dark bow). */
	static final double DARK_BOW_DRAGON_MULTIPLIER = 1.5;
	static final double DARK_BOW_OTHER_MULTIPLIER = 1.3;
	static final int DARK_BOW_DRAGON_MINIMUM = 8;
	static final int DARK_BOW_OTHER_MINIMUM = 5;
	static final int DARK_BOW_DRAGON_CAP = 48;

	/**
	 * The dark bow's special (T-032; wiki, Dark bow, read 2026-10-27): two arrows,
	 * each with its own accuracy roll. Each deals at least its minimum, 8 with
	 * dragon arrows and 5 with others, "passing the regular accuracy check is
	 * required to deal damage above that minimum"; a hit rolls up to the max
	 * raised 50% (dragon) or 30%, and a dragon arrow is held to 48.
	 *
	 * @param normalMaxHit the bow's ordinary max, before the special's multiplier
	 * @param cap          the target's per-hitsplat cap, or Integer.MAX_VALUE
	 */
	static DamageMoments darkBowSpecMoments(int normalMaxHit, double accuracy, boolean dragon, int cap)
	{
		final int top = (int) (normalMaxHit * (dragon ? DARK_BOW_DRAGON_MULTIPLIER : DARK_BOW_OTHER_MULTIPLIER));
		final int ceiling = Math.min(cap, dragon ? DARK_BOW_DRAGON_CAP : Integer.MAX_VALUE);
		final int minimum = Math.min(ceiling, dragon ? DARK_BOW_DRAGON_MINIMUM : DARK_BOW_OTHER_MINIMUM);
		double mean = 0;
		double second = 0;
		for (int rolled = 0; rolled <= Math.max(0, top); rolled++)
		{
			final int dealt = Math.min(ceiling, Math.max(minimum, rolled));
			mean += dealt;
			second += (double) dealt * dealt;
		}
		final int rolls = Math.max(0, top) + 1;
		final DamageMoments hit = new DamageMoments(mean / rolls, second / rolls);
		final DamageMoments arrow = hit.weighted(accuracy).or(DamageMoments.fixed(minimum).weighted(1.0 - accuracy));
		return arrow.plus(arrow);
	}

	/**
	 * The expected damage of a special that rolls its accuracy once and then
	 * lands every hitsplat, which is all of them bar the claws.
	 */
	static double specAverage(int[] hitMaxima, double accuracy, double minFraction, int cap)
	{
		return specAverage(hitMaxima, accuracy, minFraction, 0, cap);
	}

	/**
	 * As above, with a floor stated in hitpoints as well as one stated as a
	 * share of the hit's own max. Only the granite hammer needs the flat one,
	 * whose Hammer Blow adds five to a roll made against the max less five.
	 */
	static double specAverage(int[] hitMaxima, double accuracy, double minFraction,
		int flatMinimum, int cap)
	{
		return specMoments(hitMaxima, accuracy, minFraction, flatMinimum, cap, false).mean;
	}

	/**
	 * {@link #specAverage}'s special as moments. The average is the same either
	 * way; the spread is not: with {@code sharedRoll} (the abyssal dagger) one
	 * accuracy roll lands every hitsplat or none, otherwise each hitsplat rolls
	 * its own, as {@link #specialAttackLandChance} counts them. Each hitsplat's
	 * damage is its own roll.
	 */
	static DamageMoments specMoments(int[] hitMaxima, double accuracy, double minFraction,
		int flatMinimum, int cap, boolean sharedRoll)
	{
		double total = 0;
		DamageMoments independent = DamageMoments.ZERO;
		DamageMoments rolls = DamageMoments.ZERO;
		for (int max : hitMaxima)
		{
			final int floor = Math.max(flatMinimum, (int) (max * minFraction));
			final DamageMoments roll = DamageMoments.capped(Math.min(floor, max), max, cap);
			total += accuracy * roll.mean;
			independent = independent.plus(roll.landing(accuracy));
			rolls = rolls.plus(roll);
		}
		final double second = sharedRoll ? rolls.landing(accuracy).second : independent.second;
		return new DamageMoments(total, second);
	}

	/**
	 * The crimson kisten's expected damage, which is a binomial rather than a
	 * cascade: all four accuracy rolls are made and the number that SUCCEED
	 * decides the damage, where the claws stop at the first success.
	 *
	 * <p>Wiki: one success deals 70-110% of the max hit, two 90-130%, three
	 * 110-150% and four 130-170%. No success deals nothing. Each band is 40%
	 * wide, so the outcome averages the midpoints - 90%, 110%, 130%, 150%.
	 */
	static double crimsonKistenAverage(int normalMaxHit, double accuracy, int cap)
	{
		return crimsonKistenMoments(normalMaxHit, accuracy, cap).mean;
	}

	/** {@link #crimsonKistenAverage} as moments: a mixture over the number of successes, each one band's roll. */
	static DamageMoments crimsonKistenMoments(int normalMaxHit, double accuracy, int cap)
	{
		double expected = 0;
		double second = 0;
		for (int hits = 1; hits <= KISTEN_ROLLS; hits++)
		{
			final double weight = binomial(KISTEN_ROLLS, hits)
				* Math.pow(accuracy, hits) * Math.pow(1.0 - accuracy, KISTEN_ROLLS - hits);
			final double floor = KISTEN_FLOORS[hits - 1];
			final DamageMoments band = DamageMoments.capped(
				(int) (normalMaxHit * floor), (int) (normalMaxHit * (floor + KISTEN_BAND)), cap);
			expected += weight * band.mean;
			second += weight * band.second;
		}
		return new DamageMoments(expected, second);
	}

	/** Ways to choose k of n, for the kisten's four rolls. */
	private static double binomial(int n, int k)
	{
		double result = 1;
		for (int i = 0; i < k; i++)
		{
			result = result * (n - i) / (i + 1);
		}
		return result;
	}

	/**
	 * The claws' expected damage, which their cascade of accuracy rolls makes a
	 * different shape from every other special.
	 *
	 * <p>Wiki: up to four accuracy rolls are made and the first to succeed
	 * decides the damage - the first roll spreads a doubled max across four
	 * hitsplats, the second 175% across three, the third 150% across two and the
	 * fourth 125% onto one. Those totals are exactly the running sums of the
	 * claws' own 1, 1/2, 1/4, 1/4 shape bar the last, so each failed roll simply
	 * drops the largest remaining hitsplat; only the fourth outcome needs a
	 * shape of its own. Four failures still deal 0 or 2, averaging 1.
	 *
	 * <p>Modelling this matters: against a target hit four times in five the
	 * cascade is worth about a fifth more than one roll for the whole
	 * activation, because a first-roll miss is usually rescued by the second.
	 */
	static double clawsAverage(int normalMaxHit, double accuracy, int cap)
	{
		return clawsMoments(normalMaxHit, accuracy, cap).mean;
	}

	/**
	 * {@link #clawsAverage} as moments. An outcome's hitsplats are shares of one
	 * roll, so they move together ({@link DamageMoments#together}).
	 */
	static DamageMoments clawsMoments(int normalMaxHit, double accuracy, int cap)
	{
		final DamageMoments[] byOutcome = new DamageMoments[CLAW_SHAPE.length];
		for (int failed = 0; failed < byOutcome.length; failed++)
		{
			final double[] shape = failed < byOutcome.length - 1
				? Arrays.copyOf(CLAW_SHAPE, byOutcome.length - failed)
				: CLAW_LAST_RESORT;
			final DamageMoments[] splats = new DamageMoments[shape.length];
			for (int i = 0; i < shape.length; i++)
			{
				splats[i] = DamageMoments.capped(0, (int) (normalMaxHit * shape[i]), cap);
			}
			byOutcome[failed] = DamageMoments.together(splats);
		}
		// Every roll missed, which is the one outcome that still deals something: 0 or 2, alike.
		final double missMean = Math.min(1.0, cap);
		return cascadeMoments(accuracy, byOutcome, new DamageMoments(missMean, cap >= 2 ? 2.0 : missMean * missMean));
	}

	/**
	 * Burning barrage's expected damage. Three rolls, and like the dragon claws
	 * the first to connect decides the damage - but each outcome has its own
	 * damage RANGE rather than just a ceiling, so it needs its own figures.
	 *
	 * <p>Wiki: the first roll rolls a total between 75% and 175% of the max hit
	 * split 25-25-50, the second 50% to 150% split 50-50-0, the third 25% to
	 * 125% split 0-0-100, and three misses deal 0, 1 or 2 at 20/40/40. The
	 * per-hitsplat shuffles the wiki describes after each split (-1, -1, +2 and
	 * so on) move damage between hitsplats without changing the total, so they
	 * matter only against a cap and are folded into the shares below.
	 *
	 * <p>The burn is NOT counted. Each hitsplat can inflict one, worth 10 over
	 * 40 ticks and stacking to five, so a spec is worth 0 to 29 more damage
	 * arriving long after the attack. That is damage with no attack behind it,
	 * which is the same accounting problem as thrall damage and is parked for
	 * the same reason - see "Thrall damage" in HANDOFF.md before modelling it.
	 */
	static double burningClawsAverage(int normalMaxHit, double accuracy, int cap)
	{
		return burningClawsMoments(normalMaxHit, accuracy, cap).mean;
	}

	/**
	 * What a special deals on top of its own hitsplats, on average, for the
	 * loadout's spec figure (T-030). Read-only to scoring: the burn that lands is
	 * DoT, counted luck-neutral in {@code O}. The burns, from the wiki (read
	 * 2026-10-27; the whole audit is in the overlay chat's status doc):
	 * <ul>
	 * <li>Burning barrage: see {@link #burningClawsBurnAverage};</li>
	 * <li>Flames of Ralos (Arkan blade): a 10 burn when the hit succeeds;</li>
	 * <li>Scorching shackles (Scorching bow): 5 against a demon, on use; it won't
	 * fire at anything else.</li>
	 * </ul>
	 * 0 for every other weapon, -1 when the target's stats aren't known.
	 */
	double specialExtraAverage(int npcId)
	{
		final SpecialAttack spec = specialAttack();
		if (spec == SpecialAttack.SCORCHING_BOW)
		{
			final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
			return npc == null ? -1 : npc.hasAttribute("demon") ? SCORCHING_BURN : 0;
		}
		if (spec != SpecialAttack.BURNING_CLAWS && spec != SpecialAttack.ARKAN_BLADE)
		{
			return 0;
		}
		final double accuracy = hitChance(npcId, spec);
		if (accuracy < 0)
		{
			return -1;
		}
		return spec == SpecialAttack.BURNING_CLAWS ? burningClawsBurnAverage(accuracy) : accuracy * BURN_DAMAGE;
	}

	/**
	 * Burning barrage's expected burn: over the cascade's outcomes (the first
	 * roll to connect, each at {@code accuracy}), the damaging hitsplats times
	 * their burn chance times 10. Counted as 10 a burn: the wiki lists 19 and 29
	 * among the totals without saying why, so this can be a point high.
	 */
	static double burningClawsBurnAverage(double accuracy)
	{
		double expected = 0;
		double reached = 1.0;
		for (int connected = 0; connected < BURNING_CLAW_SHAPES.length; connected++)
		{
			int damaging = 0;
			for (double share : BURNING_CLAW_SHAPES[connected])
			{
				if (share > 0)
				{
					damaging++;
				}
			}
			expected += reached * accuracy * damaging * BURNING_CLAW_BURN_CHANCE[connected] * BURN_DAMAGE;
			reached *= 1.0 - accuracy;
		}
		return expected;
	}

	/** {@link #burningClawsAverage} as moments; an outcome's hitsplats split one roll, so they move together. */
	static DamageMoments burningClawsMoments(int normalMaxHit, double accuracy, int cap)
	{
		final DamageMoments[] byOutcome = new DamageMoments[BURNING_CLAW_SHAPES.length];
		for (int failed = 0; failed < byOutcome.length; failed++)
		{
			final double floor = BURNING_CLAW_FLOORS[failed];
			final double ceiling = BURNING_CLAW_CEILINGS[failed];
			final double[] shape = BURNING_CLAW_SHAPES[failed];
			final DamageMoments[] splats = new DamageMoments[shape.length];
			for (int i = 0; i < shape.length; i++)
			{
				splats[i] = DamageMoments.capped(
					(int) (normalMaxHit * floor * shape[i]),
					(int) (normalMaxHit * ceiling * shape[i]),
					cap);
			}
			byOutcome[failed] = DamageMoments.together(splats);
		}
		// 0, 1 or 2 at 20/40/40, which averages 1.2 and squares to 2.
		final double missMean = Math.min(1.2, cap);
		return cascadeMoments(accuracy, byOutcome, new DamageMoments(missMean, cap >= 2 ? 2.0 : missMean * missMean));
	}

	/**
	 * Walks a cascade of accuracy rolls, where the first roll to connect decides
	 * the damage and a roll is only made because the one before it missed.
	 *
	 * <p>Split out because both claws share it and it is the half that is easy
	 * to get wrong: outcome k is reached only when the first k rolls all missed,
	 * so its weight is {@code (1-a)^k * a} rather than {@code a}. A mixture, so
	 * the second moments are weighted the same way.
	 */
	private static DamageMoments cascadeMoments(double accuracy, DamageMoments[] byOutcome, DamageMoments miss)
	{
		double expected = 0;
		double second = 0;
		double reached = 1.0;
		for (DamageMoments outcome : byOutcome)
		{
			expected += reached * accuracy * outcome.mean;
			second += reached * accuracy * outcome.second;
			reached *= 1.0 - accuracy;
		}
		return new DamageMoments(expected + reached * miss.mean, second + reached * miss.second);
	}

	private int computeBaseMaxHit()
	{
		switch (weaponStyle())
		{
			case RANGED:
				return rangedMaxHit();
			case MAGIC:
				return magicMaxHit();
			default:
				return meleeMaxHit();
		}
	}

	private int meleeMaxHit()
	{
		final int level = boostedLevel(Skill.STRENGTH);
		if (level <= 0)
		{
			return 0;
		}
		final int effective = voidScaled(
			(int) Math.floor(level * meleePrayer()) + attackStyle().strengthLevelBonus() + 8,
			attackStyle().getAttackType(), true);
		return maxHitFromStrength(effective, equipmentBonus(true));
	}

	private int rangedMaxHit()
	{
		final int level = boostedLevel(Skill.RANGED);
		if (level <= 0)
		{
			return 0;
		}
		final int effective = voidScaled(
			(int) Math.floor(level * rangedPrayer()) + attackStyle().strengthLevelBonus() + 8,
			AttackType.RANGED, true);
		return maxHitFromStrength(effective, equipmentBonus(false));
	}

	static int maxHitFromStrength(int effectiveStrength, int strengthBonus)
	{
		return (effectiveStrength * (strengthBonus + 64) + 320) / 640;
	}

	// Magic max hit: the autocast spell's base hit, or the staff's own attack for a powered staff, scaled by the worn
	// magic damage bonus.
	/**
	 * The spell's own base max hit, before any magic damage bonus. Elemental
	 * weakness is a share of THIS rather than of the finished figure, so it has
	 * to be reachable on its own.
	 */
	private int spellBaseMaxHit()
	{
		final Spell manual = activeManualCast();
		if (manual != null)
		{
			return manual.maxHitAt(boostedLevel(Skill.MAGIC));
		}
		if (poweredStaffMaxHit() > 0)
		{
			return 0;
		}
		final WeaponCategory category = weaponCategory();
		if (category == WeaponCategory.POWERED_STAFF || category == WeaponCategory.SALAMANDER)
		{
			return 0;
		}
		final Spell spell = autocastSpell();
		return spell == null ? 0 : spell.maxHitAt(boostedLevel(Skill.MAGIC));
	}

	/**
	 * How many points of elemental weakness this attack gets to use, or 0.
	 *
	 * <p>Wiki (Elemental weakness): each point is worth 1% magic damage AND 1%
	 * magic accuracy. It applies only to the elemental spells of the standard
	 * spellbook - strike, bolt, blast, wave and surge - and explicitly not to
	 * the ancient spellbook, so a barrage gets none of it however weak the
	 * target is to the element.
	 */
	private int elementalWeakness(int npcId)
	{
		final Spell spell = castSpell();
		if (spell == null || attackStyle().getAttackType() != AttackType.MAGIC)
		{
			return 0;
		}
		final MonsterStatsProvider.MonsterStats npc = attackStats(npcId);
		if (npc == null || npc.getWeaknessElement() == null || npc.getWeaknessSeverity() <= 0)
		{
			return 0;
		}
		return spell.isElement(npc.getWeaknessElement()) ? npc.getWeaknessSeverity() : 0;
	}

	/**
	 * The flat damage elemental weakness adds. Wiki:
	 * {@code floor(BaseMax x (1 + MagicDamage%)) x (1 + Slayer/Salve%) + floor(BaseMax x Weakness%)}
	 * - so it is a share of the SPELL'S BASE max, added after the multipliers
	 * rather than folded in with them. Folded in it would compound with salve
	 * and the slayer helm, which the formula keeps it apart from.
	 */
	private int elementalWeaknessBonus(int npcId)
	{
		final int severity = elementalWeakness(npcId);
		return severity <= 0 ? 0 : (int) Math.floor(spellBaseMaxHit() * severity / 100.0);
	}

	private int magicMaxHit()
	{
		// A manual cast is what the player is actually doing, whatever is held, so it wins over the weapon's own attack.
		final Spell manual = activeManualCast();
		if (manual != null)
		{
			return applyMagicDamage(manual.maxHitAt(boostedLevel(Skill.MAGIC)), manual);
		}
		// A staff recognised by name fires its own attack. Tested before the weapon category, so it still holds when the
		// category varbit is one this plugin has no entry for, relying on the category meant an unmapped one fell through and
		// reported the autocast spell instead.
		final int staff = poweredStaffMaxHit();
		if (staff > 0)
		{
			return applyMagicDamage(staff, null);
		}
		final WeaponCategory category = weaponCategory();
		if (category == WeaponCategory.POWERED_STAFF || category == WeaponCategory.SALAMANDER)
		{
			// A powered staff never casts the autocast spell, so one that isn't recognised is unknown rather than spell-shaped.
			return 0;
		}
		final Spell spell = autocastSpell();
		return spell == null ? 0
			: applyMagicDamage(spell.maxHitAt(boostedLevel(Skill.MAGIC)), spell);
	}

	/**
	 * Scales a base magic hit by the summed magic damage % of the worn gear
	 * (occult, tormented, ancestral, ...), which Tumeken's Shadow multiplies. The
	 * magic specials that carry no other bonus use this; an ordinary magic attack
	 * goes through {@link #magicModel}, which places the rest (T-026).
	 */
	private int applyMagicDamage(int baseMaxHit, Spell spell)
	{
		return (int) Math.floor(baseMaxHit * (1.0 + magicDamagePercent(spell) / 100.0));
	}

	/**
	 * The additive magic damage %, before the parts the magic formula takes from
	 * the gear and the target ({@link GearBonusCalc#fillMagic}): the worn bonuses,
	 * which Tumeken's Shadow multiplies and caps, virtus on ancients, and the
	 * prayer.
	 */
	private double magicDamagePercent(Spell spell)
	{
		final Loadout gear = gear();
		// Magic damage is a float: several items carry fractions of a percent, so summing into an int would truncate each one
		// away.
		double percent = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			final ItemEquipmentStats e = equipmentStats(gear.id(slot));
			if (e != null)
			{
				percent += e.getMdmg();
			}
		}
		percent += gearBonuses.virtusAncientDamagePercent(gear, spell);
		// The shadow's passive belongs to its built-in spell and to nothing else. Casting blood barrage while holding it is
		// an ordinary cast: the wiki calls the effect "exclusive to its built-in spell", and applying it to every cast read a
		// manual barrage at the 100% cap when the gear alone was nowhere near it. A null spell is the weapon's own attack,
		// which is the only thing the multiplier may touch.
		final int multiplier = spell == null ? gearBonuses.shadowMultiplier(gear) : 1;
		percent *= multiplier;
		if (multiplier > 1)
		{
			// Tripled equipment damage is capped at 100%; the prayer share below is added after, and is not equipment.
			percent = Math.min(100.0, percent);
		}
		// Prayer magic damage is not worn equipment, so the shadow doesn't multiply it and the equipment cap doesn't apply to
		// it.
		percent += magicDamagePrayerPercent();
		return percent;
	}

	/**
	 * The spell this magic attack casts: a manual cast whatever is held, nothing
	 * for a powered staff's own attack, else the autocast spell. What the tomes,
	 * the staves' spell bonuses, demonbane and elemental weakness turn on.
	 */
	private Spell castSpell()
	{
		final Spell manual = activeManualCast();
		if (manual != null)
		{
			return manual;
		}
		if (poweredStaffMaxHit() > 0)
		{
			return null;
		}
		final WeaponCategory category = weaponCategory();
		if (category == WeaponCategory.POWERED_STAFF || category == WeaponCategory.SALAMANDER)
		{
			return null;
		}
		return autocastSpell();
	}

	/**
	 * The magic attack's base max hit, before any bonus: the spell's at the magic
	 * level, or the powered staff's own. Magic Dart from a slayer's staff (e) on
	 * task is {@code floor(Magic / 6) + 13} (wiki).
	 */
	private int magicBase(Spell spell, boolean onTask)
	{
		final int magic = boostedLevel(Skill.MAGIC);
		if (spell == Spell.MAGIC_DART && onTask && weaponItemId() == ItemID.SLAYER_STAFF_ENCHANTED)
		{
			return Spell.enchantedSlayerStaffDart(magic);
		}
		if (spell != null)
		{
			return spell.maxHitAt(magic);
		}
		return Math.max(0, poweredStaffMaxHit());
	}

	/**
	 * The magic formula for this target, filled in (T-026): see {@link MagicDamage}.
	 * Held per mode for the tick, like the base max hit.
	 */
	private MagicDamage magicModel(int npcId)
	{
		expireMemo();
		final int slot = mode;
		if (memoMagic[slot] == null || memoMagicNpc[slot] != npcId)
		{
			final MagicDamage m = new MagicDamage();
			final Spell spell = castSpell();
			final boolean onTask = slayerVerdict(npcId).onTask;
			m.base = magicBase(spell, onTask);
			m.percent = magicDamagePercent(spell);
			m.weaknessPercent = elementalWeakness(npcId);
			// What the formula does not place itself: the dragon hunter wand, the revenant sceptres, leafy targets.
			m.others = gearBonus(npcId).getDamage();
			memoMagicWhy[slot] = gearBonuses.fillMagic(m, gear(), monsters.get(npcId), npcId, spell, onTask);
			memoMagic[slot] = m;
			memoMagicNpc[slot] = npcId;
		}
		return memoMagic[slot];
	}

	/**
	 * Whether the magic attack in hand is the weapon's own rather than a spell.
	 * A manual cast is a cast whatever is held, and a powered staff cannot
	 * autocast. This is what the shadow's passive is gated on.
	 */
	private boolean usingWeaponsOwnAttack()
	{
		return activeManualCast() == null && poweredStaffMaxHit() > 0;
	}

	// The staff's own attack, for powered staves, or 0 if this isn't one.
	private int poweredStaffMaxHit()
	{
		final String name = weaponName();
		if (name == null || name.toLowerCase().contains("uncharged"))
		{
			return 0;
		}
		final int magic = boostedLevel(Skill.MAGIC);
		// Trident of the seas is floor(magic / 3) - 5; the rest are offsets of it.
		final int seas = magic / 3 - 5;
		final String lower = name.toLowerCase();
		if (lower.startsWith("trident of the seas"))
		{
			return Math.max(1, seas);
		}
		if (lower.startsWith("trident of the swamp"))
		{
			return Math.max(1, seas + 3);
		}
		if (lower.endsWith("sanguinesti staff"))
		{
			return Math.max(1, seas + 4);
		}
		if (lower.startsWith("tumeken's shadow"))
		{
			return Math.max(1, seas + 6);
		}
		if (lower.startsWith("eye of ayak"))
		{
			return Math.max(1, seas - 1);
		}
		if (lower.startsWith("warped sceptre"))
		{
			return Math.max(1, (8 * magic + 96) / 37);
		}
		return 0;
	}

	/**
	 * A short summary of how the loadout was resolved, for ::loadout. Written
	 * for a player pasting it into a bug report, so it says which weapon and
	 * target were read and what came out.
	 */
	List<String> describeLoadout(int npcId)
	{
		final List<String> lines = new ArrayList<>();
		final AttackStyle style = attackStyle();
		final WeaponCategory category = weaponCategory();

		lines.add(String.format("%s - %s, %s %s, %d tick",
			weaponName() == null ? "Unarmed" : weaponName(),
			category == null ? "unknown category" : category.getGameName(),
			style.getAttackType(), style.getCombatStyle(), attackSpeedTicks()));

		final Spell spell = activeSpell();
		if (spell != null)
		{
			lines.add("Casting " + spell.getDisplayName());
		}

		final MonsterStatsProvider.MonsterStats npc = monsters.get(npcId);
		// The id is printed either way. When there are no stats for it, the id is the only thing that identifies what the
		// player was actually looking at.
		lines.add(npc == null
			? String.format("Target: npc %d, no stats for it", npcId)
			: String.format("Target: %s (npc %d), defence %d (base %d), magic %d, toa raid level %d",
				npc.getName(), npcId, defenceLevel(npc), npc.getDefenceLevel(), npc.getMagicLevel(),
				// The level actually applied, not the raw varbit, which is not cleared on leaving the Tombs.
				gearBonuses.tombsRaidLevel()));
		// Which version of the monster data the target is, why, and any warning against it: the line that would have named
		// Vardorvis's 0 defence and his Awakened 1400 hitpoints as the cause in the first log.
		if (npcId >= 0)
		{
			lines.add(monsters.describe(npcId));
		}

		final double accuracy = hitChance(npcId);
		lines.add(accuracy < 0
			? String.format("Max hit %d, no accuracy without target stats", maxHit(npcId))
			: String.format("Max hit %d%s, accuracy %.1f%% (lands %.1f%%), avg hit %.2f, dps %.2f",
				maxHit(npcId), scytheBreakdown(npcId), accuracy * 100,
				landChance(npcId) * 100, averageHit(npcId), expectedDps(npcId)));

		// Where a melee or ranged max hit came from - the counterpart of the magic breakdown below. Without it a wrong max
		// hit is one number with nothing behind it, and for a blowpipe the strength bonus carries the dart last seen rather
		// than anything worn, which is the first thing to check when the figure looks too high or too low.
		if (style.getAttackType() != AttackType.MAGIC)
		{
			final boolean melee = style.getAttackType().isMelee();
			final ItemEquipmentStats dart = blowpipeDart();
			lines.add(String.format("%s: str bonus %+d%s, level %d, attack bonus %+d",
				melee ? "Melee" : "Ranged",
				equipmentBonus(melee),
				dart == null ? "" : String.format(" (dart %s %+d, %s)",
					loadedDart, dart.getRstr(), loadedDartSource),
				boostedLevel(melee ? Skill.STRENGTH : Skill.RANGED),
				attackBonus(style.getAttackType())));

			// Per slot, because a total that looks wrong says nothing about WHICH item is wrong. Only the slots that actually
			// contribute are listed, so this is short in practice.
			final StringBuilder perSlot = new StringBuilder();
			for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
			{
				final int id = gear().id(slot);
				final ItemEquipmentStats e = equipmentStats(id);
				if (e == null)
				{
					continue;
				}
				final int str = melee ? e.getStr() : e.getRstr();
				if (str != 0)
				{
					perSlot.append(perSlot.length() == 0 ? "" : ", ")
						.append(slot).append(' ').append(itemManager.getItemComposition(id).getName())
						.append(' ').append(String.format("%+d", str));
				}
			}
			lines.add("  str from: " + (perSlot.length() == 0 ? "nothing worn" : perSlot));
		}

		// Where a magic max hit came from. Every part is a separate rule, and a wrong figure is otherwise one number with
		// nothing behind it.
		if (style.getAttackType() == AttackType.MAGIC)
		{
			final Loadout gear = gear();
			double worn = 0;
			for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
			{
				final ItemEquipmentStats e = equipmentStats(gear.id(slot));
				if (e != null)
				{
					worn += e.getMdmg();
				}
			}
			final Spell cast = activeSpell();
			final boolean own = usingWeaponsOwnAttack();
			lines.add(String.format("Magic dmg: worn %.1f%%, ancient %.1f%%, prayer %.1f%%, shadow x%d (%s)",
				worn, gearBonuses.virtusAncientDamagePercent(gear, cast), magicDamagePrayerPercent(),
				own ? gearBonuses.shadowMultiplier(gear) : 1,
				own ? "own attack" : "cast, passive does not apply"));
			final GearBonus bonus = gearBonus(npcId);
			final MagicDamage magic = magicModel(npcId);
			final List<String> applied = memoMagicWhy[mode];
			lines.add(String.format("Gear multipliers: accuracy x%.3f, other damage x%.3f; magic bonuses: %s",
				bonus.getAccuracy(), bonus.getDamage(),
				applied == null || applied.isEmpty() ? "none" : String.join("; ", applied)));
			lines.add("Magic max hit: " + magic.describe());
			// Named even when it is nought, so "this target has none" reads differently from "the spell is the wrong element for
			// it".
			final MonsterStatsProvider.MonsterStats weak = monsters.get(npcId);
			lines.add(String.format("Elemental weakness: target %s, this spell gets %d%% (+%d max)",
				weak == null || weak.getWeaknessElement() == null ? "none"
					: weak.getWeaknessElement() + " " + weak.getWeaknessSeverity() + "%",
				elementalWeakness(npcId), elementalWeaknessBonus(npcId)));
		}

		// Which prayers are actually up, by name. "prayed 0/n" with augury held all fight turned out to be Mystic Lore really
		// being on, and the figures named the prayer only to someone who knew the table.
		final StringBuilder up = new StringBuilder();
		for (Prayer prayer : Prayer.values())
		{
			if (prayerActive(prayer))
			{
				up.append(up.length() == 0 ? "" : ", ").append(prayer);
			}
		}
		lines.add(String.format("Prayers up: %s (goal %s, %s)",
			up.length() == 0 ? "none" : up.toString(), prayerGoal(),
			hasOffensivePrayer() ? "met" : "NOT met"));

		final int spec = specialAttackMaxHit(npcId);
		if (spec > 0)
		{
			lines.add("Spec max hit " + spec);
		}
		final Skill main = attackStyle().getAttackType() == AttackType.MAGIC ? Skill.MAGIC
			: attackStyle().getAttackType() == AttackType.RANGED ? Skill.RANGED : Skill.STRENGTH;
		final int base = client.getRealSkillLevel(main);
		lines.add(String.format("Efficiency goal: %s, %s at %d (+%d)",
			prayerGoal(), main, base + maxBoost(main, base), maxBoost(main, base)));

		// Kept for bug reports: these raw values are what expose a misread. Every raid varbit that might say "I am in a
		// raid", so the ones that clear on leaving can be told from the ones that do not - ToA's raid level and ToB's
		// progress both persist.
		lines.add(String.format("(raid: cox indungeon %d, cox timer %d, tob progress %d,"
				+ " tob wave %d/%d, tob party %d, toa level %d, toa partystatus %d)",
			client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON),
			client.getVarbitValue(VarbitID.RAIDS_TIMER),
			client.getVarbitValue(VarbitID.TOB_PROGRESS),
			client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_VAL),
			client.getVarbitValue(VarbitID.TOB_CLIENT_WAVEPROGRESS_MAX),
			client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS),
			client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL),
			client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS)));
		// Two more that may separate a lobby from a running raid, and may explain why the level will not refresh without a
		// relog.
		lines.add(String.format("(toa path %d, toa level frozen %d)",
			client.getVarbitValue(VarbitID.TOA_CLIENT_CURRENT_PATH),
			client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL_STOP_AUTO_UPDATING)));
		lines.add(String.format("(instanced %b, toa raid level varbit %d, stale unless instanced)",
			client.isInInstancedRegion(),
			client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL)));
		lines.add(String.format("(category varbit %d, com mode %d, cox tier %d, cox timer %d, salts %d)",
			client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY),
			client.getVarpValue(VarPlayerID.COM_MODE),
			client.getVarbitValue(VarbitID.RAIDS_OVERLOAD_TIER),
			client.getVarbitValue(VarbitID.RAIDS_OVERLOAD_TIMER),
			client.getVarbitValue(VarbitID.STATRENEWAL_POTION_TIMER)));
		return lines;
	}

	/** The equipped weapon's name, or null when unarmed. */
	private String computeWeaponName()
	{
		final int weapon = weaponItemId();
		return weapon < 0 ? null : itemManager.getItemComposition(weapon).getName();
	}

	/** The equipped weapon's item id, or -1 when unarmed. */
	int equippedWeaponId()
	{
		return equippedItemId(EquipmentInventorySlot.WEAPON);
	}

	/**
	 * Whether the combat option attacks in melee, which decides where an
	 * attack's tick can be read from: melee lands on the tick it is thrown.
	 */
	boolean isMeleeEquipped()
	{
		return attackStyle().getAttackType().isMelee();
	}

	private int weaponItemId()
	{
		return equippedWeaponId();
	}

	private int equippedItemId(EquipmentInventorySlot slot)
	{
		return gear().id(slot);
	}

	private double meleePrayer()
	{
		if (intendedPrayer())
		{
			return prayerGoal().getStrengthMultiplier();
		}
		if (prayerActive(Prayer.RP_DECIMATE))
		{
			return 1.27;
		}
		if (prayerActive(Prayer.RP_ANCIENT_STRENGTH))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.ZEAL))
		{
			return 1.28;
		}
		if (prayerActive(Prayer.PIETY))
		{
			return 1.23;
		}
		if (prayerActive(Prayer.CHIVALRY))
		{
			return 1.18;
		}
		if (prayerActive(Prayer.ULTIMATE_STRENGTH))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.SUPERHUMAN_STRENGTH))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.BURST_OF_STRENGTH))
		{
			return 1.05;
		}
		return 1.0;
	}

	// Expected damage if the attack had been set up properly: the configured prayer up and stats at full boost, everything
	// else exactly as it is. Over the ticks the attack really took, so a loadout swinging at another speed would be
	// compared per tick; the weapon is held fixed, so today the speeds are the same and this is the plain average hit.
	double idealAverageHit(int npcId)
	{
		final Loadout best = bestGear(npcId);
		final double hit = averageHitWith(best, npcId, IDEAL);
		final int bestSpeed = attackSpeedWith(best);
		final int wornSpeed = attackSpeedTicks();
		return hit < 0 || bestSpeed == wornSpeed ? hit : hit * wornSpeed / bestSpeed;
	}

	/**
	 * Whether a switch was available and not made, to what, and what it cost.
	 * Compared by contents, never by identity: the answer is held across ticks
	 * while the worn loadout is rebuilt each one, so the same gear is two objects
	 * a tick later.
	 */
	SwitchCheck switchCheck(int npcId)
	{
		final Loadout best = bestGear(npcId);
		final Loadout worn = gear();
		if (best.sameItems(worn))
		{
			return SwitchCheck.NONE;
		}
		final double wornHit = averageHitWith(worn, npcId, IDEAL);
		final double bestHit = averageHitWith(best, npcId, IDEAL);
		final int wornSpeed = attackSpeedTicks();
		final int bestSpeed = attackSpeedWith(best);
		return new SwitchCheck(worn, best, wornHit, wornSpeed, bestHit, bestSpeed);
	}

	/**
	 * One tick's switch check: whether the worn loadout fell short of the best
	 * one on hand, and by how much. Holds the two loadouts rather than their
	 * names, so the description costs nothing until a missed attack asks for it.
	 */
	static final class SwitchCheck
	{
		static final SwitchCheck NONE = new SwitchCheck(null, null, 0, 1, 0, 1);

		private final Loadout worn;
		private final Loadout best;
		private final double wornHit;
		private final int wornSpeed;
		private final double bestHit;
		private final int bestSpeed;

		SwitchCheck(Loadout worn, Loadout best, double wornHit, int wornSpeed, double bestHit, int bestSpeed)
		{
			this.worn = worn;
			this.best = best;
			this.wornHit = wornHit;
			this.wornSpeed = wornSpeed;
			this.bestHit = bestHit;
			this.bestSpeed = bestSpeed;
		}

		boolean isMissed()
		{
			return best != null;
		}

		/** Expected damage the attack lost by not switching. See GearSearch.switchCost. */
		double cost()
		{
			return isMissed() ? GearSearch.switchCost(wornHit, wornSpeed, bestHit, bestSpeed) : 0;
		}

		/** What would have been switched to, and both loadouts' damage per tick, for the debug log. */
		String describe()
		{
			if (!isMissed())
			{
				return "nothing better on hand";
			}
			return String.format("%s; damage per tick %.3f worn, %.3f switched (speed %d / %d), costing %.2f a hit",
				swaps(), GearSearch.perTick(wornHit, wornSpeed), GearSearch.perTick(bestHit, bestSpeed), wornSpeed, bestSpeed,
				cost());
		}

		/** The slots the best loadout swaps, by item name ("BODY Crystal body -> Masori body"); null when none. */
		String swaps()
		{
			if (!isMissed())
			{
				return null;
			}
			final StringBuilder swaps = new StringBuilder();
			for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
			{
				if (worn.id(slot) != best.id(slot))
				{
					swaps.append(swaps.length() == 0 ? "" : ", ").append(slot).append(' ')
						.append(worn.id(slot) < 0 ? "empty" : worn.name(slot)).append(" -> ")
						.append(best.id(slot) < 0 ? "empty" : best.name(slot));
				}
			}
			return swaps.toString();
		}

		/** The best loadout's average hit and the worn one's, at the ideal prayer and boost; 0 when nothing better. */
		double bestHit()
		{
			return bestHit;
		}

		double wornHit()
		{
			return wornHit;
		}
	}

	// The best gear against this target, held until the weapon, the combat style or what the player has on them moves.
	// Deliberately not the worn items: switching into the answer must not throw it away, or the search runs on every
	// attack of a fight fought properly. Not keyed on the spell either, which changes the figure but rarely the gear that
	// maximises it.
	private Loadout bestGear(int npcId)
	{
		final Loadout worn = gear();
		final int weaponId = worn.id(EquipmentInventorySlot.WEAPON);
		final AttackStyle style = attackStyle();
		final long available = availableEquippableSignature();
		if (memoBestGear != null && memoBestGearNpc == npcId && memoBestGearWeapon == weaponId
			&& memoBestGearStyle == style && memoBestGearAvailable == available)
		{
			// Kept only while it still beats what is worn. The search is greedy, so an answer reached from one set is not
			// guaranteed to beat a different set reached later - void put on after the fact is exactly that - and a stale answer
			// would mark a player down for doing the right thing.
			if (!memoBestGear.sameItems(worn)
				&& damagePerTick(worn, npcId, IDEAL)
				> damagePerTick(memoBestGear, npcId, IDEAL) - GearSearch.MEANINGFUL)
			{
				memoBestGear = worn;
			}
			return memoBestGear;
		}
		final ItemEquipmentStats weapon = weaponStats();
		memoBestGearNpc = npcId;
		memoBestGearWeapon = weaponId;
		memoBestGearStyle = style;
		memoBestGearAvailable = available;
		memoBestGear = GearSearch.best(worn, carriedCandidates(), weapon != null && weapon.isTwoHanded(),
			candidate -> damagePerTick(candidate, npcId, IDEAL));
		return memoBestGear;
	}

	// Expected damage per tick for a loadout, so loadouts that swing at different speeds compare fairly.
	private double damagePerTick(Loadout gear, int npcId, int as)
	{
		return GearSearch.perTick(averageHitWith(gear, npcId, as), attackSpeedWith(gear));
	}

	// The attack speed a loadout would have, style included. Restores what was there afterwards, as averageHitWith does.
	private int attackSpeedWith(Loadout gear)
	{
		final Loadout worn = memoLoadout;
		try
		{
			useLoadout(gear);
			return attackSpeedTicks();
		}
		finally
		{
			useLoadout(worn);
		}
	}

	// Everything carried that could be equipped, paired with its slot. The bank is deliberately out: the question is what
	// could have been switched to without leaving.
	private List<GearSearch.Candidate> carriedCandidates()
	{
		final ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return Collections.emptyList();
		}
		final List<GearSearch.Candidate> candidates = new ArrayList<>();
		for (Item item : inventory.getItems())
		{
			final ItemEquipmentStats stats = equipmentStats(item.getId());
			if (stats == null)
			{
				continue;
			}
			final EquipmentInventorySlot slot = SLOT_BY_INDEX.get(stats.getSlot());
			if (slot != null)
			{
				candidates.add(new GearSearch.Candidate(slot, item.getId()));
			}
		}
		return candidates;
	}

	/**
	 * Expected damage for the attack as it was really set up. A prayer flicked
	 * within the tick counts as held: it was up while the server resolved it.
	 */
	double actualAverageHit(int npcId, boolean prayerHeld)
	{
		return averageHitAs(npcId, prayerHeld ? PRAYER_HELD : REAL);
	}

	/**
	 * The same attack with every stat it rolls on at the full boost, and nothing
	 * else changed - the prayer as it was, the gear as worn. Less the actual
	 * figure, that is what the missing boost cost.
	 */
	double boostedAverageHit(int npcId, boolean prayerHeld)
	{
		// With the intended prayer held, "that prayer at full boost" is the ideal setup on the worn gear.
		return averageHitAs(npcId, prayerHeld ? IDEAL : BOOSTED);
	}

	private double averageHitAs(int npcId, int as)
	{
		mode = as;
		try
		{
			return averageHit(npcId);
		}
		finally
		{
			mode = REAL;
		}
	}

	/**
	 * The level a skill rolls at. Computing the ideal, this is the base plus the
	 * full boost of the potion expected here, so a decayed or undrunk dose shows
	 * up as the damage it costs.
	 */
	private int boostedLevel(Skill skill)
	{
		if (!fullBoost())
		{
			return client.getBoostedSkillLevel(skill);
		}
		final int base = client.getRealSkillLevel(skill);
		return base + maxBoost(skill, base);
	}

	// The full boost the attack is measured against, on every stat the style rolls on - attack as well as strength for
	// melee - so the accuracy a dose buys is counted and not just the damage. Since T-015 it is worked out, not set: the
	// strongest boost on hand or seen this fight (idealBoost). A player who brought nothing reads as fully boosted at their
	// base level - intended: not bringing a potion shows in the kill time, not in Execution - and an attack below its base
	// still costs, because the ideal is never below the base.
	private int maxBoost(Skill skill, int base)
	{
		int boost = idealBoost(skill).boost;
		// The boost ACTUALLY up is one of those things, whatever it is. Raising, never lowering, so the ideal stays a
		// ceiling (a boost the table does not know would otherwise read over 100%) and a decayed dose still costs.
		boost = Math.max(boost, client.getBoostedSkillLevel(skill) - base);
		// Raid boosts that re-apply on a timer: still running is still on hand, whatever the inventory says.
		if (client.getVarbitValue(VarbitID.STATRENEWAL_POTION_TIMER) > 0)
		{
			boost = Math.max(boost, base * 16 / 100 + 11);
		}
		if (client.getVarbitValue(VarbitID.RAIDS_OVERLOAD_TIMER) > 0)
		{
			boost = Math.max(boost, chambersOverloadBoost(base));
		}
		return boost;
	}

	// The skills the ideal boost is worked out for.
	private static final Skill[] BOOSTED_SKILLS = {Skill.ATTACK, Skill.STRENGTH, Skill.RANGED, Skill.MAGIC};
	// The highest boost seen on each this fight: a pre-pot, or a dose drunk with none left. Reset at each fight's start.
	private final Map<Skill, Integer> fightPeakBoost = new EnumMap<>(Skill.class);
	// Worked out once a tick: the inventory cannot change within one, and the gear search asks dozens of times.
	private int memoIdealTick = Integer.MIN_VALUE;
	private final Map<Skill, IdealBoost.Result> memoIdealBoost = new EnumMap<>(Skill.class);

	/** A new fight: what was seen in the last one no longer counts. */
	void startFight()
	{
		fightPeakBoost.clear();
		memoIdealTick = Integer.MIN_VALUE;
		slayerKey = null;
	}

	/** Notes this tick's boosts, so a dose seen once stays the standard for the rest of the fight. Called every tick. */
	void noteBoosts()
	{
		for (Skill skill : BOOSTED_SKILLS)
		{
			final int boost = client.getBoostedSkillLevel(skill) - client.getRealSkillLevel(skill);
			fightPeakBoost.merge(skill, Math.max(0, boost), Math::max);
		}
	}

	/** The strongest boost on hand for this stat, and where it came from. See IdealBoost. */
	IdealBoost.Result idealBoost(Skill skill)
	{
		final int tick = client.getTickCount();
		if (tick != memoIdealTick)
		{
			memoIdealTick = tick;
			memoIdealBoost.clear();
		}
		IdealBoost.Result result = memoIdealBoost.get(skill);
		if (result == null)
		{
			final ItemContainer inventory = client.getItemContainer(InventoryID.INV);
			int[] ids = null;
			if (inventory != null)
			{
				final Item[] items = inventory.getItems();
				ids = new int[items.length];
				for (int i = 0; i < items.length; i++)
				{
					ids[i] = items[i] == null ? -1 : items[i].getId();
				}
			}
			// The imbued and saturated hearts share one cooldown; ready when it reads nought (RuneLite's timers plugin reads
			// the same varbit, in units of ten ticks).
			final boolean heartReady = client.getVarbitValue(VarbitID.IMBUED_HEART_TIMER) == 0;
			result = IdealBoost.best(skill, client.getRealSkillLevel(skill), client.getRealSkillLevel(Skill.HERBLORE), ids,
				heartReady, fightPeakBoost.getOrDefault(skill, 0));
			memoIdealBoost.put(skill, result);
		}
		return result;
	}

	/**
	 * What the plugin worked out about the player's setup instead of asking
	 * (T-015), as one line for the fight-start log: each style's ideal boost and
	 * where it came from, its ideal prayer and why, whether this npc is on task,
	 * and the blowpipe dart.
	 */
	String describeWorkedOut(int npcId)
	{
		final StringBuilder line = new StringBuilder("SETUP worked out:");
		line.append(" melee ").append(boostText(Skill.ATTACK)).append(" / ").append(boostText(Skill.STRENGTH))
			.append(", prayer ").append(prayerText(PrayerIdeal.Style.MELEE));
		line.append("; ranged ").append(boostText(Skill.RANGED))
			.append(", prayer ").append(prayerText(PrayerIdeal.Style.RANGED));
		line.append("; magic ").append(boostText(Skill.MAGIC))
			.append(", prayer ").append(prayerText(PrayerIdeal.Style.MAGIC));
		line.append("; on task ").append(slayerVerdict(npcId));
		line.append("; blowpipe dart ").append(loadedDart).append(" (").append(loadedDartSource).append(')');
		line.append("; ").append(gearBonuses.describeBuffs());
		return line.toString();
	}

	private String boostText(Skill skill)
	{
		final IdealBoost.Result ideal = idealBoost(skill);
		final String source = ideal.source == IdealBoost.SEEN_THIS_FIGHT ? "seen this fight"
			: ideal.source == IdealBoost.NOTHING ? "nothing on hand"
			: itemManager.getItemComposition(ideal.source).getName();
		return String.format("%s %+d (%s)", skill.getName(), ideal.boost, source);
	}

	private String prayerText(PrayerIdeal.Style style)
	{
		final PrayerIdeal.Goal goal = prayerGoal(style);
		return goal.getName() + " (" + goal.why + ")";
	}

	/** Whether any stat the style in hand rolls on is below its base level: drained, or brewed down. */
	boolean isDrained()
	{
		switch (attackStyle().getAttackType())
		{
			case MAGIC:
				return drained(Skill.MAGIC);
			case RANGED:
				return drained(Skill.RANGED);
			default:
				return drained(Skill.ATTACK) || drained(Skill.STRENGTH);
		}
	}

	private boolean drained(Skill skill)
	{
		return client.getBoostedSkillLevel(skill) < client.getRealSkillLevel(skill);
	}

	// The Chambers overload the player brewed. The tiers are far apart - at 99 they give 13, 17 and 21
	// - so treating them alike misjudges the standard.
	private int chambersOverloadBoost(int base)
	{
		switch (client.getVarbitValue(VarbitID.RAIDS_OVERLOAD_TIER))
		{
			case 1: // Overload (-)
				return base / 10 + 4;
			case 3: // Overload (+)
				return base * 16 / 100 + 6;
			default: // Overload
				return base * 13 / 100 + 5;
		}
	}

	/** The prayer the player means to be using for the style in hand. */
	private PrayerIdeal.Goal prayerGoal()
	{
		switch (attackStyle().getAttackType())
		{
			case MAGIC:
				return prayerGoal(PrayerIdeal.Style.MAGIC);
			case RANGED:
				return prayerGoal(PrayerIdeal.Style.RANGED);
			default:
				return prayerGoal(PrayerIdeal.Style.MELEE);
		}
	}

	// The strongest prayer for the style the player can use now (T-015): unlocks from their varbits, levels from the base
	// levels, and the active book. Worked out once a tick per style.
	private int memoPrayerTick = Integer.MIN_VALUE;
	private final Map<PrayerIdeal.Style, PrayerIdeal.Goal> memoPrayerGoal = new EnumMap<>(PrayerIdeal.Style.class);

	PrayerIdeal.Goal prayerGoal(PrayerIdeal.Style style)
	{
		final int tick = client.getTickCount();
		if (tick != memoPrayerTick)
		{
			memoPrayerTick = tick;
			memoPrayerGoal.clear();
		}
		PrayerIdeal.Goal goal = memoPrayerGoal.get(style);
		if (goal == null)
		{
			goal = PrayerIdeal.best(style, prayerPlayer());
			memoPrayerGoal.put(style, goal);
		}
		return goal;
	}

	private PrayerIdeal.Player prayerPlayer()
	{
		final PrayerIdeal.Player p = new PrayerIdeal.Player();
		p.prayer = client.getRealSkillLevel(Skill.PRAYER);
		p.defence = client.getRealSkillLevel(Skill.DEFENCE);
		p.attack = client.getRealSkillLevel(Skill.ATTACK);
		p.strength = client.getRealSkillLevel(Skill.STRENGTH);
		// King's Ransom's Knight Waves: states 0-7 are in progress (Quest Helper's steps), 8 is done.
		p.knightWaves = client.getVarbitValue(VarbitID.KR_KNIGHTWAVES_STATE) >= 8;
		p.zeal = client.getVarbitValue(VarbitID.PRAYER_ZEAL_UNLOCKED) != 0;
		p.rigour = client.getVarbitValue(VarbitID.PRAYER_RIGOUR_UNLOCKED) != 0;
		p.augury = client.getVarbitValue(VarbitID.PRAYER_AUGURY_UNLOCKED) != 0;
		p.deadeye = client.getVarbitValue(VarbitID.PRAYER_DEADEYE_UNLOCKED) != 0;
		p.mysticVigour = client.getVarbitValue(VarbitID.PRAYER_MYSTIC_VIGOUR_UNLOCKED) != 0;
		// 1 is the Ruinous Powers book, as RuneLite's prayer plugin reads it.
		p.ruinousBook = client.getVarbitValue(VarbitID.PRAYERBOOK) == 1;
		return p;
	}

	// Whether the prayer that was up is at least the one intended for the style.
	boolean hasOffensivePrayer()
	{
		final PrayerIdeal.Goal goal = prayerGoal();
		final double attack;
		final double strength;
		switch (attackStyle().getAttackType())
		{
			case MAGIC:
				attack = magicAccuracyPrayer();
				strength = 1.0;
				break;
			case RANGED:
				attack = rangedAccuracyPrayer();
				strength = rangedPrayer();
				break;
			default:
				attack = meleeAccuracyPrayer();
				strength = meleePrayer();
				break;
		}
		return attack >= goal.getAttackMultiplier() && strength >= goal.getStrengthMultiplier();
	}

	// Whether every combat stat the current style rolls on is at the full boost the best potion here would give.
	boolean isPotted()
	{
		switch (attackStyle().getAttackType())
		{
			case MAGIC:
				return isPotted(Skill.MAGIC);
			case RANGED:
				return isPotted(Skill.RANGED);
			default:
				return isPotted(Skill.ATTACK) && isPotted(Skill.STRENGTH);
		}
	}

	private boolean isPotted(Skill skill)
	{
		final int base = client.getRealSkillLevel(skill);
		return client.getBoostedSkillLevel(skill) >= base + maxBoost(skill, base);
	}

	/**
	 * How much of the full boost the stats this style rolls on hold, from 0 to
	 * 1: melee's attack and strength averaged, ranged and magic their one skill.
	 * Unlike isPotted, a super combat decayed by a level reads as most of a dose
	 * rather than none of one.
	 */
	double boostShare()
	{
		switch (attackStyle().getAttackType())
		{
			case MAGIC:
				return boostShare(Skill.MAGIC);
			case RANGED:
				return boostShare(Skill.RANGED);
			default:
				return (boostShare(Skill.ATTACK) + boostShare(Skill.STRENGTH)) / 2;
		}
	}

	private double boostShare(Skill skill)
	{
		final int base = client.getRealSkillLevel(skill);
		return boostShare(client.getBoostedSkillLevel(skill), base, maxBoost(skill, base));
	}

	/**
	 * The share of a full boost one stat holds: what it is above its base over
	 * what the full boost would put it above, clamped to 0..1. Drained below the
	 * base is none of it. With no boost expected at all, a stat at or above its
	 * base holds all of nothing - the same call isPotted makes.
	 */
	static double boostShare(int boosted, int base, int maxBoost)
	{
		if (maxBoost <= 0)
		{
			return boosted >= base ? 1 : 0;
		}
		return Math.max(0, Math.min(1, (double) (boosted - base) / maxBoost));
	}

	// Whether the intended prayer stands in for whatever was really up.
	private boolean intendedPrayer()
	{
		return (mode & INTENDED_PRAYER) != 0;
	}

	// Whether the levels read are the base plus the full boost, rather than what the stats really were.
	private boolean fullBoost()
	{
		return (mode & FULL_BOOST) != 0;
	}

	// Whether a prayer was active for what the server is resolving now. Reads the varbit directly; Client.isPrayerActive
	// is deprecated with no replacement.
	private boolean prayerActive(Prayer prayer)
	{
		final int varbit = prayer.getVarbit();
		// The server's copy, always. Clicking flips the client's copy at once so the orb responds without waiting, which
		// answers for the click rather than the attack: flick off at the end of a tick and the client reads off while the
		// server resolved the attack with it up.
		return client.getServerVarbitValue(varbit) == 1;
	}

	// Magic attack prayer multiplier.
	private double magicAccuracyPrayer()
	{
		if (intendedPrayer())
		{
			return prayerGoal().getAttackMultiplier();
		}
		if (prayerActive(Prayer.RP_INTENSIFY))
		{
			return 1.50;
		}
		if (prayerActive(Prayer.RP_VAPORISE))
		{
			return 1.30;
		}
		if (prayerActive(Prayer.RP_ANCIENT_WILL))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.AUGURY))
		{
			return 1.25;
		}
		if (prayerActive(Prayer.MYSTIC_VIGOUR))
		{
			return 1.18;
		}
		if (prayerActive(Prayer.MYSTIC_MIGHT))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.MYSTIC_LORE))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.MYSTIC_WILL))
		{
			return 1.05;
		}
		return 1.0;
	}

	/**
	 * Magic damage from prayer, in percent: unlike the other styles, some magic
	 * prayers add damage as well as accuracy.
	 */
	private double magicDamagePrayerPercent()
	{
		if (intendedPrayer())
		{
			return prayerGoal().getMagicDamagePercent();
		}
		if (prayerActive(Prayer.AUGURY))
		{
			return 4.0;
		}
		if (prayerActive(Prayer.RP_VAPORISE))
		{
			return 4.0;
		}
		if (prayerActive(Prayer.RP_ANCIENT_WILL))
		{
			return 3.0;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 2.0;
		}
		if (prayerActive(Prayer.MYSTIC_VIGOUR))
		{
			return 3.0;
		}
		if (prayerActive(Prayer.MYSTIC_MIGHT))
		{
			return 2.0;
		}
		if (prayerActive(Prayer.MYSTIC_LORE))
		{
			return 1.0;
		}
		// Mystic Will is deliberately absent: the wiki's magic damage table lists Lore, Might, Vigour and Augury only.
		return 0.0;
	}

	/** Ranged attack prayer multiplier. Rigour gives less here than it does to strength. */
	private double rangedAccuracyPrayer()
	{
		if (intendedPrayer())
		{
			return prayerGoal().getAttackMultiplier();
		}
		if (prayerActive(Prayer.RP_INTENSIFY))
		{
			return 1.50;
		}
		if (prayerActive(Prayer.RP_ANNIHILATE))
		{
			return 1.30;
		}
		if (prayerActive(Prayer.RP_ANCIENT_SIGHT))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.RIGOUR))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.DEADEYE))
		{
			return 1.18;
		}
		if (prayerActive(Prayer.EAGLE_EYE))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.HAWK_EYE))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.SHARP_EYE))
		{
			return 1.05;
		}
		return 1.0;
	}

	private double rangedPrayer()
	{
		if (intendedPrayer())
		{
			return prayerGoal().getStrengthMultiplier();
		}
		if (prayerActive(Prayer.RP_ANNIHILATE))
		{
			return 1.27;
		}
		if (prayerActive(Prayer.RP_ANCIENT_SIGHT))
		{
			return 1.20;
		}
		if (prayerActive(Prayer.RP_TRINITAS))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.RIGOUR))
		{
			return 1.23;
		}
		if (prayerActive(Prayer.DEADEYE))
		{
			return 1.18;
		}
		if (prayerActive(Prayer.EAGLE_EYE))
		{
			return 1.15;
		}
		if (prayerActive(Prayer.HAWK_EYE))
		{
			return 1.10;
		}
		if (prayerActive(Prayer.SHARP_EYE))
		{
			return 1.05;
		}
		return 1.0;
	}

	/** Sum of the melee (str) or ranged (rstr) strength bonus across worn gear. */
	// Sums the strength bonus across worn gear.
	/**
	 * The bows and crossbows that make their own ammunition, so the ammo slot is
	 * only being carried rather than drawn from.
	 */
	private static final Set<Integer> AMMOLESS_BOWS = ammolessBows();

	// The crystal bow and every bow of faerdhinen, colours included (T-056: the Crwys, Meilyr, Amlodd, Deadman and BR bows
	// were missing, so their ammo slot was summed into the max), and the charged webweaver bow.
	private static Set<Integer> ammolessBows()
	{
		final Set<Integer> bows = new HashSet<>(GearBonusCalc.CRYSTAL_BOWS);
		bows.add(ItemID.WILD_CAVE_WEBWEAVER_CHARGED);
		return Collections.unmodifiableSet(bows);
	}

	/** Whether this weapon makes its own ammunition, so the ammo slot is only carried. */
	static boolean isAmmoless(int weapon)
	{
		return AMMOLESS_BOWS.contains(weapon);
	}

	/**
	 * Whether anything worn in the ammo slot actually feeds this weapon.
	 *
	 * <p>A blowpipe keeps its darts inside itself, a chinchompa and a thrown
	 * weapon ARE the missile, and a crystal bow makes its own arrows - so in all
	 * of those the ammo slot is just being carried and the game does not roll it.
	 * Summing it anyway is what made a blowpipe read a max hit of 37 where it
	 * should have read about 16: dragon bolts left in the slot are +122 ranged
	 * strength, and they went straight into the max hit. Ranged ATTACK looked
	 * right the whole time, which is what made it hard to see - bolts carry
	 * ranged strength and no ranged attack at all.
	 *
	 * <p>The eclipse atlatl is the exception that stops this being a rule about
	 * thrown weapons: it is thrown, and its darts really do sit in the ammo slot.
	 */
	private boolean ammoSlotFeedsWeapon()
	{
		if (attackStyle().getAttackType() != AttackType.RANGED)
		{
			// Ammunition carries no melee or magic bonus, so nothing changes hands.
			return true;
		}
		final int weapon = weaponItemId();
		if (weapon == ItemID.ECLIPSE_ATLATL || weapon == ItemID.BR_ECLIPSE_ATLATL)
		{
			return true;
		}
		final WeaponCategory category = weaponCategory();
		if (category == WeaponCategory.THROWN || category == WeaponCategory.CHINCHOMPAS)
		{
			return false;
		}
		return !AMMOLESS_BOWS.contains(weapon);
	}

	private int computeEquipmentBonus(boolean melee)
	{
		final Loadout gear = gear();
		final boolean skipAmmo = !ammoSlotFeedsWeapon();
		int total = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			if (skipAmmo && slot == EquipmentInventorySlot.AMMO)
			{
				continue;
			}
			final ItemEquipmentStats e = equipmentStats(gear.id(slot));
			if (e != null)
			{
				total += melee ? e.getStr() : e.getRstr();
			}
		}
		if (!melee)
		{
			final ItemEquipmentStats dart = blowpipeDart();
			if (dart != null)
			{
				total += dart.getRstr();
			}
		}
		return total;
	}

	/** Which of the three combat styles the selected combat option attacks with. */
	private Style weaponStyle()
	{
		switch (attackStyle().getAttackType())
		{
			case RANGED:
				return Style.RANGED;
			case MAGIC:
				return Style.MAGIC;
			default:
				return Style.MELEE;
		}
	}
}
