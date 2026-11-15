package com.gilfort.civmod.logistics;

import com.gilfort.civmod.api.RelevantComponents;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * What a stack is for patterns, ingredient matching, stock, Clerk rules, requests, deliveries and Delivery Slips: its item
 * plus the components on the {@link RelevantComponents} list. Two stacks are the same thing when their keys are equal, so a
 * Potion of Healing and a water bottle differ while a renamed Potion of Healing stays the same. The one value type used
 * everywhere instead of ad hoc comparisons (vault {@code Systems/Producing Jobs.md}).
 *
 * @param item       the item, {@link Items#AIR} for "nothing" ({@link #EMPTY})
 * @param components only the relevant components the stack carries
 */
public record ItemKey(Item item, DataComponentPatch components) {

    /** Nothing: an empty grid cell, or a pattern without an output. */
    public static final ItemKey EMPTY = new ItemKey(Items.AIR, DataComponentPatch.EMPTY);

    public static final MapCodec<ItemKey> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("id").forGetter(ItemKey::item),
            DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(ItemKey::components)
    ).apply(instance, ItemKey::new));

    /** Save codec. */
    public static final Codec<ItemKey> CODEC = MAP_CODEC.codec();

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemKey> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.ITEM), ItemKey::item,
            DataComponentPatch.STREAM_CODEC, ItemKey::components,
            ItemKey::new);

    /**
     * @param item an item without relevant components
     * @return its key
     */
    public static ItemKey of(Item item) {
        return item == Items.AIR ? EMPTY : new ItemKey(item, DataComponentPatch.EMPTY);
    }

    /**
     * @param stack a stack
     * @return its key, {@link #EMPTY} for an empty stack
     */
    public static ItemKey of(ItemStack stack) {
        if (stack.isEmpty()) {
            return EMPTY;
        }
        DataComponentPatch.Builder builder = DataComponentPatch.builder();
        for (DataComponentType<?> type : RelevantComponents.types()) {
            copy(builder, stack, type);
        }
        return new ItemKey(stack.getItem(), builder.build());
    }

    /**
     * @param resource an item resource of a container
     * @return its key
     */
    public static ItemKey of(ItemResource resource) {
        return resource.isEmpty() ? EMPTY : of(resource.toStack());
    }

    /** Copies a relevant component unless it is only the item's prototype default (a bare potion has empty potion contents). */
    private static <T> void copy(DataComponentPatch.Builder builder, ItemStack stack, DataComponentType<T> type) {
        T value = stack.get(type);
        if (value != null && !value.equals(stack.getItem().components().get(type))) {
            builder.set(type, value);
        }
    }

    /**
     * @return whether this is {@link #EMPTY}
     */
    public boolean isEmpty() {
        return item == Items.AIR;
    }

    /**
     * @param other an item
     * @return whether the key is that item without relevant components
     */
    public boolean is(Item other) {
        return item == other && components.isEmpty();
    }

    /**
     * @param stack a stack
     * @return whether the stack is this thing
     */
    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && equals(of(stack));
    }

    /**
     * @param resource an item resource of a container
     * @return whether the resource is this thing
     */
    public boolean matches(ItemResource resource) {
        return !resource.isEmpty() && resource.getItem() == item && equals(of(resource));
    }

    /**
     * @param count the stack size
     * @return a stack of this thing, with its relevant components
     */
    public ItemStack toStack(int count) {
        ItemStack stack = new ItemStack(item, count);
        stack.applyComponents(components);
        return stack;
    }

    /**
     * @return one of this thing, for icons and tooltips
     */
    public ItemStack toStack() {
        return toStack(1);
    }
}
