package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.gametest.FletcherGameTests.Setup;
import com.gilfort.civmod.job.JobStatus;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.ReservedFor;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Patterns;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the tool supply of innate recipes (#347, vault {@code Systems/Producing Jobs.md}): a Producing settler
 * whose innate recipe needs a tool it lacks takes one from its Home Clerk's store or orders it there, reserved for itself,
 * but only while an open request or "Always produce" wants the recipe; a request only such a recipe serves shows its
 * need as status "Blocked".
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class InnateToolSupplyGameTests {

    /** Work time of the previous workday the tests catch up: plenty for a few executions at job factor 0.3. */
    private static final long WORK_TICKS = 4000L;

    private InnateToolSupplyGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "innate_tool_from_store", InnateToolSupplyGameTests::fromStore);
            register(helper, "innate_tool_ordered", InnateToolSupplyGameTests::ordered);
            register(helper, "innate_tool_auto_supply_off", InnateToolSupplyGameTests::autoSupplyOff);
            register(helper, "innate_tool_not_wanted", InnateToolSupplyGameTests::notWanted);
            register(helper, "innate_station_blocks_request", InnateToolSupplyGameTests::stationBlocksRequest);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Shears in the Home Clerk's store: the Fletcher takes them, puts them on its belt and turns the wool into string. */
    private static void fromStore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WOOL.white()), new ItemStack(Items.SHEARS)),
                List.of(new ItemStack(Items.STRING, 2)));
        Workshop fletchery = fletchery(helper, setup);
        CraftspersonGameTests.catchUp(level, fletchery.settler(), WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.STRING) == 2,
                "2 string arrived (status " + fletchery.settler().getWork().status(fletchery.craft()) + ")");
        ItemStack worn = fletchery.settler().getInventory().getItem(Settler.BELT_START);
        helper.assertTrue(worn.is(Items.SHEARS) && worn.getDamageValue() == 1, "The shears from the store are on the belt, worn by 1: " + worn);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.SHEARS) == 0, "The shears left the store");
        helper.assertTrue(shearsRequest(setup, fletchery.settler()).isEmpty(), "Nothing was ordered");
        FletcherGameTests.cleanUp(setup, fletchery.settler());
        helper.succeed();
    }

    /**
     * No shears anywhere: the Fletcher orders a pair at its Home Clerk, reserved for itself, and its status names what
     * blocks the string request ("Needs Shears").
     */
    private static void ordered(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WOOL.white())), List.of(new ItemStack(Items.STRING, 2)));
        Workshop fletchery = fletchery(helper, setup);
        CraftspersonGameTests.catchUp(level, fletchery.settler(), WORK_TICKS);
        helper.assertTrue(shearsRequest(setup, fletchery.settler()).isPresent(),
                "A shears request reserved for the Fletcher: " + setup.settlement().requests());
        assertBlocked(helper, fletchery, "Needs Shears");
        FletcherGameTests.cleanUp(setup, fletchery.settler());
        helper.succeed();
    }

    /** "Auto supply" off at the Clerk: nothing is ordered, the string request stays blocked by the missing shears. */
    private static void autoSupplyOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WOOL.white())), List.of(new ItemStack(Items.STRING, 2)));
        JobAssignments.setAutoSupply(setup.data(), setup.clerk(), false);
        Workshop fletchery = fletchery(helper, setup);
        CraftspersonGameTests.catchUp(level, fletchery.settler(), WORK_TICKS);
        helper.assertTrue(shearsRequest(setup, fletchery.settler()).isEmpty(), "Nothing was ordered: " + setup.settlement().requests());
        assertBlocked(helper, fletchery, "Needs Shears");
        FletcherGameTests.cleanUp(setup, fletchery.settler());
        helper.succeed();
    }

    /** Nobody wants string: the Fletcher works its flint request and orders no shears for the unused string recipe. */
    private static void notWanted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.GRAVEL)), List.of(new ItemStack(Items.FLINT)));
        Workshop fletchery = fletchery(helper, setup);
        CraftspersonGameTests.catchUp(level, fletchery.settler(), WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.FLINT) == 1, "Flint arrived");
        helper.assertTrue(shearsRequest(setup, fletchery.settler()).isEmpty(), "No shears ordered: " + setup.settlement().requests());
        FletcherGameTests.cleanUp(setup, fletchery.settler());
        helper.succeed();
    }

    /**
     * A white wool request only the bleaching recipe serves, without a water cauldron: the Dyer's status names the missing
     * station instead of "No request".
     */
    private static void stationBlocksRequest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Setup setup = FletcherGameTests.setup(helper, List.of(new ItemStack(Items.WOOL.red())), List.of(new ItemStack(Items.WOOL.white())));
        BlockPos craft = DyerGameTests.dyehouse(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.LOOM);
        Settler settler = FletcherGameTests.worker(helper, setup, craft, DyerGameTests.dyer());
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        helper.assertTrue(FletcherGameTests.count(level, setup.clerk(), Items.WOOL.red()) == 1, "The red wool stays");
        assertBlocked(helper, new Workshop(craft, settler), "Needs a station nearby: Water Cauldron");
        FletcherGameTests.cleanUp(setup, settler);
        helper.succeed();
    }

    /** A Producing JobBlock and the settler working it. */
    private record Workshop(BlockPos craft, Settler settler) {
    }

    /** A Fletcher JobBlock with its fletching table and a settler working it, no tool on the belt. */
    private static Workshop fletchery(GameTestHelper helper, Setup setup) {
        BlockPos craft = FletcherGameTests.fletchery(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.FLETCHING_TABLE);
        Settler settler = FletcherGameTests.worker(helper, setup, craft);
        Patterns.syncInnate(helper.getLevel(), craft, FletcherGameTests.fletcher());
        return new Workshop(craft, settler);
    }

    /** The shears request at the Clerk reserved for the settler, if there is one. */
    private static Optional<Request> shearsRequest(Setup setup, Settler settler) {
        return setup.settlement().requests().stream()
                .filter(request -> request.item().equals(ItemKey.of(Items.SHEARS)) && request.target().equals(Optional.of(setup.clerk()))
                        && request.reservedFor().equals(Optional.of(ReservedFor.settler(settler.getUUID()))))
                .findFirst();
    }

    private static void assertBlocked(GameTestHelper helper, Workshop workshop, String reason) {
        JobStatus status = workshop.settler().getWork().status(workshop.craft());
        helper.assertTrue(status == JobStatus.BLOCKED, "Blocked, not " + status);
        helper.assertValueEqual(workshop.settler().getWork().statusText(workshop.craft()).getString(), reason, "Reason");
    }
}
