package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the furnace check of the Smelter, Cook and Founder (#340, vault {@code Jobs/Smelter.md},
 * {@code Systems/Producing Jobs.md}): a furnace is free while its input and output slots are empty and it does not burn
 * on its own. Fuel a player left in the fuel slot neither blocks the furnace nor is ever touched.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class FurnaceFuelGameTests {

    private static final int INPUT_SLOT = 0;
    private static final int FUEL_SLOT = 1;
    private static final int OUTPUT_SLOT = 2;
    /** The fuel the player leaves in a furnace. */
    private static final int LEFT_COAL = 3;

    private FurnaceFuelGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "furnace_fuel_only_smelter", h -> fuelOnly(h, smelterRig(h)));
            register(helper, "furnace_fuel_only_cook", h -> fuelOnly(h, cookRig(h)));
            register(helper, "furnace_fuel_only_founder", h -> fuelOnly(h, founderRig(h)));
            register(helper, "furnace_input_blocks", FurnaceFuelGameTests::inputBlocks);
            register(helper, "furnace_output_blocks", FurnaceFuelGameTests::outputBlocks);
            register(helper, "furnace_burning_blocks", FurnaceFuelGameTests::burningBlocks);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /**
     * A furnace that only holds 3 coal is used: the item is made, the Clerk's coal burns and the 3 coal stay in the
     * fuel slot.
     */
    private static void fuelOnly(GameTestHelper helper, Rig rig) {
        ServerLevel level = helper.getLevel();
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        furnace.setItem(FUEL_SLOT, new ItemStack(Items.COAL, LEFT_COAL));
        Settler settler = rig.workerAfterRequest();
        CraftspersonGameTests.catchUp(level, settler, 6000L);

        helper.assertTrue(rig.count(level, rig.output) == 1, "Made at the furnace with fuel only: " + settler.getWork().status(rig.block));
        helper.assertTrue(rig.count(level, Items.COAL) == 0, "The Clerk's coal burned: " + rig.count(level, Items.COAL));
        helper.assertTrue(furnace.getItem(FUEL_SLOT).is(Items.COAL) && furnace.getItem(FUEL_SLOT).getCount() == LEFT_COAL,
                "The player's coal is untouched: " + furnace.getItem(FUEL_SLOT));
        helper.assertTrue(furnace.getItem(INPUT_SLOT).isEmpty() && furnace.getItem(OUTPUT_SLOT).isEmpty(), "Nothing else was put into the furnace");
        rig.cleanUp(settler);
        helper.succeed();
    }

    /** An item in the input slot keeps the furnace blocked, with fuel next to it. */
    private static void inputBlocks(GameTestHelper helper) {
        Rig rig = smelterRig(helper);
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        furnace.setItem(INPUT_SLOT, new ItemStack(Items.COBBLESTONE, 5));
        furnace.setItem(FUEL_SLOT, new ItemStack(Items.COAL, LEFT_COAL));
        assertBlocked(helper, rig, furnace);
    }

    /** A finished item in the output slot keeps the furnace blocked, also with 3 coal in the fuel slot. */
    private static void outputBlocks(GameTestHelper helper) {
        Rig rig = smelterRig(helper);
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        furnace.setItem(OUTPUT_SLOT, new ItemStack(Items.IRON_INGOT, 1));
        furnace.setItem(FUEL_SLOT, new ItemStack(Items.COAL, LEFT_COAL));
        assertBlocked(helper, rig, furnace);
    }

    /** A furnace that still burns stays blocked until the fire is out, then it is used. */
    private static void burningBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Rig rig = smelterRig(helper);
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        furnace.setItem(FUEL_SLOT, new ItemStack(Items.COAL, LEFT_COAL));
        furnace.litTimeRemaining = 200;
        Settler settler = rig.workerAfterRequest();
        CraftspersonGameTests.catchUp(level, settler, 6000L);
        helper.assertTrue(settler.getWork().status(rig.block) == JobStatus.NO_WORK_PLACE && rig.count(level, rig.output) == 0,
                "A burning furnace is no work place: " + settler.getWork().status(rig.block));

        furnace.litTimeRemaining = 0;
        CraftspersonGameTests.catchUp(level, settler, 6000L);
        helper.assertTrue(rig.count(level, rig.output) == 1, "Used once the fire is out: " + settler.getWork().status(rig.block));
        helper.assertTrue(furnace.getItem(FUEL_SLOT).getCount() == LEFT_COAL, "The player's coal is untouched: " + furnace.getItem(FUEL_SLOT));
        rig.cleanUp(settler);
        helper.succeed();
    }

    /** The only furnace is blocked: no work place and nothing made; the furnace keeps what the player put into it. */
    private static void assertBlocked(GameTestHelper helper, Rig rig, AbstractFurnaceBlockEntity furnace) {
        ServerLevel level = helper.getLevel();
        List<ItemStack> before = List.of(furnace.getItem(INPUT_SLOT).copy(), furnace.getItem(FUEL_SLOT).copy(), furnace.getItem(OUTPUT_SLOT).copy());
        Settler settler = rig.workerAfterRequest();
        CraftspersonGameTests.catchUp(level, settler, 6000L);
        helper.assertTrue(settler.getWork().status(rig.block) == JobStatus.NO_WORK_PLACE && rig.count(level, rig.output) == 0,
                "The furnace is no work place: " + settler.getWork().status(rig.block));
        for (int slot = 0; slot < before.size(); slot++) {
            helper.assertTrue(ItemStack.matches(before.get(slot), furnace.getItem(slot)), "Slot " + slot + " is untouched: " + furnace.getItem(slot));
        }
        rig.cleanUp(settler);
        helper.succeed();
    }

    private static AbstractFurnaceBlockEntity furnace(GameTestHelper helper) {
        return (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(SmelterGameTests.FURNACE));
    }

    // ---------------------------------------------------------------- rigs

    /** A Smelter at a furnace asked for one iron ingot, with the Clerk holding 1 raw iron and 1 coal. */
    private static Rig smelterRig(GameTestHelper helper) {
        SmelterGameTests.Setup setup = SmelterGameTests.setup(helper, List.of(new ItemStack(Items.RAW_IRON, 1), new ItemStack(Items.COAL, 1)));
        return new Rig(setup.clerk(), setup.block(), setup.data(), setup.settlement(), Items.IRON_INGOT, () -> SmelterGameTests.worker(helper, setup));
    }

    /** A Cook at a smoker asked for one cooked beef. */
    private static Rig cookRig(GameTestHelper helper) {
        return specializedRig(helper, SmelterSpecializationGameTests.cook(), Items.SMOKER, Blocks.SMOKER, "cooked_beef_from_smoking", Items.BEEF,
                Items.COOKED_BEEF);
    }

    /** A Founder at a blast furnace asked for one iron ingot. */
    private static Rig founderRig(GameTestHelper helper) {
        return specializedRig(helper, SmelterSpecializationGameTests.founder(), Items.BLAST_FURNACE, Blocks.BLAST_FURNACE,
                "iron_ingot_from_blasting_raw_iron", Items.RAW_IRON, Items.IRON_INGOT);
    }

    private static Rig specializedRig(GameTestHelper helper, Job job, Item unlock, Block station, String recipe, Item input, Item output) {
        // Bread first: the settlers' provisions come from the first edible stack, not from the raw beef.
        SmelterSpecializationGameTests.Setup setup = SmelterSpecializationGameTests.setup(helper, job, unlock, station, recipe, input,
                List.of(new ItemStack(Items.BREAD, 32), new ItemStack(input, 1), new ItemStack(Items.COAL, 1)));
        return new Rig(setup.clerk(), setup.block(), setup.data(), setup.settlement(), output, () -> SmelterSpecializationGameTests.worker(helper, setup, job));
    }

    /** What a test needs of a set-up Producing Job: its Clerk and JobBlock, the wanted item and a way to get its worker. */
    private record Rig(BlockPos clerk, BlockPos block, SettlementData data, Settlement settlement, Item output, Supplier<Settler> worker) {

        /** Posts the request for one output item at the Clerk and lets the worker take the job. */
        Settler workerAfterRequest() {
            Requests.post(data, settlement, ItemKey.of(output), 1, Optional.of(clerk), Optional.empty());
            return worker.get();
        }

        int count(ServerLevel level, Item item) {
            return SmelterGameTests.count(level, clerk, item);
        }

        void cleanUp(Settler settler) {
            TestAssignments.unassign(data, clerk);
            TestAssignments.unassign(data, block);
            settler.discard();
        }
    }
}
