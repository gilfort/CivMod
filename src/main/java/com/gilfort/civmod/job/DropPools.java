package com.gilfort.civmod.job;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.DropOutcome;
import com.gilfort.civmod.api.Drops;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The shared drop pools of {@code drops} recipes (vault {@code Systems/Producing Jobs.md}): the files
 * {@code data/<namespace>/civmod_drop_pools/<name>.json} of all packs, one pool per id {@code <namespace>:<name>}. The
 * files of several packs with the same id are merged: their outcomes are added, lowest pack first. A file that does not
 * parse is skipped with a log warning and the other files of the id still count.
 * <p>
 * The server sends the merged pools to the clients ({@link Sync}), so the recipe viewers can show the chances. A recipe
 * that names a pool nobody ships finds nothing (a log warning says so once per datapack load).
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class DropPools {

    private static final Identifier ID = CivMod.id("drop_pools");
    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_drop_pools");
    private static final Codec<List<DropOutcome>> FILE_CODEC = DropOutcome.CODEC.listOf().fieldOf("outcomes").codec();

    private static Map<Identifier, List<DropOutcome>> pools = Map.of();
    /** Pools a recipe named without any file, warned about once per load. */
    private static final Set<Identifier> WARNED = ConcurrentHashMap.newKeySet();
    /** The scripted random source of a test, else null. */
    private static @Nullable RandomSource scripted;

    private DropPools() {
    }

    /**
     * @param json a pool file
     * @return its outcomes, or the error that makes the file invalid
     */
    public static DataResult<List<DropOutcome>> parseFile(JsonElement json) {
        return FILE_CODEC.parse(JsonOps.INSTANCE, json);
    }

    /**
     * @param files the outcomes of the files of one pool id, lowest pack first
     * @return them added together
     */
    public static List<DropOutcome> merge(List<List<DropOutcome>> files) {
        return files.stream().flatMap(List::stream).toList();
    }

    /**
     * @param drops the {@code drops} of a recipe
     * @return the outcomes to draw from: the pool's merged outcomes, else the recipe's own
     */
    public static List<DropOutcome> outcomes(Drops drops) {
        return drops.resolve(DropPools::pool);
    }

    /**
     * @param drops the {@code drops} of a recipe
     * @return the first stack any of its outcomes gives (the item a Patterns tab shows for a recipe without
     *         {@code result}), empty if no outcome gives anything
     */
    public static Optional<ItemStackTemplate> representative(Drops drops) {
        return outcomes(drops).stream().flatMap(outcome -> outcome.results().stream()).findFirst();
    }

    /**
     * One draw of a {@code drops} recipe.
     *
     * @param drops    the {@code drops} of the recipe
     * @param level    the settler's skill level in the Job
     * @param fallback the random source to use unless a test scripted one
     * @return the stacks the drawn outcome gives
     */
    public static List<ItemStack> draw(Drops drops, int level, RandomSource fallback) {
        RandomSource random = scripted != null ? scripted : fallback;
        return DropDraw.draw(outcomes(drops), level, random).stream().map(ItemStackTemplate::create).toList();
    }

    /**
     * Lets a test script the draw of every {@code drops} recipe.
     *
     * @param random the scripted random source, null to draw at random again
     */
    @ApiStatus.Internal
    public static void script(@Nullable RandomSource random) {
        scripted = random;
    }

    private static List<DropOutcome> pool(Identifier id) {
        List<DropOutcome> found = pools.get(id);
        if (found == null) {
            if (WARNED.add(id)) {
                CivMod.LOGGER.warn("A drops recipe names the pool {}, but no pack ships it: it finds nothing", id);
            }
            return List.of();
        }
        return found;
    }

    /**
     * Server → client: the merged pools, for the recipe viewers.
     *
     * @param pools the outcomes per pool id
     */
    public record Sync(Map<Identifier, List<DropOutcome>> pools) implements CustomPacketPayload {

        /** Payload type. */
        public static final Type<Sync> TYPE = new Type<>(CivMod.id("drop_pools"));

        /** Network codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.map(HashMap::new, Identifier.STREAM_CODEC, DropOutcome.STREAM_CODEC.apply(ByteBufCodecs.list())), Sync::pools,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ID, new Loader());
    }

    /** Sends the pools before the recipes, so the recipe viewers have them when they reload on the recipes. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onDatapackSync(OnDatapackSyncEvent event) {
        Sync sync = new Sync(pools);
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, sync));
    }

    private static final class Loader extends SimplePreparableReloadListener<Map<Identifier, List<DropOutcome>>> {

        @Override
        protected Map<Identifier, List<DropOutcome>> prepare(ResourceManager manager, ProfilerFiller profiler) {
            Map<Identifier, List<DropOutcome>> merged = new TreeMap<>();
            FILES.listMatchingResourceStacks(manager).forEach((path, stack) -> {
                Identifier id = FILES.fileToId(path);
                List<List<DropOutcome>> files = new ArrayList<>();
                for (Resource resource : stack) {
                    read(id, resource).ifPresent(files::add);
                }
                merged.put(id, merge(files));
            });
            return merged;
        }

        private static Optional<List<DropOutcome>> read(Identifier id, Resource resource) {
            try (Reader reader = resource.openAsReader()) {
                DataResult<List<DropOutcome>> parsed = parseFile(JsonParser.parseReader(reader));
                parsed.error().ifPresent(error -> CivMod.LOGGER.warn("Ignoring invalid drop pool file {} from pack {}: {}", id, resource.sourcePackId(),
                        error.message()));
                return parsed.result();
            } catch (Exception e) {
                CivMod.LOGGER.warn("Ignoring unreadable drop pool file {} from pack {}", id, resource.sourcePackId(), e);
                return Optional.empty();
            }
        }

        @Override
        protected void apply(Map<Identifier, List<DropOutcome>> prepared, ResourceManager manager, ProfilerFiller profiler) {
            pools = Map.copyOf(prepared);
            WARNED.clear();
            CivMod.LOGGER.info("Loaded {} drop pool(s)", prepared.size());
        }
    }
}
