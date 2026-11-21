package com.gilfort.civmod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.gametest.MasteryGameTests.Run;
import com.gilfort.civmod.job.ToolStaircase;
import com.gilfort.civmod.job.ToolTiers;
import com.gilfort.civmod.logistics.ItemKey;
import com.gilfort.civmod.mastery.Masterwork;
import com.gilfort.civmod.mastery.MasterworkData;
import com.gilfort.civmod.mastery.MasterworkNotice;
import com.gilfort.civmod.mastery.MasterworkRules;
import com.gilfort.civmod.mastery.MasterworkRules.Effect;
import com.gilfort.civmod.registry.ModDataComponents;
import com.gilfort.civmod.settlement.Requests;
import com.gilfort.civmod.settlement.StockRules;
import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the masterworks of gear (spec #304, story #303, vault {@code Systems/Producing Jobs.md}): a Smith with a
 * scripted roll crafts gear in catch-up and the stack in the Clerk shows the components. The global {@link QuietMastery}
 * keeps every other test free of the roll. The scripted source answers each pool draw with the index of the option it is
 * told to pick, so a test chooses the effects of the hits.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class MasterworkGameTests {

    /** What the settler's custom name is in {@link FletcherGameTests#worker}. */
    private static final String MAKER = "Flavia";
    private static final float SPEED_TOLERANCE = 1.0e-4F;
    /** Where the players of the message tests stand from the settler, in blocks. */
    private static final double NEAR_BLOCKS = 10.0;
    private static final double FAR_BLOCKS = 40.0;
    /** The index of durability +25 % in the options of every item of the default file: the pool for all items comes first. */
    private static final int DURABILITY = 0;
    /** The index of the effect of the item's kind: it follows the pool for all items. */
    private static final int OTHER = 1;

    private MasterworkGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "mastery_masterwork_one_effect", MasterworkGameTests::oneEffect);
            register(helper, "mastery_masterwork_two_effects", MasterworkGameTests::twoEffects);
            register(helper, "mastery_masterwork_same_effect_twice", MasterworkGameTests::sameEffectTwice);
            register(helper, "mastery_masterwork_free_draw", MasterworkGameTests::freeDraw);
            register(helper, "mastery_masterwork_first_hit_is_free", MasterworkGameTests::firstHitIsFree);
            register(helper, "mastery_masterwork_free_combinations", MasterworkGameTests::freeCombinations);
            register(helper, "mastery_masterwork_weapon", MasterworkGameTests::weapon);
            register(helper, "mastery_masterwork_armour", MasterworkGameTests::armour);
            register(helper, "mastery_masterwork_counts_as_plain_item", MasterworkGameTests::countsAsPlainItem);
            register(helper, "mastery_masterwork_message_range", MasterworkGameTests::messageRange);
            register(helper, "mastery_masterwork_catch_up_is_silent", MasterworkGameTests::catchUpIsSilent);
            register(helper, "mastery_masterwork_anvil_keeps_bonuses", MasterworkGameTests::anvilKeepsBonuses);
            register(helper, "mastery_masterwork_upgrade_keeps_bonuses", MasterworkGameTests::upgradeKeepsBonuses);
            register(helper, "mastery_masterwork_upgrade_keeps_attributes", MasterworkGameTests::upgradeKeepsAttributes);
            register(helper, "mastery_masterwork_upgrade_with_hits_adds", MasterworkGameTests::upgradeWithHitsAdds);
            register(helper, "mastery_masterwork_player_upgrade", MasterworkGameTests::playerUpgrade);
            register(helper, "mastery_masterwork_player_upgrade_quick", MasterworkGameTests::playerUpgradeQuick);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // Ingredients are built per call: the items are not bound while this class loads.

    private static List<ItemStack> pickaxeStock() {
        return List.of(new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.STICK, 2));
    }

    private static List<ItemStack> swordStock() {
        return List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.STICK));
    }

    private static List<ItemStack> chestplateStock() {
        return List.of(new ItemStack(Items.IRON_INGOT, 8));
    }

    /** Every stack of the item the Clerk's storage and the settler's buffer hold: where a masterwork waits is not the point. */
    private static List<ItemStack> made(GameTestHelper helper, Run run, Item item) {
        List<ItemStack> found = new ArrayList<>();
        Container store = ClerkGameTests.store(helper.getLevel(), run.setup().clerk());
        for (int slot = 0; slot < store.getContainerSize(); slot++) {
            if (store.getItem(slot).is(item)) {
                found.add(store.getItem(slot));
            }
        }
        run.settler().getWork().buffer(run.craft()).stacks().stream().filter(stack -> stack.is(item)).forEach(found::add);
        return found;
    }

    /** A Smith crafts the item once with the given hits and the one stack it made comes back. */
    private static ItemStack craft(GameTestHelper helper, Run run, Item item) {
        MasteryGameTests.work(helper, run);
        List<ItemStack> crafted = made(helper, run, item);
        helper.assertTrue(crafted.size() == 1, "One " + item + " (status " + run.settler().getWork().status(run.craft()) + "): " + crafted);
        return crafted.getFirst();
    }

    /** A Smith whose craft has the hits; the pool draws take the picks (indices into the item's options, see {@link MasteryRolls#drawing}). */
    private static Run smith(GameTestHelper helper, List<ItemStack> stock, Item item, int hits, int... picks) {
        return MasteryGameTests.smith(helper, stock, item, MasteryRolls.drawing(hits, picks));
    }

    private static String key(Component component) {
        return component != null && component.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "<" + component + ">";
    }

    private static List<Component> lore(ItemStack stack) {
        return stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines();
    }

    private static List<String> loreKeys(ItemStack stack) {
        return lore(stack).stream().map(MasterworkGameTests::key).toList();
    }

    /** The name, the rarity and the "Made by" line every masterwork has, whatever its effects. */
    private static void assertLook(GameTestHelper helper, ItemStack stack, Rarity rarity) {
        helper.assertTrue("civmod.masterwork.name".equals(key(stack.get(DataComponents.ITEM_NAME))), "Prefix through ITEM_NAME: " + stack.get(DataComponents.ITEM_NAME));
        helper.assertTrue(stack.get(DataComponents.CUSTOM_NAME) == null, "The custom name stays free for the anvil");
        helper.assertTrue(stack.getRarity() == rarity, "Rarity " + rarity + ": " + stack.getRarity());
        helper.assertTrue("civmod.masterwork.made_by".equals(loreKeys(stack).getFirst()), "The lore starts with the maker: " + lore(stack));
        Component maker = (Component) ((TranslatableContents) lore(stack).getFirst().getContents()).getArgs()[0];
        helper.assertTrue(maker.getString().equals(MAKER), "The lore names " + MAKER + ": " + maker);
    }

    private static void assertDurability(GameTestHelper helper, ItemStack stack, Item item, int percent) {
        int plain = new ItemStack(item).getMaxDamage();
        helper.assertTrue(stack.getMaxDamage() == plain * (100 + percent) / 100, "Durability +" + percent + " %: " + stack.getMaxDamage() + " of " + plain);
    }

    /** The tool rules mine and drop the same blocks, only {@code factor} faster, so the tier stays. */
    private static void assertToolSpeed(GameTestHelper helper, ItemStack stack, float factor) {
        Tool plain = new ItemStack(stack.getItem()).get(DataComponents.TOOL);
        Tool tool = stack.get(DataComponents.TOOL);
        helper.assertTrue(tool.rules().size() == plain.rules().size() && tool.defaultMiningSpeed() == plain.defaultMiningSpeed(), "Same rules: " + tool);
        for (int i = 0; i < plain.rules().size(); i++) {
            Tool.Rule was = plain.rules().get(i);
            Tool.Rule now = tool.rules().get(i);
            helper.assertTrue(now.blocks().equals(was.blocks()) && now.correctForDrops().equals(was.correctForDrops()), "Same blocks and drops in rule " + i);
            helper.assertTrue(was.speed().isPresent() == now.speed().isPresent()
                    && (was.speed().isEmpty() || Math.abs(now.speed().get() - was.speed().get() * factor) < SPEED_TOLERANCE),
                    "Rule " + i + " is " + factor + " times faster: " + was.speed() + " to " + now.speed());
        }
        helper.assertTrue(ToolTiers.tier(stack) == ToolTiers.tier(new ItemStack(stack.getItem())), "The tool tier stays");
    }

    /** Whether the masterwork's own modifier of the attribute is on the stack, in the slot, with the amount, next to the item's own ones. */
    private static void assertBonus(GameTestHelper helper, ItemStack stack, Holder<Attribute> attribute, double amount, EquipmentSlotGroup slot) {
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        ItemAttributeModifiers plain = new ItemStack(stack.getItem()).get(DataComponents.ATTRIBUTE_MODIFIERS);
        helper.assertTrue(modifiers.modifiers().containsAll(plain.modifiers()), "The item's own modifiers are kept: " + modifiers);
        long bonuses = modifiers.modifiers().stream().filter(entry -> entry.attribute().equals(attribute) && entry.modifier().id().getPath().startsWith("masterwork/")).count();
        helper.assertTrue(bonuses == 1, "One masterwork modifier for " + attribute.getRegisteredName() + ": " + modifiers);
        helper.assertTrue(modifiers.modifiers().stream().anyMatch(entry -> entry.attribute().equals(attribute) && entry.modifier().id().getPath().startsWith("masterwork/")
                && entry.modifier().amount() == amount && entry.slot() == slot), attribute.getRegisteredName() + " +" + amount + " in " + slot + ": " + modifiers);
    }

    /**
     * One hit: the first effect, durability +25 %, drawn from the pool for all items. The name gets the prefix through
     * ITEM_NAME (the anvil's CUSTOM_NAME stays free), the rarity is uncommon (yellow), the lore names the settler and the
     * durability bonus vanilla does not show. The masterwork takes the place of a saving: all ingredients are used up.
     */
    private static void oneEffect(GameTestHelper helper) {
        Run run = smith(helper, pickaxeStock(), Items.IRON_PICKAXE, 1);
        ItemStack stack = craft(helper, run, Items.IRON_PICKAXE);
        helper.assertTrue(MasteryGameTests.total(helper, run, Items.IRON_INGOT) == 0 && MasteryGameTests.total(helper, run, Items.STICK) == 0,
                "Nothing is saved from a pickaxe, the hit makes a masterwork instead");
        assertDurability(helper, stack, Items.IRON_PICKAXE, 25);
        assertLook(helper, stack, Rarity.UNCOMMON);
        helper.assertTrue(loreKeys(stack).equals(List.of("civmod.masterwork.made_by", "civmod.masterwork.durability")), "Lore: made by, durability: " + lore(stack));
        assertToolSpeed(helper, stack, 1.0F);
        helper.assertTrue(stack.get(DataComponents.ATTRIBUTE_MODIFIERS).equals(new ItemStack(Items.IRON_PICKAXE).get(DataComponents.ATTRIBUTE_MODIFIERS)),
                "No attribute bonus with one effect");
        MasteryGameTests.finish(helper, run);
    }

    /** Two hits on a tool: durability, then the tool pool's mining speed +10 %; aqua, and a lore line for the mining speed. */
    private static void twoEffects(GameTestHelper helper) {
        Run run = smith(helper, pickaxeStock(), Items.IRON_PICKAXE, 2, DURABILITY, OTHER);
        ItemStack stack = craft(helper, run, Items.IRON_PICKAXE);
        assertDurability(helper, stack, Items.IRON_PICKAXE, 25);
        assertToolSpeed(helper, stack, 1.1F);
        assertLook(helper, stack, Rarity.RARE);
        helper.assertTrue(loreKeys(stack).equals(List.of("civmod.masterwork.made_by", "civmod.masterwork.durability", "civmod.masterwork.mining_speed")),
                "Lore: made by, durability, mining speed: " + lore(stack));
        MasteryGameTests.finish(helper, run);
    }

    /** Three hits on a tool: the tool pool has one effect, so the mining speed is drawn twice and adds up to +20 %; light purple. */
    private static void sameEffectTwice(GameTestHelper helper) {
        Run run = smith(helper, pickaxeStock(), Items.IRON_PICKAXE, 3, DURABILITY, OTHER, OTHER);
        ItemStack stack = craft(helper, run, Items.IRON_PICKAXE);
        assertDurability(helper, stack, Items.IRON_PICKAXE, 25);
        assertToolSpeed(helper, stack, 1.2F);
        assertLook(helper, stack, Rarity.EPIC);
        helper.assertTrue(loreKeys(stack).size() == 3, "One line per bonus, not per effect: " + lore(stack));
        helper.assertTrue(((TranslatableContents) lore(stack).get(2).getContents()).getArgs()[0].equals("20"), "The lore shows the sum: " + lore(stack).get(2));
        MasteryGameTests.finish(helper, run);
    }

    /** The kinds of change of the parts of an effect, in order. */
    private static List<MasterworkRules.EffectPart.Type> types(Effect effect) {
        return effect.parts().stream().map(MasterworkRules.EffectPart::type).toList();
    }

    /**
     * Every hit draws from one list of all the options of the item: the pool for all items and the pool of its kind. A pickaxe
     * has durability and mining speed, each as likely as the other (the draw asks for a number below the number of options) and
     * each reachable. Armour +1 and toughness +0.5 stay one option of two parts. An item of no kind draws from the pool for all
     * items alone.
     */
    private static void freeDraw(GameTestHelper helper) {
        MasterworkRules rules = MasterworkRules.get();
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        List<Effect> options = rules.options(pickaxe);
        helper.assertTrue(options.size() == 2 && types(options.get(0)).equals(List.of(MasterworkRules.EffectPart.Type.DURABILITY))
                && types(options.get(1)).equals(List.of(MasterworkRules.EffectPart.Type.MINING_SPEED)), "A pickaxe has durability and mining speed: " + options);
        List<Integer> bounds = new ArrayList<>();
        RandomSource asking = new LegacyRandomSource(0L) {
            @Override
            public int nextInt(int bound) {
                bounds.add(bound);
                return 1;
            }
        };
        helper.assertTrue(rules.draw(pickaxe, 1, asking).equals(List.of(options.get(1))), "The draw can pick the second option");
        helper.assertTrue(bounds.equals(List.of(options.size())), "One draw over all options, equal chance for each: " + bounds);
        for (int pick = 0; pick < options.size(); pick++) {
            helper.assertTrue(rules.draw(pickaxe, 1, MasteryRolls.drawing(1, pick)).equals(List.of(options.get(pick))), "Option " + pick + " is reachable");
        }
        List<Effect> armour = rules.options(new ItemStack(Items.IRON_CHESTPLATE));
        helper.assertTrue(armour.size() == 2 && armour.get(1).parts().size() == 2, "Armour +1 and toughness +0.5 are one option of two parts: " + armour);
        helper.assertTrue(rules.options(new ItemStack(Items.BOW)).equals(rules.all()), "A bow has no kind: the pool for all items alone");
        helper.succeed();
    }

    /** Nothing is fixed on the first hit: a single hit that draws mining speed gives mining speed and no durability. */
    private static void firstHitIsFree(GameTestHelper helper) {
        Run run = smith(helper, pickaxeStock(), Items.IRON_PICKAXE, 1, OTHER);
        ItemStack stack = craft(helper, run, Items.IRON_PICKAXE);
        helper.assertTrue(stack.getMaxDamage() == new ItemStack(Items.IRON_PICKAXE).getMaxDamage(), "No durability bonus: " + stack.getMaxDamage());
        assertToolSpeed(helper, stack, 1.1F);
        assertLook(helper, stack, Rarity.UNCOMMON);
        helper.assertTrue(loreKeys(stack).equals(List.of("civmod.masterwork.made_by", "civmod.masterwork.mining_speed")), "Lore: made by, mining speed: " + lore(stack));
        MasteryGameTests.finish(helper, run);
    }

    /** Every combination is possible: three mining speeds add up to +30 % with the durability untouched, and a bow, which has only the pool for all items, gets +75 % durability from three hits. */
    private static void freeCombinations(GameTestHelper helper) {
        MasterworkRules rules = MasterworkRules.get();
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        Masterwork.turnInto(pickaxe, rules.draw(pickaxe, 3, MasteryRolls.drawing(3, OTHER, OTHER, OTHER)), MAKER);
        helper.assertTrue(pickaxe.getMaxDamage() == new ItemStack(Items.IRON_PICKAXE).getMaxDamage(), "No durability bonus");
        assertToolSpeed(helper, pickaxe, 1.3F);
        assertLook(helper, pickaxe, Rarity.EPIC);
        ItemStack bow = new ItemStack(Items.BOW);
        Masterwork.turnInto(bow, rules.draw(bow, 3, MasteryRolls.drawing(3, 0, 1, 2)), MAKER);
        assertDurability(helper, bow, Items.BOW, 75);
        helper.succeed();
    }

    /** Two hits on a weapon: durability, then attack damage +1 on top of the sword's own modifiers, in the main hand. */
    private static void weapon(GameTestHelper helper) {
        Run run = smith(helper, swordStock(), Items.IRON_SWORD, 2, DURABILITY, OTHER);
        ItemStack stack = craft(helper, run, Items.IRON_SWORD);
        assertDurability(helper, stack, Items.IRON_SWORD, 25);
        assertBonus(helper, stack, Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlotGroup.MAINHAND);
        assertLook(helper, stack, Rarity.RARE);
        helper.assertTrue(loreKeys(stack).equals(List.of("civmod.masterwork.made_by", "civmod.masterwork.durability")), "Vanilla shows the damage itself: " + lore(stack));
        MasteryGameTests.finish(helper, run);
    }

    /** Two hits on armour: durability, then armour +1 and toughness +0.5 together, in the armour's own slot. */
    private static void armour(GameTestHelper helper) {
        Run run = smith(helper, chestplateStock(), Items.IRON_CHESTPLATE, 2, DURABILITY, OTHER);
        ItemStack stack = craft(helper, run, Items.IRON_CHESTPLATE);
        assertDurability(helper, stack, Items.IRON_CHESTPLATE, 25);
        assertBonus(helper, stack, Attributes.ARMOR, 1.0, EquipmentSlotGroup.CHEST);
        assertBonus(helper, stack, Attributes.ARMOR_TOUGHNESS, 0.5, EquipmentSlotGroup.CHEST);
        assertLook(helper, stack, Rarity.RARE);
        MasteryGameTests.finish(helper, run);
    }

    /**
     * A masterwork pickaxe is the plain pickaxe for the Clerk: it fills the request the Smith crafted it for, meets a Min rule
     * of the plain item and has the plain item's tier.
     */
    private static void countsAsPlainItem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Run run = smith(helper, pickaxeStock(), Items.IRON_PICKAXE, 3);
        ItemStack stack = craft(helper, run, Items.IRON_PICKAXE);
        helper.assertTrue(stack.get(DataComponents.RARITY) == Rarity.EPIC, "A masterwork it is");
        helper.assertTrue(ItemKey.of(stack).equals(ItemKey.of(Items.IRON_PICKAXE)), "Same item key as the plain pickaxe: " + ItemKey.of(stack));
        helper.assertTrue(ToolTiers.tier(stack) == ToolTiers.tier(new ItemStack(Items.IRON_PICKAXE)), "Same tier");
        // The tool choice ranks by tier and enchantment only: at no skill is the masterwork allowed or enough where the plain one is not.
        ItemStack plain = new ItemStack(Items.IRON_PICKAXE);
        for (int skill : new int[] {1, 30, 60, 100}) {
            ToolStaircase.Rule rule = ToolStaircase.forSkill(skill);
            helper.assertTrue(rule.allows(stack) == rule.allows(plain) && rule.meets(stack) == rule.meets(plain),
                    "At skill " + skill + " the settler treats the masterwork like the plain pickaxe");
        }
        helper.assertTrue(FletcherGameTests.count(level, run.setup().clerk(), Items.IRON_PICKAXE) == 1, "The Clerk holds the masterwork for the request of the plain pickaxe");
        StockRules.check(level, run.setup().clerk(), ClerkGameTests.MORNING);
        helper.assertTrue(Requests.byPriority(run.setup().settlement()).stream().noneMatch(request -> request.amount() > 0),
                "The masterwork meets the Min rules of the plain pickaxe: " + Requests.byPriority(run.setup().settlement()));
        MasteryGameTests.finish(helper, run);
    }

    /**
     * The anvil renames and repairs a masterwork and an enchantment goes on it: the name prefix, the rarity, the lore and the
     * bonuses stay, and the rename lands in CUSTOM_NAME next to the ITEM_NAME prefix.
     */
    private static void anvilKeepsBonuses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack masterwork = new ItemStack(Items.IRON_PICKAXE);
        Masterwork.turnInto(masterwork, MasterworkRules.get().draw(masterwork, 2, MasteryRolls.drawing(2, DURABILITY, OTHER)), MAKER);
        Holder<Enchantment> unbreaking = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING);
        masterwork.enchant(unbreaking, 1);
        masterwork.setDamageValue(100);
        AnvilMenu anvil = new AnvilMenu(0, helper.makeMockPlayer(GameType.SURVIVAL).getInventory(), ContainerLevelAccess.create(level, helper.absolutePos(BlockPos.ZERO)));
        anvil.getSlot(AnvilMenu.INPUT_SLOT).set(masterwork.copy());
        anvil.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.IRON_INGOT));
        anvil.setItemName("Gilda");
        anvil.createResult();
        ItemStack result = anvil.getSlot(AnvilMenu.RESULT_SLOT).getItem();
        helper.assertTrue(!result.isEmpty() && result.getDamageValue() < 100, "The anvil repaired it: " + result);
        helper.assertTrue(result.get(DataComponents.CUSTOM_NAME) != null && result.get(DataComponents.CUSTOM_NAME).getString().equals("Gilda"), "And renamed it");
        helper.assertTrue(result.getMaxDamage() == masterwork.getMaxDamage() && result.get(DataComponents.TOOL).equals(masterwork.get(DataComponents.TOOL)),
                "The durability and the mining speed stay: " + result.getMaxDamage());
        helper.assertTrue(result.get(DataComponents.ITEM_NAME).equals(masterwork.get(DataComponents.ITEM_NAME)) && result.get(DataComponents.LORE).equals(masterwork.get(DataComponents.LORE))
                && result.get(DataComponents.RARITY) == Rarity.RARE, "The prefix, the lore and the rarity stay");
        helper.assertTrue(result.has(DataComponents.ENCHANTMENTS), "The enchantment stays");
        helper.succeed();
    }

    private static final String UPGRADER_MAKER = "Gilda";

    /** A masterwork of the item with the options at the indices drawn, made by the maker. */
    private static ItemStack masterwork(Item item, String maker, int... picks) {
        ItemStack stack = new ItemStack(item);
        Masterwork.turnInto(stack, MasterworkRules.get().draw(stack, picks.length, MasteryRolls.drawing(picks.length, picks)), maker);
        return stack;
    }

    private static List<ItemStack> upgradeStock(ItemStack base) {
        return List.of(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), base, new ItemStack(Items.NETHERITE_INGOT));
    }

    /** What the masterwork carries: how many effects and who made it, and that its name is the one of this item, not of the old one. */
    private static void assertCarries(GameTestHelper helper, ItemStack stack, int effects, String maker) {
        MasterworkData data = stack.get(ModDataComponents.MASTERWORK.get());
        helper.assertTrue(data != null && data.effects().size() == effects && data.maker().equals(maker), "Carries " + effects + " effects by " + maker + ": " + data);
        Component made = (Component) ((TranslatableContents) lore(stack).getFirst().getContents()).getArgs()[0];
        helper.assertTrue(made.getString().equals(maker), "The lore names " + maker + ": " + lore(stack));
        helper.assertTrue(((TranslatableContents) stack.get(DataComponents.ITEM_NAME).getContents()).getArgs()[0].equals(new ItemStack(stack.getItem()).getItemName()),
                "The name is the prefix and the name of this item: " + stack.get(DataComponents.ITEM_NAME));
    }

    /**
     * A Smith upgrades a masterwork diamond pickaxe (durability +25 %, mining speed +10 %) to netherite without a hit: the
     * netherite pickaxe has its own durability and mining speeds with the same bonuses on top, the name of the netherite item,
     * the maker and the rarity of two effects.
     */
    private static void upgradeKeepsBonuses(GameTestHelper helper) {
        Run run = MasteryGameTests.smith(helper, upgradeStock(masterwork(Items.DIAMOND_PICKAXE, MAKER, DURABILITY, OTHER)), Items.NETHERITE_PICKAXE, MasteryRolls.never());
        ItemStack stack = craft(helper, run, Items.NETHERITE_PICKAXE);
        assertDurability(helper, stack, Items.NETHERITE_PICKAXE, 25);
        assertToolSpeed(helper, stack, 1.1F);
        helper.assertTrue(stack.getMaxDamage() != new ItemStack(Items.DIAMOND_PICKAXE).getMaxDamage() * 125 / 100, "Not the durability of the diamond pickaxe: " + stack.getMaxDamage());
        assertLook(helper, stack, Rarity.RARE);
        assertCarries(helper, stack, 2, MAKER);
        helper.assertTrue(loreKeys(stack).equals(List.of("civmod.masterwork.made_by", "civmod.masterwork.durability", "civmod.masterwork.mining_speed")),
                "Lore: made by, durability, mining speed: " + lore(stack));
        MasteryGameTests.finish(helper, run);
    }

    /** The attack damage bonus of a masterwork sword comes on top of the netherite sword's own attack damage, not the diamond sword's. */
    private static void upgradeKeepsAttributes(GameTestHelper helper) {
        Run run = MasteryGameTests.smith(helper, upgradeStock(masterwork(Items.DIAMOND_SWORD, MAKER, OTHER)), Items.NETHERITE_SWORD, MasteryRolls.never());
        ItemStack stack = craft(helper, run, Items.NETHERITE_SWORD);
        assertBonus(helper, stack, Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlotGroup.MAINHAND);
        helper.assertTrue(stack.getMaxDamage() == new ItemStack(Items.NETHERITE_SWORD).getMaxDamage(), "No durability bonus on this one: " + stack.getMaxDamage());
        assertLook(helper, stack, Rarity.UNCOMMON);
        assertCarries(helper, stack, 1, MAKER);
        MasteryGameTests.finish(helper, run);
    }

    /**
     * An upgrade with hits adds to the effects the sword carries and keeps the first maker: a masterwork sword with durability
     * and attack damage, upgraded with two more hits of attack damage, has +25 % durability and +3 attack damage, four effects,
     * light purple, and still names the settler who made the diamond sword.
     */
    private static void upgradeWithHitsAdds(GameTestHelper helper) {
        Run run = MasteryGameTests.smith(helper, upgradeStock(masterwork(Items.DIAMOND_SWORD, UPGRADER_MAKER, DURABILITY, OTHER)), Items.NETHERITE_SWORD,
                MasteryRolls.drawing(2, OTHER, OTHER));
        ItemStack stack = craft(helper, run, Items.NETHERITE_SWORD);
        assertDurability(helper, stack, Items.NETHERITE_SWORD, 25);
        assertBonus(helper, stack, Attributes.ATTACK_DAMAGE, 3.0, EquipmentSlotGroup.MAINHAND);
        assertCarries(helper, stack, 4, UPGRADER_MAKER);
        helper.assertTrue(stack.getRarity() == Rarity.EPIC, "Light purple above three effects: " + stack.getRarity());
        MasteryGameTests.finish(helper, run);
    }

    /** A menu of a player at the smithing table with the inputs of a netherite upgrade of the base, the result already offered. */
    private static SmithingMenu smithingTable(GameTestHelper helper, Player player, ItemStack base) {
        SmithingMenu menu = new SmithingMenu(0, player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(BlockPos.ZERO)));
        menu.getSlot(SmithingMenu.TEMPLATE_SLOT).set(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        menu.getSlot(SmithingMenu.BASE_SLOT).set(base);
        menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.NETHERITE_INGOT));
        helper.assertTrue(menu.getSlot(SmithingMenu.RESULT_SLOT).getItem().is(Items.NETHERITE_PICKAXE), "The table offers the netherite pickaxe");
        return menu;
    }

    private static void assertUpgraded(GameTestHelper helper, ItemStack stack) {
        helper.assertTrue(stack.is(Items.NETHERITE_PICKAXE), "A netherite pickaxe: " + stack);
        assertDurability(helper, stack, Items.NETHERITE_PICKAXE, 25);
        assertToolSpeed(helper, stack, 1.1F);
        assertLook(helper, stack, Rarity.RARE);
        assertCarries(helper, stack, 2, MAKER);
    }

    /** A player takes the upgrade out of the smithing table with a click: the pickaxe on the cursor has the bonuses on top of the netherite base. */
    private static void playerUpgrade(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SmithingMenu menu = smithingTable(helper, player, masterwork(Items.DIAMOND_PICKAXE, MAKER, DURABILITY, OTHER));
        menu.clicked(SmithingMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
        assertUpgraded(helper, menu.getCarried());
        helper.succeed();
    }

    /** A player shift-clicks the upgrade out of the smithing table: the pickaxe in the inventory has the bonuses on top of the netherite base. */
    private static void playerUpgradeQuick(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        SmithingMenu menu = smithingTable(helper, player, masterwork(Items.DIAMOND_PICKAXE, MAKER, DURABILITY, OTHER));
        menu.quickMoveStack(player, SmithingMenu.RESULT_SLOT);
        ItemStack stack = ItemStack.EMPTY;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(Items.NETHERITE_PICKAXE)) {
                stack = player.getInventory().getItem(slot);
            }
        }
        helper.assertTrue(!stack.isEmpty(), "The pickaxe landed in the inventory");
        assertUpgraded(helper, stack);
        helper.succeed();
    }

    /** A player who writes down the action bar messages. */
    private static final class Watcher extends ServerPlayer {
        private final List<Component> overlays = new ArrayList<>();

        Watcher(ServerLevel level) {
            super(level.getServer(), level, new GameProfile(UUID.randomUUID(), "test-watcher"), ClientInformation.createDefault());
        }

        @Override
        public GameType gameMode() {
            return GameType.CREATIVE;
        }

        @Override
        public void sendOverlayMessage(Component message) {
            overlays.add(message);
        }
    }

    private static Watcher watcher(GameTestHelper helper, Entity settler, double blocksAway) {
        Watcher watcher = new Watcher(helper.getLevel());
        watcher.setPos(settler.getX() + blocksAway, settler.getY(), settler.getZ());
        return watcher;
    }

    /** The players within 24 blocks of the settler get the message with its name and the masterwork, a player beyond does not. */
    private static void messageRange(GameTestHelper helper) {
        Run run = smith(helper, pickaxeStock(), Items.IRON_PICKAXE, 1);
        ItemStack stack = craft(helper, run, Items.IRON_PICKAXE);
        Watcher near = watcher(helper, run.settler(), NEAR_BLOCKS);
        Watcher far = watcher(helper, run.settler(), FAR_BLOCKS);
        MasterworkNotice.show(helper.getLevel(), List.of(near, far), run.settler(), stack);
        helper.assertTrue(near.overlays.size() == 1, "The player within 24 blocks gets one message: " + near.overlays);
        helper.assertTrue(far.overlays.isEmpty(), "The player beyond gets none: " + far.overlays);
        TranslatableContents message = (TranslatableContents) near.overlays.getFirst().getContents();
        helper.assertTrue("civmod.masterwork.made".equals(message.getKey()) && message.getArgs()[0].equals(run.settler().getName())
                && message.getArgs()[1] instanceof Component made && made.getString().contains(stack.getItemName().getString()),
                "The message names the settler and the masterwork: " + near.overlays.getFirst());
        MasteryGameTests.finish(helper, run);
    }

    /** Catch-up rolls and makes the masterwork, but a player standing next to the settler gets no message. */
    private static void catchUpIsSilent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Run run = smith(helper, pickaxeStock(), Items.IRON_PICKAXE, 1);
        Watcher near = watcher(helper, run.settler(), NEAR_BLOCKS);
        // A connection on an embedded channel is what the world tick needs of a player in the level (the player list would
        // send the mod's sync payloads, which the channel cannot take).
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(level.getServer(), connection, near, CommonListenerCookie.createInitial(near.getGameProfile(), false));
        level.addNewPlayer(near);
        try {
            helper.assertTrue(level.players().contains(near), "The watcher is in the level");
            ItemStack stack = craft(helper, run, Items.IRON_PICKAXE);
            helper.assertTrue(stack.get(DataComponents.RARITY) == Rarity.UNCOMMON, "Catch-up makes the masterwork");
            helper.assertTrue(near.overlays.isEmpty(), "And sends no message: " + near.overlays);
            // The same watcher does get the message of a live masterwork, so the silence above is catch-up's.
            MasterworkNotice.show(level, level.players(), run.settler(), stack);
            helper.assertTrue(near.overlays.size() == 1, "A live masterwork reaches the watcher: " + near.overlays);
        } finally {
            near.discard();
        }
        MasteryGameTests.finish(helper, run);
    }
}
