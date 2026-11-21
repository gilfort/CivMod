package com.gilfort.civmod.mastery;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.gilfort.civmod.CivMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Reads the datapack files of Mastery: {@code data/<namespace>/civmod_mastery/<name>.json} of every pack, in pack order, so
 * a later pack can override the values of an earlier one.
 */
final class MasteryFiles {

    private static final FileToIdConverter FILES = FileToIdConverter.json("civmod_mastery");

    private MasteryFiles() {
    }

    /**
     * @param manager the resource manager of the reload
     * @param name    the file name without the extension
     * @return the parsed files of all packs, sorted by file id; a file that cannot be read is logged and left out
     */
    static List<JsonElement> read(ResourceManager manager, String name) {
        List<JsonElement> files = new ArrayList<>();
        FILES.listMatchingResourceStacks(manager).entrySet().stream()
                .filter(entry -> FILES.fileToId(entry.getKey()).getPath().equals(name))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    for (Resource resource : entry.getValue()) {
                        try (Reader reader = resource.openAsReader()) {
                            files.add(JsonParser.parseReader(reader));
                        } catch (Exception e) {
                            CivMod.LOGGER.error("Failed to read mastery file {} from pack {}", entry.getKey(), resource.sourcePackId(), e);
                        }
                    }
                });
        return files;
    }
}
