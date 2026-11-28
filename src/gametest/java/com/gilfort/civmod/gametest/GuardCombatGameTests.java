package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.job.CatchUp;
import com.gilfort.civmod.job.GuardCombat;
import com.gilfort.civmod.job.GuardEnemies;
import com.gilfort.civmod.job.GuardLoot;
import com.gilfort.civmod.job.GuardNumbers;
import com.gilfort.civmod.job.JobBalancing;
import com.gilfort.civmod.job.JobBuffer;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.job.SkillRules;
import com.gilfort.civmod.logistics.ReservedFor;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.ThoughtBubble;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.GuardMode;
import com.gilfort.civmod.settlement.GuardPosts;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.TestSettlements;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the combat of the Guard (vault {@code Jobs/Guard.md}, spec #349, ticket #353): the numbers of a hit and of a
 * kill, which mobs are enemies, the fight itself (a zombie in range dies, one behind a wall is left alone, the chase limit),
 * the weapon and its order, the loot, the delivery and the mode change after a fight. All fights run at night so the zombies
 * do not burn.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class GuardCombatGameTests {

    private static final BlockPos POST = ServiceGameTests.JOB_BLOCK;
    /** Where a mob stands in plain sight of the Guard, a few blocks from it. */
    private static final BlockPos IN_SIGHT = new BlockPos(8, 1, 2);
    /** Long enough for a Guard to walk over, kill a zombie and for the scan that finds it (a second at most). */
    private static final int FIGHT_TICKS = 400;
    /** Ticks after which a Guard that went too far out has still not hit its mob (it ignores it for 100). */
    private static final int PAST_LIMIT_CHECK_TICKS = 90;
    /** The Guard Post level whose skill cap lets a Guard reach level 100. */
    private static final int TOP_LEVEL = 5;

    private GuardCombatGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "guard_combat_numbers", GuardCombatGameTests::numbers);
            register(helper, "guard_enemy_rules", GuardCombatGameTests::enemyRules);
            register(helper, "guard_kills_zombie_in_range", GuardCombatGameTests::killsZombie);
            register(helper, "guard_ignores_zombie_behind_wall", GuardCombatGameTests::ignoresZombieBehindWall);
            register(helper, "guard_chase_ends_24_past_border", GuardCombatGameTests::chaseLimit);
            register(helper, "guard_without_weapon_orders_one", GuardCombatGameTests::withoutWeapon);
            register(helper, "guard_attacks_angry_enderman_not_calm", GuardCombatGameTests::angryEnderman);
            register(helper, "guard_hits_and_wear", GuardCombatGameTests::hitsAndWear);
            register(helper, "guard_kill_exp", GuardCombatGameTests::killExp);
            register(helper, "guard_loot_into_buffer", GuardCombatGameTests::lootIntoBuffer);
            register(helper, "guard_loot_overflow", GuardCombatGameTests::lootOverflow);
            register(helper, "guard_delivers_at_80_percent_and_at_change", GuardCombatGameTests::delivers);
            register(helper, "guard_mode_change_after_fight", GuardCombatGameTests::modeChange);
            register(helper, "guard_enemy_ends_break", GuardCombatGameTests::enemyEndsBreak);
            register(helper, "guard_mob_fights_back_no_damage", GuardCombatGameTests::mobFightsBack);
            register(helper, "guard_no_kill_in_catch_up", GuardCombatGameTests::noKillInCatchUp);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- setup

    /** A Guard Post at {@link #POST} and a Guard with the weapon on its belt, decided once so it is on its job step. */
    private static ServiceGameTests.Scene scene(GameTestHelper helper, ItemStack weapon) {
        ServiceGameTests.Scene scene = ServiceGameTests.scene(helper, CivMod.id("guard_post"), CivMod.id("guard"));
        scene.performer().getInventory().setItem(Settler.BELT_START, weapon);
        scene.performer().getWork().decideNow(helper.getLevel());
        return scene;
    }

    private static Settler guard(ServiceGameTests.Scene scene) {
        return scene.performer();
    }

    /** A mob that stands where it is: no AI, no random armor (it would soak up damage), and a pumpkin on a zombie's head against the sun. */
    private static <T extends Mob> T spawn(GameTestHelper helper, EntityType<T> type, BlockPos relative) {
        T mob = spawnLive(helper, type, relative);
        mob.setNoAi(true);
        return mob;
    }

    /** Like {@link #spawn}, with its AI on. */
    private static <T extends Mob> T spawnLive(GameTestHelper helper, EntityType<T> type, BlockPos relative) {
        T mob = helper.spawn(type, relative);
        mob.setPersistenceRequired();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            mob.setItemSlot(slot, ItemStack.EMPTY);
        }
        if (mob instanceof Zombie) {
            mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        }
        return mob;
    }

    /** The Guard Post at its top level and the Guard at the given skill level. */
    private static void skill(GameTestHelper helper, Settler guard, int level) {
        BlockPos post = helper.absolutePos(POST);
        helper.getBlockEntity(POST, JobBlockEntity.class).setJobLevel(TOP_LEVEL);
        SettlementManager.setLevel(SettlementData.get(helper.getLevel()), post, TOP_LEVEL, SettlementManager.Listener.NONE);
        guard.getSkills().set(ModJobs.GUARD.get(), new SettlerSkills.Skill(level, 0));
    }

    private static JobStatus status(GameTestHelper helper, Settler guard) {
        return guard.getWork().status(helper.absolutePos(POST));
    }

    private static int totalExp(Settler settler) {
        var skill = settler.getSkills().get(ModJobs.GUARD.get());
        int total = skill.exp();
        for (int level = 1; level < skill.level(); level++) {
            total += SkillRules.get().expToNext(level);
        }
        return total;
    }

    // ---------------------------------------------------------------- the numbers

    /**
     * The damage of a hit, the hits a kill needs, the time between hits and the EXP of a kill, from literal numbers of the
     * ledger and of vanilla.
     */
    private static void numbers(GameTestHelper helper) {
        double[] vanilla = {4.0, 5.0, 6.0, 7.0, 8.0};
        ItemStack[] swords = {new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.STONE_SWORD), new ItemStack(Items.IRON_SWORD),
                new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.NETHERITE_SWORD)};
        for (int i = 0; i < swords.length; i++) {
            helper.assertTrue(GuardCombat.weaponDamage(swords[i]) == vanilla[i], swords[i] + " hits for " + vanilla[i] + ": " + GuardCombat.weaponDamage(swords[i]));
        }
        helper.assertTrue(GuardCombat.weaponDamage(new ItemStack(Items.IRON_AXE)) == 9.0, "An iron axe hits for 9: " + GuardCombat.weaponDamage(new ItemStack(Items.IRON_AXE)));
        helper.assertTrue(GuardCombat.weaponDamage(new ItemStack(Items.STICK)) == 1.0, "A stick is a bare hand");

        // One point of damage for every 25 levels.
        int[] levels = {1, 24, 25, 49, 50, 74, 75, 99, 100};
        double[] bonus = {0, 0, 1, 1, 2, 2, 3, 3, 4};
        for (int i = 0; i < levels.length; i++) {
            helper.assertTrue(GuardCombat.damage(6.0, levels[i]) == 6.0 + bonus[i], "Level " + levels[i] + " adds " + bonus[i]);
        }
        // A zombie has 20 HP: an iron sword needs 4 hits at level 1 and 2 at level 100 (the ledger).
        helper.assertTrue(GuardCombat.hitsToKill(20.0, GuardCombat.damage(6.0, 1)) == 4, "Level 1, iron sword: 4 hits");
        helper.assertTrue(GuardCombat.hitsToKill(20.0, GuardCombat.damage(6.0, 100)) == 2, "Level 100, iron sword: 2 hits");

        // Vanilla attack speed: an iron sword hits 1.6 times a second, an iron axe 0.9 times.
        helper.assertTrue(Math.abs(GuardCombat.weaponSpeed(new ItemStack(Items.IRON_SWORD)) - 1.6) < 1.0E-6, "Iron sword speed: " + GuardCombat.weaponSpeed(new ItemStack(Items.IRON_SWORD)));
        helper.assertTrue(GuardCombat.hitInterval(1.6) == 13 && GuardCombat.hitInterval(0.9) == 22, "Hit intervals 13 and 22 ticks");
        helper.assertTrue(GuardCombat.hitInterval(100.0) == GuardCombat.MIN_HIT_INTERVAL_TICKS && GuardCombat.hitInterval(0.0) == GuardCombat.MAX_HIT_INTERVAL_TICKS,
                "The interval stays within its bounds");

        // Kill EXP: half the max health per level of the post (a zombie: 10 on a level 1 post).
        double half = GuardCombat.DEFAULT_KILL_EXP_PER_HEALTH;
        helper.assertTrue(GuardCombat.killExp(20.0, 1, half) == 10 && GuardCombat.killExp(20.0, 3, half) == 30 && GuardCombat.killExp(20.0, 5, half) == 50,
                "A zombie on level 1, 3 and 5");
        helper.assertTrue(GuardCombat.killExp(16.0, 1, half) == 8 && GuardCombat.killExp(40.0, 1, half) == 20, "A spider 8, an enderman 20");
        helper.assertTrue(GuardCombat.killExp(20.0, 1, 1.0) == 20, "A pack that sets one point per health doubles it");

        // The job file: the default, an override, and the later file wins per field.
        helper.assertTrue(JobBalancing.of(ModJobs.GUARD.get()).guard().isEmpty(), "The Guard ships without an override");
        GuardNumbers parsed = GuardNumbers.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"kill_exp_per_health\": 2.0}")).getOrThrow();
        helper.assertTrue(parsed.killExpPerHealthOrDefault() == 2.0 && GuardNumbers.NONE.killExpPerHealthOrDefault() == half, "Override and default");
        helper.assertTrue(GuardNumbers.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"kill_exp_per_health\": -1}")).result().isEmpty(),
                "A negative value is refused");
        JobBalancing merged = JobBalancing.merge(List.of(JsonParser.parseString("{\"guard\": {\"kill_exp_per_health\": 2.0}}"), JsonParser.parseString("{\"base_exp\": 3}")));
        helper.assertTrue(merged.guard().orElseThrow().killExpPerHealthOrDefault() == 2.0 && merged.baseExp().orElseThrow() == 3, "Files merge");
        helper.succeed();
    }

    // ---------------------------------------------------------------- enemies

    /** Every Enemy counts, a calm neutral mob does not, an angry one does, whoever provoked it; the ignore tag ships empty. */
    private static void enemyRules(GameTestHelper helper) {
        Zombie zombie = spawn(helper, EntityTypes.ZOMBIE, new BlockPos(3, 1, 3));
        Mob creeper = spawn(helper, EntityTypes.CREEPER, new BlockPos(4, 1, 3));
        Mob skeleton = spawn(helper, EntityTypes.SKELETON, new BlockPos(5, 1, 3));
        Mob pig = spawn(helper, EntityTypes.PIG, new BlockPos(6, 1, 3));
        Enderman enderman = spawn(helper, EntityTypes.ENDERMAN, new BlockPos(7, 1, 3));
        Wolf wolf = spawn(helper, EntityTypes.WOLF, new BlockPos(8, 1, 3));
        IronGolem golem = spawn(helper, EntityTypes.IRON_GOLEM, new BlockPos(9, 1, 3));
        helper.assertTrue(GuardEnemies.isEnemy(zombie) && GuardEnemies.isEnemy(creeper) && GuardEnemies.isEnemy(skeleton), "Zombie, creeper and skeleton are enemies");
        helper.assertFalse(GuardEnemies.isEnemy(pig), "A pig is no enemy");
        helper.assertFalse(GuardEnemies.isEnemy(enderman) || GuardEnemies.isEnemy(wolf) || GuardEnemies.isEnemy(golem), "Calm neutral mobs are no enemies, though an enderman is an Enemy too");
        enderman.setTimeToRemainAngry(600L);
        wolf.setTimeToRemainAngry(600L);
        golem.setTimeToRemainAngry(600L);
        helper.assertTrue(GuardEnemies.isEnemy(enderman) && GuardEnemies.isEnemy(wolf) && GuardEnemies.isEnemy(golem), "Angry neutral mobs are enemies");
        helper.assertTrue(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENTITY_TYPE).get(GuardEnemies.IGNORED).map(set -> set.size() == 0).orElse(true),
                "civmod:guard_ignores ships empty");
        for (Mob mob : List.of(zombie, creeper, skeleton, pig, enderman, wolf, golem)) {
            mob.discard();
        }
        helper.succeed();
    }

    /** An angry enderman is attacked, a calm one next to it is not. */
    private static void angryEnderman(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        Enderman angry = spawn(helper, EntityTypes.ENDERMAN, IN_SIGHT);
        Enderman calm = spawn(helper, EntityTypes.ENDERMAN, IN_SIGHT.offset(0, 0, 3));
        angry.setTimeToRemainAngry(6000L);
        helper.onEachTick(() -> {
            if (angry.getHealth() < angry.getMaxHealth() || !angry.isAlive()) {
                helper.assertTrue(calm.getHealth() == calm.getMaxHealth(), "The calm enderman was left alone");
                angry.discard();
                calm.discard();
                ServiceGameTests.cleanUp(level, scene);
                helper.succeed();
            }
        });
    }

    // ---------------------------------------------------------------- the fight

    /** A zombie in range and in sight dies; no chat message is sent for it. */
    private static void killsZombie(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        Zombie zombie = spawn(helper, EntityTypes.ZOMBIE, IN_SIGHT);
        Settler guard = guard(scene);
        int[] fighting = new int[1];
        helper.onEachTick(() -> {
            if (status(helper, guard) == JobStatus.FIGHTING) {
                fighting[0]++;
            }
            if (!zombie.isAlive()) {
                helper.assertTrue(fighting[0] > 0, "The Guard was fighting");
                ServiceGameTests.cleanUp(level, scene);
                helper.succeed();
            }
        });
    }

    /** A zombie behind a wall is not seen, so it is left alone. */
    private static void ignoresZombieBehindWall(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        for (int z = 0; z < 9; z++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(5, y, z), Blocks.STONE);
            }
        }
        Zombie zombie = spawn(helper, EntityTypes.ZOMBIE, IN_SIGHT);
        Settler guard = guard(scene);
        boolean[] fought = new boolean[1];
        helper.onEachTick(() -> fought[0] |= status(helper, guard) == JobStatus.FIGHTING);
        helper.runAfterDelay(FIGHT_TICKS, () -> {
            helper.assertFalse(fought[0], "The Guard never fought");
            helper.assertTrue(zombie.getHealth() == zombie.getMaxHealth(), "The zombie behind the wall is untouched: " + zombie.getHealth());
            zombie.discard();
            ServiceGameTests.cleanUp(level, scene);
            helper.succeed();
        });
    }

    /**
     * The chase ends 24 blocks past the border: a mob out there is not chased from the start, a Guard that went that far out
     * lets its mob go, and one just inside the limit fights. The limit is found along the x axis from the border the
     * settlement really has (the test settlement has a Center Block too).
     */
    private static void chaseLimit(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        Settlement settlement = scene.settlement();
        int found = 15;
        while (JobAssignments.distancePastBorder(settlement, helper.absolutePos(new BlockPos(found, 1, 4))) <= 24.0) {
            found++;
        }
        int limit = found;
        helper.assertTrue(JobAssignments.distancePastBorder(settlement, helper.absolutePos(new BlockPos(5, 1, 4))) < 0, "The Guard Post is inside the border");
        helper.assertTrue(limit < 80, "The limit lies within reach: x = " + limit);
        List<long[]> forced = LumberjackGameTests.forceChunks(helper, BlockPos.ZERO, new BlockPos(limit + 12, 0, 8));
        for (int x = 15; x < limit + 12; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Settler guard = guard(scene);
        // A zombie past the limit is not chased, though the Guard stands next to it.
        Zombie far = spawn(helper, EntityTypes.ZOMBIE, new BlockPos(limit + 3, 1, 4));
        Zombie[] near = new Zombie[1];
        helper.startSequence().thenExecute(() -> teleport(helper, guard, new BlockPos(limit + 1, 1, 4)))
                .thenIdle(FIGHT_TICKS / 2)
                .thenExecute(() -> {
                    helper.assertTrue(far.getHealth() == far.getMaxHealth(), "A zombie past the limit is left alone: " + far.getHealth());
                    // A Guard past the limit lets a mob inside it go.
                    near[0] = spawn(helper, EntityTypes.ZOMBIE, new BlockPos(limit - 3, 1, 4));
                    teleport(helper, guard, new BlockPos(limit + 1, 1, 4));
                })
                // Within the 5 seconds the mob is left alone after the Guard let it go.
                .thenIdle(PAST_LIMIT_CHECK_TICKS)
                .thenExecute(() -> {
                    helper.assertTrue(near[0].getHealth() == near[0].getMaxHealth(), "A Guard past the limit does not fight: " + near[0].getHealth());
                    // Inside the limit the same Guard fights.
                    teleport(helper, guard, new BlockPos(limit - 6, 1, 4));
                })
                .thenIdle(FIGHT_TICKS)
                .thenExecute(() -> {
                    helper.assertTrue(!near[0].isAlive(), "Inside the limit the zombie dies: " + near[0].getHealth());
                    far.discard();
                    near[0].discard();
                    forced.forEach(chunk -> level.setChunkForced((int) chunk[0], (int) chunk[1], false));
                    ServiceGameTests.cleanUp(level, scene);
                })
                .thenSucceed();
    }

    private static void teleport(GameTestHelper helper, Settler guard, BlockPos relative) {
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(relative));
        guard.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
        guard.getWork().decideNow(helper.getLevel());
    }

    /** A mob that was hit fights back, and a settler takes no damage from it. */
    private static void mobFightsBack(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        Zombie zombie = spawnLive(helper, EntityTypes.ZOMBIE, IN_SIGHT);
        Settler guard = guard(scene);
        boolean[] targeted = new boolean[1];
        helper.onEachTick(() -> {
            targeted[0] |= zombie.getTarget() == guard;
            helper.assertTrue(guard.getHealth() == guard.getMaxHealth(), "The settler is never hurt: " + guard.getHealth());
            if (!zombie.isAlive()) {
                helper.assertTrue(targeted[0], "The zombie fought back");
                ServiceGameTests.cleanUp(level, scene);
                helper.succeed();
            }
        });
    }

    // ---------------------------------------------------------------- hits, wear, EXP

    /**
     * Level 1 with an iron sword needs 4 hits on a zombie; at level 100 a hit does 10 (9.8 after the zombie's armor, as in
     * vanilla). The hits wear the belt sword, not the copy in hand.
     */
    private static void hitsAndWear(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        skill(helper, guard, 1);
        ItemStack belt = guard.getInventory().getItem(Settler.BELT_START);
        Zombie[] zombie = {spawn(helper, EntityTypes.ZOMBIE, IN_SIGHT)};
        List<Float> healths = new ArrayList<>();
        boolean[] secondRound = new boolean[1];
        helper.onEachTick(() -> {
            if (healths.isEmpty() || healths.getLast() != zombie[0].getHealth()) {
                healths.add(zombie[0].getHealth());
            }
            if (zombie[0].isAlive()) {
                return;
            }
            if (!secondRound[0]) {
                // A zombie's own armor (2) takes a little off every hit, as in vanilla: 6 becomes 5.9.
                helper.assertTrue(healths.size() == 5 && Math.abs(20.0F - healths.get(1) - 5.9F) < 0.1F, "Level 1, iron sword: four hits of about 6: " + healths);
                helper.assertTrue(belt.getDamageValue() >= 4, "The belt sword wore: " + belt.getDamageValue());
                helper.assertFalse(guard.getMainHandItem() == belt, "The hand holds a copy, not the belt item");
                secondRound[0] = true;
                skill(helper, guard, 100);
                zombie[0] = spawn(helper, EntityTypes.ZOMBIE, IN_SIGHT);
                healths.clear();
                healths.add(zombie[0].getHealth());
                return;
            }
            helper.assertTrue(healths.size() <= 4 && Math.abs(20.0F - healths.get(1) - 9.8F) < 0.2F, "Level 100, iron sword: hits of about 10: " + healths);
            ServiceGameTests.cleanUp(level, scene);
            helper.succeed();
        });
    }

    /** A kill on a level 1 post gives half the zombie's max health: 10 EXP (a talent may add some). */
    private static void killExp(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        int before = totalExp(guard);
        Zombie zombie = spawn(helper, EntityTypes.ZOMBIE, IN_SIGHT);
        helper.onEachTick(() -> {
            if (!zombie.isAlive()) {
                int gained = totalExp(guard) - before;
                helper.assertTrue(gained >= 10 && gained < 20, "A kill gives 10 EXP, the duty EXP of a few seconds is none: " + gained);
                ServiceGameTests.cleanUp(level, scene);
                helper.succeed();
            }
        });
    }

    // ---------------------------------------------------------------- the weapon

    /** Without a weapon there is no fight, but the "No tool" bubble and a weapon request through the tool supply. */
    private static void withoutWeapon(GameTestHelper helper) {
        BlockPos clerk = ToolSupplyGameTests.clerkWith(helper, List.of());
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        Settlement settlement = ToolSupplyGameTests.settlement(helper, clerk);
        BlockPos relative = new BlockPos(1, 1, 13);
        TestSettlements.setBlock(helper, relative, ModBlocks.GUARD_POST.get());
        BlockPos post = helper.absolutePos(relative);
        Settler guard = ToolSupplyGameTests.worker(helper, data, settlement, post, post.offset(1, 0, 1), "Guard", ModJobs.GUARD.get());
        Zombie zombie = spawn(helper, EntityTypes.ZOMBIE, relative.offset(4, 0, 0));
        guard.getWork().restoreToolNeeds(List.of());
        guard.getWork().decideNow(level);

        helper.assertTrue(guard.getWork().status(post) == JobStatus.NO_TOOL, "The status is No tool: " + guard.getWork().status(post));
        helper.assertTrue(guard.getWork().recentThoughts().stream().anyMatch(show -> show.bubble() == ThoughtBubble.NO_TOOL && show.on() && show.icon() == Items.IRON_SWORD),
                "The tool missing bubble shows a sword: " + guard.getWork().recentThoughts());
        var asked = settlement.requests().stream().filter(request -> request.reservedFor().filter(ReservedFor.settler(guard.getUUID())::equals).isPresent()).toList();
        helper.assertTrue(asked.size() == 1 && asked.getFirst().target().equals(java.util.Optional.of(clerk))
                && new ItemStack(asked.getFirst().item().item()).is(ItemTags.SWORDS) | new ItemStack(asked.getFirst().item().item()).is(ItemTags.AXES)
                | new ItemStack(asked.getFirst().item().item()).is(ItemTags.SPEARS), "One request for a weapon at the Home Clerk: " + settlement.requests());
        helper.runAfterDelay(FIGHT_TICKS / 2, () -> {
            helper.assertTrue(zombie.getHealth() == zombie.getMaxHealth(), "The unarmed Guard does not fight: " + zombie.getHealth());
            zombie.discard();
            ToolSupplyGameTests.cleanUp(helper, guard);
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- loot and delivery

    /** What a mob dropped for a kill of a Guard goes into the job buffer, and nothing lies on the ground. */
    private static void lootIntoBuffer(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        Zombie zombie = spawn(helper, EntityTypes.ZOMBIE, IN_SIGHT);
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND, 3));
        // More than 1.0: dropped for every kill, not only by a player.
        zombie.setDropChance(EquipmentSlot.MAINHAND, 2.0F);
        BlockPos post = helper.absolutePos(POST);
        helper.onEachTick(() -> {
            if (!zombie.isAlive()) {
                JobBuffer buffer = guard.getWork().buffer(post);
                helper.assertTrue(buffer.stacks().stream().anyMatch(stack -> stack.is(Items.DIAMOND) && stack.getCount() == 3), "The diamonds are in the buffer: " + buffer.stacks());
                helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, zombie.getBoundingBox().inflate(6.0), item -> item.getItem().is(Items.DIAMOND)).isEmpty(),
                        "Nothing lies on the ground");
                ServiceGameTests.cleanUp(level, scene);
                helper.succeed();
            }
        });
    }

    /** Loot that does not fit into the buffer stays among the drops and falls to the ground. */
    private static void lootOverflow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        JobBuffer buffer = new JobBuffer();
        buffer.add(new ItemStack(Items.COBBLESTONE, JobBuffer.CAPACITY - 5));
        ItemEntity whole = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.DIAMOND, 3));
        ItemEntity part = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.EMERALD, 4));
        ItemEntity none = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.GOLD_INGOT, 1));
        List<ItemEntity> drops = new ArrayList<>(List.of(whole, part, none));
        GuardLoot.take(buffer, drops);
        helper.assertTrue(buffer.count() == JobBuffer.CAPACITY, "The buffer is full: " + buffer.count());
        helper.assertTrue(drops.equals(List.of(part, none)), "The first drop went in whole, the others stay: " + drops.size());
        helper.assertTrue(part.getItem().getCount() == 2 && none.getItem().getCount() == 1, "Two emeralds fit, two fall to the ground: " + part.getItem());
        helper.succeed();
    }

    /**
     * The Guard takes its buffer to the drop-off point at about 80 % and once when the half day changes (18:30), but it does not
     * deliver for a few items in the same half day.
     */
    private static void delivers(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        guard.setNoAi(true);
        BlockPos post = helper.absolutePos(POST);
        long morning = 2000L;
        ServiceGameTests.setDayTime(level, morning);
        guard.getWork().decideNow(level);
        helper.assertTrue(status(helper, guard) == JobStatus.GUARDING, "On guard: " + status(helper, guard));

        guard.getWork().buffer(post).add(new ItemStack(Items.COBBLESTONE, 10));
        ServiceGameTests.setDayTime(level, morning + 100);
        guard.getWork().decideNow(level);
        helper.assertTrue(status(helper, guard) == JobStatus.GUARDING, "A few items wait for the change of the half day: " + status(helper, guard));

        ServiceGameTests.setDayTime(level, GuardPosts.EVENING + 10);
        guard.getWork().decideNow(level);
        helper.assertTrue(status(helper, guard) == JobStatus.DELIVERING, "At 18:30 it takes them to the drop-off: " + status(helper, guard));

        guard.getWork().buffer(post).takeOne(stack -> true);
        guard.getWork().buffer(post).takeOne(stack -> true);
        guard.getWork().buffer(post).add(new ItemStack(Items.COBBLESTONE, 4));
        guard.getWork().buffer(post).add(new ItemStack(Items.DIRT, (int) (JobBuffer.CAPACITY * JobBuffer.DELIVER_RATIO)));
        ServiceGameTests.setDayTime(level, GuardPosts.EVENING + 200);
        guard.getWork().decideNow(level);
        helper.assertTrue(status(helper, guard) == JobStatus.DELIVERING, "At 80 % it delivers at once: " + status(helper, guard));
        ServiceGameTests.setDayTime(level, morning);
        ServiceGameTests.cleanUp(level, scene);
        helper.succeed();
    }

    // ---------------------------------------------------------------- a fight in the way

    /** A mode changed during a fight applies after it, and the fight holds the decisions (no delivery in between). */
    private static void modeChange(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        BlockPos post = helper.absolutePos(POST);
        Zombie zombie = spawn(helper, EntityTypes.ZOMBIE, IN_SIGHT);
        zombie.setHealth(zombie.getMaxHealth());
        boolean[] changed = new boolean[1];
        helper.onEachTick(() -> {
            if (!changed[0] && status(helper, guard) == JobStatus.FIGHTING) {
                changed[0] = true;
                GuardPosts.setMode(SettlementData.get(level), post, GuardMode.PATROL);
                guard.getWork().buffer(post).add(new ItemStack(Items.COBBLESTONE, JobBuffer.CAPACITY));
                guard.getWork().decideNow(level);
                helper.assertTrue(guard.getWork().appliedGuardMode(post).orElseThrow() == GuardMode.GUARD, "Still the old mode during the fight");
                helper.assertTrue(status(helper, guard) == JobStatus.FIGHTING, "The decision was held: " + status(helper, guard));
            }
            if (changed[0] && !zombie.isAlive() && status(helper, guard) != JobStatus.FIGHTING) {
                guard.getWork().decideNow(level);
                helper.assertTrue(guard.getWork().appliedGuardMode(post).orElseThrow() == GuardMode.PATROL, "The new mode applies after the fight");
                ServiceGameTests.cleanUp(level, scene);
                helper.succeed();
            }
        });
    }

    /** An enemy that comes into range ends a break at once. */
    private static void enemyEndsBreak(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        BlockPos jobBlock = helper.absolutePos(POST);
        BlockPos point = helper.absolutePos(new BlockPos(6, 0, 4));
        GuardPosts.togglePoint(SettlementData.get(level), jobBlock, point, true, net.minecraft.core.Direction.SOUTH);
        Vec3 stand = Vec3.atBottomCenterOf(point.above());
        guard.snapTo(stand.x, stand.y, stand.z, 0.0F, 0.0F);
        // A few ticks first, so the Guard is on the ground when the break sits it down.
        helper.runAfterDelay(10, () -> {
            int decisions = 0;
            while (status(helper, guard) != JobStatus.PAUSING && decisions < 400) {
                guard.getWork().decideNow(level);
                decisions++;
            }
            helper.assertTrue(guard.getWork().status(jobBlock) == JobStatus.PAUSING && guard.isSitting(), "The Guard sits on a break");
            Zombie zombie = spawn(helper, EntityTypes.ZOMBIE, IN_SIGHT);
            helper.succeedWhen(() -> {
                helper.assertFalse(guard.isSitting(), "The Guard stands up");
                helper.assertTrue(status(helper, guard) == JobStatus.FIGHTING || !zombie.isAlive(), "It fights: " + status(helper, guard));
                zombie.discard();
                ServiceGameTests.cleanUp(level, scene);
            });
        });
    }

    /** Catch-up simulates no fight: a zombie in range is not touched, the duty EXP is paid as before. */
    private static void noKillInCatchUp(GameTestHelper helper) {
        ServiceGameTests.Scene scene = scene(helper, new ItemStack(Items.IRON_SWORD));
        ServerLevel level = helper.getLevel();
        Settler guard = guard(scene);
        guard.setNoAi(true);
        Zombie zombie = spawn(helper, EntityTypes.ZOMBIE, IN_SIGHT);
        long dayStart = DayClock.day(level) * DayClock.DAY_TICKS;
        guard.getWork().setCatchUp(dayStart - DayClock.DAY_TICKS, dayStart);
        CatchUp.runNow(level, List.of(guard));
        helper.assertTrue(zombie.getHealth() == zombie.getMaxHealth() && zombie.isAlive(), "No kill while the settler was away: " + zombie.getHealth());
        zombie.discard();
        ServiceGameTests.cleanUp(level, scene);
        helper.succeed();
    }
}
