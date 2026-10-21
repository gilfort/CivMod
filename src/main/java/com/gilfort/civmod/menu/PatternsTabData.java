package com.gilfort.civmod.menu;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.api.ProducingProfile;
import com.gilfort.civmod.logistics.Pattern;
import com.gilfort.civmod.settlement.Patterns;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * What one "Patterns" tab of a JobBlock shows (vault {@code Systems/Producing Jobs.md}, {@code Jobs/Craftsperson.md}):
 * the pattern library of one Producing Job, the checkmark limit of the settler last seen working it and the state line
 * ({@link SettlementBlock#notice(Job)}). The server sends the tab of the Job the player looks at while the menu is open
 * whenever it changed ({@link Sync}); the client edits patterns with {@link Edit}.
 *
 * @param job          the Job whose library it is (empty: nothing synced yet)
 * @param locked       whether the Job is no longer unlocked at the JobBlock: its library is kept but unusable
 * @param limit        the checkmark limit
 * @param patterns     the patterns by slot
 * @param filtered     the slots of patterns the Job's filters reject
 * @param foreign      the slots of patterns of a recipe type the Job does not follow (they keep their checkmark)
 * @param filteredLine the state line of a filtered pattern ("Not allowed for this Job")
 * @param notice       the state line: "A Craftsperson masters only 6 recipes at once – choose which to check." while a new
 *                     pattern stayed unchecked at the limit, or the checkmarks removed by a dropped limit
 * @param recipeTypes  the recipe types the Job follows (what a recipe viewer's transfer button may fill)
 */
public record PatternsTabData(Optional<Job> job, boolean locked, int limit, List<Pattern> patterns, Set<Integer> filtered, Set<Integer> foreign,
        Component filteredLine, Optional<Component> notice, List<RecipeType<?>> recipeTypes) {

    /** Nothing (any other JobBlock, or before the first sync). */
    public static final PatternsTabData EMPTY = new PatternsTabData(Optional.empty(), false, Patterns.BASE_CHECKMARKS, List.of(), Set.of(), Set.of(),
            Component.empty(), Optional.empty(), List.of());

    /** Network codec of a set of library slots. */
    private static final StreamCodec<ByteBuf, Set<Integer>> SLOTS_CODEC = ByteBufCodecs.VAR_INT
            .apply(ByteBufCodecs.collection(HashSet::new, Pattern.SLOTS)).map(slots -> slots, HashSet::new);

    /** Network codec. */
    public static final StreamCodec<RegistryFriendlyByteBuf, PatternsTabData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(Job.STREAM_CODEC), PatternsTabData::job,
            ByteBufCodecs.BOOL, PatternsTabData::locked,
            ByteBufCodecs.VAR_INT, PatternsTabData::limit,
            Pattern.STREAM_CODEC.apply(ByteBufCodecs.list(Pattern.SLOTS)), PatternsTabData::patterns,
            SLOTS_CODEC, PatternsTabData::filtered,
            SLOTS_CODEC, PatternsTabData::foreign,
            ComponentSerialization.STREAM_CODEC, PatternsTabData::filteredLine,
            ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC), PatternsTabData::notice,
            ByteBufCodecs.registry(Registries.RECIPE_TYPE).apply(ByteBufCodecs.list()), PatternsTabData::recipeTypes,
            PatternsTabData::new);

    /**
     * @param job          the Job whose library it is
     * @param locked       whether the Job is no longer unlocked at the JobBlock
     * @param limit        the checkmark limit
     * @param patterns     the patterns
     * @param filtered     the slots of filtered patterns
     * @param foreign      the slots of patterns of a foreign recipe type
     * @param filteredLine the state line of a filtered pattern
     * @param notice       the state line
     * @param recipeTypes  the recipe types the producer follows
     */
    public PatternsTabData {
        patterns = List.copyOf(patterns);
        filtered = Set.copyOf(filtered);
        foreign = Set.copyOf(foreign);
        recipeTypes = List.copyOf(recipeTypes);
    }

    /**
     * @param level the level
     * @param pos   the JobBlock position
     * @param job   a Producing Job of it
     * @return the current tab of the Job's library at that JobBlock ({@link #EMPTY} if it keeps none for the Job)
     */
    public static PatternsTabData of(ServerLevel level, BlockPos pos, Job job) {
        return SettlementData.get(level).settlementAt(pos).flatMap(settlement -> settlement.block(pos)).filter(block -> Patterns.hasLibrary(block, job))
                .map(block -> of(level, block, job)).orElse(EMPTY);
    }

    private static PatternsTabData of(ServerLevel level, SettlementBlock block, Job job) {
        ProducingProfile profile = Patterns.profile(job);
        List<Pattern> patterns = block.patterns(job);
        Set<Integer> foreign = slots(patterns, pattern -> Patterns.foreign(level, profile, pattern));
        Set<Integer> filtered = slots(patterns, pattern -> !foreign.contains(pattern.slot()) && Patterns.filtered(level, profile, pattern));
        Optional<Component> notice = block.notice(job).map(found -> switch (found) {
            case SettlementBlock.Notice.AtLimit atLimit -> Component.translatable("civmod.gui.patterns.at_limit", job.displayName(), atLimit.limit());
            case SettlementBlock.Notice.Trimmed trimmed -> Component.translatable("civmod.gui.patterns.trimmed", trimmed.removed(), trimmed.limit());
        });
        return new PatternsTabData(Optional.of(job), Patterns.locked(block, job), block.checkLimit(job), patterns, filtered, foreign, profile.filteredLine(),
                notice, profile.recipeTypes());
    }

    /**
     * @param type a recipe type
     * @return whether the producer follows recipes of that type (a recipe viewer's transfer button may fill a pattern)
     */
    public boolean follows(RecipeType<?> type) {
        return !locked && recipeTypes.contains(type);
    }

    private static Set<Integer> slots(List<Pattern> patterns, Predicate<Pattern> test) {
        return patterns.stream().filter(test).map(Pattern::slot).collect(Collectors.toSet());
    }

    /**
     * @param slot a library slot
     * @return whether the Job cannot use the pattern in that slot although it makes something (also every pattern of a
     *         locked library)
     */
    public boolean unusable(int slot) {
        return filtered.contains(slot) || foreign.contains(slot) || (locked && pattern(slot).filter(Pattern::valid).isPresent());
    }

    /**
     * @param slot a library slot
     * @return the pattern in that slot
     */
    public Optional<Pattern> pattern(int slot) {
        return patterns.stream().filter(pattern -> pattern.slot() == slot).findFirst();
    }

    /**
     * @return how many patterns are checked and usable by the producer (what counts against the limit; foreign patterns
     *         keep their checkmark without counting)
     */
    public long checked() {
        return patterns.stream().filter(pattern -> pattern.checked() && !unusable(pattern.slot())).count();
    }


    /** What an {@link Edit} does. */
    public enum Op {
        /** Make the pattern from its output (the first matching recipe). */
        SET_OUTPUT,
        /** Set one grid cell (exact inputs). */
        SET_INPUT,
        /** Cycle to the next / previous recipe. */
        CYCLE,
        /** Check or uncheck. */
        TOGGLE_CHECK,
        /** Empty the slot. */
        CLEAR,
        /** Fill from a recipe viewer's transfer button. */
        TRANSFER;

        /** Network codec. */
        public static final StreamCodec<ByteBuf, Op> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], Op::ordinal);
    }

    /**
     * Server → client: the Patterns tab of the open menu changed.
     *
     * @param containerId the menu's container id
     * @param tab         the tab
     */
    public record Sync(int containerId, PatternsTabData tab) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("pattern_tab"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::containerId,
                PatternsTabData.STREAM_CODEC, Sync::tab,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: change a pattern of one library of the open JobBlock menu.
     *
     * @param containerId the menu's container id
     * @param job         the Job whose library it is
     * @param op          what to do
     * @param slot        the library slot
     * @param index       the grid cell ({@link Op#SET_INPUT}) or the direction ({@link Op#CYCLE})
     * @param item        the item ({@link Op#SET_OUTPUT}, {@link Op#SET_INPUT}; {@link Items#AIR} empties a cell)
     * @param recipe      the recipe the viewer showed ({@link Op#TRANSFER})
     * @param grid        the items the viewer showed, row by row ({@link Op#TRANSFER})
     */
    public record Edit(int containerId, Job job, Op op, int slot, int index, Item item, Optional<ResourceKey<Recipe<?>>> recipe, List<Item> grid)
            implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Edit> TYPE = new Type<>(CivMod.id("edit_pattern"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Edit> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Edit::containerId,
                Job.STREAM_CODEC, Edit::job,
                Op.STREAM_CODEC, Edit::op,
                ByteBufCodecs.VAR_INT, Edit::slot,
                ByteBufCodecs.INT, Edit::index,
                ByteBufCodecs.registry(Registries.ITEM), Edit::item,
                ByteBufCodecs.optional(ResourceKey.streamCodec(Registries.RECIPE)), Edit::recipe,
                ByteBufCodecs.registry(Registries.ITEM).apply(ByteBufCodecs.list(Pattern.GRID)), Edit::grid,
                Edit::new);

        /**
         * @param containerId the menu's container id
         * @param job         the Job whose library it is
         * @param slot        the library slot
         * @param output      the item to craft
         * @return the edit
         */
        public static Edit setOutput(int containerId, Job job, int slot, Item output) {
            return new Edit(containerId, job, Op.SET_OUTPUT, slot, 0, output, Optional.empty(), List.of());
        }

        /**
         * @param containerId the menu's container id
         * @param job         the Job whose library it is
         * @param slot        the library slot
         * @param index       the grid cell
         * @param item        the item, {@link Items#AIR} to empty the cell
         * @return the edit
         */
        public static Edit setInput(int containerId, Job job, int slot, int index, Item item) {
            return new Edit(containerId, job, Op.SET_INPUT, slot, index, item, Optional.empty(), List.of());
        }

        /**
         * @param containerId the menu's container id
         * @param job         the Job whose library it is
         * @param slot        the library slot
         * @param direction   +1 next, -1 previous
         * @return the edit
         */
        public static Edit cycle(int containerId, Job job, int slot, int direction) {
            return new Edit(containerId, job, Op.CYCLE, slot, direction, Items.AIR, Optional.empty(), List.of());
        }

        /**
         * @param containerId the menu's container id
         * @param job         the Job whose library it is
         * @param slot        the library slot
         * @return the edit
         */
        public static Edit toggleCheck(int containerId, Job job, int slot) {
            return new Edit(containerId, job, Op.TOGGLE_CHECK, slot, 0, Items.AIR, Optional.empty(), List.of());
        }

        /**
         * @param containerId the menu's container id
         * @param job         the Job whose library it is
         * @param slot        the library slot
         * @return the edit
         */
        public static Edit clear(int containerId, Job job, int slot) {
            return new Edit(containerId, job, Op.CLEAR, slot, 0, Items.AIR, Optional.empty(), List.of());
        }

        /**
         * @param containerId the menu's container id
         * @param job         the Job whose library it is
         * @param slot        the library slot
         * @param recipe      the recipe the viewer showed
         * @param grid        the items it showed, row by row
         * @return the edit
         */
        public static Edit transfer(int containerId, Job job, int slot, Optional<ResourceKey<Recipe<?>>> recipe, List<Item> grid) {
            return new Edit(containerId, job, Op.TRANSFER, slot, 0, Items.AIR, recipe, grid);
        }

        @Override
        public Type<Edit> type() {
            return TYPE;
        }
    }
}
