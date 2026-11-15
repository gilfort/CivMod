package com.gilfort.civmod.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;

/**
 * The short list of data components that make two stacks of the same item different things for patterns, ingredient
 * matching, Clerk rules, requests, deliveries and Delivery Slips (vault {@code Systems/Producing Jobs.md}). All potions are
 * the one item {@code minecraft:potion}, so {@code potion_contents} is on the list from the start. Components off the list
 * (a custom name, damage, lore) are ignored: a renamed potion still is that potion.
 * <p>
 * An addon adds the components of its own item kinds (enchanted books, for example) once while it loads, before any
 * pattern is read.
 */
@ApiStatus.Experimental
public final class RelevantComponents {

    private static final CopyOnWriteArrayList<DataComponentType<?>> TYPES = new CopyOnWriteArrayList<>(List.of(DataComponents.POTION_CONTENTS));

    private RelevantComponents() {
    }

    /**
     * Adds a component type to the list; adding one twice does nothing.
     *
     * @param type the component type that tells stacks of one item apart
     */
    public static void register(DataComponentType<?> type) {
        TYPES.addIfAbsent(type);
    }

    /**
     * @return the relevant component types, {@code potion_contents} first
     */
    public static List<DataComponentType<?>> types() {
        return TYPES;
    }
}
