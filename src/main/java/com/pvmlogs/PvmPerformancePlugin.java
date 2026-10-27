package com.pvmlogs;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.NPCManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "PvM Performance Tracker",
	description = "Tracks your real vs expected combat performance against any NPC",
	tags = {"pvm", "dps", "combat", "performance", "damage", "accuracy", "boss", "luck"},
	internalName = PvmPerformanceConfig.INTERNAL_NAME,
	legacyDataDirectory = PvmPerformanceConfig.DATA_DIR
)
public class PvmPerformancePlugin extends Plugin
{
	private static final int MAX_HISTORY = 500;
	// Single-NPC bosses from the RuneLite hiscore list. Raid and minigame entries are on that list too but never match a
	// combat NPC, so they fall out here and are handled separately.
	private static final Set<String> BOSS_NAMES = buildBossNames();
	private static final List<String> BOSS_DISPLAY_NAMES = buildBossDisplayNames();
	// How long a click on an NPC vouches for a projectile aimed at it. A cast is five ticks, so this covers the attack the
	// click ordered and lapses before the next would be thrown without a further click.
	private static final int CLICK_ATTRIBUTION_TICKS = 5;
	// How long a counted projectile is remembered: past any real flight, short enough that the map stays a handful of
	// entries.
	private static final int PROJECTILE_KEY_TICKS = 20;
	// How many of the player's recent tiles a projectile may have been fired from. Long enough to cover a projectile's
	// flight while the player runs.
	private static final int RECENT_TILES = 6;
	// How far a projectile may have left from a tile I am known to have occupied. Two, because that is how far running
	// moves in the tick the shot went out.
	private static final int TILE_SLACK = 2;
	private static final int LOCAL_TILE_SIZE = 128;
	// How far back to look for the loadout that threw a projectile, when a switch has already replaced it. A switch lands
	// within a tick or two.
	private static final int SWITCH_LOOKBACK_TICKS = 4;
	private static final int PRAYER_HISTORY_TICKS = 10;
	// The switch history needs the same reach as the prayer one, and for the same reason: the gap between an attack and
	// its booking differs by style.
	private static final int SWITCH_HISTORY_TICKS = 10;
	// How long an attack may stay in the air before its expected figures are given up on. Past any real flight, so only
	// one that truly never landed is dropped by this rather than by the fight ending.
	private static final int PENDING_SAMPLE_TICKS = 10;
	/**
	 * How long after an attack of ours another npc's hitsplat can still be that
	 * same attack catching it. Covers a chinchompa's flight, which is the
	 * longest of them; a deliberate switch cannot be mistaken for this, because
	 * switching means a NEW attack, and that books its own target before its
	 * hitsplat lands.
	 */
	private static final int AOE_WINDOW_TICKS = 5;
	// How many ticks after an attack goes out it is booked. Measured, not reasoned about: with a flick on the attack tick
	// the mark lands two ticks below the booking tick for a projectile and one for a melee blow.
	private static final int MELEE_BOOKING_LAG = 1;
	private static final int PROJECTILE_BOOKING_LAG = 2;
	// None at all for a cast with no projectile, which is booked from the caster's animation and so is seen on the tick it
	// goes out.
	private static final int CAST_BOOKING_LAG = 0;
	// The animations a projectile-less cast is thrown with. Only these spells reach the animation path. Extend it from the
	// "cast UNKNOWN ANIM" trace.
	private static final Set<Integer> CAST_ANIMATIONS =
		Collections.unmodifiableSet(new HashSet<>(Collections.singletonList(10092)));
	// Casts with no projectile, waiting to be told whether they landed. Usually none or one; two only while a long-range
	// cast is still in the air as the next goes out. A single slot let the new cast overwrite the old one's verdict, and
	// at range the two coincide exactly.
	private final List<PendingCast> castsAwaiting = new ArrayList<>();
	// The ticks an attack of mine dealt damage on, from the hitpoints experience drop, real or fake. See onFakeXpDrop.
	private final Set<Integer> damageDealtTicks = new HashSet<>();
	// Hitpoints experience as last seen, so a level change is not read as damage.
	private int hitpointsXp;


	// The tick the worn items last changed on.
	private static final int CYCLES_PER_TICK = 30;
	// How far either side of its due tick a projectile may land and still be recognised. One tick each way; nothing swings
	// faster than two.
	private static final int FLIGHT_SLACK_TICKS = 1;
	// How long the player must be looking away before a fight they opened by targeting, and have not yet attacked, is
	// closed.
	private static final int LOOK_AWAY_TICKS = 5;
	// How far a target's defence has to move before the expected figures are logged again.
	private static final int EXPECTED_LOG_DEFENCE_STEP = 5;
	private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	/** The column names, kept beside the row builders so the two cannot drift. */
	static final String CSV_HEADER =
		"level,raid,raidRun,room,"
		+ "started,npc,npcId,maxHp,killed,damageDealt,damageTaken,attempts,targets,hits,"
		+ "accuracyPct,durationSec,dps,avgHit,expMaxHit,expAccuracyPct,expAvgHit,"
		+ "ticksLost,ticksLostPct,ticksLostEating,"
		+ "attacksMade,attacksPrayed,attacksPotted,attacksSwitched,efficiencyPct,boostPct,boostCostPct,switchCostPct,"
		+ "thrallDamage,dotDamage,healed,statsWarning,"
		// T-018, at the end so no older column moves. damageTaken above is hits only from T-018 on.
		+ "dotTaken,hitsTaken,biggestHitTaken,damageTakenUnprotected,"
		// T-017 Part B: recoil and vengeance, at the end for the same reason.
		+ "reflectDamage\n";

	private static final DateTimeFormatter ROW_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PvmPerformanceOverlay overlay;

	@Inject
	private LoadoutOverlay loadoutOverlay;

	@Inject
	private PvmPerformanceConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private NPCManager npcManager;

	// Only for the names in the EAT debug lines.
	@Inject
	private ItemManager itemManager;

	// For the blowpipe dart remembered per account (T-015), a hidden key rather than a setting.
	@Inject
	private ConfigManager configManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	@Inject
	private CombatCalc combatCalc;

	@Inject
	private MonsterStatsProvider monsterStats;

	// From getPluginDirectory() at startUp; read by the executor's writes. Null if RuneLite couldn't give one.
	private volatile Filepath dataDirectory;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DefenceDrain drain;

	@Inject
	private GearBonusCalc gearBonuses;

	@Inject
	private DebugLog debug;

	// What the target has left, from the boss health bar or from every splat on it. Shared with CombatCalc's overkill cap
	// and MonsterStatsProvider's choice of version and hitpoints-scaled defence. See TargetHealth.
	@Inject
	private TargetHealth health;

	// The boss health bar as last read, so a change is logged once rather than every tick.
	private long lastHealthBarReading = Long.MIN_VALUE;
	// The version and defence the expected figures were last logged at, so a boss whose defence moves with its hitpoints
	// (Vardorvis) logs a line as it moves rather than once per target.
	private String debuggedExpectedVersion;
	private int debuggedExpectedDefence = Integer.MIN_VALUE;

	private PvmPerformancePanel panel;
	private NavigationButton navButton;

	// Expected figures for the current loadout, recomputed each tick.
	private int expectedMaxHit;
	private double expectedAccuracy = -1;
	private double expectedAverageHit = -1;
	private int expectedSpecMaxHit;
	private double expectedSpecAccuracy = -1;
	private double expectedSpecAverageHit = -1;
	private double expectedDps = -1;
	private SpecialAttack specialAttack;
	// What the loadout's figures are for, named for the overlay (T-016): weapon, and spell when one is cast.
	private String attackName;
	// Which target the figures above were computed against, so an attack is only sampled with figures that were actually
	// meant for it.
	private int expectedForNpcId = -1;
	// The target the debug log last described. Kept so the expected figures are written once per target rather than once
	// per tick, since they hold still through a fight.
	private int debuggedExpectedId = Integer.MIN_VALUE;
	// The NPC last clicked on, and when. A click is unambiguously mine and always precedes the attack it orders, which the
	// interaction is not on the opening attack of a fight.
	private int clickedNpcIndex = -1;
	private int clickedNpcTick = -1;
	// Ticks left before the weapon can attack again; 0 means ready. Counted from the tick the last attack went out, not
	// the tick it was booked: from the booking it measures the gap between bookings, which is a tick longer whenever the
	// style changes from melee to a projectile one.
	private int attackDueTick = Integer.MIN_VALUE;
	// Whether the last attack was proven by a projectile, and so booked two ticks after it went out rather than one. A
	// switch can leave one weapon's attack in flight while another is in hand.
	private boolean lastAttackFromProjectile;
	// How much of MY damage each npc has taken in the fight now open, so the target's remaining health can be counted
	// rather than read off a health bar that is bucketed into thirtieths and not always drawn. Per npc because one AoE
	// attack puts damage on several and only the target's own counts towards what the target has left.
	private final Map<Integer, Integer> damageByIndex = new HashMap<>();
	// The tick one of my hitsplats last landed on, whichever npc took it. Two in a tick are one attack.
	private int lastMineHitsplatTick = Integer.MIN_VALUE;
	// The tick my last attack of any style went out on. Apart from attackDueTick, which is only set once booked: this has
	// to be right immediately, being what says the weapon is busy.
	private int lastAttackSeenTick = Integer.MIN_VALUE;
	// The last tick a melee weapon was in hand. A swap is not wielded until the tick after it, so a blow thrown before the
	// swap lands while the client is already showing the new weapon.
	private int lastMeleeTick = Integer.MIN_VALUE;
	// How far behind the attack the observation that proved it was.
	private int attackObservedLag = MELEE_BOOKING_LAG;
	// The last tick a cast was booked from its animation, so the hitsplat does not book the same cast a second time.
	private int lastCastBookedTick = Integer.MIN_VALUE;
	// Expected figures for attacks that have gone out but not yet landed.
	private final List<PendingSample> pendingSamples = new ArrayList<>();
	// The tick a projectile was last taken as mine, so at most one is taken per tick. A player cannot throw two attacks in
	// one.
	private int acceptedProjectileTick = Integer.MIN_VALUE;
	// What getAnimation returns when nothing is playing.
	private static final int IDLE_ANIMATION = -1;
	/** How long after a swap a landing blow may still be the melee weapon's. */
	private static final int MELEE_SWAP_GRACE_TICKS = 1;
	// The tick an attack was last seen going out on, from the events that prove one rather than from what the player looks
	// like, and which event proved it: a projectile is a cast or a shot, a hitsplat a melee blow.
	private int attackObservedTick = -1;
	private boolean attackObservedFromProjectile;
	// Prayer and boost as they stood the instant the attack was proven, not at the end of the tick: a potion drunk after
	// the attack but inside the same tick has already raised the boosted level by the time GameTick runs.
	private boolean attackObservedPotted;
	// How much of the full boost the attack's stats held, read with the potted flag (T-010).
	private double attackObservedBoostShare = -1;
	// Whether any stat the attack rolled on was below its base (T-015).
	private boolean attackObservedDrained;
	// The tick the attack left on, which is not always the tick its event surfaces: a projectile's start cycle can be a
	// tick before the plugin hears of it, by which time the player may have switched. Melee has no such gap.
	private int attackOriginTick = -1;
	// The figures as they stood when the attack was seen. The per-tick snapshot is written at GameTick, after any switch
	// made during it; the projectile event fires while the weapon that threw the attack is still in hand.
	private int gearChangedTick = Integer.MIN_VALUE;
	private int attackObservedNpcId = -1;
	private int attackObservedMaxHit;
	private double attackObservedAccuracy = -1;
	private double attackObservedAverageHit = -1;
	private int attackObservedSpecMaxHit;
	private double attackObservedSpecAccuracy = -1;
	private double attackObservedSpecAverageHit = -1;
	// The expected figures as they stood on each of the last few ticks, so an attack can be scored with the loadout that
	// actually threw it. Each row carries the ordinary figures AND the special's, because which of the two an attack needs
	// is not known until it is booked - see sampleExpected.
	private static final int EXP_MAX = 0;
	private static final int EXP_ACCURACY = 1;
	private static final int EXP_AVERAGE = 2;
	private static final int EXP_NPC_ID = 3;
	private static final int EXP_SPEC_MAX = 4;
	private static final int EXP_SPEC_ACCURACY = 5;
	private static final int EXP_SPEC_AVERAGE = 6;
	private static final int EXP_WIDTH = 7;
	private final Map<Integer, double[]> expectedByTick = new HashMap<>();
	// The tick the special attack energy last fell, which is the tick a special went out on. Read back at the booking
	// rather than at the sample: the varp can arrive after the tick's onGameTick has already run. How long after the
	// energy falls an attack may still be that special's.
	private static final int SPEC_BOOKING_WINDOW_TICKS = 1;
	private int specFiredTick = Integer.MIN_VALUE;
	private int specEnergy = -1;
	// Where I stood on each of the last few ticks, in scene coordinates: a projectile names the tile it left, which is
	// where the player was when it was fired, not where they are when the event arrives.
	private final Map<Integer, LocalPoint> recentTiles = new HashMap<>();
	// Consecutive ticks the player has not been interacting with the open fight.
	private int notTargetingTicks;
	// Per NPC: the tick my last hitsplat landed, and whether its burst has been counted as a landed attack. Keyed by index
	// because one attack can land on several NPCs at once, and their bursts must not swallow each other.
	private final Map<Integer, Integer> lastHitsplatTick = new HashMap<>();
	private final Set<Integer> burstLanded = new HashSet<>();
	// NPCs whose current burst has already booked an attack. Claws split one special across two ticks, and each hitsplat
	// was booking its own attack, so one spec counted as two.
	private final Set<Integer> burstBooked = new HashSet<>();
	// Per NPC: the attack of mine whose splats are landing on it now, and how many it has put down (T-017). A splat beyond
	// what the attack deals is not its own: a tentacle hits once, so a second splat on its tick was the zombie's (19:34,
	// tick 439). Kept for projectile attacks too, which burstBooked is not: a dark bow's second arrow is its own.
	private final Map<Integer, AttackSplats.Burst> bursts = new HashMap<>();
	// The ticks the special energy fell on lately. Unlike specFiredTick, never spent by a booking: how many splats an attack
	// puts down turns on whether it was a special, and its last splats are judged after the booking has spent that.
	private final java.util.Deque<Integer> recentSpecDrops = new java.util.ArrayDeque<>();
	private static final int SPEC_DROPS_KEPT = 8;
	// The weapon held at the end of each recent tick, so an attack's splats are counted for the weapon that threw it: a
	// special is thrown with the claws, which are usually swapped out again before their splats land.
	private final Map<Integer, Integer> weaponByTick = new HashMap<>();
	// This tick's splats counted beyond their attack's splats, logged once the tick's re-judging is done.
	private final List<ExtraSplat> extrasThisTick = new ArrayList<>();
	// Recoil and vengeance (T-017 Part B): which hits taken they have answered. The ring worn at the end of each recent
	// tick, since the ring of recoil can shatter on the very hit whose recoil is still to land; and whether vengeance was
	// up, so its varbit falling marks the rebound.
	private final Reflect reflect = new Reflect();
	private final Map<Integer, Integer> ringByTick = new HashMap<>();
	private boolean vengeanceUp;

	/** A splat counted beyond what its attack deals, waiting for the tick's re-judging before it is logged. */
	private static final class ExtraSplat
	{
		final AttackSplats.Burst burst;
		final String npcName;
		final int amount;
		final int tick;

		ExtraSplat(AttackSplats.Burst burst, String npcName, int amount, int tick)
		{
			this.burst = burst;
			this.npcName = npcName;
			this.amount = amount;
			this.tick = tick;
		}
	}
	// Whether the goal prayer was up during each recent tick. A history rather than a slot, because the tick an attack
	// goes out on is not the tick it is booked on, and the gap differs by style.
	private final Map<Integer, Boolean> prayerUpByTick = new HashMap<>();
	// Whether the gear worn on each recent tick was the best available, kept as a history for the same reason the prayer
	// is.
	private final Map<Integer, CombatCalc.SwitchCheck> switchedByTick = new HashMap<>();
	// The attack speed in force on each recent tick, so the wait after an attack is the throwing weapon's and not that of
	// whatever replaced it.
	private final Map<Integer, Integer> speedByTick = new HashMap<>();
	// Eating, the one thing worth telling apart from idle time: each eat while an attack is pending holds it back by the
	// food's delay, and the lost ticks inside that delay are the eat's (T-012). Getting it wrong only mislabels the
	// breakdown; the total is measured from the attacks themselves.
	private final FoodDelay foodDelay = new FoodDelay();

	// Issue #1: the player's damage counted from the experience it paid, and our thrall, so the thrall's hitsplats can
	// be told from the player's. See XpLedger and ThrallTracker.
	private final XpLedger xpLedger = new XpLedger();
	// The id the fight's target wore on each recent tick, so an attack is judged by the phase it was thrown at (T-020).
	private final TargetIdHistory targetIds = new TargetIdHistory();
	private final ThrallTracker thralls = new ThrallTracker();
	// Each combat skill's experience as last seen, so a StatChanged can be read as a gain.
	private final int[] lastSkillXp = new int[XpLedger.SKILLS.length];
	private static final int MAX_XP = 200_000_000;
	// This tick's splats that went down the player's path, so a thrall projectile whose splat was given to the player can
	// be put right before the tick's attack is booked. Cleared every tick.
	private final List<PlayerSplatRecord> playerSplatsThisTick = new ArrayList<>();
	// The one being processed now, filled in as the player's path decides what it was.
	private PlayerSplatRecord splatInFlight;

	/** One splat the player's path took, with what it changed - enough to give it back. */
	private static final class PlayerSplatRecord
	{
		final int npcIndex;
		final int amount;
		// Weighed by the thrall gate, and how thrall-like it looked. Only these can be re-judged.
		final boolean judged;
		final double thrallProbability;
		final Integer previousSeen;
		final int previousAttackSeenTick;
		boolean landed;
		boolean booked;
		boolean fromFlight;
		boolean done;
		// Taken back from the player at the end of its tick, to the thrall or as a reflection.
		boolean takenBack;
		// What the reflection check needs (T-017 Part B): whether the experience paid for exactly this splat, as the
		// ledger saw it when the splat landed, and whether its npc could be what hit the player.
		boolean xpExact;
		boolean couldBeReflected;

		PlayerSplatRecord(int npcIndex, int amount, boolean judged, double thrallProbability, Integer previousSeen,
			int previousAttackSeenTick)
		{
			this.npcIndex = npcIndex;
			this.amount = amount;
			this.judged = judged;
			this.thrallProbability = thrallProbability;
			this.previousSeen = previousSeen;
			this.previousAttackSeenTick = previousAttackSeenTick;
		}
	}

	// Splats at or above this probability of being the thrall's are taken as its own.
	private static final double THRALL_THRESHOLD = 0.5;
	// How close the only thrall in view must be to be taken for ours when its summon was never seen.
	private static final int THRALL_FALLBACK_RADIUS = 3;
	// Animations a thrall plays that are not attacks.
	private static final Set<Integer> THRALL_NON_ATTACK_ANIMATIONS = Collections.unmodifiableSet(new HashSet<>(
		java.util.Arrays.asList(AnimationID.GHOST_UPDATE_THRALL_SPAWN, AnimationID.SKELETON_UPDATE_THRALL_SPAWN,
			AnimationID.GHOST_UPDATE_THRALL_SPAWN_RAISED, AnimationID.GHOST_UPDATE_TENDRILL_DEATH_THRALL,
			AnimationID.SKELETON_UPDATE_CHAMPION_DEATH_THRALL)));

	// Running totals for the trip, shown instead of the current fight when the overlay is set to whole-trip mode.
	private final SessionTotals session = new SessionTotals(System.currentTimeMillis());


	// The room the fights add up to, and the raid the rooms add up to. Both are views over the fights, so the three widths
	// cannot disagree.
	private Encounter currentEncounter;
	// The last room with something in it, kept so a finished kill stays readable while the next room is still empty. See
	// getDisplayEncounter.
	private Encounter lastFinishedEncounter;
	private Raid currentRaid;
	private RaidType raidType;
	private int raidCounter;

	// The id the target wears now, which is not the id the fight opened on once a boss transforms. Kept from the change
	// events rather than looked up.
	private int targetLiveId = -1;
	private NPC targetNpc;

	// Which Nightmare was last seen, so her totems can be told apart. Both fights use the same totem ids and nothing in a
	// totem says whose it is.
	private String nightmareBoss;

	// Which special Olm is running. Reset when the raid is, since it means nothing outside one.
	private OlmPhase olmPhase;

	// The fight currently in progress, or null between fights.
	private Fight current;
	// The most recently finished fight, kept so the overlay lingers briefly.
	private Fight lastFinished;
	// Persisted history, most-recent first. Copy-on-write so the panel (EDT) can iterate it safely while combat events
	// (client thread) append to it.
	private final List<Fight> history = new CopyOnWriteArrayList<>();

	// Splats on me (T-018): their kinds, what they add to the fight, and the hit damage tick by tick for recoil and
	// vengeance to be matched against (T-017 Part B: damageTakenOn).
	private final DamageTaken taken = new DamageTaken();
	// The fight target's animations and projectiles of the last few ticks, for the TAKEN line only (T-018).
	private final TargetActions targetSeen = new TargetActions();

	// My in-flight projectiles, each credited once. Keyed by start cycle, id and target rather than by the Projectile,
	// whose identity the client recycles.
	private final Map<Long, Integer> countedProjectiles = new HashMap<>();
	// npcIndex -> my launched attacks not yet resolved. A splash carries no caster, so it only counts as mine when it
	// resolves one of these.
	private final Map<Integer, List<PendingHit>> pendingMineHits = new HashMap<>();

