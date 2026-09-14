# Mika

把文件内容提取为 Markdown，供文件展示、分块和检索使用。运行环境：Java 21。

## 使用

```java
import ai.minum.Mika;
import ai.minum.extract.ExtractConfig;
import java.nio.file.Files;
import java.nio.file.Path;

try (var stream = Files.newInputStream(Path.of("操作手册.docx"))) {
    var result = Mika.extract("docx", stream, ExtractConfig.defaultConfig());
    if (result.isError()) {
        throw new IllegalStateException(result.getErrorMessage());
    }
    String markdown = result.getMarkdown();
    String contentType = result.getContentType(); // text/markdown
    var pages = result.getPages();               // 各段正文同样是 Markdown
    var warnings = result.getWarnings();         // 图片跳过、解码失败等非致命问题
}
```

`Mika.extract(type, stream, config)` 接受扩展名或 MIME，例如 `pdf`、`application/pdf`、`docx`、`dot`、`text/markdown`。类型不区分大小写，MIME 参数会被去除。输入流由调用方关闭。

## 输出约定

| 输入 | 输出与结构 |
| --- | --- |
| DOC / DOT / DOCX | Word 6 及以上经 Tika 结构提取后转换为 Markdown；Word 2.x 从 FIB 连续文本区或 FastSave piece table 恢复正文、脚注、页眉页脚和批注，并跳过 WordBasic 宏流；保留可解析的标题、段落、链接、表格、嵌套内容和图片位置；DOCX 使用 SAX 解析以兼容新式图表 |
| XLS / XLSX、PPT / PPTX 等 Tika 支持的格式 | 从结构化 XHTML 转换为 Markdown，避免只提取一串纯文本 |
| PDF | 按文档内容流保留阅读顺序；阿拉伯文、希伯来文等从右到左页面自动切换坐标排序；保留物理页，图片上传链接和 OCR 文字附在所在页末尾 |
| Markdown | UTF-8 原样读取，仅去除文件开头的 BOM |
| TXT | 保留换行并转义 Markdown 特殊字符，避免普通文本被误当成标题或强调 |
| 图片 | 兼容图片块 `[Image](url)OCR文字[ImageEnd]`；可以只上传图片而不开启 OCR |

`getPages()` 保留原接口。PDF 页号从 `0` 开始，空白页保留；DOC / DOCX / Tika 通用提取现在返回一个完整文档段，页号 `0`，不代表 Word 排版页数。`getMarkdown()` 用空行连接非空段，物理页定位请使用 `getPages()`。

普通表格输出 GFM 管道表格。源表没有表头时添加空表头，保留全部数据行。已经从源格式解析出 `rowspan` / `colspan` 或嵌套关系的复杂表格保留为 Markdown 内的 HTML 表格，因此展示端需支持 GFM 及经过净化的 HTML 表格。

## 图片与 OCR

```java
var config = ExtractConfig.defaultConfig()
        .imageExtractMaxSize(10 * 1024 * 1024)
        .maxHandleImageCount(100L)
        .imageUploader(image -> uploadToObjectStore(image.getData(), image.getMimeType().getMimeType()));

// 按需启用调用方的 OCR 服务：
config.ocrUrl("http://your-ocr-service/file/ocr");
```

默认关闭图片上传和 OCR；设置 `imageUploader(...)` 会开启上传，设置 `ocrUrl(...)` 会开启 OCR。只设置 `ocr(true)` 而没有 OCR 后端时返回明确错误。Mika 不会自动调用本机 Tesseract。

默认单张图片上限为 1 MiB，每次提取最多处理 100 张，数量 `-1` 表示不限制；data-extract 当前将单图上限配置为 5 MiB。PDF 中的 JPEG 扫描页会保留原始压缩数据，避免解码后转为 PNG 导致体积膨胀并被错误跳过。复用 `ExtractConfig` 时每次提取使用独立计数，不会耗尽下一次调用的额度。跳过图片不改变 `hasImage()` 对文档包含图片的判断；大小、数量和格式限制会产生 warning。

