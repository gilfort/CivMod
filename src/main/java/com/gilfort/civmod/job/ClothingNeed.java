package com.gilfort.civmod.job;

import java.util.List;

import com.gilfort.civmod.clothing.ClothingRules;
import com.gilfort.civmod.clothing.SettlerClothing;
import com.gilfort.civmod.clothing.SettlerClothing.Piece;
import com.gilfort.civmod.clothing.SettlerClothing.State;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The clothing need of a tier (vault {@code Systems/Needs.md}, decisions of 2026-11-21): all four slots hold a piece from the tag of
 * the tier or a higher one ({@link ClothingRules#requiredRank}). A worn-out piece still counts while it exists. One {@link Need}
 * per tier from 2 on, because the tag differs by tier; nothing asks for clothes at tier 1.
 */
final class ClothingNeed implements Need {

    private final int tier;

    /**
     * @param tier the tier the need belongs to (2 or higher)
     */
    ClothingNeed(int tier) {
        this.tier = tier;
    }

    @Override
    public int tier() {
        return tier;
    }

    @Override
    public Component name() {
        return Component.translatable("civmod.need.clothing");
    }

    @Override
    public Item icon() {
        return ClothingRules.clothOf(ClothingRules.requiredRank(tier), EquipmentSlot.CHEST);
    }

    /** The piece that is missing, the first one in the order head, chest, legs, feet. */
    @Override
    public Item icon(Context context) {
        int rank = ClothingRules.requiredRank(tier);
        return context.settler().getClothing().pieces(tier).stream().filter(piece -> piece.state() == State.MISSING).findFirst()
                .map(piece -> ClothingRules.clothOf(rank, piece.slot())).orElseGet(this::icon);
    }

    @Override
    public Check check(Context context) {
        SettlerClothing clothing = context.settler().getClothing();
        List<Piece> pieces = clothing.pieces(tier);
        int rank = ClothingRules.requiredRank(tier);
        List<ItemStack> icons = pieces.stream()
                .map(piece -> piece.state() == State.MISSING ? new ItemStack(ClothingRules.clothOf(rank, piece.slot())) : piece.stack().copy()).toList();
        boolean met = pieces.stream().noneMatch(piece -> piece.state() == State.MISSING);
        return new Check(met, detail(pieces), icons, tooltip(pieces));
    }

    private static Component detail(List<Piece> pieces) {
        List<Component> missing = slotNames(pieces, State.MISSING);
        if (!missing.isEmpty()) {
            return Component.translatable("civmod.need.clothing.missing", join(missing));
        }
        List<Component> worn = slotNames(pieces, State.WORN_OUT);
        return worn.isEmpty() ? Component.translatable("civmod.need.clothing.met") : Component.translatable("civmod.need.clothing.worn", join(worn));
    }

    private static Component tooltip(List<Piece> pieces) {
        MutableComponent lines = Component.empty();
        for (Piece piece : pieces) {
            if (!lines.getString().isEmpty()) {
                lines.append("\n");
            }
            Component slot = slotName(piece.slot());
            lines.append(switch (piece.state()) {
                case MISSING -> Component.translatable("civmod.need.clothing.piece.missing", slot);
                case WORN_OUT -> Component.translatable("civmod.need.clothing.piece.worn", slot, piece.stack().getHoverName());
                case MET -> Component.translatable("civmod.need.clothing.piece", slot, piece.stack().getHoverName());
            });
        }
        return lines;
    }

    private static List<Component> slotNames(List<Piece> pieces, State state) {
        return pieces.stream().filter(piece -> piece.state() == state).map(piece -> slotName(piece.slot())).toList();
    }

    private static Component slotName(EquipmentSlot slot) {
        return Component.translatable("civmod.need.clothing.slot." + slot.getName());
    }

    private static Component join(List<Component> names) {
        if (names.size() == 1) {
            return names.get(0);
        }
        MutableComponent joined = Component.empty();
        for (int i = 0; i < names.size(); i++) {
            joined.append(i == 0 ? names.get(i) : Component.literal(", ").append(names.get(i)));
        }
        return joined;
    }
}
