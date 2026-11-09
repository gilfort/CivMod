package com.gilfort.civmod.entertainment;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.job.SettlerWork;
import com.gilfort.civmod.logistics.ClerkStorage;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.settlement.DayClock;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementBlock;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The Tavern evening (vault {@code Systems/Entertainment.md}): a Pantry whose Tavern Host is on shift, in the evening.
 * Once per in-game minute ({@link #COUNT_INTERVAL}) every such Pantry counts the settlers of its settlement within the
 * Tavern radius (a Bard on its way to its Stage or on it does not count). If the crowd is big enough (condition A) or a jukebox that holds a
 * disc stands near (condition B), each guest who holds a seat at the Pantry, and the Tavern Host, gets the points of
 * {@link EntertainmentRules#tavernPoints} once per evening.
 * <p>
 * The seats and the walk to them are in {@link TavernSeats} and {@code SettlerTavern}; this class holds the count, the
 * jukebox check and the catch-up of a missed evening.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class Tavern {

    /**
     * Server ticks between two counts: one in-game minute is 24000 / 1440 = 16.7 ticks of the day clock, rounded to 17. It
     * runs on the game time, not on the day clock, so it also runs while the clock stands still.
     */
    private static final int COUNT_INTERVAL = 17;
    /** Blocks that may hold a jukebox of another mod: vanilla's jukebox and every block of this tag. */
    public static final TagKey<Block> JUKEBOXES = TagKey.create(Registries.BLOCK, CivMod.id("jukeboxes"));

    private Tavern() {
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % COUNT_INTERVAL == 0) {
            count(level, DayClock.day(level), DayClock.dayTime(level), level.getGameTime());
        }
    }

    /**
     * @param dayTime a day time (0 = 06:00)
     * @return whether it lies in the Tavern evening: from 17:30, when the first settler finishes work, to the end of the
     *         Tavern Host's shift at 20:00
     */
    public static boolean isEvening(long dayTime) {
        return dayTime >= SettlerWork.WORK_END && dayTime < ClerkStorage.TAVERN_SHIFT_END;
    }

    /**
     * One count at every Pantry of the level whose Tavern Host is on shift (outside the evening nothing happens).
     *
     * @param level    the level
     * @param day      the day
     * @param dayTime  the day time
     * @param gameTime the game time
     */
    private static void count(ServerLevel level, long day, long dayTime, long gameTime) {
        if (!isEvening(dayTime)) {
            return;
        }
        for (Settlement settlement : List.copyOf(SettlementData.get(level).settlements())) {
            for (SettlementBlock pantry : List.copyOf(settlement.blocks())) {
                count(level, settlement, pantry, day, dayTime, gameTime);
            }
        }
    }

    /**
     * {@link #count} for the Pantry at a position only.
     *
     * @param level   the level
     * @param pantry  a Pantry position
     * @param day     the day
     * @param dayTime the day time
     */
    public static void countAt(ServerLevel level, BlockPos pantry, long day, long dayTime) {
        countAt(level, pantry, day, dayTime, level.getGameTime());
    }

    /**
     * {@link #countAt(ServerLevel, BlockPos, long, long)} at a given game time, which the guests' record bubbles wait on.
     *
     * @param level    the level
     * @param pantry   a Pantry position
     * @param day      the day
     * @param dayTime  the day time
     * @param gameTime the game time
     */
    public static void countAt(ServerLevel level, BlockPos pantry, long day, long dayTime, long gameTime) {
        SettlementData.get(level).settlementAt(pantry).ifPresent(settlement -> settlement.block(pantry)
                .filter(block -> isEvening(dayTime)).ifPresent(block -> count(level, settlement, block, day, dayTime, gameTime)));
    }

    private static void count(ServerLevel level, Settlement settlement, SettlementBlock pantry, long day, long dayTime, long gameTime) {
        if (!ClerkStorage.isPantry(pantry) || !level.isLoaded(pantry.pos()) || !ClerkStorage.working(pantry, day, dayTime)) {
            return;
        }
        EntertainmentRules rules = EntertainmentRules.get();
        List<Settler> present = present(level, settlement, pantry.pos(), rules);
        List<Settler> paid = paid(pantry, present, day);
        if (!paid.stream().allMatch(settler -> settler.getWork().entertainment().tavernPaid(day))) {
            pay(paid, rules.tavernPoints(present.size(), jukeboxHoldsDisc(level, pantry.pos(), rules.jukeboxRadius())), day);
        }
        recordBubbles(level, present, pantry.pos(), rules, day, gameTime);
    }

    /**
     * The record bubble (vault {@code Systems/Thought Bubbles.md}): each guest of this Pantry who sits at its seat and whose
     * time has come gets the jukebox check of condition B. The guest shows the bubble if no jukebox holds a disc in range.
     * The guest's evening is settled in both cases, so the surroundings are read once per count at most, and only when a
     * guest is due. Catch-up never comes here.
     */
    private static void recordBubbles(ServerLevel level, List<Settler> present, BlockPos pantry, EntertainmentRules rules, long day, long gameTime) {
        List<Settler> due = present.stream().filter(guest -> guest.getWork().recordBubbleDue(pantry, day, gameTime)).toList();
        if (due.isEmpty()) {
            return;
        }
        boolean music = jukeboxHoldsDisc(level, pantry, rules.jukeboxRadius());
        due.forEach(guest -> guest.getWork().settleRecordBubble(day, !music));
    }

    /**
     * Simulates the Tavern evening of a missed day for one settler, without seats and without walking (vault
     * {@code Systems/Entertainment.md}, Catch-up). It needs a Pantry of the settlement with an assigned Tavern Host, the
     * nearest to the settler; its chunk does not have to be loaded. Every member of the settlement counts as present
     * unless it is loaded and away on a Mission (the mission state of an unloaded settler cannot be read); the jukebox is
     * read in loaded chunks only. The settler is paid once per day, with the same mark as the live count.
     *
     * @param level   the level
     * @param settler a settler whose catch-up covered the whole evening of that day (full Entertainment goes home)
     * @param day     the missed day
     */
    public static void catchUp(ServerLevel level, Settler settler, long day) {
        Entertainment entertainment = settler.getWork().entertainment();
        if (entertainment.tavernPaid(day) || entertainment.value() >= EntertainmentRules.MAX || settler.getWork().isAway()) {
            return;
        }
        SettlementData.get(level).settlement(settler.getSettlementId()).flatMap(settlement -> settlement.blocks().stream()
                .filter(block -> ClerkStorage.isPantry(block) && block.assignee(block.jobType()).isPresent())
                .min(Comparator.comparingDouble((SettlementBlock block) -> block.pos().distSqr(settler.blockPosition())))
                .map(pantry -> new Hosted(settlement, pantry.pos()))).ifPresent(missed -> {
                    EntertainmentRules rules = EntertainmentRules.get();
                    double points = rules.tavernPoints(membersOnPlace(level, missed.settlement()), jukeboxHoldsDisc(level, missed.pantry(), rules.jukeboxRadius()));
                    pay(List.of(settler), points, day);
                });
    }

    private record Hosted(Settlement settlement, BlockPos pantry) {
    }

    /** Members of the settlement that are not known to be on a Mission or to play the Bard. */
    private static int membersOnPlace(ServerLevel level, Settlement settlement) {
        int present = 0;
        for (UUID member : settlement.members()) {
            if (!(level.getEntity(member) instanceof Settler settler) || !settler.getWork().isAway() && !Stage.playsBard(settler)) {
                present++;
            }
        }
        return present;
    }

    /**
     * @param level      the level
     * @param settlement the settlement of the Pantry
     * @param pantry     the Pantry
     * @param rules      the rules
     * @return the settlers of the settlement within the Tavern radius of the Pantry that are not away (Mission, foraging); the
     *         Tavern Host is one of them, a Bard on its way to the Stage or playing there is not (before that it is a guest)
     */
    static List<Settler> present(ServerLevel level, Settlement settlement, BlockPos pantry, EntertainmentRules rules) {
        Vec3 center = Vec3.atCenterOf(pantry);
        double radius = rules.tavernRadius();
        return level.getEntitiesOfClass(Settler.class, AABB.ofSize(center, 2 * radius, 2 * radius, 2 * radius),
                settler -> settlement.members().contains(settler.getUUID()) && !settler.isAway() && !Stage.performs(level, settler)
                        && settler.position().distanceToSqr(center) <= radius * radius);
    }

    /**
     * The settlers the Tavern pays: the guests who hold a seat at this Pantry for the evening and, with them, its Tavern
     * Host. Settlers who only stand within the radius count for the crowd, not for the points; a Tavern without seats does
     * nothing.
     *
     * @param pantry  the Pantry
     * @param present the settlers present at it
     * @param day     the day of the evening
     * @return the settlers to pay, empty if nobody present holds a seat
     */
    private static List<Settler> paid(SettlementBlock pantry, List<Settler> present, long day) {
        Predicate<Settler> guest = settler -> settler.getWork().holdsTavernSeat(pantry.pos(), day);
        if (present.stream().noneMatch(guest)) {
            return List.of();
        }
        Optional<UUID> host = pantry.assignee(pantry.jobType()).map(SettlementBlock.Assignee::id);
        return present.stream().filter(settler -> guest.test(settler) || host.filter(settler.getUUID()::equals).isPresent()).toList();
    }

    /** Gives each settler who was not paid on the evening the points, and marks the evening paid. */
    private static void pay(List<Settler> settlers, double points, long day) {
        if (points <= 0.0) {
            return;
        }
        for (Settler settler : settlers) {
            Entertainment entertainment = settler.getWork().entertainment();
            if (!entertainment.tavernPaid(day)) {
                settler.getWork().setEntertainment(entertainment.payTavern(points, day));
            }
        }
    }

    /**
     * @param level  the level
     * @param center the Pantry
     * @param radius blocks around the Pantry
     * @return whether a jukebox that holds a disc (playing or not) stands within the radius, in loaded chunks only
     */
    public static boolean jukeboxHoldsDisc(ServerLevel level, BlockPos center, int radius) {
        double reach = (double) radius * radius;
        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                int fromX = Math.max(center.getX() - radius, chunkX << 4);
                int toX = Math.min(center.getX() + radius, (chunkX << 4) + 15);
                int fromZ = Math.max(center.getZ() - radius, chunkZ << 4);
                int toZ = Math.min(center.getZ() + radius, (chunkZ << 4) + 15);
                for (int x = fromX; x <= toX; x++) {
                    for (int z = fromZ; z <= toZ; z++) {
                        for (int y = Math.max(level.getMinY(), center.getY() - radius); y <= Math.min(level.getMaxY(), center.getY() + radius); y++) {
                            pos.set(x, y, z);
                            if (pos.distSqr(center) <= reach && holdsDisc(level, pos)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * A block of {@link #JUKEBOXES} reports a disc by its block state property {@code has_record} (vanilla's jukebox
     * does); a block without the property reports one when its block entity is a jukebox that holds an item.
     */
    private static boolean holdsDisc(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(JUKEBOXES)) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.HAS_RECORD)) {
            return state.getValue(BlockStateProperties.HAS_RECORD);
        }
        return level.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox && !jukebox.getTheItem().isEmpty();
    }
}
