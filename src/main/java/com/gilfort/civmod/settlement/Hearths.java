package com.gilfort.civmod.settlement;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.CivMod;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * The hearths of a settlement (vault {@code Systems/Needs.md}): blocks of the tag {@code civmod:hearth} inside the
 * border, a lit block only while it burns. They are saved with the {@link Settlement} and follow block changes through
 * {@link BlockEvent.NeighborNotifyEvent}, which also covers lighting and extinguishing. The only scan runs once after
 * the border changed, on the next look, through the loaded chunks of the border; nothing scans periodically.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class Hearths {

    /** The block tag of hearths. */
    public static final TagKey<Block> TAG = TagKey.create(Registries.BLOCK, CivMod.id("hearth"));

    private static final int CAMPFIRE_MIN = 3;
    private static final int CAMPFIRE_MAX = 6;
    /** How far up or down the ground of a campfire can lie from the Settlement Block. */
    private static final int CAMPFIRE_STEP = 3;

    private Hearths() {
    }

    /**
     * @param state a block state
     * @return whether it is a hearth: in the tag, and lit if the block can be lit
     */
    public static boolean isHearth(BlockState state) {
        return state.is(TAG) && (!state.hasProperty(BlockStateProperties.LIT) || state.getValue(BlockStateProperties.LIT));
    }

    /**
     * @param level      the settlement's level
     * @param settlement the settlement
     * @return its hearths (read-only)
     */
    public static Set<BlockPos> of(ServerLevel level, Settlement settlement) {
        int border = settlement.borderHash();
        if (settlement.hearthBorder() != border) {
            scan(level, settlement);
            settlement.setHearthBorder(border);
            SettlementData.get(level).setDirty();
        }
        return settlement.hearths();
    }

    /**
     * @param hearths hearths
     * @param from    a position
     * @return the hearth closest to the position
     */
    public static Optional<BlockPos> nearest(Collection<BlockPos> hearths, BlockPos from) {
        return hearths.stream().min(Comparator.comparingDouble(from::distSqr));
    }

    /**
     * @param from a position
     * @param to   a position
     * @return the distance in whole blocks, rounded up so that a row never reads "24 blocks away – max 24"
     */
    public static int distance(BlockPos from, BlockPos to) {
        return Mth.ceil(Math.sqrt(from.distSqr(to)));
    }

    /**
     * Places a lit campfire (no item cost) on a free solid spot 3–6 blocks from the Settlement Block, or from the fallback.
     *
     * @param level      the level
     * @param settlement the settlement
     * @param fallback   where to look if no spot is free there (the settler's JobBlock)
     * @return where it stands, empty if no spot is free
     */
    public static Optional<BlockPos> placeCampfire(ServerLevel level, Settlement settlement, BlockPos fallback) {
        Optional<BlockPos> spot = settlement.anchor().flatMap(anchor -> campfireSpot(level, anchor.pos())).or(() -> campfireSpot(level, fallback));
        spot.ifPresent(pos -> level.setBlockAndUpdate(pos, Blocks.CAMPFIRE.defaultBlockState()));
        return spot;
    }

    private static Optional<BlockPos> campfireSpot(ServerLevel level, BlockPos center) {
        for (int ring = CAMPFIRE_MIN; ring <= CAMPFIRE_MAX; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    int squared = dx * dx + dz * dz;
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || squared < CAMPFIRE_MIN * CAMPFIRE_MIN || squared > CAMPFIRE_MAX * CAMPFIRE_MAX) {
                        continue;
                    }
                    for (int step = 0; step <= 2 * CAMPFIRE_STEP; step++) {
                        int dy = (step + 1) / 2 * (step % 2 == 0 ? -1 : 1);
                        BlockPos pos = center.offset(dx, dy, dz);
                        if (canHoldCampfire(level, pos)) {
                            return Optional.of(pos);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Free, on solid ground that does not burn; chunks are not loaded for this. */
    private static boolean canHoldCampfire(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState ground = level.getBlockState(below);
        BlockState here = level.getBlockState(pos);
        return here.canBeReplaced() && level.getFluidState(pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && ground.isFaceSturdy(level, below, Direction.UP) && !ground.hasBlockEntity() && !ground.ignitedByLava()
                && !ground.is(BlockTags.LOGS) && !ground.is(BlockTags.PLANKS) && !ground.is(BlockTags.LEAVES);
    }

    /** Hearths outside the border leave, burnt out ones too; the loaded chunks of the border add theirs. */
    private static void scan(ServerLevel level, Settlement settlement) {
        Set<BlockPos> found = new HashSet<>();
        for (BlockPos known : settlement.hearths()) {
            if (JobAssignments.insideBorder(settlement, known) && (!level.isLoaded(known) || isHearth(level.getBlockState(known)))) {
                found.add(known);
            }
        }
        for (long chunk : borderChunks(settlement)) {
            LevelChunk loaded = level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
            if (loaded != null) {
                scan(loaded, settlement, found);
            }
        }
        settlement.setHearths(found);
    }

    private static void scan(LevelChunk chunk, Settlement settlement, Set<BlockPos> found) {
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(Hearths::isHearth)) {
                continue;
            }
            int bottom = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
                    for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                        pos.set(chunk.getPos().getMinBlockX() + x, bottom + y, chunk.getPos().getMinBlockZ() + z);
                        if (isHearth(section.getBlockState(x, y, z)) && JobAssignments.insideBorder(settlement, pos)) {
                            found.add(pos.immutable());
                        }
                    }
                }
            }
        }
    }

    private static LongSet borderChunks(Settlement settlement) {
        LongSet chunks = new LongOpenHashSet();
        for (SettlementBlock block : settlement.blocks()) {
            if (!block.connected()) {
                continue;
            }
            int radius = SettlementRules.joinRadius(block.level());
            int minX = SectionPos.blockToSectionCoord(block.pos().getX() - radius);
            int maxX = SectionPos.blockToSectionCoord(block.pos().getX() + radius);
            int minZ = SectionPos.blockToSectionCoord(block.pos().getZ() - radius);
            int maxZ = SectionPos.blockToSectionCoord(block.pos().getZ() + radius);
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    chunks.add(ChunkPos.pack(x, z));
                }
            }
        }
        return chunks;
    }

    /**
     * A block changed. This runs for every block change on the server, so the common case stays a tag check and one
     * set lookup per settlement.
     *
     * @param event the event
     */
    @SubscribeEvent
    static void onBlockChanged(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        SettlementData data = SettlementData.get(level);
        boolean hearth = isHearth(event.getState());
        BlockPos pos = event.getPos();
        for (Settlement settlement : data.settlements()) {
            boolean changed = hearth ? JobAssignments.insideBorder(settlement, pos) && settlement.addHearth(pos) : settlement.removeHearth(pos);
            if (changed) {
                data.setDirty();
            }
        }
    }
}
