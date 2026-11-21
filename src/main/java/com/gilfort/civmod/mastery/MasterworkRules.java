package com.gilfort.civmod.mastery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gilfort.civmod.CivMod;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * The effect pools of the masterworks (vault {@code Systems/Producing Jobs.md}), from
 * {@code data/<namespace>/civmod_mastery/masterworks.json}. The first hit of a craft draws from the pool for all items, every
 * further hit from the pool of the item's kind; the kind is the first one whose item tag holds the item. A kind with no
 * effects, and an item of no kind, draw the further hits from the pool for all items too. Files of all packs are merged, a
 * later file replaces the field it sets as a whole. Nothing here touches the world.
 *
 * @param all   the pool for all items, never empty
 * @param kinds the kinds of items with a pool of their own, the first match wins
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public record MasterworkRules(List<Effect> all, List<Kind> kinds) {

    /** Reload listener id. */
    public static final Identifier ID = CivMod.id("masterwork_rules");
    /** The pool for all items when no file is loaded: durability +25 %. */
    public static final MasterworkRules FALLBACK = new MasterworkRules(List.of(new Effect(List.of(new Part(Part.Type.DURABILITY, Optional.empty(), 0.25)))),
            List.of());

    private static MasterworkRules current = FALLBACK;

    /** What a part of an effect changes. */
    public record Part(Type type, Optional<Holder<Attribute>> attribute, double amount) {

        /** The kinds of change. */
        public enum Type implements StringRepresentable {
            /** The durability grows by the share {@code amount} of the item's own (0.25: +25 %). */
            DURABILITY("durability"),
            /** The mining speed of the item's {@code tool} rules grows by the share {@code amount}. */
            MINING_SPEED("mining_speed"),
            /** The attribute grows by {@code amount}, added to the item's own modifiers. */
            ATTRIBUTE("attribute");

            static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);

            private final String serializedName;

            Type(String serializedName) {
                this.serializedName = serializedName;
            }

            @Override
            public String getSerializedName() {
                return serializedName;
            }
        }

        /** Built on first use: this class loads with the mod, before the attribute registry is filled. */
        static final Codec<Part> CODEC = Codec.lazyInitialized(() -> RecordCodecBuilder.<Part>create(instance -> instance.group(
                Type.CODEC.fieldOf("type").forGetter(Part::type),
                BuiltInRegistries.ATTRIBUTE.holderByNameCodec().optionalFieldOf("attribute").forGetter(Part::attribute),
                Codec.doubleRange(-1024.0, 1024.0).fieldOf("amount").forGetter(Part::amount)
        ).apply(instance, Part::new)).validate(Part::validate));

        private DataResult<Part> validate() {
            if ((type == Type.ATTRIBUTE) != attribute.isPresent()) {
                return DataResult.error(() -> "A part of type attribute needs an attribute and no other part has one: " + this);
            }
            if (type != Type.ATTRIBUTE && amount <= 0.0) {
                return DataResult.error(() -> "The amount of a " + type.getSerializedName() + " part is a share above 0: " + amount);
            }
            return DataResult.success(this);
        }
    }

    /**
     * One thing a hit can give: all its parts together (armour +1 and toughness +0.5 are one effect).
     *
     * @param parts what the effect changes, at least one part
     */
    public record Effect(List<Part> parts) {

        static final Codec<Effect> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Part.CODEC.listOf(1, 16).fieldOf("parts").forGetter(Effect::parts)
        ).apply(instance, Effect::new));
    }

    /**
     * The pool of one kind of items.
     *
     * @param tag     the item tag that makes an item of the kind
     * @param effects the effects the further hits draw from, may be empty
     */
    public record Kind(TagKey<Item> tag, List<Effect> effects) {

        static final Codec<Kind> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                TagKey.codec(Registries.ITEM).fieldOf("tag").forGetter(Kind::tag),
                Effect.CODEC.listOf(0, 64).fieldOf("effects").forGetter(Kind::effects)
        ).apply(instance, Kind::new));
    }

    private record FileContent(Optional<List<Effect>> all, Optional<List<Kind>> kinds) {

        static final Codec<FileContent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Effect.CODEC.listOf(1, 64).optionalFieldOf("all").forGetter(FileContent::all),
                Kind.CODEC.listOf(0, 64).optionalFieldOf("kinds").forGetter(FileContent::kinds)
        ).apply(instance, FileContent::new));
    }

    /**
     * @return the loaded rules
     */
    public static MasterworkRules get() {
        return current;
    }

    /**
     * Draws the effects of a masterwork: the first hit from the pool for all items, every further hit from the pool of the
     * item's kind. The same effect can come up twice.
     *
     * @param stack  the item that becomes a masterwork
     * @param hits   the hits of the craft, one effect each
     * @param random the random source of the draws
     * @return one effect per hit
     */
    public List<Effect> draw(ItemStack stack, int hits, RandomSource random) {
        List<Effect> kind = kinds.stream().filter(candidate -> stack.is(candidate.tag())).findFirst().map(Kind::effects).orElse(List.of());
        List<Effect> drawn = new ArrayList<>();
        for (int hit = 0; hit < hits; hit++) {
            List<Effect> pool = hit == 0 || kind.isEmpty() ? all : kind;
            drawn.add(pool.get(random.nextInt(pool.size())));
        }
        return drawn;
    }

    /**
     * Merges JSON files in order (a later file replaces the fields it sets).
     *
     * @param files parsed JSON files
     * @return the merged rules
     * @throws IllegalStateException if a file is malformed
     */
    public static MasterworkRules merge(List<JsonElement> files) {
        MasterworkRules merged = FALLBACK;
        for (JsonElement file : files) {
            FileContent part = FileContent.CODEC.parse(JsonOps.INSTANCE, file).getOrThrow();
            merged = new MasterworkRules(part.all().orElse(merged.all()), part.kinds().orElse(merged.kinds()));
        }
        return merged;
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    private static final class Loader extends SimplePreparableReloadListener<MasterworkRules> {

        @Override
        protected MasterworkRules prepare(ResourceManager manager, ProfilerFiller profiler) {
            try {
                return merge(MasteryFiles.read(manager, "masterworks"));
            } catch (Exception e) {
                CivMod.LOGGER.error("Invalid masterwork effects, using fallback values", e);
                return FALLBACK;
            }
        }

        @Override
        protected void apply(MasterworkRules prepared, ResourceManager manager, ProfilerFiller profiler) {
            current = prepared;
        }
    }
}
