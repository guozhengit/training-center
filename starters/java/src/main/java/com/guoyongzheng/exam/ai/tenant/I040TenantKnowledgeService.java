package com.guoyongzheng.exam.ai.tenant;

import java.util.List;

public final class I040TenantKnowledgeService {
    public I040TenantKnowledgeService(int dimension) {}

    public void put(String tenantId, Document document) {
        throw new UnsupportedOperationException("TODO");
    }

    public List<SearchHit> search(String tenantId, double[] queryVector, int k) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean delete(String tenantId, String documentId) {
        throw new UnsupportedOperationException("TODO");
    }

    public static final class Document {
        public Document(String id, String text, double[] vector) {}

        public String id() {
            throw new UnsupportedOperationException("TODO");
        }

        public String text() {
            throw new UnsupportedOperationException("TODO");
        }

        public double[] vector() {
            throw new UnsupportedOperationException("TODO");
        }
    }

    public static final class SearchHit {
        private SearchHit(Document document, double score) {}

        public Document document() {
            throw new UnsupportedOperationException("TODO");
        }

        public double score() {
            throw new UnsupportedOperationException("TODO");
        }
    }
}
