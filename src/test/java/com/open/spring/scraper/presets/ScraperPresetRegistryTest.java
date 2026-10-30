package com.open.spring.scraper.presets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.open.spring.scraper.dto.ScrapeRequest;

/**
 * Covers loading presets from src/main/resources/scraper-presets/*.yaml - the replacement for
 * the old hardcoded dallasAcademicWorks() Java builder.
 */
class ScraperPresetRegistryTest {

    private final ScraperPresetRegistry registry = new ScraperPresetRegistry();

    @Test
    void loadsTheDallasAcademicWorksPresetFromYaml() {
        ScrapeRequest preset = registry.getPreset("dallas-academicworks");

        assertEquals("https://dallascollege.academicworks.com/opportunities", preset.getUrl());
        assertEquals("table tbody tr", preset.getItemsSelector());
        assertEquals("optionDataDirectUrl", preset.getPagination().getType());
        assertTrue(preset.getFields().containsKey("deadline"));
        assertEquals("deadline", preset.getFilters().getDeadlineField());
        assertEquals(20, preset.getLimits().getMaxPages());
    }

    @Test
    void presetNameIsCaseInsensitive() {
        assertEquals(
            registry.getPreset("dallas-academicworks").getUrl(),
            registry.getPreset("DALLAS-ACADEMICWORKS").getUrl());
    }

    @Test
    void unknownPresetReturnsNull() {
        assertNull(registry.getPreset("not-a-real-preset"));
    }

    @Test
    void blankOrNullPresetNameReturnsNull() {
        assertNull(registry.getPreset(null));
        assertNull(registry.getPreset(""));
        assertNull(registry.getPreset("   "));
    }

    @Test
    void pathTraversalAttemptsAreRejectedBeforeAnyFileLookup() {
        // Preset names become classpath filenames - reject anything that isn't a plain
        // lowercase-and-dashes identifier rather than letting it reach ClassPathResource.
        assertNull(registry.getPreset("../../../etc/passwd"));
        assertNull(registry.getPreset("dallas-academicworks/../secrets"));
        assertNull(registry.getPreset("dallas_academicworks"));
    }

    @Test
    void eachCallReturnsAFreshMutableInstance() {
        // ScraperController.mergePresetAndOverrides() mutates the returned ScrapeRequest
        // directly - a cached singleton would leak one request's overrides into the next.
        ScrapeRequest first = registry.getPreset("dallas-academicworks");
        first.setUrl("https://mutated.example.com");

        ScrapeRequest second = registry.getPreset("dallas-academicworks");

        assertEquals("https://dallascollege.academicworks.com/opportunities", second.getUrl());
    }
}
