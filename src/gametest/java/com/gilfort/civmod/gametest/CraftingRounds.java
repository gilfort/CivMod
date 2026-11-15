package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * Shared steps of the Specialization GameTests (Baker, Dyer, Carpenter, Printer): a timed crafting round and the check of
 * a pattern outside the domain.
 */
final class CraftingRounds {

    /** Where the crafting station of a round stands, relative to the test structure. */
    private static final BlockPos STATION = new BlockPos(3, 1, 5);

    private CraftingRounds() {
    }

    /**
     * Stocks the Clerk with {@code stock} and a Min rule on {@code wanted}, puts a station next to the JobBlock, lets a
     * settler of the Job work the work time on a checked pattern for {@code wanted} and returns how many arrived.
     *
     * @param helper    the test helper
     * @param job       the Job the settler works
     * @param workplace creates the JobBlock (called after the Clerk is set up)
     * @param station   the station block of the Job
     * @param stock     the inputs in the Clerk's store
     * @param wanted    the Min rule: the item to make and how many
     * @param slot      the pattern slot of the item
     * @param workTicks the work time to catch up
     * @param factor    the job factor the settler must work at
     * @return how many of the wanted item the Clerk holds afterwards
     */
    static int deliver(GameTestHelper helper, Job job, Supplier<BlockPos> workplace, Block station, List<ItemStack> stock,
            ItemStack wanted, int slot, long workTicks, double factor) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, stock, List.of(wanted));
        BlockPos craft = workplace.get();
        helper.setBlock(STATION, station);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, job);
        helper.assertTrue(Patterns.setOutput(level, craft, job, slot, wanted.getItem()).orElseThrow().checked(), wanted.getItem() + " checked");
        CraftspersonGameTests.catchUp(level, settler, workTicks);
        helper.assertTrue(settler.getWork().jobFactor(level, craft) == factor, "Job factor of the day " + factor + ": "
                + settler.getWork().jobFactor(level, craft));
        int delivered = FletcherGameTests.count(level, setup.clerk(), wanted.getItem());
        FletcherGameTests.cleanUp(setup, settler);
        return delivered;
    }

    /** A pattern for {@code item} in {@code slot} lies outside the Job's domain: unchecked, not checkable, greyed out with the domain line. */
    static void assertOutsideDomain(GameTestHelper helper, BlockPos craft, Job job, int slot, Item item) {
        ServerLevel level = helper.getLevel();
        helper.assertFalse(Patterns.setOutput(level, craft, job, slot, item).orElseThrow().checked(), item + " is outside the domain");
        helper.assertFalse(Patterns.toggleCheck(level, craft, job, slot), "And cannot be checked");
        PatternsTabData tab = PatternsTabData.of(level, craft, job);
        helper.assertTrue(tab.filtered().equals(Set.of(slot))
                && tab.filteredLine().equals(Component.translatable("civmod.gui.patterns.outside_domain")), "Greyed out: " + tab.filtered());
    }
}
