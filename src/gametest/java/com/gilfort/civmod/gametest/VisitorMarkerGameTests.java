package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.gametest.InnGameTests.Inn;
import com.gilfort.civmod.inn.Hiring;
import com.gilfort.civmod.inn.InnRules;
import com.gilfort.civmod.inn.VisitorOffer;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the visitor marker (vault {@code Systems/NPC Population.md}, section 6): the synced flag that makes the client draw a
 * compass above every visitor. The flag is set when a visitor arrives, cleared when it is hired, never set for a settler that was no
 * visitor, and comes back from the save of a visitor. The icon itself is drawn on the client and is part of the client test checklist.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class VisitorMarkerGameTests {

    private static final BlockPos BED = new BlockPos(1, 1, 6);

    private VisitorMarkerGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "visitor_marker_arrival", VisitorMarkerGameTests::arrival);
            register(helper, "visitor_marker_hire", VisitorMarkerGameTests::hire);
            register(helper, "visitor_marker_settler", VisitorMarkerGameTests::neverAVisitor);
            register(helper, "visitor_marker_save", VisitorMarkerGameTests::survivesSave);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** A visitor in an inn with one guest bed and a Tavern Host. */
    private static Settler arrived(GameTestHelper helper, Inn inn) {
        InnGameTests.play(helper, inn, InnGameTests.today(helper), InnGameTests.NOON);
        List<Settler> visitors = InnGameTests.visitors(helper);
        helper.assertTrue(visitors.size() == 1, "One visitor came: " + visitors.size());
        return visitors.get(0);
    }

    /** A visitor that has just arrived at the inn carries the marker. */
    private static void arrival(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 3));
        Inn inn = InnGameTests.inn(helper, 1, 1, true);
        Settler visitor = arrived(helper, inn);
        helper.assertTrue(visitor.isVisitor() && visitor.hasVisitorMarker(), "A visitor carries the marker");
        InnGameTests.end(helper, inn);
    }

    /** A hired visitor is a settler: the marker is gone, and it stays gone. */
    private static void hire(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 3));
        Inn inn = InnGameTests.inn(helper, 1, 1, true);
        VillagerConversionGameTests.bed(helper, BED);
        Settler visitor = arrived(helper, inn);
        ServerPlayer player = TestPlayers.add(helper, Vec3.atCenterOf(helper.absolutePos(InnGameTests.PANTRY)));
        player.setGameMode(GameType.SURVIVAL);
        helper.assertTrue(visitor.hasVisitorMarker(), "The marker shows before the hire");

        ItemStack price = VisitorOffer.of(visitor).price().stack();
        helper.assertTrue(Hiring.hire(player, visitor, price) == Hiring.Outcome.HIRED, "The visitor was hired");

        helper.assertTrue(!visitor.isVisitor() && !visitor.hasVisitorMarker(), "A hired visitor shows no marker");
        TestPlayers.remove(player);
        SettlementData data = SettlementData.get(helper.getLevel());
        inn.settlement().bedOf(visitor.getUUID()).ifPresent(bed -> data.releaseBed(inn.settlement(), bed));
        data.removeMember(inn.settlement(), visitor.getUUID());
        visitor.discard();
        InnGameTests.end(helper, inn);
    }

    /** A settler that was never a visitor has no marker. */
    private static void neverAVisitor(GameTestHelper helper) {
        Settler settler = ModEntityTypes.SETTLER.get().create(helper.getLevel(), EntitySpawnReason.EVENT);
        helper.assertTrue(!settler.isVisitor() && !settler.hasVisitorMarker(), "A new settler carries no marker");
        settler.discard();
        helper.succeed();
    }

    /** A visitor that is loaded from its save carries the marker again; a loaded settler does not. */
    private static void survivesSave(GameTestHelper helper) {
        InnRules.use(InnGameTests.rules(1.0, 0.0, 3));
        Inn inn = InnGameTests.inn(helper, 1, 1, true);
        Settler visitor = arrived(helper, inn);
        ServerLevel level = helper.getLevel();

        Settler loadedVisitor = reload(level, visitor);
        helper.assertTrue(loadedVisitor.isVisitor() && loadedVisitor.hasVisitorMarker(), "A loaded visitor carries the marker");

        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        Settler loadedSettler = reload(level, settler);
        helper.assertTrue(!loadedSettler.hasVisitorMarker(), "A loaded settler carries no marker");
        loadedVisitor.discard();
        settler.discard();
        loadedSettler.discard();
        InnGameTests.end(helper, inn);
    }

    private static Settler reload(ServerLevel level, Settler settler) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        settler.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        Settler loaded = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        return loaded;
    }
}
