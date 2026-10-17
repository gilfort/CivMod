package com.gilfort.civmod.gametest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Stock;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.JobAssignments.PointResult;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;
import com.gilfort.civmod.settlement.SettlementRules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Clerk stock view (#66): CivMod's own transactions and changes of the block's own slots are visible
 * at once, foreign changes in linked containers after the re-read interval (at most 10 s).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class ClerkStockGameTests {

    /** The longest re-read interval of a linked container (10 s), plus a tick of margin. */
    private static final int REREAD = 201;

    private ClerkStockGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "clerk_stock_counts", ClerkStockGameTests::counts);
            register(helper, "clerk_stock_own_transactions", ClerkStockGameTests::ownTransactions);
            register(helper, "clerk_stock_foreign_change", ClerkStockGameTests::foreignChange);
            register(helper, "clerk_stock_shortfall", ClerkStockGameTests::shortfall);
            register(helper, "clerk_stock_unloaded", ClerkStockGameTests::unloaded);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The counts match the real containers: the block's own slots plus the linked chest. */
    private static void counts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        Container own = (Container) level.getBlockEntity(clerk);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        own.setItem(0, new ItemStack(Items.BREAD, 2));
        own.setItem(5, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(0, new ItemStack(Items.BREAD, 5));
        chest.setItem(3, new ItemStack(Items.APPLE, 3));
        chest.setItem(26, new ItemStack(Items.STONE_PICKAXE));

        Stock stock = ClerkStorage.stock(level, clerk, true);
        helper.assertTrue(stock.counts().equals(Map.of(Items.BREAD, 7, Items.COBBLESTONE, 64, Items.APPLE, 3, Items.STONE_PICKAXE, 1)),
                "Counts of all containers: " + stock.counts());
        helper.assertTrue(stock.count(stack -> stack.is(Items.BREAD) || stack.is(Items.APPLE)) == 10, "Count of a kind");
        helper.assertTrue(stock.contains(stack -> stack.is(Items.APPLE)) && !stock.contains(stack -> stack.is(Items.DIAMOND)), "Contains");
        Stock block = ClerkStorage.stock(level, clerk, false);
        helper.assertTrue(block.counts().equals(Map.of(Items.BREAD, 2, Items.COBBLESTONE, 64)), "Block slots only: " + block.counts());
        helper.succeed();
    }

    /** What CivMod puts into or takes out of a linked container is visible at once, within the same tick. */
    private static void ownTransactions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        Container own = (Container) level.getBlockEntity(clerk);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        for (int i = 0; i < own.getContainerSize(); i++) {
            own.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        helper.assertTrue(bread(level, clerk) == 0, "No bread at first");

        helper.assertTrue(ClerkStorage.stock(level, clerk, true).insert(new ItemStack(Items.BREAD, 10)).isEmpty(), "Delivered");
        helper.assertTrue(bread(chest) == 10 && bread(level, clerk) == 10, "A delivery into the linked chest shows at once");
        List<ItemStack> taken = ClerkStorage.stock(level, clerk, true).take(Items.BREAD, 4);
        helper.assertTrue(taken.stream().mapToInt(ItemStack::getCount).sum() == 4, "Taken: " + taken);
        helper.assertTrue(bread(chest) == 6 && bread(level, clerk) == 6, "A hand-out shows at once: " + bread(level, clerk));
        helper.succeed();
    }

    /**
     * Taking checks the real container: what a player took out meanwhile is missing, as without a stock view, and the
     * stock is corrected at once.
     */
    private static void shortfall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.BREAD, 5));
        helper.assertTrue(bread(level, clerk) == 5, "Counted");

        chest.setItem(0, ItemStack.EMPTY);
        helper.assertTrue(bread(level, clerk) == 5, "A foreign change is not re-read yet");
        helper.assertTrue(ClerkStorage.stock(level, clerk, true).take(Items.BREAD, 5).isEmpty(), "Nothing to take in the real chest");
        helper.assertTrue(bread(level, clerk) == 0, "The stock is corrected at once");
        helper.succeed();
    }

    /** A linked container in an unloaded chunk is skipped, and its chunk is not loaded. */
    private static void unloaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        SettlementData data = SettlementData.get(level);
        SettlementManager.setLevel(data, clerk, SettlementRules.MAX_LEVEL, SettlementManager.Listener.NONE);
        // Its own batch (environment own_area_unloaded): the wide border must not pull in JobBlocks of other tests.
        int reach = SettlementRules.joinRadius(SettlementRules.MAX_LEVEL);
        Optional<BlockPos> far = Arrays.stream(new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST})
                .map(direction -> clerk.relative(direction, reach))
                .filter(pos -> !chunkLoaded(level, pos))
                .findFirst();
        helper.assertTrue(far.isPresent(), "An unloaded chunk within the settlement border");
        BlockPos pos = far.orElseThrow();
        helper.assertTrue(JobAssignments.cycleClerkPoint(data, clerk, pos, true, false, Direction.NORTH) == PointResult.STORAGE,
                "Unloaded position linked");
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        chest.setItem(0, new ItemStack(Items.BREAD, 5));

        helper.assertTrue(bread(level, clerk) == 5, "The loaded chest counts, the unloaded container is skipped");
        helper.assertTrue(!chunkLoaded(level, pos), "The unloaded chunk stays unloaded");
        JobAssignments.cycleClerkPoint(data, clerk, pos, true, false, Direction.NORTH);
        SettlementManager.setLevel(data, clerk, SettlementRules.MIN_LEVEL, SettlementManager.Listener.NONE);
        helper.succeed();
    }

    private static boolean chunkLoaded(ServerLevel level, BlockPos pos) {
        return level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /**
     * A foreign change (player, hopper) in the block's own slots shows at once, one in a linked container
     * after the re-read interval; after a re-read that found a change, the next one shows sooner.
     */
    private static void foreignChange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = ClerkGameTests.clerkWithStorage(helper);
        Container own = (Container) level.getBlockEntity(clerk);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        helper.assertTrue(bread(level, clerk) == 0, "Empty at first");

        chest.setItem(0, new ItemStack(Items.BREAD, 5));
        own.setItem(0, new ItemStack(Items.BREAD, 2));
        helper.assertTrue(bread(level, clerk) == 2, "Own slots: the change shows at once");
        helper.runAfterDelay(1, () -> helper.assertTrue(bread(level, clerk) == 2, "Linked container: not re-read yet"));
        helper.runAfterDelay(REREAD - 50, () -> helper.assertTrue(bread(level, clerk) == 2,
                "Nothing changed at the first read: the interval is the longest one"));
        helper.runAfterDelay(REREAD, () -> {
            helper.assertTrue(bread(level, clerk) == 7, "Linked container re-read after at most 10 s, got " + bread(level, clerk));
            chest.setItem(1, new ItemStack(Items.BREAD, 3));
        });
        helper.runAfterDelay(REREAD + 100, () -> {
            helper.assertTrue(bread(level, clerk) == 10, "After a change the re-read comes sooner, got " + bread(level, clerk));
            helper.succeed();
        });
    }

    private static int bread(Container container) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(Items.BREAD)) {
                count += container.getItem(i).getCount();
            }
        }
        return count;
    }

    private static int bread(ServerLevel level, BlockPos clerk) {
        return ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(Items.BREAD));
    }
}
