package me.jddev0.ep.loading;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import me.jddev0.ep.EnergizedPowerMod;
import me.jddev0.ep.screen.EnergizedPowerBookScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resource.JsonDataLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Environment(EnvType.CLIENT)
public class EnergizedPowerBookReloadListener extends JsonDataLoader implements IdentifiableResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public Identifier getFabricId() {
        return new Identifier(EnergizedPowerMod.MODID, "energizedpowerbook");
    }

    public EnergizedPowerBookReloadListener() {
        super(new GsonBuilder().create(), "book_pages");
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> elements, ResourceManager resourceManager, Profiler profilerFiller) {
        List<EnergizedPowerBookScreen.PageContent> pages = new LinkedList<>();

        List<Map.Entry<Identifier, JsonElement>> elementEntries = elements.entrySet().stream().
                sorted(Comparator.comparing(o -> o.getKey().getPath())).
                collect(Collectors.toList());

        for(Map.Entry<Identifier, JsonElement> elementEntry:elementEntries) {
            Identifier resourceLocation = elementEntry.getKey();
            JsonElement element = elementEntry.getValue();

            try {
                if(!element.isJsonObject()) {
                    LOGGER.error(String.format("Failed to load energized power book page '%s' from data pack '%s': Element must be a JSON Object",
                            resourceLocation.getPath(), resourceLocation.getNamespace()));

                    continue;
                }

                JsonObject object = element.getAsJsonObject();

                Text chapterTitleComponent = null;
                if(object.has("title"))
                    chapterTitleComponent = Text.Serializer.fromJson(object.get("title"));

                Text contentComponent = null;
                if(object.has("content"))
                    contentComponent = Text.Serializer.fromJson(object.get("content"));

                Identifier imageResourceLocation = null;
                if(object.has("image")) {
                    JsonElement imageElement = object.get("image");
                    if(!imageElement.isJsonPrimitive() || !imageElement.getAsJsonPrimitive().isString()) {
                        LOGGER.error(String.format("Failed to load energized power book page '%s' from data pack '%s': image must be a string primitive",
                                resourceLocation.getPath(), resourceLocation.getNamespace()));

                        continue;
                    }

                    imageResourceLocation = Identifier.tryParse(imageElement.getAsJsonPrimitive().getAsString());
                }

                Identifier blockResourceLocation = null;
                if(object.has("block")) {
                    JsonElement imageElement = object.get("block");
                    if(!imageElement.isJsonPrimitive() || !imageElement.getAsJsonPrimitive().isString()) {
                        LOGGER.error(String.format("Failed to load energized power book page '%s' from data pack '%s': block must be a string primitive",
                                resourceLocation.getPath(), resourceLocation.getNamespace()));

                        continue;
                    }

                    blockResourceLocation = Identifier.tryParse(imageElement.getAsJsonPrimitive().getAsString());
                }

                pages.add(new EnergizedPowerBookScreen.PageContent(chapterTitleComponent, contentComponent, imageResourceLocation, blockResourceLocation));
            }catch(Exception e) {
                LOGGER.error(String.format("Failed to load energized power book page '%s' from data pack '%s'",
                        resourceLocation.getPath(), resourceLocation.getNamespace()), e);
            }
        }

        EnergizedPowerBookScreen.pages = pages;
    }
}
