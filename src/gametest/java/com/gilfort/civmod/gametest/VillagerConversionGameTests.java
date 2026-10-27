package com.gilfort.civmod.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.gilfort.civmod.CivMod;
import com.gilfort.civmod.api.Job;
import com.gilfort.civmod.config.CivModConfig;
import com.gilfort.civmod.job.SettlerSkills;
import com.gilfort.civmod.npc.Settler;
import com.gilfort.civmod.npc.SettlerBeds;
import com.gilfort.civmod.npc.VillagerConversion;
import com.gilfort.civmod.registry.ModAttachments;
import com.gilfort.civmod.registry.ModBlocks;
import com.gilfort.civmod.registry.ModJobs;
import com.gilfort.civmod.settlement.Settlement;
import com.gilfort.civmod.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * GameTests for the Villager conversion (vault {@code Systems/NPC Population.md}): a water bottle makes a Villager
 * receptive, bread then turns it into a Settler of the settlement after five minutes.
 */
@EventBusSubscriber(modid = CivMod.MOD_ID)
public final class VillagerConversionGameTests {

    private static final BlockPos VILLAGER = new BlockPos(4, 1, 4);
    private static final BlockPos JOB_BLOCK = new BlockPos(1, 1, 1);
    private static final BlockPos BED = new BlockPos(6, 1, 2);
    private static final BlockPos SECOND_BED = new BlockPos(7, 1, 6);

