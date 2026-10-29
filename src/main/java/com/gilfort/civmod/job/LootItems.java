package com.gilfort.civmod.job;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Every item a loot table can give (vault {@code Systems/Resource Collection Jobs.md}, the Max check of a type): the
 * item entries of its pools, the items of its tag entries and those of the loot tables it nests, in table order. Read
 * from the table's definition, never rolled, so the answer does not depend on chance; conditions and functions (a
 * cooked drop when burning) are not followed.
 */
final class LootItems {

    private static final String ITEM = "minecraft:item";
    private static final String TAG = "minecraft:tag";
    private static final String NESTED = "minecraft:loot_table";

    private LootItems() {
    }

    /**
     * @param level the level (its loot tables)
     * @param key   a loot table
     * @return every item it can give, in table order (empty for an unknown or empty table)
     */
    static Set<Item> of(ServerLevel level, ResourceKey<LootTable> key) {
        Set<Item> items = new LinkedHashSet<>();
        collect(level, key, items, new HashSet<>());
        return Collections.unmodifiableSet(items);
    }

    private static void collect(ServerLevel level, ResourceKey<LootTable> key, Set<Item> items, Set<ResourceKey<LootTable>> visited) {
        if (!visited.add(key)) {
            return;
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        LootTable.DIRECT_CODEC.encodeStart(level.registryAccess().createSerializationContext(JsonOps.INSTANCE), table).result()
                .ifPresent(json -> walk(level, json, items, visited));
    }

    private static void walk(ServerLevel level, JsonElement json, Set<Item> items, Set<ResourceKey<LootTable>> visited) {
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(element -> walk(level, element, items, visited));
            return;
        }
        if (!json.isJsonObject()) {
            return;
        }
        JsonObject object = json.getAsJsonObject();
        String type = string(object, "type");
        if (ITEM.equals(type)) {
            id(object, "name").flatMap(BuiltInRegistries.ITEM::getOptional).ifPresent(items::add);
        } else if (TAG.equals(type)) {
            id(object, "name").ifPresent(tag -> BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, tag))
                    .forEach(holder -> items.add(holder.value())));
        } else if (NESTED.equals(type) && object.has("value") && object.get("value").isJsonPrimitive()) {
            id(object, "value").ifPresent(nested -> collect(level, ResourceKey.create(Registries.LOOT_TABLE, nested), items, visited));
        }
        for (Map.Entry<String, JsonElement> member : object.entrySet()) {
            walk(level, member.getValue(), items, visited);
        }
    }

    private static String string(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static Optional<Identifier> id(JsonObject object, String member) {
        return Optional.ofNullable(Identifier.tryParse(string(object, member)));
    }
}
