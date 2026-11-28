package com.gilfort.civmod.debug;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.block.JobBlockEntity;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerSkins;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Guard benchmark (vault {@code Jobs/Guard.md}, "Benchmark (G5)"; spec #349): one settlement on a flat stone arena with
 * Guards on their Guard Posts and monsters that keep standing around in the arena, topped up whenever a Guard kills one.
 * The Guards only attack: the monsters ignore the settlers. It is the "Guards attack only" baseline that the later step
 * "monsters attack" is compared with, and separate from {@link Benchmark} (the PoC benchmark), which has no Guards.
 * <p>
 * The scale is a {@link Scale}: how many Guards and how many monsters. Settlers that get attacked and defended are added later
 * as a further field of the scale and a further step of {@link #build}. {@link CivProfiler} measures the work of the Guards
 * ({@link CivProfiler.Section#GUARD}) next to the usual sections.
 */
public final class GuardBenchmark {

    /**
     * How big the benchmark is.
     *
     * @param guards   Guards on duty
     * @param monsters monsters in the arena at all times (each kill is replaced)
     */
    public record Scale(int guards, int monsters) {

        /** The scale of the baseline: modest, like the PoC benchmark's 30 settlers. */
        public static final Scale BASELINE = new Scale(10, 30);
    }

    /** Guards a level 5 Guard Post takes. */
    private static final int GUARDS_PER_POST = 3;
    private static final int POST_LEVEL = 5;
    /** Distance between two Guard Posts, in blocks. */
    private static final int POST_SPACING = 16;
    /** The edge of the stone arena beyond the outermost posts, in blocks. */
    private static final int MARGIN = 14;
    /** Ground layers below the arena and air above it. */
    private static final int GROUND_DEPTH = 3;
    private static final int AIR_HEIGHT = 8;
    /** Monsters appear at least this far from a Guard (blocks) and at most this far: inside the 16 blocks the Guard looks. */
    private static final int MIN_SPAWN_RADIUS = 6;
    private static final int MAX_SPAWN_RADIUS = 14;
    private static final int FLAGS = Block.UPDATE_CLIENTS;
    private static final int BREAD = 16;

    private final ServerLevel level;
    private final BlockPos origin;
    private final Scale scale;
    private final AABB arena;
    private final List<Settler> guards = new ArrayList<>();
    private long monstersSpawned;

    /**
     * @param level  the level
     * @param origin the corner block of the first Guard Post
     * @param scale  how big the benchmark is
     */
    public GuardBenchmark(ServerLevel level, BlockPos origin, Scale scale) {
        this.level = level;
        this.origin = origin;
        this.scale = scale;
        int edge = postsPerSide() * POST_SPACING + 2 * MARGIN;
        this.arena = AABB.of(BoundingBox.fromCorners(origin.offset(-MARGIN, -1, -MARGIN), origin.offset(edge - MARGIN + 1, AIR_HEIGHT, edge - MARGIN + 1)));
    }

    private int postsPerSide() {
        int posts = Math.ceilDiv(scale.guards(), GUARDS_PER_POST);
        return Math.max(1, (int) Math.ceil(Math.sqrt(posts)));
    }

    /**
     * @return the horizontal edge of the arena in blocks, for forcing its chunks
     */
    public int edge() {
        return postsPerSide() * POST_SPACING + 2 * MARGIN;
    }

    /**
     * Builds the arena, the settlement, the Guards and the first monsters, and starts the profiler.
     *
     * @return the number of Guards spawned
     */
    public int build() {
        int edge = edge();
        int last = edge - MARGIN;
        for (int x = -MARGIN; x <= last; x++) {
            for (int z = -MARGIN; z <= last; z++) {
                for (int y = -GROUND_DEPTH; y <= AIR_HEIGHT; y++) {
                    level.setBlock(origin.offset(x, y, z), (y < 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), FLAGS);
                }
            }
        }
        // The Center Block founds the settlement; it stands next to the first Guard Post, and the posts join one by one.
        BlockPos center = origin.offset(-MARGIN / 2, 0, -MARGIN / 2);
        level.setBlock(center, ModBlocks.HUMAN_CENTER_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        SettlementData data = SettlementData.get(level);
        Settlement settlement = data.settlementAt(center).orElseThrow();
        data.settlersArrived(settlement);
        int perSide = postsPerSide();
        int placed = 0;
        for (int post = 0; placed < scale.guards(); post++) {
            BlockPos postPos = origin.offset(post % perSide * POST_SPACING, 0, post / perSide * POST_SPACING);
            level.setBlock(postPos, ModBlocks.GUARD_POST.get().defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(postPos) instanceof JobBlockEntity block) {
                block.setJobLevel(POST_LEVEL);
            }
            SettlementWorld.setLevel(level, postPos, POST_LEVEL);
            for (int slot = 0; slot < GUARDS_PER_POST && placed < scale.guards(); slot++) {
                Settler guard = spawnGuard(data, settlement, postPos, "Bench Guard " + (placed + 1));
                if (guard != null) {
                    guards.add(guard);
                }
                placed++;
            }
        }
        topUp();
        CivProfiler.enable();
        return guards.size();
    }

    private @Nullable Settler spawnGuard(SettlementData data, Settlement settlement, BlockPos post, String name) {
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.COMMAND);
        if (settler == null) {
            return null;
        }
        Vec3 spawn = Vec3.atBottomCenterOf(post.offset(0, 0, 2));
        settler.snapTo(spawn.x, spawn.y, spawn.z, level.getRandom().nextFloat() * 360.0F, 0.0F);
        settler.setSkin(level.getRandom().nextInt(SettlerSkins.COUNT));
        settler.setCustomName(Component.literal(name));
        level.addFreshEntity(settler);
        settler.joinSettlement(data, settlement);
        settler.getInventory().setItem(Settler.POCKETS_START, new ItemStack(Items.BREAD, BREAD));
        settler.getInventory().setItem(Settler.BELT_START, new ItemStack(Items.IRON_SWORD));
        SettlementWorld.assign(level, post, settler, ModJobs.GUARD.get());
        return settler;
    }

    /**
     * Brings the number of monsters in the arena back to the scale: call it about once a second.
     */
    public void topUp() {
        int alive = level.getEntitiesOfClass(Zombie.class, arena).size();
        for (int missing = scale.monsters() - alive; missing > 0; missing--) {
            double angle = level.getRandom().nextDouble() * 2.0 * Math.PI;
            double radius = MIN_SPAWN_RADIUS + level.getRandom().nextDouble() * (MAX_SPAWN_RADIUS - MIN_SPAWN_RADIUS);
            // Around a Guard of the benchmark, so there is always something to fight (the arena's middle without Guards).
            BlockPos middle = guards.isEmpty() ? origin.offset(edge() / 2 - MARGIN, 0, edge() / 2 - MARGIN)
                    : guards.get(level.getRandom().nextInt(guards.size())).blockPosition().atY(origin.getY());
            Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            if (zombie == null) {
                return;
            }
            zombie.snapTo(middle.getX() + 0.5 + Math.cos(angle) * radius, middle.getY(), middle.getZ() + 0.5 + Math.sin(angle) * radius, 0.0F, 0.0F);
            // A pumpkin keeps the sun off it, so it stays until a Guard comes; no random armor changes the hits needed.
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                zombie.setItemSlot(slot, ItemStack.EMPTY);
            }
            zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            zombie.setPersistenceRequired();
            level.addFreshEntity(zombie);
            monstersSpawned++;
        }
    }

    /**
     * @return the Guards of the benchmark
     */
    public List<Settler> guards() {
        return guards;
    }

    /** Removes the Guards and the monsters. */
    public void finish() {
        guards.forEach(Settler::discard);
        level.getEntitiesOfClass(Zombie.class, arena).forEach(Zombie::discard);
    }

    /**
     * @return how many monsters were spawned so far, the first ones included
     */
    public long monstersSpawned() {
        return monstersSpawned;
    }

    /**
     * @return how many monsters stand in the arena now
     */
    public int monstersAlive() {
        return level.getEntitiesOfClass(Zombie.class, arena).size();
    }

    /**
     * @return the scale of this benchmark
     */
    public Scale scale() {
        return scale;
    }
}
