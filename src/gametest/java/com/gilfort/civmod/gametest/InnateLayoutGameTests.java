package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.logistics.InnateLayout;
import com.gilfort.civmod.logistics.Pattern;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the pure innate slot layout (#232): no settler, no settlement, no world state.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class InnateLayoutGameTests {

    private InnateLayoutGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "innate_layout_pure", InnateLayoutGameTests::pure);
            register(helper, "innate_layout_displaced_lost", InnateLayoutGameTests::displacedLost);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    private static ResourceKey<Recipe<?>> id(String path) {
        return ResourceKey.create(Registries.RECIPE, CivMod.id(path));
    }

    private static Pattern player(int slot, Item output) {
        return new Pattern(slot, output, 1, List.of(), Optional.empty(), false, true);
    }

    private static Pattern innate(int slot, ResourceKey<Recipe<?>> recipe, boolean checked) {
        return new Pattern(slot, Items.FLINT, 1, List.of(), Optional.of(recipe), false, checked, true);
    }

    /**
     * Two innate recipes take slots 0 and 1: the player pattern on slot 1 moves to the first free slot, an innate
     * pattern keeps its check state and always flag, a new one is reported as fresh, a removed one frees its slot.
     */
    private static void pure(GameTestHelper helper) {
        ResourceKey<Recipe<?>> a = id("layout/a");
        ResourceKey<Recipe<?>> b = id("layout/b");
        ResourceKey<Recipe<?>> gone = id("layout/gone");
        List<Pattern> current = List.of(innate(0, gone, true), player(1, Items.STICK), player(5, Items.COAL),
                innate(7, a, true).withAlways(true, 4));
        InnateLayout.Result result = InnateLayout.layout(current, List.of(a, b), InnateLayoutGameTests::innate);
        List<Pattern> expected = List.of(innate(0, a, true).withAlways(true, 4), innate(1, b, false), player(2, Items.STICK), player(5, Items.COAL));
        helper.assertTrue(result.patterns().equals(expected), "Laid out: " + result.patterns());
        helper.assertTrue(result.fresh().equals(List.of(1)), "Only recipe b is new: " + result.fresh());
        helper.assertTrue(InnateLayout.layout(result.patterns(), List.of(a, b), InnateLayoutGameTests::innate).patterns().equals(result.patterns()),
                "Laying out a laid out library changes nothing");
        helper.succeed();
    }

    /** With every slot beyond the innate ones taken, a displaced player pattern is lost (ghost items, nothing to give back). */
    private static void displacedLost(GameTestHelper helper) {
        List<Pattern> current = new ArrayList<>();
        for (int slot = 0; slot < Pattern.SLOTS; slot++) {
            current.add(player(slot, Items.STICK));
        }
        InnateLayout.Result result = InnateLayout.layout(current, List.of(id("layout/a")), InnateLayoutGameTests::innate);
        helper.assertTrue(result.patterns().size() == Pattern.SLOTS, "Slots stay full: " + result.patterns().size());
        helper.assertTrue(result.patterns().getFirst().innate() && result.patterns().stream().filter(p -> !p.innate()).count() == Pattern.SLOTS - 1,
                "The displaced pattern on slot 0 is lost");
        helper.succeed();
    }
}
