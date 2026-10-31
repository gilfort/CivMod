package com.gilfort.civmod.job;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.gilfort.civmod.config.CivModConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Output rules of the Lumberjack (vault {@code Jobs/Lumberjack.md}): 90 % logs by wood type, 10 % rare drops. Excluded
 * wood types leave the log weights; with every type excluded a log roll is empty (vault
 * {@code Systems/Resource Collection Jobs.md}).
 */
public final class LumberjackOutput {

    /** Share of logs in the output. */
    public static final double COMMON_SHARE = 0.9;

    private static final Identifier OAK_LEAVES = Identifier.withDefaultNamespace("oak_leaves");
    private static final Identifier DARK_OAK_LEAVES = Identifier.withDefaultNamespace("dark_oak_leaves");

    private LumberjackOutput() {
    }

    /**
     * @param level the skill level
     * @return the base speed in resources per second ({@code 0.25 × 2^(2.75 × (level − 20) / 80)})
     */
    public static double speed(int level) {
        return 0.25 * Math.pow(2, 2.75 * (level - 20) / 80.0);
    }

    /**
     * @param totalLogs the estimated logs in the scan area
     * @return the productivity factor: 0 below the minimum, else logs / full amount up to 1 (server config)
     */
    public static double productivity(int totalLogs) {
        return hasEnoughLogs(totalLogs) ? Math.min(1.0, totalLogs / (double) CivModConfig.LUMBERJACK_FULL_LOGS.get()) : 0.0;
    }

    /**
     * @param totalLogs the estimated logs in the scan area
     * @return whether they reach the minimum the job needs to work (server config)
     */
    static boolean hasEnoughLogs(int totalLogs) {
        return totalLogs >= CivModConfig.LUMBERJACK_MINIMUM_LOGS.get();
    }

    /**
     * @return up to how many logs the scan has to count exactly: below this the amount decides over block or speed
     */
    static int logsCountedExactly() {
        return Math.max(CivModConfig.LUMBERJACK_MINIMUM_LOGS.get(), CivModConfig.LUMBERJACK_FULL_LOGS.get());
    }

    /** What the next produced resource is. */
    public enum Kind {
        /** A log (common). */
        LOG,
        /** Sticks (rare). */
        STICK,
        /** A sapling (rare). */
        SAPLING,
        /** An apple (rare). */
        APPLE,
        /** A leaf block (rare). */
        LEAVES
    }

    /**
     * Chooses the next resource and its item.
     *
     * @param scan       the scan result
     * @param poiLog     block id of the work place of the day (×2 weight), may be {@code null}
     * @param leaves     whether leaves are enabled and shears are available
     * @param silkTouch  whether the axe has Silk Touch
     * @param excluded   the wood types excluded at the JobBlock
     * @param random     the random source
     * @return the produced item ({@link ItemStack#EMPTY} for an empty log roll), or empty if nothing can be produced
     */
    public static Optional<Produced> produce(WoodScan.Result scan, Identifier poiLog, boolean leaves, boolean silkTouch, Set<Identifier> excluded,
            RandomSource random) {
        if (scan.logs().isEmpty()) {
            return Optional.empty();
        }
        if (random.nextDouble() < COMMON_SHARE) {
            Map<Identifier, Integer> weights = new LinkedHashMap<>(scan.logs());
            weights.keySet().removeAll(excluded);
            if (weights.isEmpty()) {
                return Optional.of(new Produced(Kind.LOG, ItemStack.EMPTY));
            }
            if (poiLog != null) {
                weights.computeIfPresent(poiLog, (id, weight) -> weight * 2);
            }
            return item(pick(weights, random)).map(item -> new Produced(Kind.LOG, new ItemStack(item)));
        }
        Kind kind = rareKind(scan, leaves, silkTouch, random);
        return switch (kind) {
            case STICK -> Optional.of(new Produced(kind, new ItemStack(Items.STICK)));
            case APPLE -> Optional.of(new Produced(kind, new ItemStack(Items.APPLE)));
            case SAPLING -> scan.leaves().isEmpty() ? Optional.of(new Produced(Kind.STICK, new ItemStack(Items.STICK)))
                    : sapling(pick(scan.leaves(), random)).map(item -> new Produced(kind, new ItemStack(item)))
                            .or(() -> Optional.of(new Produced(Kind.STICK, new ItemStack(Items.STICK))));
            case LEAVES -> scan.leaves().isEmpty() ? Optional.of(new Produced(Kind.STICK, new ItemStack(Items.STICK)))
                    : item(pick(scan.leaves(), random)).map(item -> new Produced(kind, new ItemStack(item)));
            case LOG -> Optional.empty();
        };
    }

    /**
     * A produced resource.
     *
     * @param kind  the kind (leaves cost shear durability, everything else axe durability)
     * @param stack the item
     */
    public record Produced(Kind kind, ItemStack stack) {
    }

    /**
     * @param scan      the scan result
     * @param leaves    whether leaves are enabled and shears are available
     * @param silkTouch whether the axe has Silk Touch
     * @param random    the random source
     * @return the kind of rare drop
     */
    static Kind rareKind(WoodScan.Result scan, boolean leaves, boolean silkTouch, RandomSource random) {
        if (silkTouch) {
            return Kind.LEAVES;
        }
        boolean apples = scan.leaves().containsKey(OAK_LEAVES) || scan.leaves().containsKey(DARK_OAK_LEAVES);
        Map<Kind, Integer> weights = new LinkedHashMap<>();
        if (leaves) {
            weights.put(Kind.STICK, 40);
            weights.put(Kind.SAPLING, 25);
            weights.put(Kind.LEAVES, 25);
        } else {
            weights.put(Kind.STICK, 55);
            weights.put(Kind.SAPLING, 35);
        }
        if (apples) {
            weights.put(Kind.APPLE, 10);
        }
        // Without apples the remaining weights simply sum to 90, i.e. the apple share is redistributed proportionally.
        return pick(weights, random);
    }

    static <K> K pick(Map<K, Integer> weights, RandomSource random) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int roll = random.nextInt(Math.max(1, total));
        for (Map.Entry<K, Integer> entry : weights.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                return entry.getKey();
            }
        }
        return weights.keySet().iterator().next();
    }

    private static Optional<Item> item(Identifier blockId) {
        return BuiltInRegistries.ITEM.getOptional(blockId).filter(item -> item != Items.AIR);
    }

    /**
     * @param leavesId a leaf block id
     * @return the matching sapling item ({@code x_leaves} → {@code x_sapling}; mangrove → propagule; azalea → azalea)
     */
    public static Optional<Item> sapling(Identifier leavesId) {
        String path = leavesId.getPath();
        if (path.equals("mangrove_leaves")) {
            return item(Identifier.fromNamespaceAndPath(leavesId.getNamespace(), "mangrove_propagule"));
        }
        if (path.equals("azalea_leaves")) {
            return item(Identifier.fromNamespaceAndPath(leavesId.getNamespace(), "azalea"));
        }
        if (path.equals("flowering_azalea_leaves")) {
            return item(Identifier.fromNamespaceAndPath(leavesId.getNamespace(), "flowering_azalea"));
        }
        if (!path.endsWith("_leaves")) {
            return Optional.empty();
        }
        return item(Identifier.fromNamespaceAndPath(leavesId.getNamespace(), path.substring(0, path.length() - "_leaves".length()) + "_sapling"));
    }
}
