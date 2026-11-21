package com.gilfort.civmod.mastery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.mastery.MasterworkRules.Effect;
import com.gilfort.civmod.mastery.MasterworkRules.EffectPart;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Turns an item into a masterwork: the effects of the hits as vanilla data components on the one stack (vault
 * {@code Systems/Producing Jobs.md}). Item identity does not change: none of these components is a relevant component, so
 * the Clerk, stock rules, requests, the tool tier and recipe ingredients still see the plain item.
 *
 * <p>Every value is worked out from the item's own defaults, never from what the stack carries, so a masterwork made from
 * another one (a smithing upgrade keeps the components of its base) is made fresh and nothing adds up twice. The same effect
 * drawn twice adds up before it is applied, so every attribute gets one modifier. Anvil, Mending and enchanting leave the
 * components alone.
 */
public final class Masterwork {

    /** Percent shown in the lore for a share of 1.0. */
    private static final int PERCENT = 100;

    private Masterwork() {
    }

    /** The effects of a masterwork added up per kind of change. */
    private record Totals(double durability, double miningSpeed, Map<Holder<Attribute>, Double> attributes) {

        static Totals of(List<Effect> effects) {
            double durability = 0.0;
            double miningSpeed = 0.0;
            Map<Holder<Attribute>, Double> attributes = new LinkedHashMap<>();
            for (Effect effect : effects) {
                for (EffectPart part : effect.parts()) {
                    switch (part.type()) {
                        case DURABILITY -> durability += part.amount();
                        case MINING_SPEED -> miningSpeed += part.amount();
                        case ATTRIBUTE -> attributes.merge(part.attribute().orElseThrow(), part.amount(), Double::sum);
                    }
                }
            }
            return new Totals(durability, miningSpeed, attributes);
        }
    }

    /**
     * Gives the stack the effects, the prefix "Masterwork", the rarity of the number of effects and the lore. The stack is
     * changed in place.
     *
     * @param stack   the finished item
     * @param effects the effects of the hits, one per hit, at least one
     * @param maker   the name of the settler who made it
     */
    public static void turnInto(ItemStack stack, List<Effect> effects, String maker) {
        if (effects.isEmpty()) {
            throw new IllegalArgumentException("A masterwork needs at least one effect: " + stack);
        }
        Totals totals = Totals.of(effects);
        ItemStack defaults = stack.getItem().getDefaultInstance();
        if (totals.durability() > 0.0) {
            stack.set(DataComponents.MAX_DAMAGE, (int) Math.floor(defaults.getMaxDamage() * (1.0 + totals.durability())));
        }
        if (totals.miningSpeed() > 0.0 && defaults.get(DataComponents.TOOL) != null) {
            stack.set(DataComponents.TOOL, fasterTool(defaults.get(DataComponents.TOOL), totals.miningSpeed()));
        }
        if (!totals.attributes().isEmpty()) {
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, withAttributes(defaults, totals.attributes()));
        }
        stack.set(DataComponents.ITEM_NAME, Component.translatable("civmod.masterwork.name", defaults.getItemName()));
        stack.set(DataComponents.RARITY, rarity(effects.size()));
        stack.set(DataComponents.LORE, lore(totals, maker));
    }

    /**
     * @param effects how many effects the masterwork has, at least one
     * @return yellow for one, aqua for two, light purple for three or more: the rarities from uncommon up
     */
    static Rarity rarity(int effects) {
        if (effects < 1) {
            throw new IllegalArgumentException("A masterwork has at least one effect, not " + effects);
        }
        return switch (effects) {
            case 1 -> Rarity.UNCOMMON;
            case 2 -> Rarity.RARE;
            default -> Rarity.EPIC;
        };
    }

    /** The tool with the speed of every rule raised by the share; which blocks it mines and drops is untouched, so its tier stays. */
    private static Tool fasterTool(Tool tool, double share) {
        List<Tool.Rule> rules = tool.rules().stream()
                .map(rule -> new Tool.Rule(rule.blocks(), rule.speed().map(speed -> (float) (speed * (1.0 + share))), rule.correctForDrops()))
                .toList();
        return new Tool(rules, tool.defaultMiningSpeed(), tool.damagePerBlock(), tool.canDestroyBlocksInCreative());
    }

    private static ItemAttributeModifiers withAttributes(ItemStack defaults, Map<Holder<Attribute>, Double> bonuses) {
        ItemAttributeModifiers modifiers = defaults.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        for (Map.Entry<Holder<Attribute>, Double> bonus : bonuses.entrySet()) {
            Holder<Attribute> attribute = bonus.getKey();
            modifiers = modifiers.withModifierAdded(attribute, new AttributeModifier(modifierId(attribute), bonus.getValue(), AttributeModifier.Operation.ADD_VALUE),
                    slot(defaults, modifiers, attribute));
        }
        return modifiers;
    }

    /** The id of the masterwork's modifier of an attribute: one per attribute, apart from the ids of the item's own modifiers. */
    private static Identifier modifierId(Holder<Attribute> attribute) {
        Identifier id = attribute.unwrapKey().orElseThrow().identifier();
        return CivMod.id("masterwork/" + id.getNamespace() + "/" + id.getPath());
    }

    /**
     * Where the bonus counts: where the item's own modifier of the attribute counts, else the slot it is worn in, else the main hand.
     */
    private static EquipmentSlotGroup slot(ItemStack defaults, ItemAttributeModifiers modifiers, Holder<Attribute> attribute) {
        return modifiers.modifiers().stream().filter(entry -> entry.attribute().equals(attribute)).findFirst()
                .map(ItemAttributeModifiers.Entry::slot)
                .orElseGet(() -> {
                    Equippable equippable = defaults.get(DataComponents.EQUIPPABLE);
                    EquipmentSlot worn = equippable == null ? null : equippable.slot();
                    return worn == null ? EquipmentSlotGroup.MAINHAND : EquipmentSlotGroup.bySlot(worn);
                });
    }

    /** "Made by <name>", then a line for each bonus vanilla does not show: the durability and the mining speed. */
    private static ItemLore lore(Totals totals, String maker) {
        List<Component> lines = new ArrayList<>();
        lines.add(line("civmod.masterwork.made_by", ChatFormatting.GRAY, Component.literal(maker)));
        if (totals.durability() > 0.0) {
            lines.add(line("civmod.masterwork.durability", ChatFormatting.BLUE, percent(totals.durability())));
        }
        if (totals.miningSpeed() > 0.0) {
            lines.add(line("civmod.masterwork.mining_speed", ChatFormatting.BLUE, percent(totals.miningSpeed())));
        }
        return new ItemLore(lines);
    }

    private static String percent(double share) {
        return Long.toString(Math.round(share * PERCENT));
    }

    /** A lore line, not italic like the default lore style. */
    private static Component line(String key, ChatFormatting color, Object argument) {
        return Component.translatable(key, argument).withStyle(color).withStyle(style -> style.withItalic(false));
    }
}
