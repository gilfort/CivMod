package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.logistics.Request;
import com.gilfort.civmod.logistics.StockRule;
import com.gilfort.civmod.menu.PatternsTabData;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.settlement.JobAssignments;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.StockRules;
import com.gilfort.civmod.settlement.TestAssignments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for crafting domains and the Mechanic (#94, vault {@code Systems/Producing Jobs.md}, {@code Jobs/Mechanic.md}):
 * a crafting Specialization works only recipes with an input in its domain tag, intermediates included, four times as
 * fast as the Craftsperson.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MechanicGameTests {

    /** The work time the repeater tests catch up: enough for the Mechanic's 4 operations, not for the Craftsperson's. */
    private static final long WORK_TICKS = 1200L;

    private MechanicGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mechanic_domain_any_input", MechanicGameTests::domainAnyInput);
            register(helper, "mechanic_intermediate_outside_domain", MechanicGameTests::intermediateOutsideDomain);
            register(helper, "mechanic_fills_request", MechanicGameTests::fillsRequest);
            register(helper, "mechanic_craftsperson_slower", MechanicGameTests::craftspersonSlower);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Mechanic. */
    static Job mechanic() {
        return StationTypeGameTests.job(CivMod.id("mechanic"));
    }

    /** A level-2 Craftsperson JobBlock with the Mechanic unlocked by a Crafter in its second Station Types slot. */
    static BlockPos workshop(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = CraftspersonGameTests.craftsperson(helper, relative);
        StationTypeGameTests.setLevel(helper, relative, 2);
        helper.assertTrue(StationTypeGameTests.unlock(helper, pos, 1, Items.CRAFTER), "A Crafter unlocks the Mechanic");
        return pos;
    }

    /**
     * A recipe belongs to the Mechanic's domain ({@code civmod:domains/redstone}) if any input is in the tag: a piston
     * (planks, cobblestone, iron, redstone) and a redstone torch are checked automatically; sticks (planks only) are
     * greyed out with the domain's state line and cannot be checked. The Craftsperson keeps every recipe.
     */
    private static void domainAnyInput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job mechanic = mechanic();
        helper.assertTrue(mechanic != null && mechanic.parent().orElseThrow() == CraftspersonGameTests.craftJob(), "The Mechanic specializes the Craftsperson");
        BlockPos pos = workshop(helper, new BlockPos(1, 1, 1));
        helper.assertTrue(Patterns.setOutput(level, pos, mechanic, 0, Items.PISTON).orElseThrow().checked(), "Piston: one redstone input is enough");
        helper.assertTrue(Patterns.setOutput(level, pos, mechanic, 1, Items.REDSTONE_TORCH).orElseThrow().checked(), "Redstone torch checked");
        Pattern sticks = Patterns.setOutput(level, pos, mechanic, 2, Items.STICK).orElseThrow();
        helper.assertFalse(sticks.checked(), "Sticks are outside the domain: unchecked");
        helper.assertFalse(Patterns.toggleCheck(level, pos, mechanic, 2), "And cannot be checked");
        PatternsTabData tab = PatternsTabData.of(level, pos, mechanic);
        helper.assertTrue(tab.filtered().contains(2) && !tab.filtered().contains(0) && tab.unusable(2),
                "Greyed out: " + tab.filtered());
        helper.assertTrue(tab.filteredLine().equals(Component.translatable("civmod.gui.patterns.outside_domain")),
                "Domain state line: " + tab.filteredLine());
        helper.assertTrue(Patterns.setOutput(level, pos, CraftspersonGameTests.craftJob(), 0, Items.STICK).orElseThrow().checked(),
                "The Craftsperson crafts sticks");
        helper.succeed();
    }

    /**
     * A specialist resolves only intermediates inside its domain: 4 repeaters need 8 redstone torches, which the Mechanic
     * crafts, but their sticks are outside the redstone domain; with no sticks in stock the request reports "Missing:
     * Stick" although the Mechanic's library holds a stick pattern.
     */
    private static void intermediateOutsideDomain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Job mechanic = mechanic();
        Setup setup = setup(helper, List.of(new ItemStack(Items.REDSTONE, 12), new ItemStack(Items.STONE, 12)));
        BlockPos craft = workshop(helper, new BlockPos(1, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.CRAFTER);
        Settler settler = worker(helper, setup, craft, mechanic);
        helper.assertTrue(Patterns.setOutput(level, craft, mechanic, 0, Items.REPEATER).orElseThrow().checked(), "Repeaters checked");
        helper.assertTrue(Patterns.setOutput(level, craft, mechanic, 1, Items.REDSTONE_TORCH).orElseThrow().checked(), "Torches checked");
        helper.assertFalse(Patterns.setOutput(level, craft, mechanic, 2, Items.STICK).orElseThrow().checked(), "Sticks stay unchecked");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);

        helper.assertTrue(delivered(level, setup.clerk()) == 0, "No repeaters without sticks");
        Request request = Requests.byPriority(setup.settlement()).stream().filter(found -> found.item() == Items.REPEATER).findFirst().orElseThrow();
        List<Item> missing = request.missing().stream().map(Request.Missing::item).toList();
        helper.assertTrue(missing.contains(Items.STICK), "Missing: Stick " + request.missing());
        helper.assertFalse(missing.contains(Items.REDSTONE_TORCH), "Torches are made in the domain: " + request.missing());
        cleanUp(setup, settler);
        helper.succeed();
    }

    /**
     * The Mechanic is unlocked by and works at a Crafter and fills a Clerk request for 4 repeaters within the work time
     * (job factor 1.2, four times the Craftsperson's 0.3).
     */
    private static void fillsRequest(GameTestHelper helper) {
        Job mechanic = mechanic();
        helper.assertTrue(mechanic.jobFactor() == 1.2 && CraftspersonGameTests.craftJob().jobFactor() == 0.3,
                "Job factors 1.2 / 0.3: " + mechanic.jobFactor());
        helper.setBlock(new BlockPos(3, 1, 9), Blocks.CRAFTER);
        helper.assertTrue(mechanic.unlockItem().equals(Optional.of(Items.CRAFTER))
                && mechanic.isValidWorkPlace(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 9))), "Unlocked by and works at a Crafter");
        int delivered = repeaters(helper, mechanic, Blocks.CRAFTER);
        helper.assertTrue(delivered == 4, "The Mechanic delivered 4 repeaters: " + delivered);
        helper.succeed();
    }

    /** The same request in the same work time: the Craftsperson at a crafting table is not done yet. */
    private static void craftspersonSlower(GameTestHelper helper) {
        int delivered = repeaters(helper, CraftspersonGameTests.craftJob(), Blocks.CRAFTING_TABLE);
        helper.assertTrue(delivered < 4, "The Craftsperson is slower: " + delivered);
        helper.succeed();
    }

    /**
     * Requests 4 repeaters (torches, redstone and stone in the Clerk's storage), lets a settler of the Job work
     * {@link #WORK_TICKS} at its station and returns how many arrived.
     */
    private static int repeaters(GameTestHelper helper, Job job, Block station) {
        ServerLevel level = helper.getLevel();
        Setup setup = setup(helper, List.of(new ItemStack(Items.REDSTONE_TORCH, 8), new ItemStack(Items.REDSTONE, 4), new ItemStack(Items.STONE, 12)));
        BlockPos relative = new BlockPos(1, 1, 5);
        BlockPos craft = job == mechanic() ? workshop(helper, relative) : CraftspersonGameTests.craftsperson(helper, relative);
        helper.setBlock(new BlockPos(3, 1, 5), station);
        Settler settler = worker(helper, setup, craft, job);
        helper.assertTrue(Patterns.setOutput(level, craft, job, 0, Items.REPEATER).orElseThrow().checked(), "Repeaters checked");
        CraftspersonGameTests.catchUp(level, settler, WORK_TICKS);
        int delivered = delivered(level, setup.clerk());
        cleanUp(setup, settler);
        return delivered;
    }

    private record Setup(BlockPos clerk, SettlementData data, Settlement settlement) {
    }

    /** A working Clerk whose storage holds the items, with a rule for 4 repeaters. */
    private static Setup setup(GameTestHelper helper, List<ItemStack> stock) {
        ServerLevel level = helper.getLevel();
        BlockPos clerk = StockRuleGameTests.workingClerk(helper);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(clerk).orElseThrow();
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 1)));
        for (int i = 0; i < stock.size(); i++) {
            chest.setItem(i, stock.get(i).copy());
        }
        helper.assertTrue(StockRules.setRule(data, clerk, StockRule.minimum(0, Items.REPEATER, 4, 0)), "Rule set");
        StockRules.check(level, clerk, ClerkGameTests.MORNING);
        return new Setup(clerk, data, settlement);
    }

    /** A settler working the Job at the JobBlock. */
    private static Settler worker(GameTestHelper helper, Setup setup, BlockPos craft, Job job) {
        Settler settler = ClerkGameTests.settler(helper, new BlockPos(2, 1, 7), setup.data(), setup.settlement());
        settler.setCustomName(Component.literal("Mira"));
        settler.getFood().setMorningDay(Math.floorDiv(helper.getLevel().getOverworldClockTime(), 24000L));
        helper.assertTrue(TestAssignments.assign(setup.data(), craft, settler.getUUID(), "Mira", setup.settlement().id(), List.of(), job)
                == JobAssignments.AssignResult.OK, job + " assigned");
        settler.addJob(new SettlerJob(job, craft));
        return settler;
    }

    private static int delivered(ServerLevel level, BlockPos clerk) {
        return ClerkStorage.stock(level, clerk, true).count(stack -> stack.is(Items.REPEATER));
    }

    private static void cleanUp(Setup setup, Settler settler) {
        TestAssignments.unassign(setup.data(), setup.clerk());
        settler.discard();
    }
}
