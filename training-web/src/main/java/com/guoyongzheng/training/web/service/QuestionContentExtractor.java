package com.guoyongzheng.training.web.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a markdown answer section into named subsections so the practice UI can
 * reveal the model answer, follow-up drills, and project structure step by step.
 *
 * <p>Oral questions use bold markers on their own line ({@code **完整口述稿**}),
 * while project cases use numbered H3 headings ({@code ### 3. 可验证依据}).</p>
 */
final class QuestionContentExtractor {
    private static final Pattern BOLD_HEADING = Pattern.compile("(?m)^\\*\\*(.+?)\\*\\*\\s*$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("(?m)^(###\\s+\\d+\\.\\s+.+?)\\s*$");

    private QuestionContentExtractor() {
    }

    /** Splits text into subsections delimited by standalone bold markers. */
    static List<DashboardService.ContentSection> splitBoldSections(String text) {
        return split(text, BOLD_HEADING);
    }

    /** Splits text into subsections delimited by numbered H3 headings. */
    static List<DashboardService.ContentSection> splitNumberedSections(String text) {
        return split(text, NUMBERED_HEADING);
    }

    /** Returns the content of the first section whose heading equals the given label. */
    static String sectionContent(List<DashboardService.ContentSection> sections, String heading) {
        if (heading == null || heading.isBlank()) {
            return "";
        }
        for (DashboardService.ContentSection section : sections) {
            if (heading.equals(section.heading())) {
                return section.content();
            }
        }
        return "";
    }

    private static List<DashboardService.ContentSection> split(String text, Pattern pattern) {
        List<DashboardService.ContentSection> sections = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sections;
        }
        Matcher matcher = pattern.matcher(text);
        String label = null;
        int contentStart = 0;
        while (matcher.find()) {
            if (label != null) {
                sections.add(new DashboardService.ContentSection(label, text.substring(contentStart, matcher.start()).strip()));
            }
            label = matcher.group(1).strip();
            contentStart = matcher.end();
        }
        if (label != null) {
            sections.add(new DashboardService.ContentSection(label, text.substring(contentStart).strip()));
        }
        return sections;
    }
}
