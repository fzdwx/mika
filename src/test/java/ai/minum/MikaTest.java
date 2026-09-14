package ai.minum;

import ai.minum.extract.ExtractConfig;
import ai.minum.extract.ExtractResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MikaTest {
    @Test
    void preservesExistingMarkdownAndReadsUtf8Bom() {
        String markdown = "# 中文标题\n\n- 项目\n\n```java\nString a = \"x\";\n```\n";
        ExtractResult result = extract(" Text/Markdown; charset=UTF-8 ", "\uFEFF" + markdown);
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(markdown, result.getMarkdown());
        assertEquals("text/markdown", result.getContentType());
    }

    @Test
    void convertsHtmlHeadingsListsLinksAndTablesToMarkdown() {
        String html = "<html><body><h1>中文标题</h1><p>参阅 <a href='https://example.com/docs'>文档</a></p>"
                + "<ul><li>项目一</li><li>项目二</li></ul>"
                + "<table><tr><th>名称</th><th>值</th></tr><tr><td>温度</td><td>20</td></tr></table>"
                + "</body></html>";
        ExtractResult result = extract("html", html);
        assertFalse(result.isError(), result.getErrorMessage());
        String markdown = result.getMarkdown();
        assertTrue(markdown.contains("# 中文标题"), markdown);
        assertTrue(markdown.contains("- 项目一"), markdown);
        assertTrue(markdown.contains("[文档](https://example.com/docs)"), markdown);
        assertTrue(markdown.contains("|"), markdown);
        assertTrue(markdown.contains("温度"), markdown);
        assertTrue(result.hasTable());
    }

    @Test
    void retainsTheEndOfLongTextBeyondTikasDefaultLimit() {
        String content = "long paragraph 内容\n".repeat(10_000) + "\nEND_OF_DOCUMENT";
        ExtractResult result = extract("txt", content);
        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.getMarkdown().contains("END"), result.getErrorMessage());
        assertTrue(result.getMarkdown().length() > 100_000);
        assertTrue(result.getMarkdown().endsWith("END\\_OF\\_DOCUMENT"), result.getMarkdown().substring(result.getMarkdown().length() - 100));
    }

    @Test
    void keepsSourceMarkdownSeparateFromLiteralPlainText() {
        ExtractResult result = extract("txt", "# literal *text* <tag>\n");
        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.getMarkdown().contains("\\*text\\*"), result.getMarkdown());
    }

    @Test
    void retainsPlainTextLineAndParagraphBoundaries() {
        ExtractResult result = extract("txt", "第一行\n第二行\n\n下一段\n===\n");
        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.getMarkdown().contains("第一行\n第二行\n\n下一段"), result.getMarkdown());
        assertTrue(result.getMarkdown().contains("\\==="), result.getMarkdown());
    }

    @Test
    void retainsMergedHtmlCellsUsingHtmlInsideMarkdown() {
        ExtractResult result = extract("html", "<h1>合并表格</h1><table>"
                + "<tr><td rowspan='2'>设备</td><td>甲</td></tr><tr><td>乙</td></tr></table>");
        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.getMarkdown().contains("rowspan=\"2\""), result.getMarkdown());
        assertTrue(result.getMarkdown().contains("甲") && result.getMarkdown().contains("乙"), result.getMarkdown());
    }

    @Test
    void keepsUnsupportedTypeErrorWhenFallbackIsDisabled() {
        ExtractResult result = Mika.extract("not-supported", new ByteArrayInputStream(new byte[0]),
                ExtractConfig.defaultConfig().fallback(false));
        assertTrue(result.isError());
    }

    @Test
    void inputSizeLimitAppliesToMarkdownPath() {
        ExtractResult result = Mika.extract("markdown", new ByteArrayInputStream("123456".getBytes(StandardCharsets.UTF_8)),
                ExtractConfig.defaultConfig().maxExtractInputSize(5));

        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("File size limit exceeded"), result.getErrorMessage());
    }

    @Test
    void serializedXhtmlSizeLimitIncludesMarkup() {
        String html = "<br>".repeat(1_000);
        ExtractResult result = Mika.extract("html", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                ExtractConfig.defaultConfig().maxExtractedContentSize(256));

        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("Extracted content size limit exceeded"),
                result.getErrorMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dot", "application/vnd.ms-word", "application/vnd.ms-word.template", "application/x-dot"})
    void routesLegacyWordTemplatesToTheDocExtractor(String type) throws Exception {
        try (var input = getClass().getResourceAsStream("/documents/poi-word2.doc")) {
            assertNotNull(input);

            ExtractResult result = Mika.extract(type, input, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.getMarkdown().contains("Member of 3GPP (ARIB)"), result.getMarkdown());
        }
    }

    private static ExtractResult extract(String type, String content) {
        return Mika.extract(type, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                ExtractConfig.defaultConfig());
    }
}
