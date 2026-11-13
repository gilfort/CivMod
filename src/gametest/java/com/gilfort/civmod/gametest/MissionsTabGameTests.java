package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.menu.JobBlockInfo;
import com.gilfort.civmod.menu.JobBlockMenu;
import com.gilfort.civmod.menu.MissionsTab;
import com.gilfort.civmod.menu.MissionsTabData;
import com.gilfort.civmod.mission.MissionOffer;
import com.gilfort.civmod.mission.Missions;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.MissionOffers;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Missions tab of the Mission Board window (#230, vault {@code Systems/GUI.md},
 * {@code Systems/Missions.md}): after each operation the synced {@link MissionsTabData} is right, a second window at the
 * same board sees the change with its next broadcast, stale clicks change nothing and a new day's offer appears without
 * reopening the window.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MissionsTabGameTests {

    private MissionsTabGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "missions_tab_mark", MissionsTabGameTests::mark);
            register(helper, "missions_tab_assign", MissionsTabGameTests::assign);
            register(helper, "missions_tab_new_day", MissionsTabGameTests::newDay);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The Mission Board window as the server opens it for a player at the block. */
    private static JobBlockMenu menu(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(MissionRunGameTests.BOARD);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.snapTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 1.5);
        JobBlockEntity entity = (JobBlockEntity) level.getBlockEntity(pos);
        return new JobBlockMenu(1, player.getInventory(), entity, JobBlockInfo.of(ModJobs.ADVENTURER.get(), 2));
    }

    /** What the window holds now (what the server last sent its client). */
    private static MissionsTabData shown(JobBlockMenu menu) {
        return menu.tab(MissionsTab.class).orElseThrow().data().get();
    }

    /** What the window holds after its next broadcast: the server reads the board again. */
    private static MissionsTabData sees(JobBlockMenu menu) {
        menu.resend();
        return shown(menu);
    }

    private static int markedIndex(MissionsTabData data) {
        for (int i = 0; i < data.offer().size(); i++) {
            if (data.offer().get(i).marked()) {
                return i;
            }
        }
        throw new IllegalStateException("No marked mission: " + data.offer());
    }

    /** Marking and unmarking through the tab shows in the window and in a second one; a stale click changes nothing. */
    private static void mark(GameTestHelper helper) {
        Settler settler = MissionRunGameTests.adventurer(helper);
        JobBlockMenu menu = menu(helper);
        JobBlockMenu other = menu(helper);
        MissionsTab tab = menu.tab(MissionsTab.class).orElseThrow();
        int index = markedIndex(sees(menu));
        Identifier mission = shown(menu).offer().get(index).mission();
        helper.assertTrue(sees(other).offer().get(index).marked(), "The other window shows the mark");

        helper.assertTrue(tab.mark(index, mission, false), "Unmark is taken");
        helper.assertFalse(shown(menu).offer().get(index).marked(), "The window shows it unmarked: " + shown(menu).offer());
        helper.assertFalse(sees(other).offer().get(index).marked(), "The other window shows it unmarked");

        Identifier elsewhere = CivMod.id("not_this_one");
        helper.assertFalse(tab.mark(index, elsewhere, true), "A click for a mission that is no longer at that place is refused");
        helper.assertFalse(sees(other).offer().get(index).marked(), "Nothing changed");

        helper.assertTrue(tab.mark(index, mission, true), "Mark is taken");
        helper.assertTrue(shown(menu).offer().get(index).marked() && sees(other).offer().get(index).marked(), "Marked in both windows");
        settler.discard();
        helper.succeed();
    }

    /**
     * Assigning names the settler by its UUID: the entry is locked to it in both windows, unlocking frees it, a settler
     * that is not on the board is refused.
     */
    private static void assign(GameTestHelper helper) {
        Settler settler = MissionRunGameTests.adventurer(helper);
        JobBlockMenu menu = menu(helper);
        JobBlockMenu other = menu(helper);
        MissionsTab tab = menu.tab(MissionsTab.class).orElseThrow();
        MissionsTabData start = sees(menu);
        int index = markedIndex(start);
        Identifier mission = start.offer().get(index).mission();
        helper.assertTrue(start.settler(settler.getUUID()).filter(found -> found.job() == ModJobs.ADVENTURER.get()).isPresent(),
                "The board lists its settler with the Mission Job: " + start.settlers());

        helper.assertFalse(tab.assign(index, mission, Optional.of(UUID.randomUUID())), "A settler that is not on the board is refused");
        helper.assertTrue(tab.assign(index, mission, Optional.of(settler.getUUID())), "Assign is taken");
        MissionOffer.Entry entry = shown(menu).offer().get(index);
        helper.assertTrue(entry.assignee().equals(Optional.of(settler.getUUID())) && entry.locked(), "Assigned and locked: " + entry);
        helper.assertTrue(sees(other).offer().get(index).assignee().equals(Optional.of(settler.getUUID())), "The other window shows the assignee");

        helper.assertFalse(tab.assign(index, CivMod.id("not_this_one"), Optional.empty()), "A stale unlock is refused");
        helper.assertTrue(tab.assign(index, mission, Optional.empty()), "Unlock is taken");
        helper.assertTrue(shown(menu).offer().get(index).assignee().isEmpty() && !shown(menu).offer().get(index).locked(), "Unlocked");
        helper.assertTrue(sees(other).offer().get(index).assignee().isEmpty(), "Unlocked in the other window");
        settler.discard();
        helper.succeed();
    }

    /** An offer drawn on an earlier day is refilled when the window reads the board again: the new day's offer appears. */
    private static void newDay(GameTestHelper helper) {
        Settler settler = MissionRunGameTests.adventurer(helper);
        ServerLevel level = helper.getLevel();
        BlockPos board = helper.absolutePos(MissionRunGameTests.BOARD);
        SettlementData data = SettlementData.get(level);
        long today = DayClock.day(level);
        MissionOffers.refresh(data, board, today - 3, Missions.all(level.registryAccess()), RandomSource.create(9L));
        helper.assertValueEqual(data.settlementAt(board).flatMap(s -> s.block(board)).orElseThrow().missionOffer().day(), today - 3, "An old offer");
        JobBlockMenu menu = menu(helper);
        List<MissionOffer.Entry> offer = sees(menu).offer();
        helper.assertValueEqual(data.settlementAt(board).flatMap(s -> s.block(board)).orElseThrow().missionOffer().day(), today, "Refilled for today");
        helper.assertTrue(offer.equals(MissionRunGameTests.offer(helper)), "The window holds the new offer: " + offer);
        settler.discard();
        helper.succeed();
    }
}
