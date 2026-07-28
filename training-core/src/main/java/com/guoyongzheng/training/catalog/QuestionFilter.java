package com.guoyongzheng.training.catalog;

import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.Track;

/** Exact-match criteria; null fields are intentionally unconstrained. */
public record QuestionFilter(
        Track track,
        String groupName,
        String topic,
        String difficulty,
        String language) {

    public boolean matches(QuestionDescriptor question) {
        return (track == null || track == question.track())
                && matches(groupName, question.groupName())
                && matches(topic, question.topic())
                && matches(difficulty, question.difficulty())
                && matches(language, question.language());
    }

    private static boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }
}