    private VillagerConversionGameTests() {
    }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            register(helper, "villager_conversion_skill_roll", VillagerConversionGameTests::skillRoll);
            register(helper, "villager_conversion_receptive", VillagerConversionGameTests::receptive);
            register(helper, "villager_conversion_bread_trades", VillagerConversionGameTests::breadTrades);
            register(helper, "villager_conversion_splash", VillagerConversionGameTests::splash);
            register(helper, "villager_conversion_refusals", VillagerConversionGameTests::refusals);
            register(helper, "villager_conversion_cap", VillagerConversionGameTests::cap);
            register(helper, "villager_conversion_success", VillagerConversionGameTests::success);
            register(helper, "villager_conversion_bed_not_shared", VillagerConversionGameTests::bedNotShared);
            register(helper, "villager_conversion_villager_bed_free", VillagerConversionGameTests::villagerBedFree);
            register(helper, "villager_conversion_bed_broken_other_bed", test -> bedBroken(test, true));
            register(helper, "villager_conversion_bed_broken_fails", test -> bedBroken(test, false));
            register(helper, "villager_conversion_level_one", VillagerConversionGameTests::levelOne);
            register(helper, "villager_conversion_death", VillagerConversionGameTests::death);
            register(helper, "villager_conversion_left_border", VillagerConversionGameTests::leftBorder);
            register(helper, "villager_conversion_dissolved", VillagerConversionGameTests::dissolved);
            register(helper, "villager_conversion_chunk_reload", VillagerConversionGameTests::chunkReload);
        });
    }

    private static void register(RegisterEvent.RegisterHelper<Consumer<GameTestHelper>> helper, String name, Consumer<GameTestHelper> test) {
        helper.register(ResourceKey.create(Registries.TEST_FUNCTION, CivMod.id(name)), test);
    }

    // ---------------------------------------------------------------- fixtures

    /** A stone floor under the test area. */
    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** An adult Farmer (it has trades, so plain interaction opens trading). */
    private static Villager villager(GameTestHelper helper, BlockPos at) {
        ServerLevel level = helper.getLevel();
        Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
        villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.FARMER));
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(at));
        villager.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        villager.setNoAi(true);
        level.addFreshEntity(villager);
        return villager;
    }

    /**
     * Places a Lumberjack JobBlock at {@link #JOB_BLOCK} on the floor; its settlement's starting settlers count as arrived,
     * so none spawn during the test.
     */
    private static Settlement settlement(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(JOB_BLOCK, ModBlocks.LUMBERJACK_BLOCK.get());
        SettlementData data = SettlementData.get(helper.getLevel());
        Settlement settlement = data.settlementAt(helper.absolutePos(JOB_BLOCK)).orElseThrow();
        data.settlersArrived(settlement);
        return settlement;
    }

    /** Places a bed (both halves) with its head at the given relative position, facing north. */
    private static BlockPos bed(GameTestHelper helper, BlockPos relativeHead) {
        BlockPos head = helper.absolutePos(relativeHead);
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        helper.getLevel().setBlock(head.relative(Direction.SOUTH), bed.setValue(BedBlock.PART, BedPart.FOOT), flags);
        helper.getLevel().setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), flags);
        return head;
    }

    private static ItemStack waterBottle() {
        return PotionContents.createItemStack(Items.POTION, Potions.WATER);
    }

    /** The player uses the item in its main hand on the Villager. */
    private static void use(Player player, Villager villager, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.interactOn(villager, InteractionHand.MAIN_HAND, villager.position());
    }

    /** Water bottle, then bread: the conversion starts and uses up one bread. */
    private static void convert(GameTestHelper helper, Villager villager) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        use(player, villager, waterBottle());
        use(player, villager, new ItemStack(Items.BREAD, 2));
        helper.assertTrue(VillagerConversion.isConverting(villager), "Converting after water bottle and bread: "
                + VillagerConversion.refusal(helper.getLevel(), villager));
        helper.assertTrue(player.getMainHandItem().getCount() == 1, "One bread used up");
        helper.assertFalse(VillagerConversion.isReceptive(villager), "The Receptive state is used up");
    }

    /** Lets the running conversion complete in a few ticks instead of five minutes. */
    private static void hurry(GameTestHelper helper, Villager villager) {
        VillagerConversion.Conversion conversion = villager.getData(ModAttachments.CONVERSION);
        villager.setData(ModAttachments.CONVERSION, conversion.withDue(helper.getLevel().getGameTime() + 2));
    }

    /** The Settler of the settlement standing near a position, if any. */
    private static Optional<Settler> settlerNear(GameTestHelper helper, Settlement settlement, Vec3 pos) {
        return helper.getLevel().getEntitiesOfClass(Settler.class, new AABB(pos, pos).inflate(40)).stream()
                .filter(settler -> settler.getSettlementId() == settlement.id())
                .findFirst();
    }

    /** Entities only tick in entity-ticking chunks: forces the test area (see {@link LumberjackGameTests#forceChunks}). */
    private static List<long[]> force(GameTestHelper helper, int size) {
        return LumberjackGameTests.forceChunks(helper, new BlockPos(size, 0, 9));
    }

    private static void unforce(GameTestHelper helper, List<long[]> forced) {
        forced.forEach(chunk -> helper.getLevel().setChunkForced((int) chunk[0], (int) chunk[1], false));
    }

    private static Optional<BlockPos> reserved(Settlement settlement, Villager villager) {
        return Optional.ofNullable(settlement.bedReservations().get(villager.getUUID()));
    }

    // ---------------------------------------------------------------- tests

    /**
     * Each Job's starting level is the average of 4 dice from 1 to the mission level: always inside the range, centred on
     * the middle, the extremes rare (worked distribution of 4d5 rounded half to even: 0.8 % / 29.6 % / 39.2 % / 29.6 %
     * / 0.8 %).
     */
    private static void skillRoll(GameTestHelper helper) {
        RandomSource random = RandomSource.create(117L);
        helper.assertTrue(VillagerConversion.rollLevel(1, random) == 1, "Mission level 1: always 1");
        int samples = 20000;
        int[] counts = new int[6];
        long sum = 0;
        for (int i = 0; i < samples; i++) {
            int level = VillagerConversion.rollLevel(5, random);
            helper.assertTrue(level >= 1 && level <= 5, "Inside 1–5: " + level);
            counts[level]++;
            sum += level;
        }
        double mean = (double) sum / samples;
        helper.assertTrue(mean > 2.95 && mean < 3.05, "Mostly around 3: mean " + mean);
        helper.assertTrue(counts[3] > 0.36 * samples && counts[3] < 0.43 * samples, "Level 3 the most common: " + counts[3]);
        helper.assertTrue(counts[1] < 0.02 * samples && counts[5] < 0.02 * samples, "Extremes rare: " + counts[1] + " / " + counts[5]);
        long hundred = 0;
        int top = 0;
        for (int i = 0; i < samples; i++) {
            int level = VillagerConversion.rollLevel(100, random);
            helper.assertTrue(level >= 1 && level <= 100, "Inside 1–100: " + level);
            hundred += level;
            top = Math.max(top, level);
        }
        double hundredMean = (double) hundred / samples;
        helper.assertTrue(hundredMean > 49.0 && hundredMean < 52.0, "Mission level 100: mostly around 50, mean " + hundredMean);
        helper.assertTrue(top < 100, "A top roll stays special: " + top);
        helper.succeed();
    }

    /**
     * A water bottle makes an adult Villager receptive for 60 s and becomes a glass bottle, without opening trading; the
     * window expires.
     */
    private static void receptive(GameTestHelper helper) {
        floor(helper);
        Villager villager = villager(helper, VILLAGER);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        use(player, villager, waterBottle());
        helper.assertTrue(VillagerConversion.isReceptive(villager), "Receptive after the water bottle");
        helper.assertTrue(player.getMainHandItem().is(Items.GLASS_BOTTLE), "Water bottle became a glass bottle: " + player.getMainHandItem());
        helper.assertTrue(villager.getTradingPlayer() == null, "The water bottle never opens trading");
        helper.runAfterDelay(VillagerConversion.RECEPTIVE_TICKS - 20, () -> {
            helper.assertTrue(VillagerConversion.isReceptive(villager), "Still receptive shortly before 60 s");
            helper.runAfterDelay(40, () -> {
                helper.assertFalse(VillagerConversion.isReceptive(villager), "No longer receptive after 60 s");
                villager.discard();
                helper.succeed();
            });
        });
    }

    /** Bread without the Receptive state is vanilla: the Villager trades and keeps nothing. */
    private static void breadTrades(GameTestHelper helper) {
        settlement(helper);
        bed(helper, BED);
        Villager villager = villager(helper, VILLAGER);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        use(player, villager, new ItemStack(Items.BREAD, 3));
        helper.assertTrue(player.equals(villager.getTradingPlayer()), "Bread alone opens trading");
        helper.assertTrue(player.getMainHandItem().getCount() == 3, "No bread taken: " + player.getMainHandItem());
        helper.assertFalse(VillagerConversion.isConverting(villager), "No conversion without the water bottle");
        villager.discard();
        helper.succeed();
    }

    /** A splash water bottle hitting a Villager makes it Receptive too. */
    private static void splash(GameTestHelper helper) {
        List<long[]> forced = force(helper, 9);
        floor(helper);
        Villager villager = villager(helper, VILLAGER);
        ServerLevel level = helper.getLevel();
        Vec3 above = villager.position().add(0.0, 2.5, 0.0);
        ThrownSplashPotion potion = new ThrownSplashPotion(level, above.x, above.y, above.z,
                PotionContents.createItemStack(Items.SPLASH_POTION, Potions.WATER));
        potion.setDeltaMovement(0.0, -0.5, 0.0);
        level.addFreshEntity(potion);
        helper.succeedWhen(() -> {
            helper.assertTrue(VillagerConversion.isReceptive(villager), "Receptive after the splash water bottle");
            villager.discard();
            unforce(helper, forced);
        });
    }

    /**
     * Refusals at feeding, in order, each consuming nothing and keeping the Receptive state: a baby, outside every
     * settlement border, no free bed.
     */
    private static void refusals(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        floor(helper);
        Villager outside = villager(helper, VILLAGER);
        VillagerConversion.makeReceptive(level, outside);
        expectRefusal(helper, player, outside, VillagerConversion.Refusal.OUTSIDE);

        Villager baby = villager(helper, new BlockPos(2, 1, 6));
        baby.setAge(-24000);
        use(player, baby, waterBottle());
        helper.assertFalse(VillagerConversion.isReceptive(baby), "A baby is never Receptive");
        helper.assertTrue(player.getMainHandItem().is(Items.POTION) && baby.getTradingPlayer() == null, "Water bottle kept, no trading");
        VillagerConversion.makeReceptive(level, baby);
        expectRefusal(helper, player, baby, VillagerConversion.Refusal.BABY);

        settlement(helper);
        expectRefusal(helper, player, outside, VillagerConversion.Refusal.NO_BED);
        outside.discard();
        baby.discard();
        helper.succeed();
    }

    /** Feeding is refused with the reason; nothing is used up and the Villager stays Receptive. */
    private static void expectRefusal(GameTestHelper helper, Player player, Villager villager, VillagerConversion.Refusal reason) {
        helper.assertTrue(VillagerConversion.refusal(helper.getLevel(), villager).filter(reason::equals).isPresent(),
                "Refusal " + reason + ": " + VillagerConversion.refusal(helper.getLevel(), villager));
        use(player, villager, new ItemStack(Items.BREAD, 2));
        helper.assertTrue(player.getMainHandItem().getCount() == 2, reason + ": no bread used up");
        helper.assertFalse(VillagerConversion.isConverting(villager), reason + ": no conversion");
        helper.assertTrue(VillagerConversion.isReceptive(villager), reason + ": still Receptive");
        helper.assertTrue(villager.getTradingPlayer() == null, reason + ": no trading");
    }

    /**
     * {@code maxSettlersPerSettlement}: Settlers and running conversions count; 0 means unlimited.
     */
    private static void cap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settlement settlement = settlement(helper);
        bed(helper, BED);
        SettlementData data = SettlementData.get(level);
        data.addMember(settlement, UUID.randomUUID());
        data.addMember(settlement, UUID.randomUUID());
        Villager villager = villager(helper, VILLAGER);
        VillagerConversion.makeReceptive(level, villager);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        int before = CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.get();
        try {
            CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.set(2);
            expectRefusal(helper, player, villager, VillagerConversion.Refusal.FULL);
            CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.set(0);
            helper.assertTrue(VillagerConversion.refusal(level, villager).isEmpty(), "0 = unlimited");
            CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.set(3);
            use(player, villager, new ItemStack(Items.BREAD, 2));
            helper.assertTrue(VillagerConversion.isConverting(villager), "Below the cap: converting");
            helper.assertTrue(player.getMainHandItem().getCount() == 1, "One bread used up");
            Villager second = villager(helper, new BlockPos(2, 1, 6));
            bed(helper, SECOND_BED);
            VillagerConversion.makeReceptive(level, second);
            helper.assertTrue(VillagerConversion.refusal(level, second).filter(VillagerConversion.Refusal.FULL::equals).isPresent(),
                    "The running conversion counts towards the cap");
            second.discard();
        } finally {
            CivModConfig.MAX_SETTLERS_PER_SETTLEMENT.set(before);
            villager.discard();
        }
        helper.succeed();
    }

    /**
     * Feeding reserves the free bed; at the end the Villager is replaced by a Settler of the settlement at its position:
     * name tag kept, empty inventory, the reserved bed claimed, every Job rolled within the mission level.
     */
    private static void success(GameTestHelper helper) {
        List<long[]> forced = force(helper, 9);
        Settlement settlement = settlement(helper);
        BlockPos bed = bed(helper, BED);
        Villager villager = villager(helper, VILLAGER);
        villager.setCustomName(Component.literal("Hilde"));
        villager.setData(ModAttachments.MISSION_LEVEL, 5);
        convert(helper, villager);
        helper.assertTrue(reserved(settlement, villager).filter(bed::equals).isPresent(), "The free bed is reserved");
        Vec3 pos = villager.position();
        hurry(helper, villager);
        helper.succeedWhen(() -> {
            helper.assertTrue(villager.isRemoved(), "The Villager is gone");
            Settler settler = settlerNear(helper, settlement, pos).orElseThrow(() -> helper.assertionException("No Settler"));
            helper.assertTrue(settler.position().distanceTo(pos) < 0.5, "At the Villager's position: " + settler.position());
            helper.assertTrue(settler.getName().getString().equals("Hilde"), "Name tag kept: " + settler.getName().getString());
            helper.assertTrue(settlement.members().contains(settler.getUUID()), "Member of the settlement");
            helper.assertTrue(settlement.bedOf(settler.getUUID()).filter(bed::equals).isPresent(), "Claims the reserved bed");
            helper.assertTrue(settlement.bedReservations().isEmpty(), "Reservation gone");
            helper.assertTrue(settler.getInventory().isEmpty(), "Empty inventory");
            for (Job job : ModJobs.REGISTRY) {
                SettlerSkills.Skill skill = settler.getSkills().get(job);
                helper.assertTrue(skill.level() >= 1 && skill.level() <= 5 && skill.exp() == 0, job + " rolled within 1–5: " + skill);
            }
            settler.discard();
            unforce(helper, forced);
        });
    }

    /** Two conversions never share a bed: the second Villager finds none until another bed is placed. */
    private static void bedNotShared(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        bed(helper, BED);
        Villager first = villager(helper, VILLAGER);
        Villager second = villager(helper, new BlockPos(2, 1, 6));
        convert(helper, first);
        VillagerConversion.makeReceptive(helper.getLevel(), second);
        helper.assertTrue(VillagerConversion.refusal(helper.getLevel(), second).filter(VillagerConversion.Refusal.NO_BED::equals).isPresent(),
                "The reserved bed is not free");
        BlockPos other = bed(helper, SECOND_BED);
        convert(helper, second);
        helper.assertTrue(reserved(settlement, second).filter(other::equals).isPresent(), "The second Villager reserves the new bed");
        SettlementData.get(helper.getLevel()).assignBed(settlement, other, UUID.randomUUID());
        helper.assertTrue(reserved(settlement, second).isEmpty(), "A bed given to a Settler is no longer reserved");
        first.discard();
        second.discard();
        helper.succeed();
    }

    /** A bed a Villager holds counts as free; the new Settler takes it and the Villager loses its home. */
    private static void villagerBedFree(GameTestHelper helper) {
        List<long[]> forced = force(helper, 9);
        ServerLevel level = helper.getLevel();
        Settlement settlement = settlement(helper);
        BlockPos bed = bed(helper, BED);
        Villager holder = villager(helper, new BlockPos(7, 1, 7));
        level.getPoiManager().take(type -> type.is(PoiTypes.HOME), (type, pos) -> pos.equals(bed), bed, 1);
        holder.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
        Villager villager = villager(helper, VILLAGER);
        convert(helper, villager);
        Vec3 pos = villager.position();
        hurry(helper, villager);
        helper.succeedWhen(() -> {
            Settler settler = settlerNear(helper, settlement, pos).orElseThrow(() -> helper.assertionException("No Settler"));
            helper.assertTrue(settlement.bedOf(settler.getUUID()).filter(bed::equals).isPresent(), "The Settler took the Villager's bed");
            helper.assertTrue(holder.getBrain().getMemory(MemoryModuleType.HOME).isEmpty(), "The Villager lost its home");
            settler.discard();
            holder.discard();
            SettlerBeds.releasePoi(level, bed);
            unforce(helper, forced);
        });
    }

    /**
     * The reserved bed is broken during the conversion: the Settler takes another free bed, or without one the conversion
     * fails (the Villager stays, the bread is gone, the reservation is freed).
     */
    private static void bedBroken(GameTestHelper helper, boolean otherBed) {
        List<long[]> forced = force(helper, 9);
        Settlement settlement = settlement(helper);
        bed(helper, BED);
        BlockPos other = otherBed ? bed(helper, SECOND_BED) : null;
        Villager villager = villager(helper, VILLAGER);
        convert(helper, villager);
        BlockPos reservedBed = reserved(settlement, villager).orElseThrow();
        helper.getLevel().setBlock(reservedBed.relative(Direction.SOUTH), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(reservedBed, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Vec3 pos = villager.position();
        hurry(helper, villager);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(settlement.bedReservations().isEmpty(), "Reservation freed");
            if (other != null) {
                Settler settler = settlerNear(helper, settlement, pos).orElseThrow(() -> helper.assertionException("No Settler"));
                helper.assertTrue(settlement.bedOf(settler.getUUID()).filter(other::equals).isPresent(), "Takes the other free bed");
                settler.discard();
            } else {
                helper.assertTrue(villager.isAlive() && !VillagerConversion.isConverting(villager), "Stays a Villager");
                helper.assertTrue(settlerNear(helper, settlement, pos).isEmpty(), "No Settler");
                villager.discard();
            }
            unforce(helper, forced);
            helper.succeed();
        });
    }

    /** Nitwits and Villagers without a mission level start at level 1 in every Job; without a name tag a name is picked. */
    private static void levelOne(GameTestHelper helper) {
        List<long[]> forced = force(helper, 9);
        Settlement settlement = settlement(helper);
        bed(helper, BED);
        bed(helper, SECOND_BED);
        Villager nitwit = villager(helper, VILLAGER);
        nitwit.setVillagerData(nitwit.getVillagerData().withProfession(helper.getLevel().registryAccess(), VillagerProfession.NITWIT));
        nitwit.setData(ModAttachments.MISSION_LEVEL, 50);
        Villager local = villager(helper, new BlockPos(2, 1, 6));
        convert(helper, nitwit);
        convert(helper, local);
        hurry(helper, nitwit);
        hurry(helper, local);
        helper.succeedWhen(() -> {
            List<Settler> settlers = helper.getLevel().getEntitiesOfClass(Settler.class, new AABB(helper.absolutePos(VILLAGER)).inflate(20)).stream()
                    .filter(settler -> settler.getSettlementId() == settlement.id()).toList();
            helper.assertTrue(settlers.size() == 2, "Both converted: " + settlers.size());
            for (Settler settler : settlers) {
                helper.assertTrue(settler.hasCustomName() && !settler.getName().getString().isBlank(), "Got a name");
                for (Job job : ModJobs.REGISTRY) {
                    helper.assertTrue(settler.getSkills().get(job).level() == 1, settler.getName().getString() + " " + job + " at level 1");
                }
            }
            settlers.forEach(Settler::discard);
            unforce(helper, forced);
        });
    }

    /** A converting Villager that dies frees its bed reservation. */
    private static void death(GameTestHelper helper) {
        List<long[]> forced = force(helper, 9);
        Settlement settlement = settlement(helper);
        bed(helper, BED);
        Villager villager = villager(helper, VILLAGER);
        convert(helper, villager);
        villager.kill(helper.getLevel());
        helper.succeedWhen(() -> {
            helper.assertTrue(villager.isRemoved(), "The dead Villager is removed");
            helper.assertTrue(settlement.bedReservations().isEmpty(), "Reservation freed after death: " + settlement.bedReservations());
            unforce(helper, forced);
        });
    }

    /** A Villager that walks out of the border still converts into the settlement it was fed in. */
    private static void leftBorder(GameTestHelper helper) {
        List<long[]> forced = force(helper, 32);
        Settlement settlement = settlement(helper);
        BlockPos bed = bed(helper, BED);
        Villager villager = villager(helper, VILLAGER);
        convert(helper, villager);
        BlockPos away = helper.absolutePos(VILLAGER).offset(25, 0, 0);
        helper.getLevel().setBlockAndUpdate(away.below(), Blocks.STONE.defaultBlockState());
        villager.teleportTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
        helper.assertTrue(SettlementData.get(helper.getLevel()).settlementContaining(villager.blockPosition()).isEmpty(), "Outside the border");
        Vec3 pos = villager.position();
        hurry(helper, villager);
        helper.succeedWhen(() -> {
            Settler settler = settlerNear(helper, settlement, pos).orElseThrow(() -> helper.assertionException("No Settler"));
            helper.assertTrue(settlement.bedOf(settler.getUUID()).filter(bed::equals).isPresent(), "Joined with the reserved bed");
            settler.discard();
            helper.getLevel().setBlockAndUpdate(away.below(), Blocks.AIR.defaultBlockState());
            unforce(helper, forced);
        });
    }

    /** The settlement is dissolved during the conversion: it fails and the Villager stays. */
    private static void dissolved(GameTestHelper helper) {
        List<long[]> forced = force(helper, 9);
        Settlement settlement = settlement(helper);
        bed(helper, BED);
        Villager villager = villager(helper, VILLAGER);
        convert(helper, villager);
        helper.setBlock(JOB_BLOCK, Blocks.AIR);
        helper.assertTrue(SettlementData.get(helper.getLevel()).settlement(settlement.id()).isEmpty(), "Settlement dissolved");
        hurry(helper, villager);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(villager.isAlive() && !VillagerConversion.isConverting(villager), "Stays a Villager");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(Settler.class, new AABB(villager.position(), villager.position()).inflate(20))
                    .isEmpty(), "No Settler");
            villager.discard();
            unforce(helper, forced);
            helper.succeed();
        });
    }

    /**
     * The conversion is saved with the Villager and its reservation with the settlement: unloaded past its end, it
     * completes on its first tick after loading (catch-up).
     */
    private static void chunkReload(GameTestHelper helper) {
        List<long[]> forced = force(helper, 9);
        ServerLevel level = helper.getLevel();
        Settlement settlement = settlement(helper);
        BlockPos bed = bed(helper, BED);
        Villager villager = villager(helper, VILLAGER);
        convert(helper, villager);
        hurry(helper, villager);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        villager.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        villager.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(reserved(settlement, villager).filter(bed::equals).isPresent(), "Reservation kept while unloaded");
            Villager loaded = EntityTypes.VILLAGER.create(level, EntitySpawnReason.LOAD);
            loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
            helper.assertTrue(VillagerConversion.isConverting(loaded), "Conversion saved with the Villager");
            Vec3 pos = loaded.position();
            level.addFreshEntity(loaded);
            helper.succeedWhen(() -> {
                Settler settler = settlerNear(helper, settlement, pos).orElseThrow(() -> helper.assertionException("No Settler"));
                helper.assertTrue(settlement.bedOf(settler.getUUID()).filter(bed::equals).isPresent(), "Caught up: bed claimed");
                settler.discard();
                unforce(helper, forced);
            });
        });
    }
}
