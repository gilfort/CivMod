package com.gilfort.civmod.gametest;

import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.origin.OriginData;
import com.gilfort.civmod.origin.SettlerOrigin;
import com.gilfort.civmod.npc.SettlerSkins;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Settler spawn egg (vault {@code Systems/NPC Population.md}). The egg's spawn path is run the way the item
 * runs it: {@code EntityType.spawn} with the egg's stack and the spawn-item reason.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class SettlerSpawnEggGameTests {

    /** Far from every test area, so no settlement of another test is within the homeless join distance. */
    private static final int FAR_AWAY = 1_000_000;

    private SettlerSpawnEggGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "settler_spawn_egg_new_settler", SettlerSpawnEggGameTests::newSettler);
            register(helper, "settler_spawn_egg_custom_name", SettlerSpawnEggGameTests::customName);
            register(helper, "settler_spawn_egg_homeless_rules", SettlerSpawnEggGameTests::homelessRules);
            register(helper, "settler_spawn_egg_spawner", SettlerSpawnEggGameTests::spawnerStaysUntouched);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Spawns a Settler as the egg does. */
    static Settler spawn(GameTestHelper helper, ItemStack egg, BlockPos relative) {
        Settler settler = ModEntityTypes.SETTLER.get().spawn(helper.getLevel(), egg, null, helper.absolutePos(relative),
                EntitySpawnReason.SPAWN_ITEM_USE, false, false);
        helper.assertTrue(settler != null, "The egg should spawn a Settler");
        settler.setNoAi(true);
        return settler;
    }

    /** The new Settler has a race from the race data, a skin, a name from the race's list, level 1 everywhere and is homeless. */
    private static void newSettler(GameTestHelper helper) {
        Settler settler = spawn(helper, ModItems.SETTLER_SPAWN_EGG.get().getDefaultInstance(), new BlockPos(1, 1, 1));
        helper.assertTrue(settler.isHomeless(), "The egg Settler is homeless");
        helper.assertTrue(OriginData.races().contains(settler.getRace()), "Race from the race data: " + settler.getRace());
        helper.assertTrue(settler.getSkin() >= 0 && settler.getSkin() < SettlerSkins.COUNT, "Skin in range: " + settler.getSkin());
        helper.assertTrue(settler.hasCustomName(), "The egg Settler has a name");
        helper.assertTrue(settler.getOrigin().flatMap(SettlerOrigin::name).isPresent(), "The egg Settler has an origin with a rolled name");
        for (Job job : ModJobs.REGISTRY) {
            helper.assertTrue(settler.getSkills().get(job).level() == 1, "Level 1 in " + job);
        }
        settler.discard();
        helper.succeed();
    }

    /** A custom name on the egg (anvil) is the Settler's name. */
    private static void customName(GameTestHelper helper) {
        ItemStack egg = ModItems.SETTLER_SPAWN_EGG.get().getDefaultInstance();
        egg.set(DataComponents.CUSTOM_NAME, Component.literal("Egbert"));
        Settler settler = spawn(helper, egg, new BlockPos(1, 1, 1));
        helper.assertTrue(settler.getName().getString().equals("Egbert"), "The egg's name wins: " + settler.getName().getString());
        settler.discard();
        helper.succeed();
    }

    /** The egg Settler joins a settlement with a connected JobBlock within reach by the homeless rules, and stays homeless without one. */
    private static void homelessRules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settler lonely = spawn(helper, ModItems.SETTLER_SPAWN_EGG.get().getDefaultInstance(), new BlockPos(1, 1, 1));
        lonely.setPos(lonely.getX() + FAR_AWAY, lonely.getY(), lonely.getZ());
        lonely.checkMembership(level);
        helper.assertTrue(lonely.isHomeless(), "No settlement in reach: stays homeless");
        lonely.discard();

        TestSettlements.setBlock(helper, new BlockPos(1, 1, 1), ModBlocks.CRAFTSPERSON_BLOCK.get());
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(helper.absolutePos(new BlockPos(1, 1, 1))).orElseThrow();
        Settler near = spawn(helper, ModItems.SETTLER_SPAWN_EGG.get().getDefaultInstance(), new BlockPos(2, 1, 1));
        near.checkMembership(level);
        helper.assertTrue(near.getSettlementId() == settlement.id(), "A connected JobBlock in reach: joins its settlement");
        near.discard();
        helper.succeed();
    }

    /** Using the egg on a monster spawner does nothing: the egg is kept and the spawner is not changed. */
    private static void spawnerStaysUntouched(GameTestHelper helper) {
        BlockPos spawner = new BlockPos(1, 1, 1);
        helper.setBlock(spawner, Blocks.SPAWNER);
        ItemStack egg = ModItems.SETTLER_SPAWN_EGG.get().getDefaultInstance();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(helper.absolutePos(spawner)), Direction.UP, helper.absolutePos(spawner), false);
        InteractionResult result = egg.useOn(new UseOnContext(helper.getLevel(), null, InteractionHand.MAIN_HAND, egg, hit));
        helper.assertTrue(result == InteractionResult.PASS, "The egg passes on a spawner: " + result);
        helper.assertTrue(egg.getCount() == 1, "The egg is not used up");
        helper.succeed();
    }
}
