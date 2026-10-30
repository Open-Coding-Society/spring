package com.open.spring.scraper.presets;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.open.spring.scraper.dto.ScrapeRequest;

/**
 * Loads scraper presets from src/main/resources/scraper-presets/&lt;name&gt;.yaml instead of
 * building them with ScrapeRequest's Java setters. A preset is the exact same JSON/YAML shape
 * the scraper lessons already teach students to write by hand (see
 * _projects/systems/scraper/docs/ in the frontend repo) - keeping presets in that same format,
 * rather than ~40 lines of nested `new ScrapeRequest.PaginationConfig()` construction, means
 * anyone who can write a lesson payload can also read or add a preset.
 *
 * Presets are read fresh on every request rather than cached: ScrapeRequest's fields are
 * mutable and ScraperController.mergePresetAndOverrides() mutates the returned instance
 * directly, so a cached singleton would leak overrides from one request's preset into the
 * next's.
 */
@Component
public class ScraperPresetRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ScraperPresetRegistry.class);
    private static final String PRESET_DIRECTORY = "scraper-presets";

    private final YAMLMapper yamlMapper = new YAMLMapper();

    public ScrapeRequest getPreset(String presetName) {
        if (presetName == null || presetName.isBlank()) {
            return null;
        }

        // Preset names become filenames on the classpath - reject anything that isn't a
        // plain lowercase-and-dashes identifier before it ever reaches a resource lookup.
        String normalized = presetName.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9-]+")) {
            return null;
        }

        String resourcePath = PRESET_DIRECTORY + "/" + normalized + ".yaml";
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            return null;
        }

        try (InputStream in = resource.getInputStream()) {
            return yamlMapper.readValue(in, ScrapeRequest.class);
        } catch (IOException e) {
            logger.error("Could not read scraper preset '{}' from {}: {}", presetName, resourcePath, e.getMessage());
            return null;
        }
    }
}
