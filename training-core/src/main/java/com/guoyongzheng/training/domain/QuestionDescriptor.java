package com.guoyongzheng.training.domain;

import java.util.Objects;

/** Immutable, answer-free metadata used to select a training question. */
public record QuestionDescriptor(
        String id,
        Track track,
        String groupName,
        String title,
        String topic,
        String difficulty,
        String language,
        String sourceRef,
        String starterRef) {

    public QuestionDescriptor {
        id = requireText(id, "id");
        track = Objects.requireNonNull(track, "track");
        groupName = requireText(groupName, "groupName");
        title = requireText(title, "title");
        topic = requireText(topic, "topic");
        difficulty = requireText(difficulty, "difficulty");
        language = requireText(language, "language");
        sourceRef = requireText(sourceRef, "sourceRef");
        starterRef = starterRef == null ? null : requireText(starterRef, "starterRef");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
