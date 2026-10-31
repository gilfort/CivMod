package com.gilfort.civmod.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.ResourceCollectionProfile;
import com.gilfort.civmod.api.ScanResult;
import com.gilfort.civmod.api.SectionScan;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerJob;
import com.gilfort.civmod.registry.ModEntityTypes;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.JobBlockSite;
import com.gilfort.civmod.settlement.SettlementData;
import com.gilfort.civmod.settlement.SettlementManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the need limit in the Work rules (vault {@code Systems/Needs.md}): a Resource Collection job is worked
 * at the level the settler's needs allow, for the speed and for every roll.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class NeedLimitedWorkGameTests {

    /** A JobBlock of level 3: skill cap 60. */
    private static final int BLOCK_LEVEL = 3;
    private static final int SKILL = 45;
    /** The top of need tier 1. */
    private static final int LIMIT = 20;
    private static final int ROLLS = 3;

    private NeedLimitedWorkGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "need_limits_collection_work", NeedLimitedWorkGameTests::limitsCollectionWork);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    /** The level passed to the speed formula and to each roll, one resource per second of the formula. */
    private static final class LevelSpy implements ResourceCollectionProfile {

        private final List<Integer> speedLevels = new ArrayList<>();
        private final List<Integer> rollLevels = new ArrayList<>();

        @Override
        public boolean isTool(ItemStack stack) {
            return false;
        }

        @Override
        public Item toolIcon() {
            return Items.STICK;
        }

        @Override
        public boolean isWorkPlace(ServerLevel level, BlockPos pos) {
            return true;
        }

        @Override
        public boolean isAssignableWorkPlace(Level level, BlockPos pos) {
            return true;
        }

        @Override
        public @Nullable BlockPos autoSearch(ServerLevel level, BlockPos jobBlock) {
            return null;
        }

        @Override
        public boolean rescanDaily() {
            return false;
        }

        @Override
        public SectionScan<? extends ScanResult> createScan(BlockPos center, int radius) {
            throw new UnsupportedOperationException("The test does not scan");
        }

        @Override
        public boolean needsTool() {
            return false;
        }

        @Override
        public boolean needsResources() {
            return false;
        }

        @Override
        public double speed(int skillLevel, ItemStack tool) {
            speedLevels.add(skillLevel);
            return 20.0;
        }

        @Override
        public Optional<Produced> produce(Context context) {
            rollLevels.add(context.skillLevel());
            return Optional.of(new Produced(List.of(), ItemStack.EMPTY));
        }
    }

    /** Output that takes everything. */
    private static final WorkRules.Output NOWHERE = new WorkRules.Output() {
        @Override
        public boolean hasRoom() {
            return true;
        }

        @Override
        public boolean accept(List<ItemStack> stacks) {
            return true;
        }
    };

    /** The full level while the needs are met; 45 at the top of tier 1 once the settler is downgraded. */
    private static void limitsCollectionWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementData data = SettlementData.get(level);
        BlockPos jobBlock = helper.absolutePos(new BlockPos(1, 1, 1));
        SettlementManager.place(data, jobBlock, ModJobs.FISHERMAN.get(), BLOCK_LEVEL, 0L, RandomSource.create(0L), SettlementManager.Listener.NONE);
        Settler settler = ModEntityTypes.SETTLER.get().create(level, EntitySpawnReason.EVENT);
        settler.snapTo(jobBlock.getX() + 0.5, jobBlock.getY(), jobBlock.getZ() + 0.5, 0.0F, 0.0F);
        settler.setNoAi(true);
        level.addFreshEntity(settler);
        settler.joinSettlement(data, data.settlementAt(jobBlock).orElseThrow());
        settler.getSkills().set(ModJobs.FISHERMAN.get(), new SettlerSkills.Skill(SKILL, 0));
        SettlerJob job = new SettlerJob(ModJobs.FISHERMAN.get(), jobBlock);
        settler.addJob(job);

        LevelSpy met = work(level, settler, job);
        helper.assertTrue(met.speedLevels.get(0) == SKILL && met.rollLevels.size() == ROLLS, "Needs met: the full level: " + met.speedLevels);

        settler.getWork().restoreNeeds(new NeedState(1, NeedState.NO_DAY, NeedState.NO_DAY));
        LevelSpy limited = work(level, settler, job);
        helper.assertTrue(limited.rollLevels.size() == ROLLS, "All rolls made: " + limited.rollLevels);
        helper.assertTrue(limited.speedLevels.stream().allMatch(used -> used == LIMIT), "Speed at the limit: " + limited.speedLevels);
        helper.assertTrue(limited.rollLevels.stream().allMatch(used -> used == LIMIT), "Rolls at the limit: " + limited.rollLevels);

        settler.discard();
        SettlementManager.remove(data, jobBlock, SettlementManager.Listener.NONE);
        helper.succeed();
    }

    /** One tick per roll at the spy's speed. */
    private static LevelSpy work(ServerLevel level, Settler settler, SettlerJob job) {
        LevelSpy spy = new LevelSpy();
        WorkRules.work(level, settler, job, spy, JobBlockSite.at(level, job.jobBlock()).orElseThrow(), settler.getWork().workplace(job.jobBlock()),
                job.jobBlock(), ROLLS, WorkRules.ToolSupply.BELT, NOWHERE);
        return spy;
    }
}
