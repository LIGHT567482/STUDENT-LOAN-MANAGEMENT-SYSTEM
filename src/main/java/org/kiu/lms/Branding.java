package org.kiu.lms;

import javafx.scene.paint.Color;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Single source of truth for branding. Reads names, asset paths and colours
 * from {@code /org/kiu/lms/branding/branding.properties} (assets live in the
 * same branding folder). Changing that one file re-themes the whole system.
 */
public final class Branding {
    private static final String RESOURCE = "/org/kiu/lms/branding/branding.properties";
    private static final String FOLDER = "/org/kiu/lms/branding/";
    private static Properties loaded;

    private Branding() {
    }

    private static Properties props() {
        if (loaded == null) {
            loaded = new Properties();
            try (InputStream stream = Branding.class.getResourceAsStream(RESOURCE)) {
                if (stream == null) {
                    throw new IllegalStateException("Branding configuration is missing: " + RESOURCE);
                }
                loaded.load(stream);
            } catch (IOException exception) {
                throw new IllegalStateException("Branding configuration could not be read: " + RESOURCE, exception);
            }
        }
        return loaded;
    }

    private static String get(String key, String fallback) {
        return props().getProperty(key, fallback).trim();
    }

    private static double getDouble(String key, double fallback) {
        try {
            return Double.parseDouble(get(key, Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Color colorOf(String key, String fallback) {
        return Color.web(get(key, fallback));
    }

    // ---------- System identity ----------
    public static String name() {
        return get("system.name", "KIU LOANS");
    }

    public static String subtitle() {
        return get("system.subtitle", "STUDENT FINANCE");
    }

    public static String title() {
        return get("system.title", "KIU Student Loan Management System");
    }

    public static String loginBrand() {
        return get("system.login.brand", "KIU STUDENTS LMS");
    }

    // ---------- Assets ----------
    public static String logoPath() {
        return FOLDER + get("logo.file", "logo.png");
    }

    public static String loginBackgroundPath() {
        return FOLDER + get("login.background.file", "login-bg.jpeg");
    }

    // ---------- Core colours ----------
    public static Color primary() {
        return colorOf("color.primary", "#075b30");
    }

    public static Color primaryDark() {
        return colorOf("color.primary.dark", "#064a28");
    }

    public static Color sidebar() {
        return colorOf("color.sidebar", "#123b2a");
    }

    public static Color sidebarHover() {
        return colorOf("color.sidebar.hover", "#0a2a1c");
    }

    public static Color sidebarLine() {
        return colorOf("color.sidebar.line", "#2a6b4e");
    }

    public static Color text() {
        return colorOf("color.text", "#183b28");
    }

    public static Color muted() {
        return colorOf("color.muted", "#5f7867");
    }

    public static Color border() {
        return colorOf("color.border", "#d7e9d9");
    }

    public static Color surface() {
        return colorOf("color.surface", "#f7fcf8");
    }

    public static Color surfaceAlt() {
        return colorOf("color.surface.alt", "#eef7f0");
    }

    public static Color pageSurface() {
        return colorOf("color.page.surface", "#edf5ee");
    }

    public static Color inputSurface() {
        return colorOf("color.input.surface", "#f8fdf9");
    }

    public static Color dialogSurface() {
        return colorOf("color.dialog.surface", "#f2f9f4");
    }

    public static Color cardBorder() {
        return colorOf("color.card.border", "#e2ebe4");
    }

    public static Color cardHover() {
        return colorOf("color.card.hover", "#eaf6ec");
    }

    public static Color tableHeader() {
        return colorOf("color.table.header", "#e4f0e6");
    }

    public static Color tableSelected() {
        return colorOf("color.table.selected", "#d8eddd");
    }

    public static Color chartPlot() {
        return colorOf("color.chart.plot", "#f6fbf7");
    }

    // ---------- Header (sidebar colour, white text by default) ----------
    public static Color headerBackground() {
        return colorOf("color.header.background", sidebar().toString());
    }

    public static Color headerText() {
        return colorOf("color.header.text", "#ffffff");
    }

    // ---------- Watermark ----------
    public static double watermarkOpacity() {
        return getDouble("watermark.opacity", 0.18);
    }

    public static double watermarkTableOpacity() {
        return getDouble("watermark.table.opacity", 0.45);
    }

    public static double watermarkWidth() {
        return getDouble("watermark.width", 390);
    }

    public static double watermarkHeight() {
        return getDouble("watermark.height", 174);
    }
}
