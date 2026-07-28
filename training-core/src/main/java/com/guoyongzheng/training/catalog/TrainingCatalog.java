package com.guoyongzheng.training.catalog;

import com.guoyongzheng.training.domain.QuestionDescriptor;

import java.util.List;

/** Read-only unified view of all training questions. */
public interface TrainingCatalog {
    QuestionDescriptor require(String id);

    List<QuestionDescriptor> find(QuestionFilter filter);
}
