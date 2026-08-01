package com.guoyongzheng.training.web.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionContentExtractorTest {

    @Test
    void splitsOralSectionsOnStandaloneBoldMarkers() {
        String text = """
                ### 1. HashMap 在 Java 8 中的数据结构是什么？

                **完整口述稿**

                数组、链表和红黑树的组合。

                **原理补充**

                树化降低极端冲突时的查找复杂度。

                **继续追问**

                1. 为什么容量是 2 的幂？答：位运算定位。
                2. hash 相同就代表 key 相同吗？答：不代表。
                """;

        List<DashboardService.ContentSection> sections = QuestionContentExtractor.splitBoldSections(text);

        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).heading()).isEqualTo("完整口述稿");
        assertThat(sections.get(0).content()).contains("数组、链表和红黑树的组合");
        assertThat(sections.get(1).heading()).isEqualTo("原理补充");
        assertThat(sections.get(2).heading()).isEqualTo("继续追问");
        assertThat(sections.get(2).content()).contains("1. 为什么容量是 2 的幂");
    }

    @Test
    void extractsNamedSectionContentByExactHeading() {
        String text = """
                ## 四、核心案例一

                ### 1. 两分钟口述答案

                维护跨对象的不变量。

                ### 3. 可验证依据

                ContractServiceImpl、定时发起方法。

                ### 9. 不支持声称警示

                不能虚构 QPS 与成功率。
                """;

        List<DashboardService.ContentSection> sections = QuestionContentExtractor.splitNumberedSections(text);

        assertThat(sections).hasSize(3);
        assertThat(QuestionContentExtractor.sectionContent(sections, "### 3. 可验证依据"))
                .contains("ContractServiceImpl");
        assertThat(QuestionContentExtractor.sectionContent(sections, "### 9. 不支持声称警示"))
                .contains("QPS");
        assertThat(QuestionContentExtractor.sectionContent(sections, "### 2. 系统与业务边界")).isEmpty();
    }

    @Test
    void ignoresInlineBoldTextThatIsNotAStandaloneMarker() {
        String text = "**先看代码**再说结论。\n\n**结论**\n\n关键点是 **hash 扰动** 与树化阈值。";

        List<DashboardService.ContentSection> sections = QuestionContentExtractor.splitBoldSections(text);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).heading()).isEqualTo("结论");
        assertThat(sections.get(0).content()).contains("关键点是 **hash 扰动**");
    }

    @Test
    void emptyTextYieldsNoSections() {
        assertThat(QuestionContentExtractor.splitBoldSections("")).isEmpty();
        assertThat(QuestionContentExtractor.splitNumberedSections(null)).isEmpty();
    }
}
