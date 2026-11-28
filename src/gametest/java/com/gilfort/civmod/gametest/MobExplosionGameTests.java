package com.gilfort.civmod.gametest;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the block protection against mob explosions inside a settlement border (#351): a mob's explosion breaks no
 * block inside, also when the mob stands outside; entities still take damage; explosions caused by players and the config
 * switch are unchanged.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MobExplosionGameTests {

    private static final float RADIUS = 4.0F;
    /** The block under the blast that an unprotected explosion always breaks. */
    private static final BlockPos TARGET = new BlockPos(5, 4, 4);
    private static final Vec3 BLAST = new Vec3(5.5, 5.5, 4.5);
    private static final BlockPos CLERK = new BlockPos(1, 1, 1);
    /** Blast height of the border test and the blocks of its line. */
    private static final int LINE_Y = 5;
    private static final float LINE_RADIUS = 8.0F;
    /** Last X (relative) whose horizontal distance to the Clerk at X 1 is still within a level 1 join radius of 10. */
    private static final int LAST_INSIDE_X = 11;
    private static final BlockPos FAR_CORNER = new BlockPos(48, 0, 16);

    private MobExplosionGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mob_explosion_inside_border", MobExplosionGameTests::insideBorder);
            register(helper, "mob_explosion_from_outside", MobExplosionGameTests::fromOutside);
            register(helper, "mob_explosion_far_from_border", MobExplosionGameTests::farFromBorder);
            register(helper, "mob_explosion_protection_off", MobExplosionGameTests::protectionOff);
            register(helper, "mob_explosion_tnt_owner", MobExplosionGameTests::tntOwner);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A creeper's blast inside the border breaks no block and still hurts entities. */
    private static void insideBorder(GameTestHelper helper) {
        LumberjackGameTests.forceChunks(helper, new BlockPos(24, 0, 24));
        TestSettlements.setBlock(helper, CLERK, ModBlocks.CLERK_BLOCK.get());
        Pig pig = helper.spawn(EntityTypes.PIG, new BlockPos(5, 5, 9));
        helper.assertTrue(survives(helper, creeper(helper), Level.ExplosionInteraction.MOB), "The block inside the border survives a creeper");
        helper.assertTrue(!pig.isAlive() || pig.getHealth() < pig.getMaxHealth(), "Entity damage stays: pig health " + pig.getHealth());
        helper.succeed();
    }

    /**
     * The blast stands just outside a level 1 border, the line of blocks runs across it: only the blocks inside the border
     * survive.
     */
    private static void fromOutside(GameTestHelper helper) {
        LumberjackGameTests.forceChunks(helper, FAR_CORNER);
        TestSettlements.earlierVersion(SettlementData.get(helper.getLevel()), helper.absolutePos(CLERK));
        for (int x = LAST_INSIDE_X - 3; x <= LAST_INSIDE_X + 5; x++) {
            helper.setBlock(new BlockPos(x, LINE_Y, 1), Blocks.DIRT);
        }
        Vec3 blast = helper.absoluteVec(new Vec3(LAST_INSIDE_X + 1.5, LINE_Y + 0.5, 1.5));
        helper.getLevel().explode(creeper(helper), blast.x, blast.y, blast.z, LINE_RADIUS, Level.ExplosionInteraction.MOB);
        for (int x = LAST_INSIDE_X - 3; x <= LAST_INSIDE_X; x++) {
            helper.assertTrue(helper.getBlockState(new BlockPos(x, LINE_Y, 1)).is(Blocks.DIRT), "Inside the border at X " + x + " survives");
        }
        for (int x = LAST_INSIDE_X + 1; x <= LAST_INSIDE_X + 3; x++) {
            helper.assertTrue(!helper.getBlockState(new BlockPos(x, LINE_Y, 1)).is(Blocks.DIRT), "Outside the border at X " + x + " breaks");
        }
        helper.succeed();
    }

    /** A blast far from every border breaks blocks as before. */
    private static void farFromBorder(GameTestHelper helper) {
        LumberjackGameTests.forceChunks(helper, FAR_CORNER);
        TestSettlements.earlierVersion(SettlementData.get(helper.getLevel()), helper.absolutePos(CLERK));
        BlockPos block = new BlockPos(40, LINE_Y, 1);
        helper.setBlock(block, Blocks.DIRT);
        Vec3 blast = helper.absoluteVec(new Vec3(40.5, LINE_Y + 1.5, 1.5));
        helper.getLevel().explode(creeper(helper), blast.x, blast.y, blast.z, RADIUS, Level.ExplosionInteraction.MOB);
        helper.assertTrue(!helper.getBlockState(block).is(Blocks.DIRT), "A block far from every border breaks");
        helper.succeed();
    }

    /** With the switch off a mob's blast breaks blocks inside the border again. */
    private static void protectionOff(GameTestHelper helper) {
        LumberjackGameTests.forceChunks(helper, new BlockPos(24, 0, 24));
        TestSettlements.setBlock(helper, CLERK, ModBlocks.CLERK_BLOCK.get());
        boolean protectedBefore = CivModConfig.MOB_EXPLOSION_PROTECTION.get();
        CivModConfig.MOB_EXPLOSION_PROTECTION.set(false);
        try {
            helper.assertTrue(!survives(helper, creeper(helper), Level.ExplosionInteraction.MOB), "The switch is off: the block breaks");
        } finally {
            CivModConfig.MOB_EXPLOSION_PROTECTION.set(protectedBefore);
        }
        helper.succeed();
    }

    /** Only the owner counts: TNT of a player or of nobody breaks blocks inside the border, TNT a creeper chained does not. */
    private static void tntOwner(GameTestHelper helper) {
        LumberjackGameTests.forceChunks(helper, new BlockPos(24, 0, 24));
        TestSettlements.setBlock(helper, CLERK, ModBlocks.CLERK_BLOCK.get());
        ServerLevel level = helper.getLevel();
        Vec3 at = helper.absoluteVec(BLAST);
        ServerPlayer player = TestPlayers.add(helper, helper.absoluteVec(new Vec3(5.5, 1.0, 12.5)));
        try {
            helper.assertTrue(!survives(helper, new PrimedTnt(level, at.x, at.y, at.z, player), Level.ExplosionInteraction.TNT),
                    "TNT lit by a player breaks the block");
        } finally {
            TestPlayers.remove(player);
        }
        helper.assertTrue(!survives(helper, new PrimedTnt(level, at.x, at.y, at.z, null), Level.ExplosionInteraction.TNT),
                "TNT without an owner breaks the block");
        helper.assertTrue(survives(helper, new PrimedTnt(level, at.x, at.y, at.z, creeper(helper)), Level.ExplosionInteraction.TNT),
                "TNT lit by a creeper's blast keeps the block");
        helper.succeed();
    }

    private static Creeper creeper(GameTestHelper helper) {
        return helper.spawn(EntityTypes.CREEPER, new BlockPos(1, 1, 12));
    }

    /**
     * Puts a dirt block under the blast point and explodes there.
     *
     * @return whether the block is still there afterwards
     */
    private static boolean survives(GameTestHelper helper, Entity source, Level.ExplosionInteraction interaction) {
        helper.setBlock(TARGET, Blocks.DIRT);
        Vec3 at = helper.absoluteVec(BLAST);
        helper.getLevel().explode(source, at.x, at.y, at.z, RADIUS, interaction);
        return helper.getBlockState(TARGET).is(Blocks.DIRT);
    }
}
