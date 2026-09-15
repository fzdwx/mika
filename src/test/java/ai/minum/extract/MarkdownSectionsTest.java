package ai.minum.extract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownSectionsTest {
    @Test
    void headingsAndWordPageBreaksBecomeStableLogicalPages() {
        String markdown = "intro\n\n# First\n\nbody\n\n---\n\n# Second\n\nend";

        List<String> sections = MarkdownSections.split(markdown);

        assertEquals(List.of("intro", "# First\n\nbody", "---", "# Second\n\nend"), sections);
        assertEquals(markdown, String.join("\n\n", sections));
    }

    @Test
    void oversizedParagraphIsNotRewrittenToForceASectionLimit() {
        String markdown = "a".repeat(70 * 1024);

        List<String> sections = MarkdownSections.split(markdown);

        assertEquals(List.of(markdown), sections);
        assertEquals(markdown, String.join("\n\n", sections));
    }

    @Test
    void oversizedSectionUsesAnExistingParagraphSeparator() {
        String markdown = "a".repeat(66 * 1024) + "\n\nlast";

        List<String> sections = MarkdownSections.split(markdown);

        assertEquals(2, sections.size());
        assertEquals(markdown, String.join("\n\n", sections));
    }
}
