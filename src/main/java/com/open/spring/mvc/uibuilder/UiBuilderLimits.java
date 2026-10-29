package com.open.spring.mvc.uibuilder;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * The page document limits Spring checks, bound from ui-builder.limits.* in application.properties.
 * The defaults are the limits in the Pages registry (_projects/systems/ui-builder/data/ui_builder.yml),
 * so a page the editor accepts is a page Spring accepts. Change both together.
 */
@Component
@ConfigurationProperties(prefix = "ui-builder.limits")
@Data
public class UiBuilderLimits {
    private int sections = 25;
    private int blocksPerSection = 50;
    private int cellsPerSection = 3;
    private int liveBlocks = 8;
    /** Length of the spec as Jackson writes it, compared with JSON.stringify(page).length in the browser. */
    private int specChars = 200_000;
    /** Every object and array is a level; the page object itself is level 1. */
    private int depth = 12;
    private int stringChars = 20_000;
    private int apiItemChars = 500;
    private int apiSnapshotItems = 50;
    private int apiSnapshotChars = 64_000;
    private int galleryMax = 200;
    private int classPagesMax = 500;
    private String slugPattern = "^[a-z0-9](?:[a-z0-9-]{1,62}[a-z0-9])$";
    private List<String> reservedSlugs = new ArrayList<>(List.of(
            "admin", "login", "logout", "help", "teacher", "teachers", "staff", "official",
            "ocs", "new", "edit", "view", "gallery", "canvas", "api", "draft"));
}