启用图片上传或 OCR 时，Mika 会把输入暂存到系统临时目录并进行两遍流式解析：第一遍确定正文图片位置，第二遍只读取正文实际引用且位于数量预算内的图片。所有格式的输入默认上限为 100 MiB，可通过 `maxExtractInputSize(...)` 调整；Tika 序列化内容默认上限为 32 MiB，可通过 `maxExtractedContentSize(...)` 调整。临时文件在提取结束时删除，删除失败时登记 JVM 退出清理。嵌入附件不递归提取，应作为独立文件提交。

图片块沿用 `[Image](key)…[ImageEnd]`，供现有下游定位图片地址并把 OCR 正文留在原位置。`key` 是 `ImageUploader` 返回值的原文，Mika 不做 URL 编码。没有上传地址时输出 `[Image]OCR文字[ImageEnd]`。它是 Mika 在 Markdown 上保留的兼容扩展，展示或分块前可按这对边界解析。

PDF 会查找实际绘制的普通图片、嵌套 Form 内的图片和 inline 图片，同页重复使用的同一个图片对象只处理一次。不开启 OCR/上传时不解码图片。无法解码的图片产生 warning 并保留该页文字；OCR、上传抛出的异常仍使提取失败，供上游重试。

OCR 请求沿用 multipart `file` 协议，响应要求包含字符串 `data`，例如 `{"code":0,"data":"识别正文"}`；空字符串是有效结果。使用 UTF-8 读取响应，非 2xx、空响应或缺少 `data` 会失败。连接超时为 10 秒，读取超时为 120 秒。业务 `code` 的成功值因服务而异，目前未校验，沿用既有协议。OCR 文字按普通文本转义，不推测成标题或表格。

## 迁移与限制

- 正文统一为 Markdown，同时保留旧的 `[Image]...[ImageEnd]` 图片边界，兼容现有下游解析器。
- DOC / DOCX 原有的段落序号不再作为 `getPages()` 的分段方式；调用方应按 Markdown 结构分块。
- `mvn package` 生成可直接供 `data-extract/lib` 使用的 shaded JAR；只内嵌并重定位新增的 Flexmark、Jsoup 运行时，Tika、POI、HTTP 和日志依赖仍由应用提供。
- `data-extract` 应在存储层保留 Markdown 空行和图片块边界。
- `hasImage()` 表示存在图片，不代表已识别所有图片。`hasTable()` 表示解析器识别到了表格；PDF 的 Form XObject 不再被误判为表格，PDF 表格结构仍需版面识别。
- PDF 内容流正确的多栏文档会保留阅读顺序；内容流本身错误时仍需版面识别。扫描页拼接、图文精确穿插、公式和 PDF 表格识别不在这轮实现范围内。Word 合并单元格及复杂编号的结构恢复仍受 Tika 输出能力限制，当前不保证还原合并几何或生成原生嵌套列表。
- Mika 可在 JVM 内提取未加密 Word 2.x 的普通保存和 FastSave 文件，并按 FIB 的语言和字符集解码正文、脚注、页眉页脚和批注。域结果、项目符号、分页和旧式单元格控制符会转为 Markdown，WordBasic 宏流不会进入检索正文；旧图形数据不能解码时保留 `[Image][ImageEnd]` 并产生 warning。加密或损坏文件返回明确错误，可再用 LibreOffice 转换后重试。DOCX 批注会转换为 Markdown 内容，嵌入附件不递归并入正文。
- 长文本不再经过 `Tika.parseToString()` 默认长度上限，但当前仍在内存构建完整结果；超大文件的流式处理需要后续升级。

## 验证

```sh
mvn test
mvn package -DskipTests
```

回归测试主要使用程序生成的 DOCX / XLSX / PPTX / PDF / PNG 和内嵌文本，并包含 Apache POI 的 ChartEx 与 Word 2.0 真实样本；OCR 测试使用临时本地 HTTP 服务，不连接业务系统。覆盖中文编码、长文本结尾、表格实际渲染、嵌套表格和图片、PDF 物理页和阅读顺序、图片限制、配置复用、Word 2.0 正文、子文档、FastSave、旧表格、代码页和错误边界，以及后端失败。
