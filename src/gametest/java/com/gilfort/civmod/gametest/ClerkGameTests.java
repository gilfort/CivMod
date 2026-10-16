package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.JobCategory;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Clerk and its storage (#55): access rule, storage limits, food points, Clerk as drop-off and
 * catch-up food.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClerkGameTests {

    /** Day time 08:00, inside the Clerk's shift. */
    static final long MORNING = 2000L;
    /** Day time 19:00, after the Clerk's shift. */
    static final long EVENING = 13000L;
    private static final UUID CLARA = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private ClerkGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "clerk_registry_and_block", ClerkGameTests::registryAndBlock);
            register(helper, "clerk_access_rule", ClerkGameTests::accessRule);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Clerk is a Logistics Job offered by the Clerk JobBlock, whose 27 slots are its item capability. */
    private static void registryAndBlock(GameTestHelper helper) {
        helper.assertTrue(ModJobs.CLERK.get().category() == JobCategory.LOGISTICS, "Clerk is a Logistics Job");
        helper.assertTrue(ModJobs.CLERK.get().id().equals(CivMod.id("clerk")), "Registered as civmod:clerk");
        helper.assertTrue(ModBlocks.CLERK_BLOCK.get().getJobType() == ModJobs.CLERK.get(), "The Clerk JobBlock offers the Clerk");
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWithStorage(helper);
        var handler = level.getCapability(Capabilities.Item.BLOCK, clerk, null);
        helper.assertTrue(handler != null && handler.size() == 27, "Hoppers see the 27 block slots only");
        helper.assertTrue(SettlementData.get(level).settlementAt(clerk).isPresent(), "The Clerk JobBlock joins a settlement");
        helper.succeed();
    }

    /**
     * Without a working Clerk only the 27 block slots are accessible; a Clerk settler on shift opens the linked storage
     * at its counter.
     */
    private static void accessRule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = clerkWithStorage(helper);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.BREAD, 5));

        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, true)) == 0, "No settler: linked storage is closed");
        ((Container) level.getBlockEntity(clerk)).setItem(3, new ItemStack(Items.BREAD, 2));
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, true)) == 2, "No settler: the block slots are open");

        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        helper.assertTrue(JobAssignments.assign(data, clerk, CLARA, "Clara", settlement.id(), List.of()) == JobAssignments.AssignResult.OK,
                "Clerk assigned");
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, true)) == 7, "Working Clerk: linked storage at the counter");
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, MORNING, false)) == 2, "Not at the counter: block slots only");
        helper.assertTrue(count(ClerkStorage.stock(level, clerk, EVENING, true)) == 2, "After the shift: block slots only");

        Stock open = ClerkStorage.stock(level, clerk, MORNING, true);
        ItemStack rest = open.insert(new ItemStack(Items.COBBLESTONE, 64 * 27));
        helper.assertTrue(rest.getCount() < 64 * 26, "A working Clerk fills the linked storage as well: rest " + rest.getCount());
        Stock closed = ClerkStorage.stock(level, clerk, EVENING, true);
        helper.assertTrue(closed.insert(new ItemStack(Items.STICK, 64)).getCount() == 64, "Closed Clerk with full block slots takes nothing");
        JobAssignments.unassign(data, clerk);
        helper.succeed();
    }

    /** Places a Clerk JobBlock at (1, 1, 1) with a chest at (4, 1, 1) linked as storage. */
    static BlockPos clerkWithStorage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 1), ModBlocks.CLERK_BLOCK.get());
        helper.setBlock(new BlockPos(4, 1, 1), Blocks.CHEST);
        BlockPos clerk = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chest = helper.absolutePos(new BlockPos(4, 1, 1));
        helper.assertTrue(JobAssignments.cycleClerkPoint(SettlementData.get(level), clerk, chest, true, false, net.minecraft.core.Direction.NORTH)
                == JobAssignments.PointResult.STORAGE, "Chest linked as storage");
        return clerk;
    }

    private static int count(Stock stock) {
        return stock.count(stack -> stack.is(Items.BREAD));
    }
}
