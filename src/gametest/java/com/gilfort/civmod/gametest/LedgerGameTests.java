package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.ClerkTabData;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.registry.ModItems;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementRules;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestSettlements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
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
 * GameTests for the Ledger (vault {@code Jobs/Clerk.md}, decisions of 2026-11-02): right-clicked on a Clerk JobBlock it
 * adds 9 stock rule slots, at most 3 per block; active slots = (JobBlock level + Ledgers) × 9; the count stays on the
 * dropped block item.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class LedgerGameTests {

    private static final BlockPos CLERK = new BlockPos(1, 1, 1);
    private static final BlockPos OTHER = new BlockPos(5, 1, 1);

    private LedgerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "ledger_slots", LedgerGameTests::slots);
            register(helper, "ledger_other_block", LedgerGameTests::otherBlock);
            register(helper, "ledger_break_and_place", LedgerGameTests::breakAndPlace);
            register(helper, "ledger_recipe", LedgerGameTests::recipe);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * Each Ledger adds 9 active slots and is used up; the fourth is refused and kept; Lv 1 with 3 Ledgers has 36 slots,
     * Lv 5 with 3 has 72. A rule in a Ledger slot is accepted; creative keeps the item.
     */
    private static void slots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertTrue(ClerkTabData.of(level, clerk).slots() == 9, "Lv 1 without Ledgers: 9 slots");
        for (int ledgers = 1; ledgers <= 3; ledgers++) {
            ItemStack ledger = new ItemStack(ModItems.LEDGER.get());
            click(helper, player, CLERK, ledger);
            int slots = ClerkTabData.of(level, clerk).slots();
            helper.assertTrue(ledger.isEmpty(), "Ledger " + ledgers + " is used up");
            helper.assertTrue(slots == (1 + ledgers) * 9, "Lv 1 with " + ledgers + " Ledgers: " + slots + " slots");
        }
        ItemStack fourth = new ItemStack(ModItems.LEDGER.get());
        click(helper, player, CLERK, fourth);
        helper.assertTrue(fourth.getCount() == 1, "A fourth Ledger is refused and kept");
        helper.assertTrue(ClerkTabData.of(level, clerk).slots() == 36, "Lv 1 with 3 Ledgers: 36 slots");
        helper.assertTrue(helper.getBlockEntity(CLERK, JobBlockEntity.class).getLedgers() == 3, "The block holds 3 Ledgers");

        SettlementData data = SettlementData.get(level);
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(35, Items.BREAD, 4, 0)), "Slot 35 takes a rule");
        helper.assertTrue(ClerkTabData.of(level, clerk).nextEmptyGhostSlot() == 0, "Slot 0 still empty");

        StationTypeGameTests.setLevel(helper, CLERK, SettlementRules.MAX_LEVEL);
        helper.assertTrue(ClerkTabData.of(level, clerk).slots() == 72, "Lv 5 with 3 Ledgers: 72 slots");
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(71, Items.TORCH, 4, 0)), "Slot 71 takes a rule");
        helper.assertFalse(StockRules.setRule(data, clerk, StockRule.minimum(72, Items.STICK, 4, 0)), "Slot 72 does not exist");
        helper.succeed();
    }

    /** Another JobBlock does not take a Ledger: the item is kept and the block has no Ledgers; creative keeps the item. */
    private static void otherBlock(GameTestHelper helper) {
        floor(helper);
        TestSettlements.setBlock(helper, OTHER, ModBlocks.MINER_BLOCK.get());
        TestSettlements.setBlock(helper, CLERK.east(), ModBlocks.CLERK_BLOCK.get());
        ItemStack ledger = new ItemStack(ModItems.LEDGER.get());
        click(helper, helper.makeMockPlayer(GameType.SURVIVAL), OTHER, ledger);
        helper.assertTrue(ledger.getCount() == 1, "The Miner Block does not take a Ledger");
        helper.assertTrue(helper.getBlockEntity(OTHER, JobBlockEntity.class).getLedgers() == 0, "The Miner Block has no Ledgers");

        Player creative = helper.makeMockPlayer(GameType.CREATIVE);
        // The mock player does not set the abilities of its game mode.
        creative.getAbilities().instabuild = true;
        click(helper, creative, CLERK.east(), ledger);
        helper.assertTrue(ledger.getCount() == 1 && helper.getBlockEntity(CLERK.east(), JobBlockEntity.class).getLedgers() == 1,
                "Creative adds a Ledger without using the item");
        helper.setBlock(OTHER, Blocks.AIR);
        helper.setBlock(CLERK.east(), Blocks.AIR);
        helper.succeed();
    }

    /** The dropped Clerk JobBlock keeps its Ledger count; placing it again restores the slots. */
    private static void breakAndPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        click(helper, player, CLERK, new ItemStack(ModItems.LEDGER.get()));
        click(helper, player, CLERK, new ItemStack(ModItems.LEDGER.get()));

        // Check the loot table result directly; item entities are unreliable in non-ticking test chunks.
        List<ItemStack> drops = Block.getDrops(level.getBlockState(clerk), level, clerk, level.getBlockEntity(clerk));
        helper.assertTrue(drops.size() == 1 && drops.getFirst().is(ModItems.CLERK_BLOCK.get()), "Expected the Clerk Block as drop: " + drops);
        ItemStack drop = drops.getFirst();
        helper.assertTrue(drop.getOrDefault(ModDataComponents.LEDGERS.get(), 0) == 2, "The dropped block keeps 2 Ledgers");

        helper.getBlockEntity(CLERK, JobBlockEntity.class).clearContent();
        level.destroyBlock(clerk, false);
        StarterToolGameTests.place(helper, helper.makeMockPlayer(GameType.SURVIVAL), drop, CLERK);
        helper.assertTrue(helper.getBlockEntity(CLERK, JobBlockEntity.class).getLedgers() == 2, "The placed block has 2 Ledgers");
        helper.assertTrue(ClerkTabData.of(level, clerk).slots() == 27, "Lv 1 with 2 Ledgers again: 27 slots");
        helper.succeed();
    }

    /** Book and Quill + Chest, shapeless. */
    private static void recipe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CraftingInput input = CraftingInput.of(2, 1, List.of(new ItemStack(Items.CHEST), new ItemStack(Items.WRITABLE_BOOK)));
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(recipe.isPresent() && recipe.get().value().assemble(input).is(ModItems.LEDGER.get()), "Crafts a Ledger");
        helper.succeed();
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 4; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** Right-clicks the JobBlock at {@code pos} with the stack, without sneaking. */
    private static void click(GameTestHelper helper, Player player, BlockPos pos, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos abs = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
        helper.getLevel().getBlockState(abs).useItemOn(stack, helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
    }
}
