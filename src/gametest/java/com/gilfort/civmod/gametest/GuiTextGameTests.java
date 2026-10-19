package com.gilfort.civmod.gametest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.menu.GuiIcons;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.Registries;
import net.minecraft.data.AtlasIds;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the text rules of CivMod windows (#62): help lines hold one short item each, the Patterns help shows the
 * window's checkmark sprite inline.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class GuiTextGameTests {

    /** Longest help line in characters: about one tooltip line of 200 px. */
    private static final int MAX_HELP_LENGTH = 48;

    private GuiTextGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "gui_text_help_lines", GuiTextGameTests::helpLines);
            register(helper, "gui_text_checkmark_inline", GuiTextGameTests::checkmarkInline);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** Every help text is one short item: no "·" or ";" joining items, no second sentence. */
    private static void helpLines(GameTestHelper helper) {
        JsonObject lang = lang(helper);
        int helpKeys = 0;
        for (Map.Entry<String, com.google.gson.JsonElement> entry : lang.entrySet()) {
            if (!entry.getKey().contains(".help.")) {
                continue;
            }
            helpKeys++;
            String text = entry.getValue().getAsString();
            helper.assertFalse(text.contains("·") || text.contains(";"), "One item per line: " + entry.getKey() + " = " + text);
            helper.assertFalse(text.matches(".*[.:] [A-Z].*"), "One sentence per line: " + entry.getKey() + " = " + text);
            helper.assertTrue(text.length() <= MAX_HELP_LENGTH, "Short: " + entry.getKey() + " = " + text);
        }
        helper.assertTrue(helpKeys > 0, "Help keys found");
        helper.assertTrue("Click a pattern: edit".equals(string(lang, "civmod.gui.patterns.help.click")), "Patterns help: click");
        helper.assertTrue("Right-click: check / uncheck".equals(string(lang, "civmod.gui.patterns.help.check")), "Patterns help: right-click");
        helper.assertTrue("Click with an item: new output".equals(string(lang, "civmod.gui.patterns.help.item")), "Patterns help: item");
        helper.assertTrue("Only %s patterns are crafted.".equals(string(lang, "civmod.gui.patterns.help.crafted")), "Patterns help: crafted");
        helper.succeed();
    }

    /** "Only ✔ patterns are crafted.": the checkmark is the beacon confirm sprite of the GUI atlas, ✔ as fallback. */
    private static void checkmarkInline(GameTestHelper helper) {
        Component mark = GuiIcons.inlineCheckmark();
        helper.assertTrue(GuiIcons.CHECKMARK.equals(Identifier.withDefaultNamespace("container/beacon/confirm")),
                "The window's checkmark sprite: " + GuiIcons.CHECKMARK);
        if (!(mark.getContents() instanceof ObjectContents object)) {
            helper.fail("An object component: " + mark.getContents());
            return;
        }
        helper.assertTrue(object.contents().equals(new AtlasSprite(AtlasIds.GUI, GuiIcons.CHECKMARK)), "Sprite of the GUI atlas: " + object.contents());
        helper.assertTrue(object.fallback().map(Component::getString).filter("✔"::equals).isPresent(), "Fallback ✔: " + object.fallback());
        helper.succeed();
    }

    private static String string(JsonObject lang, String key) {
        return lang.has(key) ? lang.get(key).getAsString() : null;
    }

    static JsonObject lang(GameTestHelper helper) {
        try (InputStream in = CivMod.class.getResourceAsStream("/assets/civmod/lang/en_us.json")) {
            if (in == null) {
                throw helper.assertionException(Component.literal("en_us.json not on the classpath"));
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw helper.assertionException(Component.literal("en_us.json unreadable: " + e));
        }
    }
}
