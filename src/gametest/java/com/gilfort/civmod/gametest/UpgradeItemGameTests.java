package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.block.JobBlockUpgrade;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the JobBlock Upgrade items (vault {@code Systems/JobBlock.md}, decisions of 2026-10-29): right-clicking a
 * JobBlock with the next item raises it by one level once a worker at the block has reached its skill cap.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class UpgradeItemGameTests {

    private static final BlockPos BLOCK = new BlockPos(2, 1, 2);
    private static final BlockPos OTHER = new BlockPos(6, 1, 2);

    private UpgradeItemGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "upgrade_item_worker_at_cap", UpgradeItemGameTests::workerAtCap);
            register(helper, "upgrade_item_refused", UpgradeItemGameTests::refused);
            register(helper, "upgrade_item_specialization", UpgradeItemGameTests::specialization);
            register(helper, "upgrade_item_other_block", UpgradeItemGameTests::otherBlock);
            register(helper, "upgrade_item_recipe", UpgradeItemGameTests::recipe);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Places a JobBlock on a stone floor and returns its settlement. */
    private static Settlement jobBlock(GameTestHelper helper, BlockPos pos, Block block) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(pos, block);
        return SettlementData.get(helper.getLevel()).settlementAt(helper.absolutePos(pos)).orElseThrow();
    }

    /** A settler of the settlement working the JobBlock at {@code pos} as {@code job} with that skill level. */
    private static Settler worker(GameTestHelper helper, Settlement settlement, BlockPos pos, Job job, int skill) {
        ServerLevel level = helper.getLevel();
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Vec3 spawn = Vec3.atBottomCenterOf(helper.absolutePos(pos.south()));
        settler.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        settler.setCustomName(Component.literal("Worker"));
        level.addFreshEntity(settler);
        settler.joinSettlement(SettlementData.get(level), settlement);
        JobAssignments.AssignResult result = SettlementWorld.assign(level, helper.absolutePos(pos), settler, job);
        helper.assertTrue(result == JobAssignments.AssignResult.OK, "Assigned as " + job + ": " + result);
        settler.getSkills().set(job, new SettlerSkills.Skill(skill, 0));
        return settler;
    }

    /** Right-clicks the JobBlock at {@code pos} with the stack, as a survival player without sneaking. */
    private static InteractionResult click(GameTestHelper helper, Player player, BlockPos pos, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos abs = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
        return helper.getLevel().getBlockState(abs).useItemOn(stack, helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
    }

    /** Removes a JobBlock again, so its settlement does not reach the neighbouring tests. */
    private static void clear(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.AIR);
    }

    private static int level(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, JobBlockEntity.class).getJobLevel();
    }

    private static int settlementLevel(GameTestHelper helper, BlockPos pos) {
        BlockPos abs = helper.absolutePos(pos);
        return SettlementData.get(helper.getLevel()).settlementAt(abs).flatMap(found -> found.block(abs)).orElseThrow().level();
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents contents ? contents.getKey() : component.getString();
    }

    /** Whether the result swings the player's arm (on the client or from the server). */
    private static boolean swings(InteractionResult result) {
        return result instanceof InteractionResult.Success success && success.shouldSwing();
    }

    private static ItemStack upgrade(Item item) {
        return new ItemStack(item);
    }

    /**
     * A worker at the cap (20 at Lv 1): the Lv 2 item raises block and settlement entry to Lv 2, is used up, and the
     * click is consumed so the window does not open; the item keeps the level when the block is broken.
     */
    private static void workerAtCap(GameTestHelper helper) {
        Settlement settlement = jobBlock(helper, BLOCK, ModBlocks.MINER_BLOCK.get());
        Settler settler = worker(helper, settlement, BLOCK, ModJobs.MINER.get(), 20);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack stack = upgrade(ModItems.JOB_BLOCK_UPGRADE_2.get());
        InteractionResult result = click(helper, player, BLOCK, stack);
        helper.assertTrue(result.consumesAction(), "The click is consumed (no window): " + result);
        helper.assertTrue(swings(result), "An upgrade swings the arm: " + result);
        helper.assertTrue(level(helper, BLOCK) == 2 && settlementLevel(helper, BLOCK) == 2, "Block and settlement entry at Lv 2");
        helper.assertTrue(stack.isEmpty(), "The item is used up");
        ItemStack drop = new ItemStack(ModBlocks.MINER_BLOCK.get());
        drop.applyComponents(helper.getBlockEntity(BLOCK, JobBlockEntity.class).collectComponents());
        helper.assertTrue(drop.getOrDefault(ModDataComponents.JOB_LEVEL.get(), 1) == 2, "The broken block keeps Lv 2");

        // Creative: back to Lv 1 on the block only (the settlement entry stays at Lv 2, so its radius does not grow).
        helper.getBlockEntity(BLOCK, JobBlockEntity.class).setJobLevel(1);
        Player creative = helper.makeMockPlayer(GameType.CREATIVE);
        // The mock player does not set the abilities of its game mode.
        creative.getAbilities().instabuild = true;
        ItemStack again = upgrade(ModItems.JOB_BLOCK_UPGRADE_2.get());
        click(helper, creative, BLOCK, again);
        helper.assertTrue(level(helper, BLOCK) == 2 && again.getCount() == 1,
                "Creative upgrades without using the item: Lv " + level(helper, BLOCK) + ", " + again.getCount());
        settler.discard();
        clear(helper, BLOCK);
        helper.succeed();
    }

    /** Without a worker at the cap, with the wrong item and at Lv 5 the block stays, the item is kept and the reason is named. */
    private static void refused(GameTestHelper helper) {
        Settlement settlement = jobBlock(helper, BLOCK, ModBlocks.MINER_BLOCK.get());
        Settler settler = worker(helper, settlement, BLOCK, ModJobs.MINER.get(), 19);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BLOCK);

        ItemStack lv2 = upgrade(ModItems.JOB_BLOCK_UPGRADE_2.get());
        InteractionResult refusedClick = click(helper, player, BLOCK, lv2);
        helper.assertTrue(refusedClick.consumesAction(), "A refused click opens no window either: " + refusedClick);
        helper.assertFalse(swings(refusedClick), "A refused click does not swing the arm: " + refusedClick);
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "No window is open");
        helper.assertTrue(level(helper, BLOCK) == 1 && lv2.getCount() == 1, "Skill 19 < 20: the block stays, the item is kept");
        helper.assertTrue(JobBlockUpgrade.refusal(level, abs, 1, 2).map(UpgradeItemGameTests::key)
                .equals(java.util.Optional.of("civmod.upgrade.needs_worker")), "Reason: needs a worker at the cap");

        settler.getSkills().set(ModJobs.MINER.get(), new SettlerSkills.Skill(20, 0));
        ItemStack lv3 = upgrade(ModItems.JOB_BLOCK_UPGRADE_3.get());
        click(helper, player, BLOCK, lv3);
        helper.assertTrue(level(helper, BLOCK) == 1 && lv3.getCount() == 1, "The Lv 3 item does not work on a Lv 1 block");
        helper.assertTrue(JobBlockUpgrade.refusal(level, abs, 1, 3).map(UpgradeItemGameTests::key)
                .equals(java.util.Optional.of("civmod.upgrade.needs_item")), "Reason: needs the Lv 2 item");

        // Lv 5 on the block only: a Lv 5 settlement entry's radius would reach the neighbouring tests.
        helper.getBlockEntity(BLOCK, JobBlockEntity.class).setJobLevel(SettlementRules.MAX_LEVEL);
        settler.getSkills().set(ModJobs.MINER.get(), new SettlerSkills.Skill(100, 0));
        ItemStack lv5 = upgrade(ModItems.JOB_BLOCK_UPGRADE_5.get());
        click(helper, player, BLOCK, lv5);
        helper.assertTrue(level(helper, BLOCK) == SettlementRules.MAX_LEVEL && lv5.getCount() == 1, "Nothing above Lv 5");
        helper.assertTrue(JobBlockUpgrade.refusal(level, abs, 5, 5).map(UpgradeItemGameTests::key)
                .equals(java.util.Optional.of("civmod.upgrade.max")), "Reason: already Lv 5");
        settler.discard();
        clear(helper, BLOCK);
        helper.succeed();
    }

    /** A Specialization's skill counts: a Smith at the cap of a Lv 2 Craftsperson block lets the Lv 3 item work. */
    private static void specialization(GameTestHelper helper) {
        Settlement settlement = jobBlock(helper, BLOCK, ModBlocks.CRAFTSPERSON_BLOCK.get());
        StationTypeGameTests.setLevel(helper, BLOCK, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, helper.absolutePos(BLOCK), 1, Items.SMITHING_TABLE), "A smithing table unlocks the Smith");
        Job smith = StationTypeGameTests.job(CivMod.id("smith"));
        Settler settler = worker(helper, settlement, BLOCK, smith, 40);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack lv3 = upgrade(ModItems.JOB_BLOCK_UPGRADE_3.get());
        click(helper, player, BLOCK, lv3);
        helper.assertTrue(level(helper, BLOCK) == 3 && lv3.isEmpty(), "The Smith's skill 40 upgrades the block to Lv 3");
        settler.discard();
        clear(helper, BLOCK);
        helper.succeed();
    }

    /** A settler of the same Job at the cap at another JobBlock does not count. */
    private static void otherBlock(GameTestHelper helper) {
        Settlement settlement = jobBlock(helper, BLOCK, ModBlocks.MINER_BLOCK.get());
        helper.setBlock(OTHER, ModBlocks.MINER_BLOCK.get());
        Settler expert = worker(helper, settlement, OTHER, ModJobs.MINER.get(), 20);
        Settler novice = worker(helper, settlement, BLOCK, ModJobs.MINER.get(), 5);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack lv2 = upgrade(ModItems.JOB_BLOCK_UPGRADE_2.get());
        click(helper, player, BLOCK, lv2);
        helper.assertTrue(level(helper, BLOCK) == 1 && lv2.getCount() == 1, "The expert at the other block does not count");
        click(helper, player, OTHER, lv2);
        helper.assertTrue(level(helper, OTHER) == 2 && lv2.isEmpty(), "Its own block is upgraded");
        expert.discard();
        novice.discard();
        clear(helper, BLOCK);
        clear(helper, OTHER);
        helper.succeed();
    }

    /** Scroll in the middle, 8 materials around: the next Upgrade item, and the scroll is returned with its binding. */
    private static void recipe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Item> materials = List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.NETHERITE_INGOT);
        List<Item> results = List.of(ModItems.JOB_BLOCK_UPGRADE_2.get(), ModItems.JOB_BLOCK_UPGRADE_3.get(), ModItems.JOB_BLOCK_UPGRADE_4.get(),
                ModItems.JOB_BLOCK_UPGRADE_5.get());
        for (int i = 0; i < materials.size(); i++) {
            ItemStack m = new ItemStack(materials.get(i));
            ItemStack scroll = new ItemStack(ModItems.SCROLL_OF_ASSIGNMENT.get());
            scroll.set(ModDataComponents.SCROLL_BINDING.get(), new com.gilfort.civmod.item.ScrollBinding(java.util.Optional.of(BlockPos.ZERO),
                    java.util.Optional.empty(), "Miner"));
            CraftingInput input = CraftingInput.of(3, 3, List.of(m, m, m, m, scroll, m, m, m, m));
            Item result = results.get(i);
            var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level);
            helper.assertTrue(recipe.isPresent() && recipe.get().value().assemble(input).is(result), "Crafts " + result);
            ItemStack left = recipe.orElseThrow().value().getRemainingItems(input).get(4);
            helper.assertTrue(left.is(ModItems.SCROLL_OF_ASSIGNMENT.get()) && left.getCount() == 1
                    && left.get(ModDataComponents.SCROLL_BINDING.get()) != null, "The scroll is returned with its binding: " + left);
        }
        helper.succeed();
    }
}