	/**
	 * One of my attacks in flight: the tick it lands, and the splats it will put
	 * down there, read from the weapon that threw it rather than whatever is held
	 * when it lands (T-017).
	 */
	private static final class PendingHit
	{
		final int lands;
		final int thrownTick;
		final AttackSplats ordinary;
		final AttackSplats special;
		final String weapon;

		PendingHit(int lands, int thrownTick, AttackSplats ordinary, AttackSplats special, String weapon)
		{
			this.lands = lands;
			this.thrownTick = thrownTick;
			this.ordinary = ordinary;
			this.special = special;
			this.weapon = weapon;
		}
	}

	@Provides
	PvmPerformanceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PvmPerformanceConfig.class);
	}

	@Override
	protected void startUp()
	{
		LoadoutOverlay.migrateLoadoutOnly(LoadoutOverlay.Settings.of(configManager));
		overlayManager.add(overlay);
		overlayManager.add(loadoutOverlay);

		panel = new PvmPerformancePanel(this);
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/com/pvmlogs/panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("PvM Performance Tracker")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		dataDirectory = openDataDirectory();
		debug.setDirectory(dataDirectory);
		executor.execute(this::loadHistory);
		monsterStats.startUp(dataDirectory);
		if (config.debugLogging())
		{
			debug.start();
		}
	}

	/**
	 * The plugin's data folder, {@code ~/.runelite/plugin-data/<INTERNAL_NAME>/}. The first call moves the old
	 * {@code ~/.runelite/<DATA_DIR>/} there (the descriptor's {@code legacyDataDirectory}), history and all. Null if
	 * RuneLite can't give one: the plugin still runs, without history, exports, debug files or the monster cache.
	 */
	private Filepath openDataDirectory()
	{
		try
		{
			return getPluginDirectory();
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("PvM Logs: no data folder, so history, exports and debug files are off", e);
			return null;
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(loadoutOverlay);
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
		current = null;
		lastFinished = null;
		history.clear();
		countedProjectiles.clear();
		taken.clear();
		targetSeen.clear();
		recentTiles.clear();
		expectedByTick.clear();
		// Both re-read from scratch: a stale energy reading across a reset would look like a special the moment the bar came
		// back lower than it was.
		specFiredTick = Integer.MIN_VALUE;
		specEnergy = -1;
		recentSpecDrops.clear();
		weaponByTick.clear();
		extrasThisTick.clear();
		ringByTick.clear();
		reflect.clear();
		vengeanceUp = false;
		lastHitsplatTick.clear();
		burstLanded.clear();
		bursts.clear();
		pendingMineHits.clear();
		drain.clear();
		health.clear();
		thralls.clear();
		xpLedger.clear();
		targetIds.clear();
		java.util.Arrays.fill(lastSkillXp, 0);
		nightmareBoss = null;
		targetNpc = null;
		debug.stop();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!PvmPerformanceConfig.GROUP.equals(event.getGroup()) || !"debugLogging".equals(event.getKey()))
		{
			return;
		}
		if (config.debugLogging())
		{
			debug.start();
		}
		else
		{
			debug.stop();
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		final Actor actor = event.getActor();
		final Hitsplat hitsplat = event.getHitsplat();
		final long now = System.currentTimeMillis();

		if (actor instanceof NPC && hitsplat.isMine() && !EncounterGroup.isIgnored(((NPC) actor).getId()))
		{
			final NPC npc = (NPC) actor;
			// Recoil and vengeance first (T-017 Part B): they are isMine() too, and a small recoil would otherwise go to the
			// thrall gate, or book a blow.
			final boolean couldBeReflected = hitsplat.getAmount() > 0 && couldHaveHitMe(npc);
			final boolean xpExact = xpLedger.trusted()
				&& xpLedger.coverage(hitsplat.getAmount(), client.getTickCount()) == XpLedger.Coverage.EXACT;
			if (couldBeReflected && !xpExact)
			{
				final Reflect.Match match = reflect.claim(client.getTickCount(), hitsplat.getAmount(), this::hitsTakenOn,
					recoilWorn(client.getTickCount()));
				if (match != null)
				{
					onReflectedSplat(npc, hitsplat, match);
					return;
				}
			}
			// Issue #1. isMine() passes our thrall's splats too, and nothing on the splat says whose it is, so with a thrall
			// out each small one is weighed before anything else hears of it. A thrall's splat opens no fight, books no
			// attack and counts no hit; it still takes the target's health down.
			splatInFlight = null;
			if (thralls.hasThrall() && hitsplat.getAmount() <= thralls.maxHit())
			{
				final double p = judgedThralls(npc, hitsplat.getAmount(), now);
				if (p >= THRALL_THRESHOLD)
				{
					return;
				}
				xpLedger.spend(hitsplat.getAmount(), client.getTickCount(), false);
				splatInFlight = new PlayerSplatRecord(npc.getIndex(), hitsplat.getAmount(), true, p,
					lastHitsplatTick.get(npc.getIndex()), lastAttackSeenTick);
			}
			else
			{
				// Too big for a thrall, or none out: certainly the player's, so it is what the ledger checks itself on.
				xpLedger.spend(hitsplat.getAmount(), client.getTickCount(), true);
				splatInFlight = new PlayerSplatRecord(npc.getIndex(), hitsplat.getAmount(), false, 0,
					lastHitsplatTick.get(npc.getIndex()), lastAttackSeenTick);
			}
			final int previousMineTick = lastMineHitsplatTick;
			lastMineHitsplatTick = client.getTickCount();
			if (current == null || current.isEnded() || current.getTargetIndex() != npc.getIndex())
			{
				if (!isFightable(npc))
				{
					debug.log("hitsplat %d on %s (npc %d) IGNORED: not something that can be fought",
						hitsplat.getAmount(), npc.getName(), npc.getId());
					return;
				}
				if (isAoeCollateral(npc, previousMineTick))
				{
					recordExtraTarget(npc, hitsplat.getAmount(), now);
					return;
				}
				startFight(npc, now);
			}
			// Hitsplats arriving together are one attack: a claw special lands four across two ticks, a dark bow two on one.
			// Grouped by adjacency, and nothing swings faster than two ticks, so no two real attacks merge.
			final int tick = client.getTickCount();
			final Integer seen = lastHitsplatTick.put(npc.getIndex(), tick);
			final boolean newBurst = seen == null || tick - seen > 1;
			if (newBurst)
			{
				burstLanded.remove(npc.getIndex());
				burstBooked.remove(npc.getIndex());
			}
			// The first hitsplat of the burst to actually deal damage is what makes its attack a landed one. A claw special that
			// opens with a zero and then connects still landed once.
			final boolean alreadyLanded = burstLanded.contains(npc.getIndex());
			final boolean landedAttack = hitsplat.getAmount() > 0 && !alreadyLanded;
			if (landedAttack)
			{
				burstLanded.add(npc.getIndex());
			}
			// Whether this hitsplat is one of MY projectiles landing. Consumed here rather than further down because the resolve
			// below depends on it.
			final PendingHit flight = consumePending(npc.getIndex());
			final boolean arrivedFromFlight = flight != null;
			// KNOWN-WRONG, deliberately: this resolves on ANY hitsplat of mine, so a melee blow can resolve a projectile still
			// in the air. The melee attack then books its own too, so the tick adds both and the total comes out right by luck -
			// and would not if the target died in between. It is left this way because the obvious fix is worse, and
			// deliberately left that way for now - see "the melee blow that resolved a crossbow" in CODE_NOTES.md. Gating it on
			// arrivedFromFlight was tried and reverted: it made ranged resolution depend on the landing-tick estimate being
			// accurate, and where that missed, the expectation was never counted at all. Magic did not show it because a cast
			// has a SECOND route through recordSplash, which is what proved the cause. Trading a total that is right by luck for
			// one that is silently short is the worse bargain; the real fix is to match a held sample to its own landing tick.
			resolvePendingSample(npc.getIndex());
			// A cast waiting on an answer has it the moment one of my hitsplats DEALS DAMAGE to the NPC it was aimed at; nothing
			// later can unsay that. A zero is deliberately taken as a splash rather than a hit that rolled nothing - not
			// strictly true, but rare, and it makes damage dealt the single question both witnesses answer.
			if (hitsplat.getAmount() > 0)
			{
				for (PendingCast cast : castsAwaiting)
				{
					// The oldest unanswered cast on this NPC is the one that landed: they resolve in the order they were thrown.
					if (cast.npcIndex == npc.getIndex() && !cast.connected)
					{
						cast.connected = true;
						break;
					}
				}
			}
			// A zero is a miss, which is what arms the confliction gauntlets for the next cast against this same enemy.
			if (combatCalc.usesConflictionGauntlets())
			{
				combatCalc.noteMagicResolved(npc.getIndex(), hitsplat.getAmount() == 0);
			}
			// The TYPE, because the amount alone cannot say where a hitsplat came from. Pairs carrying the same number on one
			// tick looked like one splat arriving twice and were burning claws: three hitsplats to a special, split across
			// two ticks, which the burst logic already books as the single attack it is. A thrall, a bleed, poison and a max
			// hit all carry their own type, and telling those apart from a real hit of the player's is the whole question
			// whenever measured damage and attacks disagree.
			// Shield, armour, charge and poise splats are the player's attack landing on something that is not hitpoints:
			// still the attack and its weapon damage, which the expected side scores, but not the target's health. See
			// HitsplatKind.
			final boolean onHitpoints = HitsplatKind.of(true, false, hitsplat.getHitsplatType()) == HitsplatKind.PLAYER;
			debug.log("hitsplat %d type %d on %s (npc %d, index %d), landedAttack=%b, fromFlight=%b%s",
				hitsplat.getAmount(), hitsplat.getHitsplatType(), current.getTargetName(), npc.getId(), npc.getIndex(),
				landedAttack, arrivedFromFlight,
				onHitpoints ? "" : ", NOT HITPOINTS (" + HitsplatKind.typeName(hitsplat.getHitsplatType()) + ")");
			current.recordDamageDealt(hitsplat.getAmount(), now, landedAttack);
			if (current.isScored())
			{
				session.recordAttempt(hitsplat.getAmount(), landedAttack, now);
			}
			// A special's drain is worked out from the hit it landed, so it can only be applied here.
			drain.onMyHitsplat(npc, hitsplat.getAmount());
			// Melee, plus a projectile-less cast the animation route did not recognise; everything else is booked from its
			// projectile. That route sees misses and this one cannot, so it books first and this is only here so an unknown cast
			// animation costs the misses rather than everything. Its lag is the hit delay, since it books from the landing and
			// so is exactly as late as the spell was slow.
			final int castLag = magicHitDelay(castDistance(npc));
			final boolean unbookedCast = combatCalc.castLandsWithoutProjectile()
				&& client.getTickCount() - lastCastBookedTick > castLag;
			// Melee as it was when the blow was THROWN, not as the client shows it now. A swap is not wielded until the tick
			// after it, so swinging and then switching to a blowpipe left the melee hitsplat arriving while a ranged weapon was
			// on screen - the attack was never booked and its damage counted against no expectation. Only non-projectile
			// hitsplats reach here, so widening this cannot claim a ranged attack.
			final boolean meleeRecently = combatCalc.isMeleeEquipped()
				|| client.getTickCount() - lastMeleeTick <= MELEE_SWAP_GRACE_TICKS;
			final boolean melee = meleeRecently || unbookedCast;
			final boolean firstOfBurst = !burstBooked.contains(npc.getIndex());
			final boolean booked = !arrivedFromFlight && melee && burstBooked.add(npc.getIndex());
			final int bookingLag = combatCalc.isMeleeEquipped() ? MELEE_BOOKING_LAG : castLag;
			if (booked)
			{
				recordAttackObserved(false, npc.getId(), bookingLag);
			}
			// Counted against what the target has left only now, after the attack it books was judged: the overkill cap reads
			// that count, and with the blow's own damage already in it a 39 on a Sire with 42 left was judged against 3 - an
			// expected 2.16 - and the blow after it, with the count at 0, against no cap at all (T-020).
			if (onHitpoints)
			{
				damageByIndex.merge(npc.getIndex(), hitsplat.getAmount(), Integer::sum);
				health.damage(npc.getIndex(), npc.getId(), hitsplat.getAmount(), tick);
			}
			else
			{
				current.recordNotHitpoints(HitsplatKind.typeName(hitsplat.getHitsplatType()), hitsplat.getAmount());
			}
			countSplat(npc, tick, hitsplat.getAmount(), booked ? tick - bookingLag : Integer.MIN_VALUE, flight);
			// What the player's path made of it, kept for this tick in case the thrall's projectile says otherwise.
			if (splatInFlight != null && splatInFlight.npcIndex == npc.getIndex())
			{
				splatInFlight.landed = landedAttack;
				splatInFlight.booked = booked;
				splatInFlight.fromFlight = arrivedFromFlight;
				splatInFlight.xpExact = xpExact;
				splatInFlight.couldBeReflected = couldBeReflected;
				splatInFlight.done = true;
				playerSplatsThisTick.add(splatInFlight);
			}
			splatInFlight = null;
		}
		else if (actor instanceof NPC && !hitsplat.isMine())
		{
			onNotMineSplat((NPC) actor, hitsplat);
		}
		else if (actor != null && actor == client.getLocalPlayer())
		{
			onSplatOnMe(hitsplat, now);
		}
	}

	/**
	 * A splat on the local player (T-018). Classified by {@link HitsplatKind#onPlayer}:
	 * a hit is damage taken, damage over time is kept beside it, and heals and splats
	 * that leave hitpoints alone count nowhere. Only an open fight takes them; the
	 * per-tick hit record and the TAKEN line take every one.
	 */
	private void onSplatOnMe(Hitsplat hitsplat, long now)
	{
		final int tick = client.getTickCount();
		final String protection = protectionUp();
		final boolean fighting = current != null && !current.isEnded();
		final HitsplatKind kind = taken.onSplat(hitsplat.getHitsplatType(), hitsplat.getAmount(), tick,
			protection != null, fighting ? current : null, session, now);
		final String target = fighting ? targetSeen.describe(tick, current.getTargetIndex()) : "";
		debug.log("%s", DamageTaken.line(hitsplat.getAmount(), hitsplat.getHitsplatType(), kind, protection,
			client.getBoostedSkillLevel(Skill.HITPOINTS), npcsOnMe(), target, fighting));
	}

	/**
	 * The hit damage on me on {@code tick}, fight or not: see {@link DamageTaken#damageTakenOn}. For recoil and
	 * vengeance (T-017 Part B), which answer a hit a tick or so after it lands.
	 */
	int damageTakenOn(int tick)
	{
		return taken.damageTakenOn(tick);
	}

	/** Each hit splat on me on {@code tick}, 0s included: see {@link DamageTaken#hitsTakenOn}. */
	List<Integer> hitsTakenOn(int tick)
	{
		return taken.hitsTakenOn(tick);
	}

	/**
	 * A splat of mine matched to a hit I took as its recoil or vengeance (T-017
	 * Part B). Real damage on the npc, so its health goes down; but no attack threw
	 * it, so it opens no fight, books no attack, counts no hit and is not weapon
	 * damage. Counted only on the fight's own target: reflected onto anything else,
	 * it is logged and left.
	 */
	private void onReflectedSplat(NPC npc, Hitsplat hitsplat, Reflect.Match match)
	{
		final int amount = hitsplat.getAmount();
		final int tick = client.getTickCount();
		final boolean ours = current != null && !current.isEnded() && current.getTargetIndex() == npc.getIndex();
		debug.log("REFLECT %d type %d on %s (npc %d, index %d): %s%s", amount, hitsplat.getHitsplatType(), npc.getName(),
			npc.getId(), npc.getIndex(), match, ours ? "" : ", not the fight's target: not counted");
		if (!ours)
		{
			return;
		}
		if (HitsplatKind.of(true, false, hitsplat.getHitsplatType()) == HitsplatKind.PLAYER)
		{
			damageByIndex.merge(npc.getIndex(), amount, Integer::sum);
			health.damage(npc.getIndex(), npc.getId(), amount, tick);
		}
		current.recordReflectDamage(amount);
		if (current.isScored())
		{
			session.recordReflectDamage(amount);
		}
	}

	/**
	 * Whether this npc could be what hit me, so a splat of mine on it could be my
	 * recoil or vengeance: the fight's target, or anything aimed at me.
	 */
	private boolean couldHaveHitMe(NPC npc)
	{
		final Player me = client.getLocalPlayer();
		return (current != null && !current.isEnded() && current.getTargetIndex() == npc.getIndex())
			|| (me != null && npc.getInteracting() == me);
	}

	/**
	 * Whether a recoil ring was worn on this tick or the one before: a ring of
	 * recoil shatters on the hit that uses its last charge, before that hit's
	 * recoil has landed.
	 */
	private boolean recoilWorn(int tick)
	{
		if (Reflect.isRecoilRing(wornRingId()))
		{
			return true;
		}
		for (int t = tick - Reflect.WINDOW_TICKS - 1; t <= tick; t++)
		{
			final Integer ring = ringByTick.get(t);
			if (ring != null && Reflect.isRecoilRing(ring))
			{
				return true;
			}
		}
		return false;
	}

	private int wornRingId()
	{
		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		final Item ring = worn == null ? null : worn.getItem(EquipmentInventorySlot.RING.getSlotIdx());
		return ring == null ? -1 : ring.getId();
	}

	/**
	 * The protection prayer up as the server has it, or null for none. The server's copy for the reason
	 * CombatCalc.prayerActive gives: a flick at the end of a tick changes the client's at once.
	 */
	private String protectionUp()
	{
		for (Prayer prayer : DamageTaken.PROTECTION)
		{
			if (client.getServerVarbitValue(prayer.getVarbit()) == 1)
			{
				return DamageTaken.protectionName(prayer);
			}
		}
		return null;
	}

	/**
	 * The npcs that have me as their target, for the TAKEN line: a splat names no source, so this is the nearest the
	 * client comes. The fight's target is marked. At most four, then a count.
	 */
	private String npcsOnMe()
	{
		final Player me = client.getLocalPlayer();
		if (me == null || client.getTopLevelWorldView() == null)
		{
			return "";
		}
		final StringBuilder out = new StringBuilder();
		int count = 0;
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getInteracting() != me)
			{
				continue;
			}
			if (++count > 4)
			{
				continue;
			}
			if (out.length() > 0)
			{
				out.append(", ");
			}
			out.append("npc ").append(npc.getId()).append(" #").append(npc.getIndex());
			if (current != null && !current.isEnded() && npc.getIndex() == current.getTargetIndex())
			{
				out.append(" (target)");
			}
		}
		if (count > 4)
		{
			out.append(" and ").append(count - 4).append(" more");
		}
		return out.toString();
	}

	/**
	 * A splat on the fight's target that {@code isMine()} does not claim: burn,
	 * poison, venom, bleed, a heal, another player's hit, or a type not known here.
	 * Each is counted against the target's health where it changes it, kept on the
	 * fight under its own heading, and logged with its type number - never as the
	 * player's damage, hits or attacks. Splats on anything else are not this
	 * fight's business.
	 */
	private void onNotMineSplat(NPC npc, Hitsplat hitsplat)
	{
		if (current == null || current.isEnded() || npc.getIndex() != current.getTargetIndex())
		{
			return;
		}
		final int index = npc.getIndex();
		final int amount = hitsplat.getAmount();
		final int type = hitsplat.getHitsplatType();
		final int tick = client.getTickCount();
		final HitsplatKind kind = HitsplatKind.of(false, hitsplat.isOthers(), type);
		switch (kind)
		{
			case DAMAGE_OVER_TIME:
				// Damage over time is the target's health going down with no attack behind it: no activity, no hit, no attempt.
				current.recordDotDamage(amount);
				if (current.isScored())
				{
					session.recordDotDamage(amount);
				}
				break;
			case HEAL:
				current.recordHeal(amount);
				health.heal(index, npc.getId(), amount, tick);
				damageByIndex.compute(index, (i, v) -> Math.max(0, (v == null ? 0 : v) - Math.max(0, amount)));
				break;
			case OTHER_PLAYER:
				current.recordOthersDamage(amount);
				break;
			case UNCLASSIFIED:
				current.recordUnclassifiedDamage(amount);
				break;
			default:
				break;
		}
		if (kind.damagesTarget())
		{
			health.damage(index, npc.getId(), amount, tick);
			damageByIndex.merge(index, Math.max(0, amount), Integer::sum);
		}
		debug.log("splat %d %s (type %d, %s) on %s (npc %d, index %d): %s, target has %d left (%s)",
			amount, HitsplatKind.typeName(type), type, hitsplat.isOthers() ? "others'" : "no owner",
			current.getTargetName(), npc.getId(), index, kind, health.remaining(index, tick),
			health.source(index, tick));
	}

	/**
	 * Weighs one small splat while our thrall is out, and takes it as the
	 * thrall's if that is likelier than not. Every judgement is logged with the
	 * evidence behind it.
	 *
	 * @return the probability it was the thrall's; at or above THRALL_THRESHOLD it has been dealt with as such
	 */
	private double judgedThralls(NPC npc, int amount, long now)
	{
		final int tick = client.getTickCount();
		final ThrallTracker.Evidence e = new ThrallTracker.Evidence();
		e.amount = amount;
		e.tick = tick;
		e.targetIndex = npc.getIndex();
		e.player = playerSplatFor(npc, tick);
		e.xp = xpLedger.coverage(amount, tick);
		e.xpTrusted = xpLedger.trusted();
		if (expectedForNpcId == npc.getId())
		{
			e.playerAccuracy = expectedAccuracy;
			e.playerMaxHit = expectedMaxHit;
		}
		final double p = thralls.probability(e);
		final boolean isThralls = p >= THRALL_THRESHOLD;
		debug.log("hitsplat %d on %s (index %d): %s, thrall p=%.2f [rate %.3f, player %s, xp %s (%s), phase %d]",
			amount, npc.getName(), npc.getIndex(), isThralls ? "THRALL" : "player", p,
			thralls.thrallRate(npc.getIndex(), tick), e.player, e.xp, xpLedger.trustSummary(), thralls.lockedPhase());
		if (!isThralls)
		{
			return p;
		}
		thralls.noteThrallSplat(npc.getIndex(), tick);
		if (current != null && !current.isEnded())
		{
			// Real damage, so it counts towards what the target has left - but none of it is the player's.
			damageByIndex.merge(npc.getIndex(), amount, Integer::sum);
			health.damage(npc.getIndex(), npc.getId(), amount, tick);
			current.recordThrallDamage(amount);
			if (current.isScored())
			{
				session.recordThrallDamage(amount);
			}
		}
		return p;
	}

	/**
	 * Counts a splat the player's path kept against the attack it belongs to
	 * (T-017). A booked blow or a projectile arriving starts an attack's count;
	 * a splat beside it on its tick, or on the next, is one more of its splats,
	 * and one beyond what the weapon deals is logged as an extra (once the
	 * tick's re-judging is done, see reportExtras). Without our thrall out, that
	 * extra is still the player's for now: T-017 Part B (recoil, vengeance)
	 * decides where those go.
	 *
	 * @param bookedThrownTick the tick a blow this splat booked was thrown on,
	 *                         or {@code Integer.MIN_VALUE} if it booked none
	 * @param flight           the projectile attack this splat arrived as, or null
	 */
	private void countSplat(NPC npc, int tick, int amount, int bookedThrownTick, PendingHit flight)
	{
		final int index = npc.getIndex();
		if (flight != null)
		{
			bursts.put(index, new AttackSplats.Burst(flight.thrownTick, tick, flight.ordinary, flight.special,
				AttackSplats.specialNear(recentSpecDrops, flight.thrownTick), flight.weapon));
			return;
		}
		if (bookedThrownTick != Integer.MIN_VALUE)
		{
			final PendingHit blow = attackThrownOn(bookedThrownTick, tick, npc.getId());
			bursts.put(index, new AttackSplats.Burst(bookedThrownTick, tick, blow.ordinary, blow.special,
				AttackSplats.specialNear(recentSpecDrops, bookedThrownTick), blow.weapon));
			return;
		}
		final AttackSplats.Burst burst = bursts.get(index);
		if (burst == null || !burst.belongsOn(tick))
		{
			// Not landing beside any attack this plugin saw go out, so there is nothing to count it against.
			bursts.remove(index);
			return;
		}
		refreshSpecial(burst);
		if (!burst.give(tick))
		{
			extrasThisTick.add(new ExtraSplat(burst, npc.getName(), amount, tick));
		}
	}

	/**
	 * The splats an attack thrown on this tick puts on this npc, read from the
	 * weapons held around the throw rather than from what is held when its
	 * splats land: a special is thrown with the claws, which are usually swapped
	 * out again by then. A swap is taken as wielded from the tick after it, as
	 * recordAttackObserved takes it, so the weapon held at the end of the tick
	 * before the throw; that tick's, and the weapon held now, are asked as well,
	 * and the larger count kept, so a switch never costs an attack its own
	 * splats. The weapon named is the one with the most.
	 */
	private PendingHit attackThrownOn(int thrownTick, int lands, int npcId)
	{
		final List<Integer> held = new ArrayList<>();
		for (int t = thrownTick - 1; t <= thrownTick; t++)
		{
			final Integer weapon = weaponByTick.get(t);
			if (weapon != null && !held.contains(weapon))
			{
				held.add(weapon);
			}
		}
		final int now = combatCalc.equippedWeaponId();
		if (!held.contains(now))
		{
			held.add(now);
		}
		AttackSplats ordinary = null;
		AttackSplats special = null;
		int named = held.get(0);
		int namedCount = 0;
		for (int weapon : held)
		{
			final AttackSplats o = combatCalc.splatsPerAttack(npcId, false, weapon);
			final AttackSplats s = combatCalc.splatsPerAttack(npcId, true, weapon);
			ordinary = AttackSplats.atLeast(ordinary, o);
			special = AttackSplats.atLeast(special, s);
			if (Math.max(o.count, s.count) > namedCount)
			{
				named = weapon;
				namedCount = Math.max(o.count, s.count);
			}
		}
		return new PendingHit(lands, thrownTick, ordinary, special, combatCalc.itemLabel(named));
	}

	// The energy can be read after the attack's first splat, so an attack counted as ordinary may turn out a special.
	private void refreshSpecial(AttackSplats.Burst burst)
	{
		if (!burst.isSpecial() && AttackSplats.specialNear(recentSpecDrops, burst.thrownTick()))
		{
			burst.markSpecial();
		}
	}

	/**
	 * What the player could have done to put a splat on this npc now. The facts
	 * are gathered here; the judgement is ThrallTracker.classifyPlayer, which the
	 * replay tests share.
	 */
	private ThrallTracker.PlayerSplat playerSplatFor(NPC npc, int tick)
	{
		final int index = npc.getIndex();
		// Their own attack landing here: a scythe's second and third splats, a claw special's, a dual macuahuitl's second
		// hit a tick after its first - but only as many as the attack deals (T-017). A tentacle hits once.
		final AttackSplats.Burst burst = bursts.get(index);
		if (burst != null)
		{
			refreshSpecial(burst);
		}
		final AttackSplats.State state = burst == null ? AttackSplats.State.NONE : burst.stateOn(tick);
		boolean projectileDue = false;
		final List<PendingHit> landing = pendingMineHits.get(index);
		if (landing != null)
		{
			for (PendingHit due : landing)
			{
				projectileDue |= Math.abs(due.lands - tick) <= FLIGHT_SLACK_TICKS;
			}
		}
		for (PendingCast cast : castsAwaiting)
		{
			projectileDue |= cast.npcIndex == index && Math.abs(cast.resolveTick - tick) <= FLIGHT_SLACK_TICKS;
		}
		final boolean melee = combatCalc.isMeleeEquipped() || tick - lastMeleeTick <= MELEE_SWAP_GRACE_TICKS;
		// The weapon's own due tick from the last booking: a blow thrown before it is impossible, which is what stops a
		// thrall's splat booking an attack inside the weapon's cooldown (T-003, Vardorvis ticks 199 and 203).
		return ThrallTracker.classifyPlayer(state, projectileDue, melee, tick, attackDueTick, lastAttackFromProjectile);
	}

	/** Closes the tick's experience into an award and keeps the thrall's state moving. Run first in the tick. */
	private void trackThrallAndXp()
	{
		final int tick = client.getTickCount();
		rejudgeUnclaimedProjectiles(tick);
		final boolean[] cannotReport = new boolean[XpLedger.SKILLS.length];
		for (int i = 0; i < cannotReport.length; i++)
		{
			cannotReport[i] = client.getSkillExperience(XpLedger.SKILLS[i]) >= MAX_XP;
		}
		// The fight's target, or what the player is aimed at: a melee blow pays its drop the tick before its splat opens
		// the fight, and read against no target its bonus would be missing.
		int targetId = current != null && !current.isEnded() ? targetLiveId : -1;
		final Player me = client.getLocalPlayer();
		if (targetId < 0 && me != null && me.getInteracting() instanceof NPC)
		{
			targetId = ((NPC) me.getInteracting()).getId();
		}
		final MonsterStatsProvider.MonsterStats stats = targetId < 0 ? null : monsterStats.get(targetId);
		final XpLedger.Award award = xpLedger.close(tick, targetId, stats == null ? 1.0 : stats.getXpMultiplier(),
			cannotReport);
		if (award != null)
		{
			debug.log("xp award %s against npc %d", award, targetId);
			// The drop is on the attack tick, so it proves an attack thrown now. If the last booking says the weapon was still
			// busy, that booking was not the player's - most likely a thrall's splat taken for a blow. Not undone here: a
			// booking touches the fight, the room and the session, and re-scoring belongs in the analyzer (workstream 03).
			if (attackDueTick != Integer.MIN_VALUE && tick < attackDueTick && current != null && !current.isEnded())
			{
				debug.log("XP CONTRADICTS BOOKING: an attack was thrown on %d, but the last booking had the weapon busy until "
					+ "%d", tick, attackDueTick);
			}
		}
		for (String note : xpLedger.expire(tick))
		{
			debug.log(note);
		}

		// The varbit, polled as well as watched, so a thrall already out when tracking began is known to be active.
		final boolean active = client.getVarbitValue(VarbitID.ARCEUUS_RESURRECTION_ACTIVE) == 1;
		if (active != thralls.varbitActive())
		{
			thralls.onResurrectionVarbit(active, tick);
		}
		thralls.tick(tick);
		if (active && !thralls.hasThrall() && client.getTopLevelWorldView() != null)
		{
			NPC only = null;
			int seen = 0;
			for (NPC npc : client.getTopLevelWorldView().npcs())
			{
				if (npc != null && Thralls.isThrall(npc.getId()))
				{
					only = npc;
					seen++;
				}
			}
			// Only one in view, and beside the player, where ours follows them: a teammate's thrall is never claimed for
			// want of seeing our own.
			if (seen == 1 && distanceFromMe(only) <= THRALL_FALLBACK_RADIUS)
			{
				thralls.onOnlyCandidate(only.getIndex(), only.getId(), tick);
			}
		}
		for (String note : thralls.drainNotes())
		{
			debug.log(note);
		}
	}

	/**
	 * Puts right a splat the player was given that the thrall's projectile says
	 * was its own. A projectile of ours always brings its splat on the tick it is
	 * first seen (223 of 223 in the Vardorvis logs), but that splat can arrive
	 * before anything else that tick says whose it is: a 0 landed ahead of the
	 * player's 21 on 351 and was judged the player's miss. Done here, at the top
	 * of the tick, because the attack is only booked further down: an undo now
	 * reverses exactly what the splat did, and nothing has been built on it yet.
	 *
	 * <p>Only a splat the thrall gate weighed, and one that did not arrive as one
	 * of the player's projectiles, is taken back. A splat that spent experience
	 * stays spent: that award is already marked as not certain, which is all the
	 * ledger learns from.
	 */
	private void rejudgeUnclaimedProjectiles(int tick)
	{
		try
		{
			if (!thralls.hasThrall())
			{
				return;
			}
			for (int target : thralls.unclaimedProjectileTargets(tick))
			{
				PlayerSplatRecord pick = null;
				for (PlayerSplatRecord r : playerSplatsThisTick)
				{
					if (r.npcIndex == target && r.judged && r.done && !r.takenBack && !r.fromFlight
						&& r.amount <= thralls.maxHit()
						&& (pick == null || r.thrallProbability > pick.thrallProbability))
					{
						pick = r;
					}
				}
				if (pick != null)
				{
					giveToThrall(pick, tick);
				}
			}
		}
		finally
		{
			try
			{
				rejudgeReflections(tick);
			}
			finally
			{
				playerSplatsThisTick.clear();
				splatInFlight = null;
				reportExtras();
			}
		}
	}

	/**
	 * Logs the tick's splats counted beyond their attack's splats (T-017), once
	 * the re-judging above is done: when the thrall's 0 lands ahead of the
	 * player's 45, the 0 takes the blow's place and the 45 reads as an extra
	 * until the thrall's unclaimed projectile hands the 0 back. Only the extras
	 * still standing are logged.
	 */
	private void reportExtras()
	{
		if (extrasThisTick.isEmpty())
		{
			return;
		}
		final Map<AttackSplats.Burst, List<ExtraSplat>> byAttack = new LinkedHashMap<>();
		for (ExtraSplat extra : extrasThisTick)
		{
			byAttack.computeIfAbsent(extra.burst, k -> new ArrayList<>()).add(extra);
		}
		extrasThisTick.clear();
		for (Map.Entry<AttackSplats.Burst, List<ExtraSplat>> attack : byAttack.entrySet())
		{
			final AttackSplats.Burst burst = attack.getKey();
			final List<ExtraSplat> extras = attack.getValue();
			final int count = burst.splats().count;
			final int standing = Math.min(extras.size(), burst.given() - count);
			for (int i = extras.size() - standing; i < extras.size(); i++)
			{
				final ExtraSplat extra = extras.get(i);
				debug.log("EXTRA SPLAT on %s: %s hits %d, this is %d%s (a %d on tick %d)", extra.npcName, burst.weapon(),
					count, count + standing - (extras.size() - i) + 1, burst.isSpecial() ? " (special)" : "", extra.amount,
					extra.tick);
			}
		}
	}

	private void giveToThrall(PlayerSplatRecord r, int tick)
	{
		final String outcome = takeBackFromPlayer(r, tick);
		if (current != null && !current.isEnded())
		{
			current.recordThrallDamage(r.amount);
			if (current.isScored())
			{
				session.recordThrallDamage(r.amount);
			}
		}
		thralls.noteThrallSplat(r.npcIndex, tick);
		debug.log("RE-JUDGED hitsplat %d on index %d (was player, thrall p=%.2f): THRALL - its projectile's splat had gone "
			+ "unclaimed%s", r.amount, r.npcIndex, r.thrallProbability, outcome);
	}

	/**
	 * Undoes what a splat given to the player did, within the tick it landed: its
	 * damage dealt and hit, and, when nothing else of the player's landed on that
	 * npc this tick, the blow it booked and the burst it began. Health is left
	 * alone: the splat was real damage on the npc whoever dealt it.
	 *
	 * @return what happened to the blow, for the log line
	 */
	private String takeBackFromPlayer(PlayerSplatRecord r, int tick)
	{
		r.takenBack = true;
		boolean otherSplat = false;
		boolean otherLanded = false;
		for (PlayerSplatRecord o : playerSplatsThisTick)
		{
			if (o != r && o.npcIndex == r.npcIndex && o.done && !o.takenBack)
			{
				otherSplat = true;
				otherLanded |= o.amount > 0;
			}
		}
		// If another of the player's splats landed beside it, that one is what made the attack a hit.
		final boolean hitTaken = r.landed && !otherLanded;
		if (current != null && !current.isEnded())
		{
			current.revokeDamageDealt(r.amount, hitTaken);
			if (current.isScored())
			{
				session.revokeAttempt(r.amount, hitTaken);
			}
		}
		// Nothing else of the player's landed on that npc this tick, so there was no blow: its booking goes, and the
		// burst it began with it.
		final boolean unbooked = r.booked && !otherSplat && attackObservedTick == tick;
		if (otherSplat)
		{
			// The attack stays, one splat short of what was counted against it.
			final AttackSplats.Burst burst = bursts.get(r.npcIndex);
			if (burst != null)
			{
				burst.takeBack();
			}
		}
		else
		{
			if (unbooked)
			{
				attackObservedTick = -1;
				lastAttackSeenTick = r.previousAttackSeenTick;
			}
			burstBooked.remove(r.npcIndex);
			burstLanded.remove(r.npcIndex);
			bursts.remove(r.npcIndex);
			if (r.previousSeen == null)
			{
				lastHitsplatTick.remove(r.npcIndex);
			}
			else
			{
				lastHitsplatTick.put(r.npcIndex, r.previousSeen);
			}
		}
		return unbooked ? "; the blow it booked is withdrawn" : otherSplat ? "; the player's other splat keeps the blow" : "";
	}

	/**
	 * Recoil and vengeance whose evidence came later in the tick than their splat
	 * (T-017 Part B): the hit on the player, or vengeance's varbit, can arrive
	 * after the splat it set off. Each player's splat this tick not yet taken back
	 * is asked again, and a match is taken back from the player as reflected
	 * damage. Run at the top of the tick, after the thrall's re-judging and before
	 * the tick's blow is booked.
	 */
	private void rejudgeReflections(int tick)
	{
		for (PlayerSplatRecord r : playerSplatsThisTick)
		{
			if (!r.done || r.takenBack || r.fromFlight || !r.couldBeReflected || r.xpExact)
			{
				continue;
			}
			final Reflect.Match match = reflect.claim(tick, r.amount, this::hitsTakenOn, recoilWorn(tick));
			if (match == null)
			{
				continue;
			}
			final String outcome = takeBackFromPlayer(r, tick);
			if (current != null && !current.isEnded() && current.getTargetIndex() == r.npcIndex)
			{
				current.recordReflectDamage(r.amount);
				if (current.isScored())
				{
					session.recordReflectDamage(r.amount);
				}
			}
			debug.log("RE-JUDGED hitsplat %d on index %d (was player): REFLECT, %s%s", r.amount, r.npcIndex, match,
				outcome);
		}
	}

	/**
	 * The thrall a projectile naming no source set off from beside: the nearest
	 * within a couple of tiles of where it started, or -1. Ours are seen this way
	 * in practice, and so is anyone else's, which this is what tells apart.
	 */
	private int thrallLeftFrom(Projectile projectile)
	{
		if (client.getTopLevelWorldView() == null)
		{
			return -1;
		}
		final int reach = ThrallTracker.PROJECTILE_SOURCE_RADIUS * LOCAL_TILE_SIZE;
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || !Thralls.isThrall(npc.getId()) || npc.getLocalLocation() == null)
			{
				continue;
			}
			final int dx = Math.abs(projectile.getX1() - npc.getLocalLocation().getX());
			final int dy = Math.abs(projectile.getY1() - npc.getLocalLocation().getY());
			if (dx <= reach && dy <= reach && Math.max(dx, dy) < bestDistance)
			{
				best = npc.getIndex();
				bestDistance = Math.max(dx, dy);
			}
		}
		return best;
	}

	private int distanceFromMe(Actor actor)
	{
		final Player me = client.getLocalPlayer();
		if (me == null || me.getWorldLocation() == null || actor.getWorldLocation() == null)
		{
			return Integer.MAX_VALUE;
		}
		return me.getWorldLocation().distanceTo2D(actor.getWorldLocation());
	}

	/**
	 * Prints how the loadout was resolved, so a player hitting a wrong figure
	 * can report why it is wrong rather than only that it is. Also logs the
	 * game's own combat option data, which is what identifies a category this
	 * plugin has wrong.
	 */
	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!"loadout".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		final Fight shown = getDisplayFight();
		// The form the target wears now, not the one the fight opened on: a boss that has transformed is described as it
		// stands.
		final int targetId = current != null && !current.isEnded() ? targetLiveId
			: shown == null ? -1 : shown.getTargetId();
		// Mirrored to the log as well as the chatbox. ::loadout is the tool for settling a wrong figure, and a chat message
		// cannot be read back out of client.log afterwards - which cost a round trip the first time it was needed.
		for (String line : combatCalc.describeLoadout(targetId))
		{
			log.debug("::loadout {}", line);
			client.addChatMessage(ChatMessageType.CONSOLE, "PvM Performance", line, null);
		}
	}

	/**
	 * Writes the last few thousand log lines to a file, with the setup that
	 * produced them at the top.
	 *
	 * <p>Called from the side panel, so it runs on Swing's thread: the loadout
	 * has to be read on the client's, and the file written on neither.
	 */
	void saveDebugLog()
	{
		clientThread.invoke(() ->
		{
			final List<String> header = new ArrayList<>();
			final Fight shown = getDisplayFight();
			final int targetId = current != null && !current.isEnded() ? targetLiveId
				: shown == null ? -1 : shown.getTargetId();
			// What used to be settings, now worked out from the game (T-015).
			header.add(combatCalc.describeWorkedOut(targetId));
			header.add(String.format("fight timeout %d ticks, file logging %b",
				config.fightTimeoutTicks(), debug.isOn()));
			header.addAll(combatCalc.describeLoadout(targetId));
			executor.execute(() ->
			{
				final Filepath written = debug.saveRecent(header);
				clientThread.invoke(() -> client.addChatMessage(ChatMessageType.CONSOLE, "PvM Performance",
					written == null ? "Nothing to save yet - fight something first."
						: "Saved the recent debug log to " + written.getFileName(), null));
			});
		});
	}

	// Catches a spell cast by hand onto an NPC. The autocast varbit only knows about autocasting, so a spell clicked while
	// holding a powered staff would otherwise be reported as the staff's own attack.
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		noteConsumed(event);
		noteDartsLoaded(event);
		// Any click on an NPC, whatever it ordered: attacking it, casting on it, or firing at it. What matters is that I
		// aimed something at that NPC.
		final NPC clicked = event.getMenuEntry().getNpc();
		if (clicked != null)
		{
			clickedNpcIndex = clicked.getIndex();
			clickedNpcTick = client.getTickCount();
		}
		if (event.getMenuAction() != MenuAction.WIDGET_TARGET_ON_NPC || !client.isWidgetSelected())
		{
			return;
		}
		final Widget selected = client.getSelectedWidget();
		if (selected == null || selected.getName() == null)
		{
			return;
		}
		final Spell spell = Spell.forDisplayName(Text.removeTags(selected.getName()));
		if (spell != null)
		{
			combatCalc.recordManualCast(spell);
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		// Magic and ranged fire a projectile before impact. Recognising mine lets a fight begin on my first cast even if it
		// splashes.
		final Projectile projectile = event.getProjectile();
		final Player me = client.getLocalPlayer();
		final Actor target = projectile.getTargetActor();
		noteTargetProjectile(projectile, me, target);
		if (me == null || !(target instanceof NPC))
		{
			return;
		}
		if (countedProjectiles.put(projectileKey(projectile, (NPC) target), client.getTickCount()) != null)
		{
			return; // this projectile was already counted on an earlier frame
		}
		final Actor source = projectile.getSourceActor();
		if ((source instanceof NPC && Thralls.isThrall(((NPC) source).getId()))
			|| (source == null && Thralls.PROJECTILES.contains(projectile.getId())))
		{
			final int lands = landingTick(projectile);
			debug.log("THRALL projectile %d from %s at npc %d (index %d), lands tick %d",
				projectile.getId(), source == null ? "no source" : "index " + ((NPC) source).getIndex(),
				((NPC) target).getId(), ((NPC) target).getIndex(), lands);
			// Ours name no source in practice, so the thrall it left from beside decides. Either way it is nobody's attack of
			// mine.
			thralls.onThrallProjectile(source instanceof NPC ? ((NPC) source).getIndex() : thrallLeftFrom(projectile),
				((NPC) target).getIndex(), client.getTickCount());
			return;
		}
		if (!isProjectileMine(projectile, me, target))
		{
			return;
		}
		// One attack a tick, because a player cannot throw two. The booking was already capped on a tick stamp; what was not
		// is everything this sets up - the figures the attack is scored against and the pending hit that says where its
		// hitsplat came from - which a neighbour's projectile could overwrite. First one wins: there is nothing to choose
		// between them, and taking the first at least keeps the figures and the hit consistent with each other.
		if (acceptedProjectileTick == client.getTickCount())
		{
			return;
		}
		acceptedProjectileTick = client.getTickCount();

		final NPC npc = (NPC) target;
		if (EncounterGroup.isIgnored(npc.getId()))
		{
			return;
		}
		final long now = System.currentTimeMillis();
		if (current == null || current.isEnded() || current.getTargetIndex() != npc.getIndex())
		{
			if (!isFightable(npc))
			{
				return;
			}
			startFight(npc, now);
		}
		final int lands = landingTick(projectile);
		pendingMineHits.computeIfAbsent(npc.getIndex(), k -> new ArrayList<>())
			.add(attackThrownOn(startTick(projectile), lands, npc.getId()));
		debug.log("projectile %d taken as mine, aimed at npc %d (index %d), lands tick %d",
			projectile.getId(), npc.getId(), npc.getIndex(), lands);
		recordAttackObserved(true, npc.getId());
		// Not always the tick the event surfaces on: the start cycle says when the projectile was fired, which for a slow one
		// can be a tick earlier.
		attackOriginTick = startTick(projectile);
	}

	// Whether a projectile left a tile the player was standing on recently.


	// Close enough to have been fired by me. Not exact, because running covers two tiles a tick while the history records
	// one, so the tile an attack was thrown from is often never in it.


	/**
	 * For the TAKEN line (T-018): a projectile from the fight's target, wherever it is aimed, or one aimed at me from
	 * anything else. Seen every frame; TargetActions keeps each once.
	 */
	private void noteTargetProjectile(Projectile projectile, Player me, Actor target)
	{
		if (me == null || current == null || current.isEnded())
		{
			return;
		}
		final Actor source = projectile.getSourceActor();
		final boolean fromTarget = source instanceof NPC && ((NPC) source).getIndex() == current.getTargetIndex();
		if (!fromTarget && target != me)
		{
			return;
		}
		final String from = fromTarget ? "" : source instanceof NPC
			? "npc " + ((NPC) source).getId() + " #" + ((NPC) source).getIndex()
			: source == null ? "no source" : "a player";
		final String at = target == me ? "me" : target instanceof NPC ? "npc #" + ((NPC) target).getIndex()
			: target == null ? "a tile" : "a player";
		targetSeen.noteProjectile(client.getTickCount(), current.getTargetIndex(), projectile.getId(),
			projectile.getStartCycle(), from, at, landingTick(projectile));
	}

	/**
	 * Identifies one cast: two projectiles sharing a start cycle, an id and a
	 * target are the same cast seen on a later frame.
	 */
	private static long projectileKey(Projectile projectile, NPC target)
	{
		return ((long) projectile.getStartCycle() << 32)
			^ ((long) projectile.getId() << 16)
			^ target.getIndex();
	}

	// Whether this projectile came from me. It names its own source actor, which settles it outright and cannot confuse me
	// with another player on my tile.
	/**
	 * Whether a projectile set off too soon after my last attack to have been
	 * mine. Static so the arithmetic stays under test without a Client.
	 */
	static boolean tooSoonToBeMine(int firedTick, int lastAttackTick, int fastestSpeed)
	{
		return lastAttackTick != Integer.MIN_VALUE && firedTick < lastAttackTick + fastestSpeed;
	}

	/**
	 * The shortest attack speed seen in the last few ticks, for deciding whether a
	 * projectile could have been mine.
	 *
	 * <p>The FASTEST rather than the current one, and deliberately. The live
	 * reading is unusable at exactly the moment this is asked: ProjectileMoved
	 * fires earlier in the frame than the GameTick that records the speed, so the
	 * current tick has no entry yet, and mid-switch the weapon is not readable at
	 * all so it defaults to four. A two tick blowpipe shot was being judged
	 * against that four and rejected as too early to be mine, while its hitsplat
	 * still counted damage - a hit that added damage and no expectation.
	 *
	 * <p>The two errors are not equal. A wrongly REJECTED attack disappears from
	 * the expected side and nothing says so; a wrongly accepted one is a
	 * neighbour's projectile that already had to leave a tile I stood on, at a
	 * target I am fighting, while I was animating. This guard is the weakest of
	 * the four and should behave like it.
	 */
	private int fastestSpeedSeen()
	{
		int fastest = combatCalc.attackSpeedTicks();
		for (int speed : speedByTick.values())
		{
			if (speed > 0 && speed < fastest)
			{
				fastest = speed;
			}
		}
		return Math.max(1, fastest);
	}

	private boolean isProjectileMine(Projectile projectile, Player me, Actor target)
	{
		final Actor source = projectile.getSourceActor();
		if (source != null)
		{
			return source == me;
		}
		// The firing tile is the only thing that identifies a caster, so it is required rather than consulted last: in
		// company, everyone else is casting at what I am fighting too. Compared in scene coordinates, which an instance does
		// not translate, against where I stood on the tick it set off - a projectile is counted once, on first sight.
		if (!firedFromWhereIWas(projectile, me))
		{
			return false;
		}
		// And I have to have been able to fire it. Two players stand on one tile as a matter of course, so position alone
		// cannot separate them - but a weapon on cooldown cannot have thrown anything.
		//
		// Judged on the tick the projectile SET OFF, and against the fastest speed seen lately rather than the live one - see
		// fastestSpeedSeen for why the live reading is unusable here.
		final int firedTick = startTick(projectile);
		if (tooSoonToBeMine(firedTick, lastAttackSeenTick, fastestSpeedSeen()))
		{
			return false;
		}
		// There is deliberately NO animation check here. There was one - a player with nothing playing did not throw this -
		// and it had to go: RuneLite delivers ProjectileMoved BEFORE AnimationChanged within a tick, so on the first attack
		// of a fight, or the first after a switch, getAnimation() is still IDLE when this is asked. Traced at six ticks stale
		// on a shot that was plainly mine, whose 24 damage counted against no expectation. No grace window fixes that: the
		// evidence does not exist yet at the moment it is needed, and widening the window only delays the same rejection.
		//
		// The three checks above carry the work: the projectile left a tile I stood on, my weapon was off cooldown when it
		// set off, and it is aimed at what I am fighting. Do not add a fourth that is unavailable exactly when the first
		// attack needs it. And aimed at something I am engaged with. Held loosely on purpose - any one of the three will do,
		// because getInteracting lapses between a cast leaving and its projectile appearing. The check above does the work.
		final NPC aimedAt = (NPC) target;
		final boolean engaged = (clickedNpcIndex == aimedAt.getIndex()
				&& client.getTickCount() - clickedNpcTick <= CLICK_ATTRIBUTION_TICKS)
			|| me.getInteracting() == target
			|| (current != null && !current.isEnded()
				&& current.getTargetIndex() == aimedAt.getIndex());
		return engaged;
	}

	// Whether the projectile set off from where I stood when it did: the start cycle says which tick that was, so one
	// still in the air after several ticks of running is judged against the right position.
	private boolean firedFromWhereIWas(Projectile projectile, Player me)
	{
		final int ticksAgo = Math.max(0,
			(client.getGameCycle() - projectile.getStartCycle()) / CYCLES_PER_TICK);
		final LocalPoint then = recentTiles.get(client.getTickCount() - ticksAgo);
		final LocalPoint here = me.getLocalLocation();
		if (then != null
			&& Math.abs(projectile.getX1() - then.getX()) <= TILE_SLACK * LOCAL_TILE_SIZE
			&& Math.abs(projectile.getY1() - then.getY()) <= TILE_SLACK * LOCAL_TILE_SIZE)
		{
			return true;
		}
		if (here != null
			&& Math.abs(projectile.getX1() - here.getX()) <= TILE_SLACK * LOCAL_TILE_SIZE
			&& Math.abs(projectile.getY1() - here.getY()) <= TILE_SLACK * LOCAL_TILE_SIZE)
		{
			return true;
		}
		return false;
	}

	/**
	 * Watches for what Olm's phase is made of. Each special leaves something of
	 * its own in the scene, and his head spawning starts a phase.
	 */
	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		final int id = event.getGameObject().getId();
		if (OlmPhase.isHeadObject(id))
		{
			// A new phase begins with nothing known about it yet.
			olmPhase = null;
			return;
		}
		final OlmPhase phase = OlmPhase.forObject(id);
		if (phase != null)
		{
			olmPhase = phase;
		}
	}

	/**
	 * A hitpoints experience drop, which is the one thing that says an attack of
	 * mine dealt damage on the tick it went out.
	 */
	@Subscribe
	public void onFakeXpDrop(FakeXpDrop event)
	{
		if (XpLedger.indexOf(event.getSkill()) >= 0 && event.getXp() > 0)
		{
			// XP TRACE (issue #1): with the hitsplat lines around it, this says whether a thrall's damage pays any
			// experience, and on which tick a drop lands relative to the hitsplat it pays for.
			debug.log("xp drop %s +%d (fake, 200m)", event.getSkill(), event.getXp());
			xpLedger.add(event.getSkill(), event.getXp());
		}
		if (event.getSkill() == Skill.HITPOINTS && event.getXp() > 0)
		{
			noteDamageDealt();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		final int skill = XpLedger.indexOf(event.getSkill());
		if (skill >= 0)
		{
			final int before = lastSkillXp[skill];
			lastSkillXp[skill] = event.getXp();
			// Only a rise, and only once a first reading exists: a level change fires this too.
			if (before > 0 && event.getXp() > before && event.getSkill() != Skill.HITPOINTS)
			{
				debug.log("xp drop %s +%d", event.getSkill(), event.getXp() - before);
				xpLedger.add(event.getSkill(), event.getXp() - before);
			}
		}
		if (event.getSkill() != Skill.HITPOINTS)
		{
			return;
		}
		// Only a rise in experience counts: this event also fires for a level change, and hitpoints has one every time the
		// player regenerates or eats.
		final int was = hitpointsXp;
		hitpointsXp = event.getXp();
		if (was > 0 && hitpointsXp > was)
		{
			debug.log("xp drop HITPOINTS +%d", hitpointsXp - was);
			xpLedger.add(Skill.HITPOINTS, hitpointsXp - was);
			noteDamageDealt();
		}
	}

	/**
	 * Whether the attack that went out on this tick dealt damage.
	 */
	private boolean dealtDamageOn(int attackTick)
	{
		return damageDealtTicks.contains(attackTick)
			|| damageDealtTicks.contains(attackTick - 1)
			|| damageDealtTicks.contains(attackTick + 1);
	}

	private void noteDamageDealt()
	{
		damageDealtTicks.add(client.getTickCount());
		damageDealtTicks.removeIf(t -> client.getTickCount() - t > PRAYER_HISTORY_TICKS);
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		// A splash produces no hitsplat, so this is what sees a missed cast - but only the fast route: it needs the spotanim
		// id to be right and the splash to land on the NPC the fight is open on, neither of which holds for an area spell.
		// resolveCasts answers the same question without either.
		if (current == null || current.isEnded())
		{
			return;
		}
		final Actor actor = event.getActor();
		// gameval's name for the splash graphic shown when a spell misses.
		if (!(actor instanceof NPC) || !actor.hasSpotAnim(SpotanimID.FAILEDSPELL_IMPACT))
		{
			return;
		}
		final int index = ((NPC) actor).getIndex();
		if (current.getTargetIndex() != index)
		{
			return;
		}
		// A splash names no caster, so ordinarily it counts as mine only when it resolves a projectile I fired. The ancient
		// area spells have none, and no hitsplat either, so for those this graphic is the only evidence the cast happened -
		// which is why the attack itself is recorded here too.
		final boolean noProjectile = combatCalc.castLandsWithoutProjectile();
		if (!noProjectile && consumePending(index) == null)
		{
			return;
		}
		recordSplash(index, System.currentTimeMillis());
	}

	/**
	 * A cast of mine that missed, however it was found out. The attack itself is
	 * not booked here - a projectile-less cast is booked from the animation, so
	 * booking it again would double it.
	 */
	private void recordSplash(int index, long now)
	{
		// Nothing is left waiting on this NPC, whichever route got here first: both can fire for one cast, and a splash
		// counted twice would leave attempts running ahead of attacks made.
		castsAwaiting.removeIf(cast -> cast.npcIndex == index);
		resolvePendingSample(index);
		if (combatCalc.usesConflictionGauntlets())
		{
			combatCalc.noteMagicResolved(index, true);
		}
		current.recordSplash(now);
		// Gated as a landed hit is: an unscored NPC spends the tick but contributes no damage, accuracy or efficiency.
		if (current.isScored())
		{
			session.recordAttempt(0, false, now);
		}
	}

	/**
	 * Judges the casts whose damage should have landed by now.
	 */
	private void resolveCasts(long now)
	{
		final Iterator<PendingCast> waiting = castsAwaiting.iterator();
		while (waiting.hasNext())
		{
			final PendingCast cast = waiting.next();
			if (client.getTickCount() < cast.resolveTick)
			{
				continue;
			}
			waiting.remove();
			if (cast.connected || current == null || current.isEnded())
			{
				continue;
			}
			splashed(cast, now);
		}
	}

	/** A cast of mine that reached the enemy it was aimed at and did nothing. */
	private void splashed(PendingCast cast, long now)
	{
		if (combatCalc.usesConflictionGauntlets())
		{
			combatCalc.noteMagicResolved(cast.npcIndex, true);
		}
		recordSplash(cast.npcIndex, now);
	}

	/**
	 * Recomputes the tick's expected figures. Everything that reads them - the
	 * overlay, the attack-tick sample, ::loadout - takes this one snapshot, so
	 * they cannot disagree within a tick.
	 */
	private void refreshExpected()
	{
		final Fight shown = getDisplayFight();
		specialAttack = combatCalc.specialAttack();
		attackName = combatCalc.attackName();
		// for the same reason the prayer is: booking happens a tick or two later, and a trident attack judged at its booking
		// was judged against the whip switched to by then. Cheap: the search is held until the weapon, style, target or
		// carried items change.
		// The id the shown fight's target wears now, not the one the fight opened on: a boss's phases are different stats.
		final int shownId = shown == null ? -1 : targetIdOn(shown, client.getTickCount());
		switchedByTick.put(client.getTickCount(), combatCalc.switchCheck(shownId));
		if (client.getTickCount() % 5 == 0)
		{
			switchedByTick.keySet().removeIf(t -> client.getTickCount() - t > SWITCH_HISTORY_TICKS);
		}
		// A sample whose attack should long since have landed is dropped, so a resolve that never comes cannot wait for the
		// next fight's hitsplat.
		pendingSamples.removeIf(sample -> client.getTickCount() - sample.tick > PENDING_SAMPLE_TICKS);
		// The speed of the weapon that will throw an attack on this tick. Asked at the booking it answers for whatever is
		// held by then, and worse: the combat tab lags a swap by a tick, so rapid would be read off the old style and applied
		// to the new weapon's speed.
		speedByTick.put(client.getTickCount(), combatCalc.attackSpeedTicks());
		weaponByTick.put(client.getTickCount(), combatCalc.equippedWeaponId());
		ringByTick.put(client.getTickCount(), wornRingId());
		if (combatCalc.isMeleeEquipped())
		{
			lastMeleeTick = client.getTickCount();
		}
		if (client.getTickCount() % 5 == 0)
		{
			speedByTick.keySet().removeIf(t -> client.getTickCount() - t > SWITCH_HISTORY_TICKS);
			weaponByTick.keySet().removeIf(t -> client.getTickCount() - t > SWITCH_HISTORY_TICKS);
			ringByTick.keySet().removeIf(t -> client.getTickCount() - t > SWITCH_HISTORY_TICKS);
		}
		if (shown != null)
		{
			combatCalc.noteTargetDamageTaken(damageByIndex.getOrDefault(shown.getTargetIndex(), 0));
			// Pass the target so salve, dragon hunter and the rest can apply.
			expectedMaxHit = combatCalc.maxHit(shownId);
			expectedAccuracy = combatCalc.hitChance(shownId);
			expectedAverageHit = combatCalc.averageHit(shownId);
			expectedDps = combatCalc.expectedDps(shownId);
			expectedSpecMaxHit = combatCalc.specialAttackMaxHit(shownId);
			expectedSpecAccuracy = combatCalc.specialAttackLandChance(shownId);
			expectedSpecAverageHit = combatCalc.specialAttackAverageHit(shownId);
			expectedForNpcId = shownId;
			final double[] row = new double[EXP_WIDTH];
			row[EXP_MAX] = expectedMaxHit;
			row[EXP_ACCURACY] = expectedAccuracy;
			row[EXP_AVERAGE] = expectedAverageHit;
			row[EXP_NPC_ID] = expectedForNpcId;
			row[EXP_SPEC_MAX] = expectedSpecMaxHit;
			row[EXP_SPEC_ACCURACY] = expectedSpecAccuracy;
			row[EXP_SPEC_AVERAGE] = expectedSpecAverageHit;
			expectedByTick.put(client.getTickCount(), row);
			expectedByTick.keySet().removeIf(t -> client.getTickCount() - t > RECENT_TILES);
			// Once per target, and again whenever the version in use changes or the defence has moved (Vardorvis's follows his
			// hitpoints), so a log shows the figures the attacks were judged by as they changed. Not gated on the debug
			// setting: the panel's saved window keeps every line whether or not the file is being written.
			final MonsterStatsProvider.MonsterStats judgedBy = monsterStats.get(expectedForNpcId);
			final String version = judgedBy == null ? null : judgedBy.getVersion();
			final int defence = judgedBy == null ? Integer.MIN_VALUE : judgedBy.getDefenceLevel();
			if (expectedForNpcId != debuggedExpectedId
				|| !java.util.Objects.equals(version, debuggedExpectedVersion)
				|| Math.abs(defence - debuggedExpectedDefence) >= EXPECTED_LOG_DEFENCE_STEP)
			{
				debuggedExpectedId = expectedForNpcId;
				debuggedExpectedVersion = version;
				debuggedExpectedDefence = defence;
				// An accuracy of -1 means the monster data had nothing for this id: without the target's defence there is no
				// hit chance, and without a hit chance there is no expected damage and no expected dps. That is what an
				// overlay showing a max hit and nothing under it is saying.
				debug.log("expected vs %s (npc %d%s): max %d, acc %.3f, avg %.2f, dps %.2f%s%s",
					shown.getTargetName(), expectedForNpcId,
					judgedBy == null ? "" : String.format(", '%s' def %d, %d hp left (%s)", version, defence,
						health.remaining(shown.getTargetIndex(), client.getTickCount()),
						health.source(shown.getTargetIndex(), client.getTickCount())),
					expectedMaxHit, expectedAccuracy, expectedAverageHit,
					expectedDps, expectedAccuracy < 0 ? "  <- NO MONSTER STATS FOR THIS ID" : "",
					// A guardian reads zero for everything unless a pickaxe is in hand, which is the mechanic and not a fault.
					EncounterGroup.isGuardian(expectedForNpcId)
						? "  (guardian: only a pickaxe does damage here)" : "");
			}
		}
		else
		{
			expectedMaxHit = combatCalc.maxHit(-1);
			expectedAccuracy = -1;
			expectedAverageHit = -1;
			expectedDps = -1;
			expectedSpecMaxHit = combatCalc.specialAttackMaxHit(-1);
			expectedSpecAccuracy = -1;
			expectedSpecAverageHit = -1;
			expectedForNpcId = -1;
		}
	}

	/**
	 * Whether the target is dead but still standing. A fight ends on the
	 * despawn, and the death animation runs for several ticks first - long enough
	 * for the next attack to come due against something unattackable. Those ticks
	 * are neither lost nor spent: no attack was possible.
	 */
	private boolean targetIsDying()
	{
		return targetNpc != null && targetNpc.isDead();
	}

	/**
	 * Ticks between an area spell going out and its damage landing. The wiki's
	 * Hit delay formula plus one, measured: the wiki says when the SERVER
	 * applies the hit, this needs when the CLIENT sees the hitsplat. Distance is
	 * Chebyshev to the NPC's south-west tile, barrage's documented exception.
	 */
	static int magicHitDelay(int distance)
	{
		return 2 + (1 + Math.max(0, distance)) / 3;
	}

	static boolean attackOverdue(int now, int dueTick, int bookingLag)
	{
		return now >= dueTick + bookingLag;
	}

	/**
	 * How long a booking might still take: the longer of what the last attack
	 * needs and what the weapon in hand would need.
	 */
	static int pendingBookingLag(boolean lastAttackWasProjectile, boolean meleeEquippedNow)
	{
		return lastAttackWasProjectile || !meleeEquippedNow ? PROJECTILE_BOOKING_LAG : MELEE_BOOKING_LAG;
	}

	private void record(boolean prayed, boolean switched, double actual, double ideal, double boostCost,
		double switchCost)
	{
		if (current != null && !current.isEnded())
		{
			current.recordAttackResolved(prayed, switched, actual, ideal, boostCost, switchCost);
		}
		session.recordAttackResolved(prayed, switched, actual, ideal, boostCost, switchCost);
	}

	/**
	 * Whether the special that fired on {@code specFiredTick} is the one that threw
	 * the attack which left on {@code attackOriginTick}.
	 *
	 * <p>NOT an equality. The energy varp does not fall on the tick the attack goes
	 * out: a traced burning claws special put the drop on tick 45 and the hitsplat
	 * on 46, so asking for equality booked it against the ordinary expectation and
	 * threw the special's own figure away. DefenceDrain has carried a window over
	 * the same varp all along, for the same reason.
	 *
	 * <p>One tick, and no wider. The fastest weapon here attacks every two ticks,
	 * so a window of two would start claiming the NEXT attack as a special as well.
	 */
	static boolean specialWentOutFor(int attackOriginTick, int specFiredTick)
	{
		if (specFiredTick == Integer.MIN_VALUE || attackOriginTick < specFiredTick)
		{
			return false;
		}
		return attackOriginTick - specFiredTick <= SPEC_BOOKING_WINDOW_TICKS;
	}

	private void sampleExpected(Fight fight)
	{
		// The id the target wore when the attack left, which the tick cache's rows are keyed by too (T-020).
		final int targetId = targetIdOn(fight, attackOriginTick);
		int maxHit = expectedMaxHit;
		double accuracy = expectedAccuracy;
		double averageHit = expectedAverageHit;
		int specMaxHit = expectedSpecMaxHit;
		double specLandChance = expectedSpecAccuracy;
		double specAverageHit = expectedSpecAverageHit;
		// Prefer the figures from the tick the attack left on. A slow projectile surfaces a tick late, and scoring it with
		// the loadout held by then credited a shadow's attack to the whip switched to mid-flight.
		final double[] atOrigin = expectedByTick.get(attackOriginTick);
		// A PROJECTILE is scored from the tick it set off, not from the live reading taken when its event surfaced - by then
		// the player may have switched, and the observed figures are the new weapon's. Alternating a blowpipe and a crossbow
		// scored every shot with the OTHER one's average, which is what this ordering is for and what it was not doing: the
		// observed branch below was consulted first and always matched.
		//
		// Melee is left alone. It lands on the tick it is thrown, so the figures taken at its hitsplat are already the right
		// ones.
		final boolean originIsAuthoritative = attackObservedFromProjectile
			&& atOrigin != null && (int) atOrigin[EXP_NPC_ID] == targetId
			&& atOrigin[EXP_ACCURACY] >= 0;
		if (originIsAuthoritative)
		{
			maxHit = (int) atOrigin[EXP_MAX];
			accuracy = atOrigin[EXP_ACCURACY];
			averageHit = atOrigin[EXP_AVERAGE];
			specMaxHit = (int) atOrigin[EXP_SPEC_MAX];
			specLandChance = atOrigin[EXP_SPEC_ACCURACY];
			specAverageHit = atOrigin[EXP_SPEC_AVERAGE];
		}
		else if (attackObservedNpcId == targetId && attackObservedAccuracy >= 0)
		{
			maxHit = attackObservedMaxHit;
			accuracy = attackObservedAccuracy;
			averageHit = attackObservedAverageHit;
			specMaxHit = attackObservedSpecMaxHit;
			specLandChance = attackObservedSpecAccuracy;
			specAverageHit = attackObservedSpecAverageHit;
		}
		else if (atOrigin != null && (int) atOrigin[EXP_NPC_ID] == targetId)
		{
			maxHit = (int) atOrigin[EXP_MAX];
			accuracy = atOrigin[EXP_ACCURACY];
			averageHit = atOrigin[EXP_AVERAGE];
			specMaxHit = (int) atOrigin[EXP_SPEC_MAX];
			specLandChance = atOrigin[EXP_SPEC_ACCURACY];
			specAverageHit = atOrigin[EXP_SPEC_AVERAGE];
		}
		else if (expectedForNpcId != targetId)
		{
			// The opening attack of a fight, whose figures the tick cache cannot hold: the fight was created by the very event
			// being sampled. Asking for them now is a memo lookup on the tick already being served.
			maxHit = combatCalc.maxHit(targetId);
			accuracy = combatCalc.hitChance(targetId);
			averageHit = combatCalc.averageHit(targetId);
			specMaxHit = combatCalc.specialAttackMaxHit(targetId);
			specLandChance = combatCalc.specialAttackLandChance(targetId);
			specAverageHit = combatCalc.specialAttackAverageHit(targetId);
		}
		// Held rather than added, unless the attack has already resolved: an expectation only belongs beside a measured
		// figure if the attack got the chance to deal it, and a cast still in the air when someone else lands the kill reads
		// as underperformance. A melee blow is booked by its own hitsplat, so it has already resolved and goes straight in.
		//
		// One attack can be several rolls, and the measured side counts the ATTACK: three scythe hitsplats land on one tick
		// and book as one landed attack. So the expectation is the chance at least one roll connected, not the sum of them. A
		// special is not the attack the ordinary figures describe: it has its own accuracy, its own per-hitsplat maxima and,
		// on Verzik's first phase, its own exemption from the cap. Decided HERE rather than when the figures were sampled
		// because the energy varp can arrive after that tick's onGameTick has already run, so whether the attack was a
		// special is only reliably known by the time it is booked.
		final boolean special = specialWentOutFor(attackOriginTick, specFiredTick)
			&& specAverageHit >= 0;
		double expectedChances =
			CombatCalc.landChance(accuracy, combatCalc.hitsPerAttack(targetId));
		if (special)
		{
			// Spent: one activation books one attack, so the next attack inside the window cannot claim it too.
			specFiredTick = Integer.MIN_VALUE;
			maxHit = specMaxHit;
			averageHit = specAverageHit;
			expectedChances = specLandChance;
		}
		debug.log("  scored as %s: max %d, chances %.2f, avg %.2f",
			special ? "a SPECIAL" : "an ordinary attack", maxHit, expectedChances, averageHit);
		if (attackObservedLag == MELEE_BOOKING_LAG)
		{
			fight.recordExpected(maxHit, expectedChances, averageHit);
			session.recordExpected(expectedChances, averageHit);
		}
		else
		{
			pendingSamples.add(new PendingSample(
				fight.getTargetIndex(), client.getTickCount(), maxHit, expectedChances, averageHit));
		}
		// Spent: they describe one attack, not the next.
		attackObservedNpcId = -1;
		attackObservedAccuracy = -1;
		attackObservedAverageHit = -1;
	}

	/**
	 * What an attack was expected to do, held until it is known to have
	 * resolved. Only the expected damage and hits wait: the attempt, the tick
	 * and the prayer, boost and switch counters are all recorded when the
	 * attack goes out, because those describe how it was set up and that
	 * happened whatever became of it.
	 */
	private static final class PendingSample
	{
		private final int npcIndex;
		private final int tick;
		private final int maxHit;
		private final double accuracy;
		private final double averageHit;

		private PendingSample(int npcIndex, int tick, int maxHit, double accuracy, double averageHit)
		{
			this.npcIndex = npcIndex;
			this.tick = tick;
			this.maxHit = maxHit;
			this.accuracy = accuracy;
			this.averageHit = averageHit;
		}
	}

	// An attack landed on this NPC, so the oldest thing waiting on it resolved. A splash counts: it landed for nought,
	// which is a real miss.
	private void resolvePendingSample(int npcIndex)
	{
		for (int i = 0; i < pendingSamples.size(); i++)
		{
			final PendingSample sample = pendingSamples.get(i);
			if (sample.npcIndex != npcIndex)
			{
				continue;
			}
			pendingSamples.remove(i);
			if (current != null && !current.isEnded())
			{
				current.recordExpected(sample.maxHit, sample.accuracy, sample.averageHit);
			}
			session.recordExpected(sample.accuracy, sample.averageHit);
			return;
		}
	}

	/**
	 * Throws away what was expected of attacks that never landed.
	 *
	 * <p>Whether the attempt goes with it depends on WHY the fight ended, and
	 * that distinction was missing. The case this was written for is a target
	 * that DIED while a cast of ours was in the air: the attack never got its
	 * chance, so counting the expectation against the nought it dealt reads as
	 * underperformance, and leaving the attempt in the denominator reads as a
	 * miss. Both halves have to go.
	 *
	 * <p>A fight that merely ENDED is not that. The attack had its chance and
	 * the answer simply arrived after the bookkeeping moved on - an ordinary
	 * miss, in other words. Nulling those erased them from the denominator, and
	 * a denominator missing its misses gives an accuracy above 100%: a real
	 * export reads 81 hits against 80 attempts in one room, with 224 of 304
	 * attacks erased. The expectation is still dropped, since nothing resolved
	 * it, but the attempt stays.
	 */
	private void dropPendingSamples(boolean died)
	{
		if (current != null && died)
		{
			for (int i = 0; i < pendingSamples.size(); i++)
			{
				current.recordAttackNulled();
				if (current.isScored())
				{
					session.recordAttackNulled();
				}
			}
		}
		pendingSamples.clear();
	}

	/** The trip totals shown when the overlay is set to whole-trip mode. */
	SessionTotals getSession()
	{
		return session;
	}

	/** The saved fights, newest first, read-only: the overlay's " vs your average" row reads them (T-014). */
	List<Fight> getHistory()
	{
		return Collections.unmodifiableList(history);
	}

	/** Starts the trip totals over, from the side panel. */
	void resetSession()
	{
		session.reset(System.currentTimeMillis());
	}

	/**
	 * Catches the boss just killed coming back, so the wait between kills can be
	 * timed. Nothing is counted here - the tick is remembered and spent when a
	 * fight opens on that NPC.
	 */
	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		final NPC npc = event.getNpc();
		if (Thralls.isThrall(npc.getId()))
		{
			traceThrall("spawned", npc);
			thralls.onThrallSpawned(npc.getIndex(), npc.getId(), distanceFromMe(npc), client.getTickCount());
		}
		final OlmPhase phase = OlmPhase.forNpc(npc.getId());
		if (phase != null)
		{
			// The flame wall is an NPC rather than an object, so it arrives here.
			olmPhase = phase;
		}
	}

	/**
	 * THRALL TRACE (issue #1). Every thrall in view, so a log can show which one
	 * is ours, how far its attack runs ahead of its hitsplat, and - with the xp
	 * lines - whether its damage pays any experience.
	 */
	private void traceThrall(String what, NPC thrall)
	{
		// Not gated on file logging: the panel's "save recent" keeps every line whether or not it streams, and without
		// these a saved log cannot show a zombie's attacks (the 16:08 log has none).
		final Player me = client.getLocalPlayer();
		final Actor at = thrall.getInteracting();
		final int distance = me == null || me.getWorldLocation() == null || thrall.getWorldLocation() == null ? -1
			: me.getWorldLocation().distanceTo2D(thrall.getWorldLocation());
		debug.log("THRALL %s: npc %d (index %d), %d tiles from me, interacting %s, active varbit %d",
			what, thrall.getId(), thrall.getIndex(), distance,
			at == null ? "nothing" : at == me ? "ME" : at instanceof NPC ? "npc index " + ((NPC) at).getIndex() : "a player",
			client.getVarbitValue(VarbitID.ARCEUUS_RESURRECTION_ACTIVE));
	}

	/**
	 * Follows the target through a transform: a boss that changes form keeps its
	 * index and changes its id, and the new id is what says whether it can be
	 * fought at all.
	 */
	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		final NPC npc = event.getNpc();
		if (current == null || current.isEnded())
		{
			return;
		}
		if (current.getTargetIndex() == npc.getIndex())
		{
			targetLiveId = npc.getId();
			// A boss that heals a phase back starts its count again the moment it changes (T-020), not at the next tick's
			// bar reading, which an attack landing this tick would be judged before.
			health.noteId(npc.getIndex(), npc.getId(), client.getTickCount());
			// Anything of mine still in the air was nulled by the change, and a cast waiting on damage that will never arrive is
			// not a splash: left in place it resolves as one and arms the gauntlets against an enemy that never dodged anything.
			// Only the pending cast is fooled - the experience is paid whether the damage lands or is nulled.
			castsAwaiting.removeIf(cast -> cast.npcIndex == npc.getIndex());
		}
		// A boss never removed from the scene announces its defeat by changing into a beaten form. That is the kill, and it
		// beats waiting for loot: in a group there may be no drop for this player at all.
		if (EncounterGroup.isDefeated(npc.getId())
			&& EncounterGroup.sameGroup(npc.getId(), current.getTargetId()))
		{
			finalizeFight(true, System.currentTimeMillis(), "changed into its beaten form");
		}
	}

	/**
	 * A drain landed by another party member, from the special attack counter's
	 * message. It carries only what others did, so it adds to rather than
	 * replaces watching our own energy.
	 */
	@Subscribe
	public void onSpecialCounterUpdate(SpecialCounterUpdate event)
	{
		if (current != null && !current.isEnded()
			&& current.getTargetIndex() == event.getNpcIndex())
		{
			drain.onSpecialCounterUpdate(event, targetNpc);
		}
	}

	/**
	 * A cast with no projectile is only visible as the caster's own animation.
	 */
	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		// For the TAKEN line (T-018): what the fight's target started, logged beside the next splats on me.
		if (event.getActor() instanceof NPC && current != null && !current.isEnded()
			&& ((NPC) event.getActor()).getIndex() == current.getTargetIndex())
		{
			targetSeen.noteAnimation(client.getTickCount(), current.getTargetIndex(), event.getActor().getAnimation());
		}
		if (event.getActor() instanceof NPC && Thralls.isThrall(((NPC) event.getActor()).getId()))
		{
			final NPC thrall = (NPC) event.getActor();
			traceThrall("animation " + thrall.getAnimation(), thrall);
			// A thrall names no target ("interacting nothing" throughout the Vardorvis log), so the window opens on whatever
			// it hits.
			final Actor at = thrall.getInteracting();
			if (thrall.getAnimation() != IDLE_ANIMATION && !THRALL_NON_ATTACK_ANIMATIONS.contains(thrall.getAnimation()))
			{
				thralls.onThrallAttackAnimation(thrall.getIndex(), at instanceof NPC ? ((NPC) at).getIndex() : -1,
					client.getTickCount());
			}
			return;
		}
		final Player me = client.getLocalPlayer();
		if (event.getActor() == me && me.getAnimation() == AnimationID.HUMAN_SPELLCAST_RESURRECT)
		{
			// A recast replaces the thrall without the varbit ever dropping, so the cast itself marks the summon.
			thralls.onSummonCast(client.getTickCount());
		}
		if (event.getActor() != me || me.getAnimation() == IDLE_ANIMATION)
		{
			return;
		}
		if (!combatCalc.castLandsWithoutProjectile()
			|| client.getTickCount() < lastAttackSeenTick + combatCalc.attackSpeedTicks())
		{
			return;
		}
		// Any animation at all was too generous: being hit plays one and so does eating, and both booked an attack once the
		// weapon was off cooldown. So the cast animation is named - a short list, not a table, because only projectile-less
		// spells come through here and a missing id costs the misses but not the hits.
		if (!CAST_ANIMATIONS.contains(me.getAnimation()))
		{
			log.debug("TRACE cast UNKNOWN ANIM tick={} id={} spell={}",
				client.getTickCount(), me.getAnimation(), combatCalc.activeSpellName());
			return;
		}
		final NPC target = castTarget();
		if (target == null)
		{
			return;
		}
		final long now = System.currentTimeMillis();
		if (current == null || current.isEnded() || current.getTargetIndex() != target.getIndex())
		{
			if (!isFightable(target))
			{
				return;
			}
			startFight(target, now);
		}
		lastCastBookedTick = client.getTickCount();
		// Due when the damage would land: hitpoints xp arrives WITH THE DAMAGE, not at the cast. Judged on the cast tick,
		// every cast read as a splash - the gauntlets armed on hits and expected damage counted at the cast.
		final int distance = castDistance(target);
		final int due = client.getTickCount() + magicHitDelay(distance);
		castsAwaiting.add(new PendingCast(target.getIndex(), due));
		recordAttackObserved(false, target.getId(), CAST_BOOKING_LAG);
	}

	/**
	 * Chebyshev distance to the target's south-west tile, which is what these
	 * spells measure. getWorldLocation on an NPC is that tile already.
	 */
	private int castDistance(NPC target)
	{
		final Player me = client.getLocalPlayer();
		if (me == null || me.getWorldLocation() == null || target.getWorldLocation() == null)
		{
			return 0;
		}
		return Math.max(
			Math.abs(me.getWorldLocation().getX() - target.getWorldLocation().getX()),
			Math.abs(me.getWorldLocation().getY() - target.getWorldLocation().getY()));
	}

	/** A cast with no projectile, and the tick its damage should land on. */
	private static final class PendingCast
	{
		private final int npcIndex;
		private final int resolveTick;
		private boolean connected;

		PendingCast(int npcIndex, int resolveTick)
		{
			this.npcIndex = npcIndex;
			this.resolveTick = resolveTick;
		}
	}

	// What the cast is aimed at: what I am interacting with, or the fight already open, which covers the tick an autocast
	// rolls onto a new target.
	private NPC castTarget()
	{
		final Player me = client.getLocalPlayer();
		final Actor interacting = me == null ? null : me.getInteracting();
		if (interacting instanceof NPC)
		{
			return (NPC) interacting;
		}
		return current != null && !current.isEnded() ? targetNpc : null;
	}

	/** A boss that never despawns still drops loot, and that is what says it died. */
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		final NPC npc = event.getNpc();
		final long now = System.currentTimeMillis();
		if (current != null && !current.isEnded()
			&& (current.getTargetIndex() == npc.getIndex()
			|| EncounterGroup.sameGroup(npc.getId(), current.getTargetId())))
		{
			finalizeFight(true, now, "loot received");
		}
		// A room ends with the kill that closed it, so the next opens a fresh one. Without it a grouped boss kept a whole
		// trip in the room the first kill opened, and the overlay read as a running total with the trip totals off. The loot
		// drop is the one event that says a KILL happened rather than a part of the boss dying - several deaths are one
		// Hueycoatl - and no loot fires inside a raid, so Olm and the Nylocas are untouched. Ended rather than dropped, so
		// the kill stays on the overlay to read.
		if (currentEncounter != null && !currentEncounter.isEnded() && currentEncounter.holds(npc.getId()))
		{
			debug.log("LOOT from %s (npc %d, index %d): room '%s' closed with %d fights", npc.getName(), npc.getId(),
				npc.getIndex(), currentEncounter.getName(), currentEncounter.getFights().size());
			currentEncounter.end(now);
			// The export rebuilds its rooms from the fights alone, so the fight the kill closed on carries the boundary into the
			// file.
			final List<Fight> held = currentEncounter.getFights();
			if (!held.isEmpty())
			{
				held.get(held.size() - 1).closeRoom();
			}
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		final NPC npc = event.getNpc();
		if (Thralls.isThrall(npc.getId()))
		{
			thralls.onThrallDespawned(npc.getIndex(), client.getTickCount());
		}
		// The drain dies with the NPC, as its stats do.
		drain.forget(npc.getIndex());
		// So does what it had left - the server can hand its index to the next spawn. The fight's own target is kept until
		// the fight is closed below, which reconciles against it.
		if (current == null || current.isEnded() || current.getTargetIndex() != npc.getIndex())
		{
			health.forget(npc.getIndex());
		}
		if (current == null || current.isEnded())
		{
			return;
		}
		if (current.getTargetIndex() == npc.getIndex())
		{
			finalizeFight(npc.isDead(), System.currentTimeMillis(), npc.isDead() ? "target died" : "target despawned");
			return;
		}
		// A boss made of several NPCs does not die on the one being hit: the Hueycoatl is killed at its head while the fight
		// is open on a body segment, so the death despawns an index the fight never heard of. Only a death, and only within
		// the group, so an add dying beside a boss cannot claim it - nor one of the group's own adds: an unscored npc is
		// never the boss. With the Abyssal Sire's spawns in its group, every spawn that died would otherwise have ended the
		// fight on the Sire as a kill (T-020).
		if (npc.isDead() && EncounterGroup.sameGroup(npc.getId(), current.getTargetId())
			&& !EncounterGroup.isUnscored(npc.getId()))
		{
			finalizeFight(true, System.currentTimeMillis(), "another part of the same boss died");
		}
	}

	// Advances the attack cooldown by a tick and books the tick as an attack, as wasted, or as still on cooldown.
	private void trackAttackCooldown()
	{
		final boolean attacked = attackObservedTick == client.getTickCount();
		attackObservedTick = -1;

		if (current == null || current.isEnded())
		{
			attackDueTick = Integer.MIN_VALUE;
			lastAttackFromProjectile = false;
			// Nothing pending to hold back, so nothing eaten out of a fight carries into the next one.
			foodDelay.reset();
			return;
		}
		if (attacked)
		{
			// Before anything reads the style: which event proved this attack decides whether a queued cast can be what went
			// out.
			combatCalc.noteAttackKind(attackObservedFromProjectile);
			// The tick the attack went out on, which is the lag of whatever proved it and not a fixed offset. The prayer is read
			// back from there, never live: read live it counts one switched on after the swing. The potion is taken as it stood
			// when the attack went out, since drinking after it cannot have boosted it.
			final int attackTick = client.getTickCount() - attackObservedLag;
			// And the fight it was thrown IN, which is not always the one open now. A booking arrives a tick or two after the
			// attack, and a kill in between opens the next fight first - so the last swings of one kill were being counted
			// against the next, which began life reading two attacks and two switches before a blow had been struck.
			final Fight attacker = fightAsOf(attackTick);
			if (attacker == null)
			{
				// Thrown during a fight that has since been discarded. The cooldown still has to advance or every tick after
				// this reads as lost, and the eats before it were spent waiting for it.
				logEats(foodDelay.attackThrown(attackTick, attackDueTick));
				attackDueTick = attackTick + combatCalc.attackSpeedTicks();
				lastAttackFromProjectile = attackObservedFromProjectile;
				combatCalc.noteAttackKind(true);
				debug.log("ATTACK thrown tick %d belongs to a fight that is gone: counted nowhere", attackTick);
				return;
			}
			// Read on the attack tick: this is when the prayers and boosts are the ones the attack actually rolled with. And
			// the target's id then, which is the phase the attack was rolled against (T-020).
			final int targetId = targetIdOn(attacker, attackTick);
			final boolean prayed = Boolean.TRUE.equals(prayerUpByTick.get(attackTick));
			final boolean potted = attackObservedPotted;
			final double boostShare = attackObservedBoostShare;
			final boolean drained = attackObservedDrained;
			// Worked out both ways while the loadout and boost are the ones that threw the attack. Which applies is decided by
			// the prayer, and for a projectile that is not known until it lands.
			final double ifPrayed = combatCalc.actualAverageHit(targetId, true);
			final double ifNot = combatCalc.actualAverageHit(targetId, false);
			// The best setup AVAILABLE, which the one actually worn is always one of - so if the search says otherwise, the
			// search is wrong and the worn one is the best known. Without this the ratio can exceed 100%, which is not a
			// thing efficiency can be: it read 107% on an ice barrage where the ideal came back at 7.31 against an actual
			// of 7.81. Clamped rather than left to show, and logged, because a clamp that keeps firing is a bug report.
			final double bestSetup = combatCalc.idealAverageHit(targetId);
			final double actualSetup = prayed ? ifPrayed : ifNot;
			final double idealSetup = Math.max(bestSetup, actualSetup);
			if (bestSetup >= 0 && actualSetup > bestSetup)
			{
				debug.log("IDEAL BELOW ACTUAL on npc %d: ideal %.2f, actual %.2f (prayed=%b) - clamped to the actual, so "
						+ "something the ideal is built from is under-reading",
					targetId, bestSetup, actualSetup);
			}
			// What the missing boost cost this attack: the same attack at full boost, prayer and gear as they were (T-010).
			final double boosted = combatCalc.boostedAverageHit(targetId, prayed);
			final double boostCost = actualSetup >= 0 && boosted >= 0 ? Math.max(0, boosted - actualSetup) : 0;
			// Read from the tick the attack went out on, never live: by now the gear may be someone else's. Absent means never
			// sampled, which is not evidence of a missed switch, so it reads as clean.
			final CombatCalc.SwitchCheck switchCheck = switchedByTick.get(attackTick);
			final boolean switched = switchCheck == null || !switchCheck.isMissed();
			final double switchCost = switchCheck == null ? 0 : switchCheck.cost();
			if (!switched && attacker.isScored())
			{
				debug.log("SWITCH MISSED on attack thrown tick %d: %s", attackTick, switchCheck.describe());
			}
			// Whether this attack dealt damage, read from the tick it went out, which is where the gauntlets are armed or spent:
			// every style passes through this one place with its attack tick worked out.
			if (combatCalc.usesConflictionGauntlets())
			{
				combatCalc.noteMagicResolved(attacker.getTargetIndex(), !dealtDamageOn(attackTick));
			}
			// The pause an eat caused is over the moment an attack goes out. Eats on or before this attack's tick were spent
			// waiting for it; one after it already belongs to the next cooldown, so it is kept. Settled against the due tick
			// this attack was waiting on, before it moves.
			logEats(foodDelay.attackThrown(attackTick, attackDueTick));
			attacker.recordAttackMade(potted, boostShare, drained);
			// The room remembers it too. From here every fight under it counts its ticks from the first one, rather than each
			// waiting to be attacked in its own right.
			if (currentEncounter != null && !currentEncounter.isEnded())
			{
				currentEncounter.noteAttack();
			}
			if (attacker.isScored())
			{
				session.recordAttackMade(potted, boostShare, drained);
				// Decided here, at the attack. The damage this is measured against was worked out on this tick from this loadout,
				// so the prayer has to be the one that went with it - read at the hitsplat it is measured at the LANDING, ticks
				// after the server scored the attack.
				record(prayed, switched, actualSetup, idealSetup, boostCost, switchCost);
				// Sampled on the tick the attack went out, so the figures describe the loadout that threw it and one attack takes
				// one sample however it ends.
				sampleExpected(attacker);
			}
			else
			{
				session.recordTickSpent();
			}
			// Last of all: everything above describes the attack that just went out, the cooldown included. Taken from the tick
			// the attack went out on and with the speed in force then - this is the booking tick, which trails the attack, so
			// reading the speed here reads it off whatever is held now.
			final Integer threwAt = speedByTick.get(attackTick);
			final int speed = threwAt != null ? threwAt : combatCalc.attackSpeedTicks();
			attackDueTick = attackTick + speed;
			// Every figure here was already worked out above, so the line costs nothing beyond formatting it. An expected
			// average of -1 is the tell that the target has no stats and that this attack was scored against nothing.
			debug.log("ATTACK on %s (npc %d) thrown tick %d, booked +%d, speed %d, next due %d, "
					+ "prayed=%b potted=%b boost %.2f (cost %.2f) switched=%b (cost %.2f) projectile=%b, scored=%b, "
					+ "expected avg %.2f, ideal %.2f",
				attacker.getTargetName(), targetId, attackTick, attackObservedLag, speed, attackDueTick,
				prayed, potted, boostShare, boostCost, switched, switchCost, attackObservedFromProjectile,
				attacker.isScored(), actualSetup, idealSetup);
			lastAttackFromProjectile = attackObservedFromProjectile;
			if (attackObservedFromProjectile)
			{
				// Only a cast can spend a cast. A melee blow landing on the tick the spell was clicked would otherwise consume it,
				// and the cast a tick later then read as the weapon.
				combatCalc.noteAttackThrown();
			}
			// Last: back to describing the loadout rather than one past attack, so the overlay goes on showing a queued cast
			// between attacks.
			combatCalc.noteAttackKind(true);
			return;
		}
		// The trip totals take the same ticks as they happen, so the share shown in whole-trip mode moves during a fight
		// rather than only at its end.
		if (EncounterGroup.isUnattackable(targetLiveId) || targetIsDying())
		{
			// Between phases, charging, or dead but standing. Neither lost nor spent: no attack was possible, so counting it
			// either way would move a figure that measures choices.
			debug.log("tick UNCOUNTED: target %d is %s", targetLiveId,
				targetIsDying() ? "dead but still standing" : "in a form that cannot be attacked");
			return;
		}
		// The room's exemption, not this fight's: a room of one-attack fights would otherwise never get past it. See
		// Fight.recordTickLost.
		final boolean roomEngaged = currentEncounter != null && !currentEncounter.isEnded()
			&& currentEncounter.isEngaged();
		final boolean counts = current.isAttacking() || roomEngaged;
		// The due tick is in attack ticks, and a booking arrives a tick or two later, so the wait is measured against the
		// tick that booking would land on. Which lag applies is decided by the weapon in hand NOW, since it is the next
		// attack's booking being waited for: holding a trident to the whip's one-tick lag booked a lost tick on every switch
		// into a projectile.
		final int lag = pendingBookingLag(lastAttackFromProjectile, combatCalc.isMeleeEquipped());
		// Eats the bookings can no longer change - on or before the attack tick this one judges - are logged with what they
		// did to the due tick.
		logEats(foodDelay.settle(client.getTickCount() - lag, attackDueTick));
		if (!attackOverdue(client.getTickCount(), attackDueTick, lag))
		{
			current.recordTickSpent(roomEngaged);
			if (counts)
			{
				session.recordTickSpent();
			}
			debug.log("tick SPENT: on cooldown, due %d + lag %d, attacking=%b", attackDueTick, lag, counts);
			return;
		}
		// Overdue against the weapon's own due tick, so lost either way; the eat's while it is inside the delay the eats
		// added, idle after it.
		final int added = foodDelay.added(attackDueTick);
		final boolean eating = FoodDelay.isEatingTick(client.getTickCount(), attackDueTick, lag, added);
		current.recordTickLost(eating, roomEngaged);
		if (counts)
		{
			session.recordTickLost(eating);
		}
		// Only booked once the fight has an attack in it, so a lost tick that is NOT counted says so - that gap is what a
		// safespot report looks like from here.
		debug.log("tick LOST%s: overdue since %d + lag %d%s",
			eating ? String.format(" (eating %d/%d)",
				FoodDelay.eatingTickNumber(client.getTickCount(), attackDueTick, lag), added) : "",
			attackDueTick, lag, counts ? "" : ", NOT COUNTED - no attack made in this fight yet");
	}

	// The hidden per-account key holding the blowpipe dart last seen (T-015), and the setting it replaced, read once as the
	// starting guess so a player who had set it keeps their dart until the game shows another.
	private static final String DART_SEEN_KEY = "blowpipeDartSeen";
	private static final String LEGACY_DART_KEY = "blowpipeDart";
	// Whether this login's remembered dart has been read. Read on the first tick logged in, when the account's profile is
	// known, and again after every logout or hop.
	private boolean dartLoaded;

	// Darts used on the blowpipe: "Dragon dart -> Toxic blowpipe". Loading them is the one moment the game shows which.
	private void noteDartsLoaded(MenuOptionClicked event)
	{
		if (!"Use".equals(event.getMenuOption()) || event.getMenuTarget() == null)
		{
			return;
		}
		final BlowpipeDart dart = BlowpipeDart.fromUseTarget(Text.removeTags(event.getMenuTarget()));
		if (dart != null)
		{
			rememberDart(dart, "darts used on the blowpipe, tick " + client.getTickCount());
		}
	}

	// The blowpipe's Check message names its darts. Any "Darts:" message not understood is logged, since the wording was
	// written from memory of the game rather than a log.
	private void noteBlowpipeCheck(String message)
	{
		if (!message.contains("Darts"))
		{
			return;
		}
		final BlowpipeDart dart = BlowpipeDart.fromCheckMessage(message);
		if (dart == null)
		{
			debug.log("BLOWPIPE message not understood: %s", message);
			return;
		}
		rememberDart(dart, "blowpipe checked, tick " + client.getTickCount());
	}

	private void rememberDart(BlowpipeDart dart, String how)
	{
		combatCalc.setBlowpipeDart(dart, how);
		configManager.setRSProfileConfiguration(PvmPerformanceConfig.GROUP, DART_SEEN_KEY, dart.name());
		debug.log("BLOWPIPE dart %s (%s)", dart, how);
	}

	// The dart remembered for this account, else the old setting's value, else nothing (the calc then assumes dragon).
	private void loadRememberedDart()
	{
		final String seen = configManager.getRSProfileConfiguration(PvmPerformanceConfig.GROUP, DART_SEEN_KEY);
		final String legacy = configManager.getConfiguration(PvmPerformanceConfig.GROUP, LEGACY_DART_KEY);
		final String name = seen != null ? seen : legacy;
		if (name == null)
		{
			// Another account may have been logged in before: its dart is not this one's.
			combatCalc.setBlowpipeDart(BlowpipeDart.DRAGON, "assumed, not seen yet");
			return;
		}
		try
		{
			combatCalc.setBlowpipeDart(BlowpipeDart.valueOf(name),
				seen != null ? "remembered for this account" : "from the old Blowpipe dart setting, not seen yet");
		}
		catch (IllegalArgumentException e)
		{
			debug.log("BLOWPIPE remembered dart '%s' is not one this knows", name);
		}
	}

	// Notes a click on food or drink. It counts once the inventory shows it eaten: a click can be refused, and two on one
	// food in a tick eat it once.
	private void noteConsumed(MenuOptionClicked event)
	{
		final String option = event.getMenuOption();
		if (!"Eat".equals(option) && !"Drink".equals(option))
		{
			return;
		}
		final ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		foodDelay.clicked(client.getTickCount(), event.getItemId(), option,
			inventory == null ? 0 : inventory.count(event.getItemId()));
	}

	// One line per eat once it is settled: what it was, its delay, and what it did to the attack it came during.
	private void logEats(List<FoodDelay.Eat> eats)
	{
		for (FoodDelay.Eat eat : eats)
		{
			final String name = itemManager.getItemComposition(eat.itemId).getName();
			if (eat.delay == 0)
			{
				debug.log("EAT %s (delay 0) on tick %d, clicked %d: a potion, no delay", name, eat.tick, eat.clickTick);
			}
			else if (eat.dueBefore == Integer.MIN_VALUE)
			{
				debug.log("EAT %s (delay %d) on tick %d, clicked %d: no attack due, no delay", name, eat.delay, eat.tick,
					eat.clickTick);
			}
			else if (eat.pending)
			{
				debug.log("EAT %s (delay %d) on tick %d, clicked %d: attack pending, due %d -> %d", name, eat.delay,
					eat.tick, eat.clickTick, eat.dueBefore, eat.dueAfter);
			}
			else
			{
				debug.log("EAT %s (delay %d) on tick %d, clicked %d: weapon ready (due %d), no delay", name, eat.delay,
					eat.tick, eat.clickTick, eat.dueBefore);
			}
		}
	}

	/**
	 * Notes that an attack went out this tick. Called only from the two events
	 * that prove one did, and whose tick is exact: a melee hitsplat lands on the
	 * tick it was thrown, and a projectile is created on the tick it was fired.
	 */
	private void recordAttackObserved(boolean fromProjectile, int npcId)
	{
		recordAttackObserved(fromProjectile, npcId,
			fromProjectile ? PROJECTILE_BOOKING_LAG : MELEE_BOOKING_LAG);
	}

	// How far behind the attack this observation is depends on what proved it, and there are three answers rather than
	// two: a melee blow lands the tick it is thrown, a projectile surfaces two ticks later, and a cast with no projectile
	// lands three.
	private void recordAttackObserved(boolean fromProjectile, int npcId, int bookingLag)
	{
		attackObservedLag = bookingLag;
		// Recorded here rather than at the booking, which is a tick or two later: this is what says a weapon is on cooldown,
		// and it has to be true from the moment the attack goes out.
		lastAttackSeenTick = client.getTickCount();
		attackObservedTick = client.getTickCount();
		attackObservedFromProjectile = fromProjectile;
		attackOriginTick = client.getTickCount();
		attackObservedNpcId = npcId;
		attackObservedMaxHit = combatCalc.maxHit(npcId);
		attackObservedAccuracy = combatCalc.hitChance(npcId);
		attackObservedAverageHit = combatCalc.averageHit(npcId);
		attackObservedSpecMaxHit = combatCalc.specialAttackMaxHit(npcId);
		attackObservedSpecAccuracy = combatCalc.specialAttackLandChance(npcId);
		attackObservedSpecAverageHit = combatCalc.specialAttackAverageHit(npcId);
		// A weapon swapped on a tick is not wielded until the next one, so an attack thrown on the tick of a switch used what
		// was held before it. The client shows the new weapon immediately, which is why the figures have to come from the
		// tick before the change rather than from now. This holds for every switch, not only one that lands on a melee
		// weapon: magic to ranged and staff to staff are the same case.
		if (gearChangedTick >= client.getTickCount() - 1)
		{
			for (int tick = gearChangedTick - 1;
				tick >= gearChangedTick - SWITCH_LOOKBACK_TICKS; tick--)
			{
				final double[] earlier = expectedByTick.get(tick);
				if (earlier != null && (int) earlier[EXP_NPC_ID] == npcId
					&& earlier[EXP_ACCURACY] >= 0)
				{
					attackObservedMaxHit = (int) earlier[EXP_MAX];
					attackObservedAccuracy = earlier[EXP_ACCURACY];
					attackObservedAverageHit = earlier[EXP_AVERAGE];
					attackObservedSpecMaxHit = (int) earlier[EXP_SPEC_MAX];
					attackObservedSpecAccuracy = earlier[EXP_SPEC_ACCURACY];
					attackObservedSpecAverageHit = earlier[EXP_SPEC_AVERAGE];
					break;
				}
			}
		}
		attackObservedPotted = combatCalc.isPotted();
		attackObservedBoostShare = combatCalc.boostShare();
		attackObservedDrained = combatCalc.isDrained();
	}

	// Notices the intended prayer going up part-way through a tick, so a prayer switched on and attacked with on the same
	// tick counts.
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.WORN)
		{
			combatCalc.invalidateGear();
			gearChangedTick = client.getTickCount();
		}
		else if (event.getContainerId() == InventoryID.INV)
		{
			// What could have been switched into may have changed. Eating and every switch fire this too, which is why nothing
			// is counted here - the next attack decides whether anything wearable was gained or lost.
			combatCalc.invalidateInventory();
			// A clicked food gone, or bitten, is the eat: this tick is when the game processed it.
			final ItemContainer inventory = event.getItemContainer();
			foodDelay.inventoryChanged(client.getTickCount(), inventory::count);
		}
	}

	/**
	 * Mark of Darkness, from the game's own messages: no varbit carries it, and
	 * the one named for it is a buff bar display toggle. The messages are exact,
	 * which beats computing a duration and hoping the formula holds.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		final String message = Text.removeTags(event.getMessage());
		noteBlowpipeCheck(message);
		if (message.startsWith("You have placed a Mark of Darkness upon yourself"))
		{
			gearBonuses.setMarkOfDarkness(true);
		}
		else if (message.startsWith("Your Mark of Darkness has faded away"))
		{
			gearBonuses.setMarkOfDarkness(false);
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() == VarbitID.ARCEUUS_RESURRECTION_ACTIVE)
		{
			debug.log("THRALL resurrection active varbit -> %d", event.getValue());
			thralls.onResurrectionVarbit(event.getValue() == 1, client.getTickCount());
		}
		if (event.getVarbitId() == VarbitID.VENGEANCE_REBOUND)
		{
			// Up from the cast until a hit sets it off: it falling is the rebound (T-017 Part B).
			final boolean up = event.getValue() == 1;
			debug.log("VENGEANCE varbit -> %d", event.getValue());
			if (vengeanceUp && !up)
			{
				reflect.vengeanceFired(client.getTickCount());
			}
			vengeanceUp = up;
		}
		// Watched here because the energy falling is what says a special went out, and the player's own specials are not
		// carried by the party message.
		drain.onEnergyChanged();
		noteSpecialEnergy();
	}

	/** "Taste vengeance!": the player's own words as the rebound goes off, beside the varbit (T-017 Part B). */
	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		if (event.getActor() != null && event.getActor() == client.getLocalPlayer()
			&& "Taste vengeance!".equals(event.getOverheadText()))
		{
			debug.log("VENGEANCE said Taste vengeance!");
			reflect.vengeanceFired(client.getTickCount());
		}
	}

	/**
	 * Marks the tick a special went out on, which is the tick the energy falls.
	 *
	 * <p>Tracked here rather than taken from {@link DefenceDrain}, which watches
	 * the same varp: that only arms for the handful of weapons whose special
	 * drains defence, and every special needs booking against its own expected
	 * damage rather than the ordinary attack's.
	 */
	private void noteSpecialEnergy()
	{
		final int energy = client.getVarpValue(VarPlayerID.SA_ENERGY);
		final int previous = specEnergy;
		specEnergy = energy;
		// A first reading cannot be compared against anything, and energy that went up is regeneration rather than a special.
		if (previous >= 0 && energy < previous)
		{
			specFiredTick = client.getTickCount();
			recentSpecDrops.addLast(specFiredTick);
			while (recentSpecDrops.size() > SPEC_DROPS_KEPT)
			{
				recentSpecDrops.removeFirst();
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!dartLoaded)
		{
			dartLoaded = true;
			loadRememberedDart();
		}
		// Before anything asks for the target's stats: the bar decides which version of a boss this is, and what it has left
		// moves Vardorvis's defence.
		readHealthBar();
		// First: the tick's experience has all arrived by now, and none of the splats it pays for can have.
		trackThrallAndXp();
		RaidScaling.setTombsRaidLevel(gearBonuses.tombsRaidLevel());
		trackRaid(System.currentTimeMillis());
		startFightOnTarget();
		// After any fight this tick opened, before anything is judged against the target: what it wears now.
		if (current != null && !current.isEnded())
		{
			targetIds.record(client.getTickCount(), current.getTargetIndex(), targetLiveId);
		}
		// Both before the attack is booked. The expected figures must describe the loadout that threw it, and the cast
		// verdict must be on screen the moment the miss is known - safe ahead of the booking because a cast is never judged
		// on its own tick.
		resolveCasts(System.currentTimeMillis());
		// The highest boost seen this fight is part of the ideal (T-015): a pre-pot counts after it has worn off.
		if (current != null && !current.isEnded())
		{
			combatCalc.noteBoosts();
		}
		refreshExpected();
		// Sampled BEFORE the booking reads it back: a cast booked from its animation has no lag, so it asks for this tick's
		// answer, and written afterwards there was none. hasOffensivePrayer reads the server's copy.
		final boolean upThisTick = combatCalc.hasOffensivePrayer();
		prayerUpByTick.put(client.getTickCount(), upThisTick);
		if (client.getTickCount() % 5 == 0)
		{
			prayerUpByTick.keySet().removeIf(t -> client.getTickCount() - t > PRAYER_HISTORY_TICKS);
		}
		trackAttackCooldown();

		// For ranged attacks where the player is right next to the target, the hitsplat can arrive on the exact same tick the
		// projectile sets off. If it did, the hitsplat's resolvePendingSample call fired empty because trackAttackCooldown
		// hadn't created the sample yet. Resolving again here catches that sample.
		for (java.util.Map.Entry<Integer, Integer> entry : lastHitsplatTick.entrySet())
		{
			if (entry.getValue() == client.getTickCount())
			{
				resolvePendingSample(entry.getKey());
			}
		}

		final Player me = client.getLocalPlayer();
		if (me != null && me.getLocalLocation() != null)
		{
			recentTiles.put(client.getTickCount(), me.getLocalLocation());
			if (client.getTickCount() % 5 == 0)
			{
				recentTiles.keySet().removeIf(t -> client.getTickCount() - t > RECENT_TILES);
			}
		}

		// Drop projectiles that have landed so the set doesn't retain them.
		countedProjectiles.values().removeIf(seen -> client.getTickCount() - seen > PROJECTILE_KEY_TICKS);
		// Bursts are over long before this; the entries are dropped so the two collections cannot grow with every NPC ever
		// hit.
		lastHitsplatTick.values().removeIf(seen -> client.getTickCount() - seen > PROJECTILE_KEY_TICKS);
		burstLanded.retainAll(lastHitsplatTick.keySet());
		burstBooked.retainAll(lastHitsplatTick.keySet());
		bursts.keySet().retainAll(lastHitsplatTick.keySet());

		if (current != null && !current.isEnded())
		{
			if (current.getAttempts() == 0)
			{
				// Opened by targeting and not yet fought. The idle timeout cannot judge it - that would expire while the player is
				// still walking into range - so it ends when they look away, and not the instant it blinks, because the interaction
				// lapses between a cast and its projectile.
				notTargetingTicks = isTargeting(current.getTargetIndex())
					? 0 : notTargetingTicks + 1;
				if (notTargetingTicks > LOOK_AWAY_TICKS)
				{
					finalizeFight(false, System.currentTimeMillis(), "looked away before attacking");
				}
			}
			else
			{
				final long idle = System.currentTimeMillis() - current.getLastActivityMillis();
				// Still aimed at it is still fighting it. The timeout is for a fight walked away from, and it was ending
				// safespotted ones too: no damage passes either way while the target is out of reach, so the clock ran out
				// while the player stood there doing exactly what the fight asks of them, and every tick after that went
				// uncounted.
				if (idle > config.fightTimeoutTicks() * 600L && !isTargeting(current.getTargetIndex()))
				{
					finalizeFight(false, current.getLastActivityMillis(),
						"idle " + (idle / 600L) + " ticks with no damage either way");
				}
			}
		}
	}

	/** Whether the player is still interacting with this NPC. */
	private boolean isTargeting(int npcIndex)
	{
		final Player me = client.getLocalPlayer();
		final Actor target = me == null ? null : me.getInteracting();
		return target instanceof NPC && ((NPC) target).getIndex() == npcIndex;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			// Possibly another account next, with its own blowpipe.
			dartLoaded = false;
			if (current != null && !current.isEnded())
			{
				finalizeFight(false, current.getLastActivityMillis(), "logged out or hopped");
			}
			// And the ROOM with it. Left open, it waits for a kill that may be a night away and counts the whole gap as
			// room time: one Hueycoatl room in a real export ran 33,541 seconds across a night's sleep and swallowed two
			// kills, because a room only ends at its loot drop and nothing else was closing it.
			if (currentEncounter != null && !currentEncounter.isEnded())
			{
				currentEncounter.end(System.currentTimeMillis());
			}
			countedProjectiles.clear();
			taken.clear();
			targetSeen.clear();
			recentTiles.clear();
			lastHitsplatTick.clear();
			burstLanded.clear();
			bursts.clear();
			reflect.clear();
			ringByTick.clear();
			vengeanceUp = false;
			pendingMineHits.clear();
			damageByIndex.clear();
			health.clear();
			lastHealthBarReading = Long.MIN_VALUE;
			drain.clear();
			thralls.clear();
			xpLedger.clear();
			targetIds.clear();
			if (state == GameState.LOGIN_SCREEN)
			{
				// Possibly another account next: its experience is not a gain on this one's.
				java.util.Arrays.fill(lastSkillXp, 0);
			}
			nightmareBoss = null;
			targetNpc = null;
		}
	}

	private void startFight(NPC npc, long now)
	{
		if (current != null && !current.isEnded())
		{
			finalizeFight(false, now, "a different target was attacked");
		}
		// Full hitpoints from the version of the monster data in use, which knows the Chambers and Vardorvis where
		// RuneLite's table does not; RuneLite's (nullable) as the fallback. The boss health bar, once read, beats both.
		final MonsterStatsProvider.MonsterStats stats = monsterStats.get(npc.getId());
		final Integer known = npcManager.getHealth(npc.getId());
		final int maxHp = stats != null && stats.getHitpoints() > 0 ? stats.getHitpoints() : known == null ? -1 : known;
		health.setTableMaxHp(npc.getIndex(), npc.getId(), maxHp, client.getTickCount());
		// Tags stripped: some names carry colour markup and it was reaching the CSV verbatim, "<col=00ffff>Rubble</col>" and
		// all.
		current = new Fight(Text.removeTags(npc.getName()), npc.getId(), npc.getIndex(),
			maxHp, now,
			raidType, raidCounter);
		current.noteStartTick(client.getTickCount());
		// A boss the monster data cannot describe says so from the first tick, not only once the fight's numbers show it.
		if (stats != null)
		{
			current.addStatsWarning(stats.getWarning());
		}
		targetLiveId = npc.getId();
		targetNpc = npc;
		combatCalc.setTarget(npc);
		// A new fight's standard: the boosts seen in the last one no longer count, and this tick's do.
		combatCalc.startFight();
		combatCalc.noteBoosts();
		labelOlmPhase(current);
		labelNightmareTotem(current, npc.getId());
		openEncounterFor(current, now);
		debug.log("fight START %s (npc %d, index %d, maxHp %d) group=%s room=%s raid=%s",
			current.getTargetName(), npc.getId(), npc.getIndex(), maxHp,
			current.getGroupName(), current.encounterName(), raidType);
		debug.log("  %s", monsterStats.describe(npc.getId()));
		debug.log("  %s", combatCalc.describeWorkedOut(npc.getId()));
	}

	// Opens a fight as soon as the player targets something attackable, so the overlay is up while the first attack is
	// still in the air.
	private void startFightOnTarget()
	{
		if (current != null && !current.isEnded())
		{
			return;
		}
		final Player me = client.getLocalPlayer();
		final Actor target = me == null ? null : me.getInteracting();
		if (!(target instanceof NPC))
		{
			return;
		}
		final NPC npc = (NPC) target;
		if (!isFightable(npc))
		{
			return;
		}
		startFight(npc, System.currentTimeMillis());
	}

	/**
	 * The id this fight's target wore on a tick: the phase an attack thrown then
	 * was rolled against. A fight keeps the id it opened on, which for a boss that
	 * changes id with its phases is the wrong set of stats for the rest of it - every
	 * attack on the Abyssal Sire was judged as its first phase, and its last stage
	 * has half the defences (T-020).
	 */
	private int targetIdOn(Fight fight, int tick)
	{
		final int fallback = fight == current && !fight.isEnded() && targetLiveId >= 0 ? targetLiveId
			: fight.getTargetId();
		return targetIds.idOn(tick, fight.getTargetIndex(), fallback);
	}

	/**
	 * The fight that was open on a given tick: the current one if it had started
	 * by then, otherwise the one before it, and null if neither covers it.
	 *
	 * <p>Only two are ever candidates. A booking trails its attack by a tick or
	 * two, and nothing can close and open more than one fight in that gap.
	 */
	private Fight fightAsOf(int tick)
	{
		if (current != null && !current.isEnded() && tick >= current.getStartTick())
		{
			return current;
		}
		if (lastFinished != null && tick >= lastFinished.getStartTick())
		{
			return lastFinished;
		}
		return current != null && !current.isEnded() ? current : null;
	}

	/**
	 * Whether this is something the player can fight at all. Asked by every path
	 * that can open a fight, not just by the one that watches what is targeted.
	 *
	 * <p>The Hueycoatl's seer is why. It is scenery with a name - no hitpoints in
	 * RuneLite's table, no entry in the monster data, no attack option - and
	 * something in that fight puts a single point of damage on it that
	 * {@code Hitsplat.isMine()} reports as the player's. That opened a fight,
	 * booked an attack against it, and counted a kill when it vanished: nine
	 * fights and nine kills in one trip against an NPC nobody had touched.
	 * {@code isMine()} is not evidence on its own that a hitsplat was ours.
	 */
	private boolean isFightable(NPC npc)
	{
		if (npc == null || EncounterGroup.isIgnored(npc.getId()))
		{
			return false;
		}
		if (npc.getCombatLevel() > 0 || npcManager.getHealth(npc.getId()) != null)
		{
			return true;
		}
		// Last resort for a boss part the other two cannot answer for: something the game offers to attack is fightable
		// whatever the tables know about it.
		final NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			return false;
		}
		for (String action : composition.getActions())
		{
			if ("Attack".equals(action))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether this hitsplat is the attack we already booked catching another
	 * npc, rather than a new fight starting.
	 *
	 * <p>Without this, one throw of chinchompas into a pile of adds opened a
	 * fight per npc, each finalising the last: a real tightrope room came out as
	 * 300 fights holding one attack apiece, with the damage split across them
	 * and the ticks between them counted nowhere.
	 */
	private boolean isAoeCollateral(NPC npc, int previousMineHitsplatTick)
	{
		if (current == null || current.isEnded() || current.getTargetIndex() == npc.getIndex())
		{
			return false;
		}
		// Close behind an attack we booked, which is the ordinary case...
		if (client.getTickCount() - lastAttackSeenTick <= AOE_WINDOW_TICKS)
		{
			return true;
		}
		// ...or simply landing in the same tick as another of my hitsplats, which is the case that does not need an attack
		// to have been booked at all. Nobody throws two attacks in one tick, so two of my hitsplats on two npcs in one tick
		// are one attack whatever the plugin did or did not see going out. A barrage cast while being hit plays the BLOCK
		// animation instead of the cast one - the log named it, 403, and the animation route is right to refuse it - so the
		// cast is invisible and five hitsplats arrived on one tick as five fights of no attacks each, all discarded.
		return previousMineHitsplatTick == client.getTickCount();
	}

	/**
	 * Another npc caught by the throw already booked. Its damage joins the
	 * attack that dealt it, and it brings its OWN expectation - rolled against
	 * its own defence, since the adds beside a target are rarely the same
	 * monster - so what one throw is expected to deal is the sum over
	 * everything it reached.
	 */
	private void recordExtraTarget(NPC npc, int damage, long now)
	{
		final int tick = client.getTickCount();
		// A burst on one npc is one target, for the same reason it is one landed attack: a scythe lands three splats on the
		// same enemy and they are one blow.
		final Integer seen = lastHitsplatTick.put(npc.getIndex(), tick);
		final boolean newTarget = seen == null || tick - seen > 1;
		if (newTarget)
		{
			burstLanded.remove(npc.getIndex());
			burstBooked.remove(npc.getIndex());
		}
		final boolean landed = damage > 0 && burstLanded.add(npc.getIndex());
		damageByIndex.merge(npc.getIndex(), damage, Integer::sum);
		health.damage(npc.getIndex(), npc.getId(), damage, tick);
		current.recordExtraTarget(damage, now, newTarget, landed);
		if (current.isScored())
		{
			session.recordExtraTarget(damage, newTarget, landed, now);
		}
		if (newTarget)
		{
			// Its own figures, not the primary target's. A negative accuracy means this npc has no stats, and then it lends
			// its damage without lending an expectation - the same rule the primary target follows.
			final double accuracy = combatCalc.hitChance(npc.getId());
			final double average = combatCalc.averageHit(npc.getId());
			if (accuracy >= 0 && average >= 0 && current.isScored())
			{
				final double chances = CombatCalc.landChance(accuracy, combatCalc.hitsPerAttack(npc.getId()));
				current.recordExpected(combatCalc.maxHit(npc.getId()), chances, average);
				session.recordExpected(chances, average);
			}
		}
		drain.onMyHitsplat(npc, damage);
		debug.log("AoE %d on %s (npc %d, index %d) joins the attack on %s: newTarget=%b landed=%b",
			damage, npc.getName(), npc.getId(), npc.getIndex(), current.getTargetName(), newTarget, landed);
	}

	// Files a fight under the room it belongs to, continuing the room already open when it is the same one.
	private void openEncounterFor(Fight fight, long now)
	{
		// Only a grouped NPC continues a room. Without that a second Vorkath would join the first - same name, same room -
		// and the overlay would quietly become a running total of the trip.
		final String name = fight.encounterName();
		// An ended room takes nothing more. It is left on show so the kill can be read, and this is what stops the fight
		// after it being added to it. Nor does one nothing was thrown in: a fight opens on merely looking at an npc, and a
		// grouped room opened by a look carried on into the next kill with the gap counted as its time - one Abyssal Sire
		// room would have run from a glance at 4191 to a kill at 4896, twice the 351 ticks of the kill (T-020).
		if (opensRoom(currentEncounter, fight))
		{
			if (currentEncounter != null)
			{
				if (!currentEncounter.isEnded())
				{
					currentEncounter.end(now);
				}
				if (currentEncounter.hasAttempts())
				{
					lastFinishedEncounter = currentEncounter;
				}
			}
			currentEncounter = new Encounter(name, raidType, now);
			if (currentRaid != null)
			{
				currentRaid.add(currentEncounter);
			}
		}
		currentEncounter.add(fight);
		log.debug("PvM Logs: fight on {} (npc {}) -> room '{}' group={} fights={}",
			fight.getTargetName(), fight.getTargetId(), currentEncounter.getName(),
			fight.getGroupName(), currentEncounter.getFights().size());
	}

	/**
	 * Whether this fight starts a room of its own rather than joining the one open:
	 * it does unless that room is open, has had something thrown in it, and is the
	 * same grouped encounter. See {@link #openEncounterFor}.
	 */
	static boolean opensRoom(Encounter room, Fight fight)
	{
		return room == null || room.isEnded() || !room.hasAttempts() || fight.getGroupName() == null
			|| !room.accepts(fight.encounterName());
	}

	/**
	 * Opens and closes raids from the game's own varbits, so a run is bounded by
	 * entering and leaving rather than by what happens to be fought.
	 */
	private void trackRaid(long now)
	{
		final RaidType now_ = RaidType.current(client);
		if (now_ == raidType)
		{
			return;
		}
		if (currentRaid != null)
		{
			currentRaid.end(now);
			currentRaid = null;
		}
		// A room does not span two raids, nor a raid and the world outside it.
		if (currentEncounter != null)
		{
			currentEncounter.end(now);
			currentEncounter = null;
		}
		raidType = now_;
		olmPhase = null;
		if (raidType != null)
		{
			currentRaid = new Raid(raidType, ++raidCounter, now);
		}
	}

	/**
	 * Names an Olm fight by the special its phase is running, so the three can
	 * be compared. A phase whose special has not shown yet keeps the plain name.
	 */
	private void labelOlmPhase(Fight fight)
	{
		if (olmPhase == null || fight.getGroupName() == null
			|| !fight.getGroupName().startsWith("Great Olm"))
		{
			return;
		}
		fight.setEncounterLabel(fight.getGroupName() + " · " + olmPhase.getLabel());
	}

	/**
	 * Files a totem under whichever Nightmare is being fought: the two share
	 * their totem ids, and the boss is always met before its totems.
	 */
	private void labelNightmareTotem(Fight fight, int npcId)
	{
		final String boss = EncounterGroup.nightmareBossName(npcId);
		if (boss != null)
		{
			nightmareBoss = boss;
			return;
		}
		if (nightmareBoss != null && EncounterGroup.isNightmareTotem(npcId))
		{
			fight.setEncounterLabel(nightmareBoss);
		}
	}

	/**
	 * The end-of-fight checks on the target: its full hitpoints corrected from the
	 * boss health bar, the reconciliation of every splat counted on it against
	 * them, and the verdict on whether its expected side can be believed.
	 *
	 * <p>The killing blow is capped by what was left, so on a kill of a target that
	 * started full, everything that hit it less everything it healed IS its full
	 * hitpoints. A gap means a splat went uncounted or was counted twice, or the
	 * table is wrong - the cheapest check there is, and logged on every kill.
	 */
	private void closeTargetHealth(boolean died)
	{
		final int index = current.getTargetIndex();
		final int tick = client.getTickCount();
		final int full = health.maxHp(index);
		final String fullFrom = health.maxSource(index);
		if (full > 0 && ("bar".equals(fullFrom) || current.getMaxHp() <= 0))
		{
			current.noteMaxHp(full);
		}
		final MonsterStatsProvider.MonsterStats stats = monsterStats.get(targetLiveId >= 0 ? targetLiveId
			: current.getTargetId());
		if (stats != null)
		{
			current.addStatsWarning(stats.getWarning());
		}
		final String verdict = StatsCheck.accuracyVerdict(current);
		current.addStatsWarning(verdict);
		// Logged whether or not the debug setting is on: the panel's saved window keeps every line, and these two are the
		// ones a fight is checked by.
		final int counted = health.damageCounted(index);
		final int healed = health.healed(index);
		final int net = counted - healed;
		debug.log("RECONCILE %s (npc %d)%s: weapon %d (of it not hitpoints %d) + thrall %d + reflect %d + dot %d + others "
				+ "%d + unclassified %d, healed %d; on the target %d - %d = %d against %d full hp (%s)%s; bar last read %d, "
				+ "now %d left (%s)",
			current.getTargetName(), current.getTargetId(), died ? " KILL" : "",
			current.getDamageDealt(), current.notHitpointsTotal(), current.getThrallDamage(), current.getReflectDamage(),
			current.getDotDamage(), current.getOthersDamage(),
			current.getUnclassifiedDamage(), current.getHealed(), counted, healed, net, full, fullFrom,
			died && full > 0 ? String.format(", diff %+d (%.1f%%)", net - full, 100.0 * (net - full) / full) : "",
			health.lastHudHp(index), health.remaining(index, tick), health.source(index, tick));
		debug.log("STATS CHECK %s: landed %d of %d against %.1f expected (variance %.2f): %s",
			current.getTargetName(), current.getHits(), current.getExpectedTargetSamples(),
			current.getSumExpectedLanded(), current.getSumExpectedLandedVariance(),
			verdict == null ? "consistent with luck" : "TRIPPED");
	}

	/**
	 * Reads the boss health bar the game draws, and hands it to TargetHealth when
	 * it names the fight's target. Varp {@code HPBAR_HUD_NPC} holds the npc's
	 * composition id, the varbits its exact current and full hitpoints; RuneLite's
	 * opponent info reads the same three and gives up when the player has turned the
	 * boss bar off, as this does. Logged whenever the reading changes, raw values and
	 * all, so a log can settle what the game actually sends.
	 */
	private void readHealthBar()
	{
		if (current == null || current.isEnded())
		{
			return;
		}
		final int barNpc = client.getVarpValue(VarPlayerID.HPBAR_HUD_NPC);
		final int hp = client.getVarbitValue(VarbitID.HPBAR_HUD_HP);
		final int base = client.getVarbitValue(VarbitID.HPBAR_HUD_BASEHP);
		final boolean disabled = client.getVarbitValue(VarbitID.HPBAR_HUD_BOSS_DISABLED) != 0;
		final NPC target = targetNpc;
		final int index = current.getTargetIndex();
		final int liveId = target == null ? -1 : target.getId();
		final NPCComposition composition = target == null ? null : target.getComposition();
		final int compositionId = composition == null ? -1 : composition.getId();
		// The id it wears now, whether or not a bar is drawn: a boss that heals a phase back restarts its count on the
		// change (T-020, the Abyssal Sire), and that has to happen before the next attack is judged against it.
		if (target != null && target.getIndex() == index)
		{
			health.noteId(index, liveId, client.getTickCount());
		}
		for (String note : health.drainNotes())
		{
			debug.log(note);
		}
		final boolean named = !disabled && target != null && target.getIndex() == index
			&& healthBarNames(barNpc, compositionId, liveId);
		// The bar names an id, not an npc. With adds of the target's id about, it may be showing one of them: then it is
		// only believed while it agrees with the count, and the count carries on otherwise.
		final int sameId = named ? npcsWithId(barNpc) : 0;
		final int counted = health.countedRemaining(index);
		final boolean ours = named && TargetHealth.barIsTarget(sameId, hp, base, counted);
		final long reading = ((long) (barNpc & 0xFFFFF) << 40) | ((long) (hp & 0xFFFFF) << 20) | (base & 0xFFFFF)
			| (disabled ? 1L << 62 : 0) | (ours ? 1L << 61 : 0) | (named ? 1L << 60 : 0);
		if (reading != lastHealthBarReading)
		{
			lastHealthBarReading = reading;
			debug.log("health bar: npc varp %d, hp %d / %d%s; target index %d, id %d, composition %d -> %s",
				barNpc, hp, base, disabled ? ", boss bar turned off" : "", index, liveId, compositionId,
				ours ? (sameId > 1 ? "OURS (" + sameId + " npcs with this id; agrees with the count, " + counted + ")"
					: "OURS")
					: named ? "AMBIGUOUS: " + sameId + " npcs with this id and the count says " + counted
						+ ", so the count is used" : "not ours");
		}
		if (ours)
		{
			// A new full hitpoints on the bar mid-fight is a phase with its own pool (or a different version than the one
			// guessed). The bar copes, but the count does not: it carries the old pool's damage into the new one. Logged so a
			// real fight can say which bosses do this before anything is built for it (T-011 item 6).
			final int previousFull = health.lastHudMax(index);
			if (previousFull > 0 && previousFull != base)
			{
				debug.log("health bar: FULL HITPOINTS CHANGED %d -> %d mid-fight on %s (npc %d, index %d); counted would "
						+ "read %d left, the bar says %d",
					previousFull, base, current.getTargetName(), liveId, index, health.countedRemaining(index), hp);
			}
			health.hud(index, liveId, hp, base, client.getTickCount());
		}
	}

	/** How many npcs in the scene wear this id, by composition or as they stand. */
	private int npcsWithId(int npcId)
	{
		if (client.getTopLevelWorldView() == null)
		{
			return 0;
		}
		int n = 0;
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null)
			{
				continue;
			}
			final NPCComposition c = npc.getComposition();
			if (npc.getId() == npcId || (c != null && c.getId() == npcId))
			{
				n++;
			}
		}
		return n;
	}

	/** Whether the bar's npc varp names this npc. -1 is no bar at all. */
	static boolean healthBarNames(int barNpc, int compositionId, int liveId)
	{
		return barNpc >= 0 && (barNpc == compositionId || barNpc == liveId);
	}

	/**
	 * Why the expected figures on screen should not be believed, or null: the
	 * target's monster data carries a warning, or the attacks landed so far are too
	 * far from expected to be luck.
	 */
	String getStatsWarning(Fight fight)
	{
		if (fight == null)
		{
			return null;
		}
		if (fight.getStatsWarning() != null)
		{
			return fight.getStatsWarning();
		}
		if (fight.isEnded())
		{
			return null;
		}
		final MonsterStatsProvider.MonsterStats stats = monsterStats.get(targetLiveId >= 0 ? targetLiveId
			: fight.getTargetId());
		if (stats != null && stats.getWarning() != null)
		{
			return stats.getWarning();
		}
		return StatsCheck.accuracyVerdict(fight);
	}

	private void finalizeFight(boolean died, long now, String reason)
	{
		debug.log("fight END %s (npc %d) %s: attempts=%d made=%d damage=%d thrallDamage=%d hits=%d ticksLost=%d "
				+ "combatTicks=%d expDamage=%.1f",
			current.getTargetName(), current.getTargetId(), reason, current.getAttempts(), current.getAttacksMade(),
			current.getDamageDealt(), current.getThrallDamage(), current.getHits(), current.getTicksLost(),
			current.getCombatTicks(), current.getSumExpectedAverageHit());
		closeTargetHealth(died);
		debug.log("%s", DamageTaken.summary(current));
		current.end(died, now);
		// Anything still in the air never landed. Counting what it was expected to deal against the nought it dealt made a
		// group kill read as underperformance.
		dropPendingSamples(died);
		// A cast still in the air when the target died is not a splash, and the fight is over either way. The charge goes
		// with it because the server reuses NPC indices, so a respawn can wear the one it was held against.
		castsAwaiting.clear();
		combatCalc.forgetConflictionCharge();
		pendingMineHits.clear();
		damageByIndex.clear();
		health.clear();
		// Nothing is being fought, so no drain should be read against anything.
		combatCalc.setTarget(null);
		targetNpc = null;
		targetLiveId = -1;
		if (current.getAttempts() == 0)
		{
			// Opened because the player looked at something and then didn't fight it. Nothing happened, so nothing is recorded.
			current = null;
			return;
		}
		session.recordFightEnded(died, current, now);
		history.add(0, current);
		while (history.size() > MAX_HISTORY)
		{
			history.remove(history.size() - 1);
		}
		lastFinished = current;
		current = null;
		if (panel != null)
		{
			panel.refresh();
		}
		saveHistory();
	}

	private Filepath historyFile()
	{
		final Filepath dir = dataDirectory;
		return dir == null ? null : dir.joinSegment("history.json");
	}

	private void loadHistory()
	{
		final Filepath file = historyFile();
		if (file == null || !file.exists())
		{
			return;
		}
		try
		{
			final String json;
			try (InputStream in = file.openInputStream())
			{
				json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			final Fight[] loaded = gson.fromJson(json, Fight[].class);
			if (loaded != null)
			{
				for (Fight fight : loaded)
				{
					if (history.size() < MAX_HISTORY)
					{
						history.add(fight);
					}
				}
			}
		}
		catch (Exception e)
		{
			log.warn("PvM Logs: could not load history", e);
		}
		if (panel != null)
		{
			panel.refresh();
		}
	}

	private void saveHistory()
	{
		// Snapshot now so a later shutdown/clear can't race the write.
		final List<Fight> snapshot = new ArrayList<>(history);
		executor.execute(() ->
		{
			try
			{
				final Filepath file = historyFile();
				if (file == null)
				{
					return;
				}
				file.getParent().createDirectories();
				file.write(gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("PvM Logs: could not save history", e);
			}
		});
	}

	// The tick a projectile was fired on, from the cycle it started. Cycles run at 20ms and ticks at 600, so thirty of the
	// former make one of the latter.
	private int startTick(Projectile projectile)
	{
		final int cyclesAgo = Math.max(0, client.getGameCycle() - projectile.getStartCycle());
		return client.getTickCount() - cyclesAgo / CYCLES_PER_TICK;
	}

	private int landingTick(Projectile projectile)
	{
		final int cyclesOut = Math.max(0, projectile.getEndCycle() - client.getGameCycle());
		return client.getTickCount() + cyclesOut / CYCLES_PER_TICK;
	}

	// Whether a hitsplat landing now is one of my attacks arriving from flight rather than a fresh melee blow. Matched on
	// when the attack was due rather than counted, because a count cannot expire: a projectile that never produces a
	// hitsplat would leave it high forever.
	//
	// Returns the attack it was, or null for none.
	private PendingHit consumePending(int npcIndex)
	{
		final List<PendingHit> due = pendingMineHits.get(npcIndex);
		if (due == null)
		{
			return null;
		}
		final int now = client.getTickCount();
		// Anything that should have landed and did not is dropped rather than left to be matched against something unrelated
		// later.
		due.removeIf(hit -> now - hit.lands > FLIGHT_SLACK_TICKS);
		for (int i = 0; i < due.size(); i++)
		{
			if (Math.abs(due.get(i).lands - now) <= FLIGHT_SLACK_TICKS)
			{
				return due.remove(i);
			}
		}
		if (due.isEmpty())
		{
			pendingMineHits.remove(npcIndex);
		}
		return null;
	}

	/** The fight the overlay should display: the active one, else the last finished. */
	Fight getDisplayFight()
	{
		return current != null ? current : lastFinished;
	}

	/** The room the overlay should display, or null if nothing has been fought. */
	Encounter getDisplayEncounter()
	{
		if (currentEncounter != null && currentEncounter.hasAttempts())
		{
			return currentEncounter;
		}
		return lastFinishedEncounter != null ? lastFinishedEncounter : currentEncounter;
	}

	/** The raid in progress, or null outside one. */
	Raid getCurrentRaid()
	{
		return currentRaid;
	}

	/** Expected max hit for the current loadout/style (0 if unknown). */
	int getExpectedMaxHit()
	{
		return expectedMaxHit;
	}

	/** Expected hit chance vs the shown target (0..1), or -1 if unavailable. */
	double getExpectedAccuracy()
	{
		return expectedAccuracy;
	}

	/** Expected damage per attack vs the shown target, or -1 if unavailable. */
	double getExpectedAverageHit()
	{
		return expectedAverageHit;
	}

	/** Expected damage a second for the loadout in hand; see CombatCalc.expectedDps. */
	double getExpectedDps()
	{
		return expectedDps;
	}

	/** Max total damage of one special attack activation (0 if the weapon has none). */
		int getExpectedSpecMaxHit()
	{
		return expectedSpecMaxHit;
	}

	double getExpectedSpecAccuracy()
	{
		return expectedSpecAccuracy;
	}

	double getExpectedSpecAverageHit()
	{
		return expectedSpecAverageHit;
	}

	/** The weapon, and the spell when one is cast, that the loadout's figures are for; null before the first tick. */
	String getAttackName()
	{
		return attackName;
	}

	/** The equipped weapon's special attack, or null if it has none that hits. */
	SpecialAttack getSpecialAttack()
	{
		return specialAttack;
	}

	/** Per-NPC aggregates in most-recently-fought order; optionally bosses only. */
	List<NpcStats> getNpcStats(boolean bossOnly)
	{
		final Map<String, NpcStats> byName = new LinkedHashMap<>();
		// Oldest first, so "does this fight continue the room before it" can be asked. The history is most-recent-first for
		// the panel, so the result is turned back round at the end.
		final Set<String> roomOpen = new HashSet<>();
		for (int i = history.size() - 1; i >= 0; i--)
		{
			final Fight fight = history.get(i);
			if (bossOnly && !isBoss(fight))
			{
				continue;
			}
			// Keyed on the room rather than the NPC, so a grouped room reads as one line instead of one per add.
			final String name = fight.encounterName();
			// A fight starts a room only if nothing has one open under that name. An ungrouped NPC never continues anything, and
			// the fight the kill closed on ends its room - which is what makes one Hueycoatl count once.
			final boolean startsRoom = fight.getGroupName() == null || !roomOpen.contains(name);
			byName.computeIfAbsent(name, NpcStats::new).add(fight, startsRoom);
			if (fight.getGroupName() == null || fight.isClosedRoom())
			{
				roomOpen.remove(name);
			}
			else
			{
				roomOpen.add(name);
			}
		}
		final List<NpcStats> stats = new ArrayList<>(byName.values());
		Collections.reverse(stats);
		return stats;
	}

	boolean isBoss(Fight fight)
	{
		return fight != null && BOSS_NAMES.contains(normalizeName(fight.getTargetName()));
	}

	private static Set<String> buildBossNames()
	{
		final Set<String> names = new HashSet<>();
		for (HiscoreSkill skill : HiscoreSkill.values())
		{
			if (skill.getType() == HiscoreSkillType.BOSS)
			{
				names.add(normalizeName(skill.getName()));
			}
		}
		return names;
	}

	private static List<String> buildBossDisplayNames()
	{
		final List<String> names = new ArrayList<>();
		for (HiscoreSkill skill : HiscoreSkill.values())
		{
			if (skill.getType() == HiscoreSkillType.BOSS)
			{
				names.add(skill.getName());
			}
		}
		Collections.sort(names);
		return names;
	}

	/** All hiscore boss display names, sorted; used by the panel's boss selector. */
	List<String> getBossDisplayNames()
	{
		return BOSS_DISPLAY_NAMES;
	}

	private static String normalizeName(String name)
	{
		return name == null ? "" : name.toLowerCase().replace("'", "").replace("’", "").replace("`", "").trim();
	}

	void clearHistory()
	{
		history.clear();
		lastFinished = null;
		saveHistory();
	}

	void exportNpc(String name)
	{
		final List<Fight> fights = new ArrayList<>();
		for (Fight fight : history)
		{
			if (fight.encounterName().equals(name))
			{
				fights.add(fight);
			}
		}
		writeCsvAsync(sanitize(name), fights);
	}

	void exportAll(boolean bossOnly)
	{
		final List<Fight> fights = new ArrayList<>();
		for (Fight fight : history)
		{
			if (!bossOnly || isBoss(fight))
			{
				fights.add(fight);
			}
		}
		writeCsvAsync(bossOnly ? "bosses" : "all", fights);
	}

	private void writeCsvAsync(String label, List<Fight> fights)
	{
		if (fights.isEmpty())
		{
			setStatus("Nothing to export.");
			return;
		}
		// Disk IO off the client thread.
		executor.execute(() ->
		{
			try
			{
				final Filepath dir = dataDirectory;
				if (dir == null)
				{
					setStatus("Export failed: no data folder.");
					return;
				}
				dir.createDirectories();
				final Filepath out = dir.joinSegment("pvm-" + label + "-" + FILE_TS.format(LocalDateTime.now()) + ".csv");
				try (Writer writer = out.openBufferedWriter())
				{
					writer.write(CSV_HEADER);
					writeRows(writer, fights);
				}
				final String path = out.toString();
				setStatus("Exported " + fights.size() + " fights → " + path);
				clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"PvM Performance: exported " + fights.size() + " fights to " + path, null));
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("PvM Logs export failed", e);
				setStatus("Export failed: " + e.getMessage());
			}
		});
	}

	// Each fight, then its room, then the raid the rooms belong to: the same numbers at three widths, told apart by the
	// level column. Phases are written whole rather than summed away, so which phase a kill went wrong on survives.
	private static void writeRows(Writer writer, List<Fight> fights) throws IOException
	{
		final List<Fight> ordered = new ArrayList<>(fights);
		ordered.sort(Comparator.comparingLong(Fight::getStartMillis));

		List<Encounter> raidRooms = new ArrayList<>();
		Encounter room = null;
		int raidRun = 0;
		String raidName = null;

		boolean closesRoom = false;
		for (Fight fight : ordered)
		{
			final boolean newRaid = fight.getRaidId() != raidRun;
			if (newRaid && !raidRooms.isEmpty())
			{
				writer.write(csvRaidRow(raidName, raidRun, raidRooms));
				raidRooms = new ArrayList<>();
			}
			// A room also ends at the fight that closed it, so a trip's kills are one room row each. Only that fight breaks it:
			// a boss made of several NPCs sets targetDied on the way down and would otherwise split a kill.
			if (room == null || newRaid || closesRoom || fight.getGroupName() == null
				|| !room.accepts(fight.encounterName()))
			{
				writeRoomRow(writer, raidName, raidRun, room);
				room = new Encounter(fight.encounterName(), null, fight.getStartMillis());
				if (fight.getRaidId() > 0)
				{
					raidRooms.add(room);
				}
			}
			raidRun = fight.getRaidId();
			raidName = fight.getRaidName();
			room.add(fight);
			closesRoom = fight.isClosedRoom();
			writer.write(csvFightRow(raidName, raidRun, fight));
		}
		writeRoomRow(writer, raidName, raidRun, room);
		if (!raidRooms.isEmpty())
		{
			writer.write(csvRaidRow(raidName, raidRun, raidRooms));
		}
	}

	// A room row only earns its place when the room holds more than one fight: outside a raid it holds exactly one, so
	// writing it repeated the fight row.
	private static void writeRoomRow(Writer writer, String raidName, int raidRun, Encounter room)
		throws IOException
	{
		if (room != null && room.getFights().size() > 1)
		{
			writer.write(csvRoomRow(raidName, raidRun, room));
		}
	}

	/** The first four columns, which say what the row is a row of. */
	private static String csvScope(String level, String raidName, int raidRun, String room)
	{
		return String.format("%s,%s,%s,\"%s\",", level,
			raidName == null ? "" : raidName,
			raidRun > 0 ? String.valueOf(raidRun) : "",
			room == null ? "" : room.replace('"', '\''));
	}

	static String csvRoomRow(String raidName, int raidRun, Encounter room)
	{
		final String started = ROW_TS.format(LocalDateTime.ofInstant(
			Instant.ofEpochMilli(room.getStartMillis()), ZoneId.systemDefault()));
		final double seconds = Math.max(0.6, room.durationMillis() / 1000.0);
		final int attempts = room.getAttempts();
		// Expected accuracy is per npc reached and expected damage per throw, which is what each side is accumulated over.
		final int targets = room.getTargets();
		return csvScope("room", raidName, raidRun, room.getName())
			+ String.format("%s,,,,%b,%d,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,,%s,%s,%d,%s,%d,%d,%d,%d,%d,%s,%s,%s,%s,%d,%d,%d,%s,"
				+ "%d,%d,%d,%d,%d%n",
				started,
				room.isKilled(),
				room.getDamageDealt(),
				room.getDamageTaken(),
				attempts,
				targets,
				room.getHits(),
				room.accuracy() * 100,
				room.durationMillis() / 1000.0,
				room.getDamageDealt() / seconds,
				room.averageHit(),
				csvExpected(targets == 0 ? -1 : room.sumExpectedAccuracy() / targets * 100, 1),
				csvExpected(attempts == 0 ? -1 : room.sumExpectedAverageHit() / attempts, 2),
				room.getTicksLost(),
				csvExpected(room.ticksLostShare() * 100, 1),
				room.getTicksLostEating(),
				room.getAttacksMade(),
				room.getAttacksPrayed(),
				room.getAttacksPotted(),
				room.getAttacksSwitched(),
				csvExpected(room.efficiency() * 100, 1),
				csvExpected(room.boostShare() * 100, 1),
				csvExpected(room.boostCostShare() * 100, 2),
				csvExpected(room.switchCostShare() * 100, 2),
				room.getThrallDamage(),
				room.getDotDamage(),
				room.getHealed(),
				csvText(room.getStatsWarning()),
				room.getDotTaken(),
				room.getHitsTaken(),
				room.getBiggestHitTaken(),
				room.getDamageTakenUnprotected(),
				room.getReflectDamage());
	}

	static String csvRaidRow(String raidName, int raidRun, List<Encounter> rooms)
	{
		final Raid raid = new Raid(raidName, raidRun, rooms.get(0).getStartMillis());
		for (Encounter room : rooms)
		{
			raid.add(room);
		}
		final String started = ROW_TS.format(LocalDateTime.ofInstant(
			Instant.ofEpochMilli(raid.getStartMillis()), ZoneId.systemDefault()));
		final double seconds = Math.max(0.6, raid.durationMillis() / 1000.0);
		final int attempts = raid.getAttempts();
		final int targets = raid.getTargets();
		return csvScope("raid", raidName, raidRun, null)
			+ String.format("%s,,,,,%d,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,,%s,%s,%d,%s,%d,%d,%d,%d,%d,%s,%s,%s,%s,%d,%d,%d,%s,"
				+ "%d,%d,%d,%d,%d%n",
				started,
				raid.getDamageDealt(),
				raid.getDamageTaken(),
				attempts,
				targets,
				raid.getHits(),
				targets == 0 ? 0 : 100.0 * raid.getHits() / targets,
				raid.durationMillis() / 1000.0,
				raid.getDamageDealt() / seconds,
				attempts == 0 ? 0 : (double) raid.getDamageDealt() / attempts,
				csvExpected(targets == 0 ? -1 : raid.sumExpectedAccuracy() / targets * 100, 1),
				csvExpected(attempts == 0 ? -1 : raid.sumExpectedAverageHit() / attempts, 2),
				raid.getTicksLost(),
				csvExpected(raid.ticksLostShare() * 100, 1),
				raid.getTicksLostEating(),
				raid.getAttacksMade(),
				raid.getAttacksPrayed(),
				raid.getAttacksPotted(),
				raid.getAttacksSwitched(),
				csvExpected(raid.efficiency() * 100, 1),
				csvExpected(raid.boostShare() * 100, 1),
				csvExpected(raid.boostCostShare() * 100, 2),
				csvExpected(raid.switchCostShare() * 100, 2),
				raid.getThrallDamage(),
				raid.getDotDamage(),
				raid.getHealed(),
				csvText(raid.getStatsWarning()),
				raid.getDotTaken(),
				raid.getHitsTaken(),
				raid.getBiggestHitTaken(),
				raid.getDamageTakenUnprotected(),
				raid.getReflectDamage());
	}

	private static String csvFightRow(String raidName, int raidRun, Fight fight)
	{
		// "fight" rather than "phase": a phase is what it is inside a raid, but outside one it is a single kill, and the
		// label read as jargon.
		return csvScope("fight", raidName, raidRun, fight.encounterName()) + csvRow(fight);
	}

	static String csvRow(Fight fight)
	{
		final String started = ROW_TS.format(LocalDateTime.ofInstant(
			Instant.ofEpochMilli(fight.getStartMillis()), ZoneId.systemDefault()));
		return String.format("%s,\"%s\",%d,%d,%b,%d,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,%s,%s,%s,%d,%s,%d,%d,%d,%d,%d,%s,%s,%s,%s,%d,%d,%d,%s,"
			+ "%d,%d,%d,%d,%d%n",
			started,
			fight.getTargetName().replace('"', '\''),
			fight.getTargetId(),
			fight.getMaxHp(),
			fight.isTargetDied(),
			fight.getDamageDealt(),
			fight.getDamageTaken(),
			fight.getAttempts(),
			fight.resolvedTargets(),
			fight.getHits(),
			fight.accuracy() * 100,
			fight.durationMillis() / 1000.0,
			fight.dps(),
			fight.averageHit(),
			csvExpected(fight.expectedMaxHit(), 1),
			csvExpected(fight.expectedAccuracy() * 100, 1),
			csvExpected(fight.expectedAverageHit(), 2),
			fight.getTicksLost(),
			csvExpected(fight.ticksLostShare() * 100, 1),
			fight.getTicksLostEating(),
			fight.getAttacksMade(),
			fight.getAttacksPrayed(),
			fight.getAttacksPotted(),
			fight.getAttacksSwitched(),
			csvExpected(fight.efficiency() * 100, 1),
			csvExpected(fight.boostShare() * 100, 1),
			csvExpected(fight.boostCostShare() * 100, 2),
			csvExpected(fight.switchCostShare() * 100, 2),
			fight.getThrallDamage(),
			fight.getDotDamage(),
			fight.getHealed(),
			csvText(fight.getStatsWarning()),
			fight.getDotTaken(),
			fight.getHitsTaken(),
			fight.getBiggestHitTaken(),
			fight.getDamageTakenUnprotected(),
			fight.getReflectDamage());
	}

	/**
	 * Expected figures are left blank rather than written as a number when the
	 * model had nothing to say, so a reader can tell "unknown" from a real zero.
	 */
	private static String csvExpected(double value, int decimals)
	{
		return value < 0 ? "" : String.format("%." + decimals + "f", value);
	}

	/**
	 * A free-text column: quoted, empty when there is nothing to say. Commas become
	 * semicolons and quotes apostrophes, so a reader that splits on commas - as some
	 * spreadsheet imports and this plugin's own tests do - still sees one column.
	 */
	static String csvText(String text)
	{
		return text == null || text.isEmpty() ? "" : "\"" + text.replace(',', ';').replace('"', '\'') + "\"";
	}

	private static String sanitize(String name)
	{
		return name.replaceAll("[^a-zA-Z0-9]+", "_");
	}

	private void setStatus(String text)
	{
		if (panel != null)
		{
			panel.setStatus(text);
		}
	}
}
