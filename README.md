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
| DOC / DOT / DOCX | Word 6 及以上经 Tika 结构提取后转换为 Markdown，旧 DOC 域只保留实际显示值；Word 2.x 从 FIB 连续文本区或 FastSave piece table 恢复正文、脚注、页眉页脚和批注，并跳过 WordBasic 宏流；保留可解析的标题、段落、链接、表格、嵌套内容、图片位置及作者填写的图片替代描述；DOCX 使用 SAX 解析以兼容新式图表，并从 OOXML 补回合并单元格几何 |
| XLS / XLSX、PPT / PPTX 等 Tika 支持的格式 | 从结构化 XHTML 转换为 Markdown，避免只提取一串纯文本 |
| PDF | 按文档内容流保留阅读顺序；逐字断行或阿拉伯文、希伯来文等页面自动切换坐标排序，并修复重叠文字层幸存后缀的粘词；读取结构树 `/ActualText` 纠正错误字符，并保留 Figure / Formula 的 `/Alt` 描述；按物理页补充批注、安全链接和 AcroForm / XFA 当前表单值，图片上传链接和 OCR 文字附在所在页末尾 |
| Markdown | UTF-8 原样读取，仅去除文件开头的 BOM |
| TXT | 保留换行并转义 Markdown 特殊字符，避免普通文本被误当成标题或强调 |
| 图片 | 兼容图片块 `[Image](url)OCR文字[ImageEnd]`；可以只上传图片而不开启 OCR |

`getPages()` 保留原接口。PDF 页号从 `0` 开始，空白页保留；DOC / DOCX / Tika 通用提取现在返回一个完整文档段，页号 `0`，不代表 Word 排版页数。`getMarkdown()` 用空行连接非空段，物理页定位请使用 `getPages()`。

普通表格输出紧凑的 GFM 管道表格，不按最长单元格补齐空格和分隔横线。源表没有表头时添加空表头，保留全部数据行。DOCX 的 `gridSpan`、`vMerge` 和旧式 `hMerge` 会转换成 `colspan` / `rowspan`；这类合并表格和嵌套表格保留为 Markdown 内经过净化的 HTML 表格，因为 GFM 管道语法不能表达合并关系。不可见的 Word 书签和空格式节点不会进入 HTML 表格。展示端需同时支持 GFM 和 Markdown 内 HTML。下划线和修订插入没有通用 Markdown 语法，输出时只保留可见文字，不生成 Flexmark 专用的 `++文字++`。

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

DOCX 会把输入暂存到系统临时目录，以便在 Tika 解析后读取 OOXML 合并关系；启用图片上传或 OCR 时，其他 Tika 格式也会暂存，并进行两遍流式解析：第一遍确定正文图片位置，第二遍只读取正文实际引用且位于数量预算内的图片。所有格式的输入默认上限为 100 MiB，可通过 `maxExtractInputSize(...)` 调整；最终提取内容默认上限为 32 MiB，可通过 `maxExtractedContentSize(...)` 调整，Tika 路径还会在 XHTML 序列化阶段提前执行同一上限。临时文件在提取结束时删除，删除失败时登记 JVM 退出清理。嵌入附件不递归提取，应作为独立文件提交。

图片块沿用 `[Image](key)…[ImageEnd]`，供现有下游定位图片地址并把 OCR 正文留在原位置。`key` 是 `ImageUploader` 返回值的原文，Mika 不做 URL 编码。没有上传地址时输出 `[Image]OCR文字[ImageEnd]`。它是 Mika 在 Markdown 上保留的兼容扩展，展示或分块前可按这对边界解析。

Word 图片的作者替代描述不依赖 OCR 或上传。DOCX 从 Tika 的 `alt` 恢复，旧 DOC 从 HWPF 图片属性旁路恢复；有可靠位置时写进对应图片块，无法可靠绑定时汇总到 `### Image descriptions`。纯文件名、图片素材 URL、相机编号和 Office 自动对象名不进入正文。若同时开启 OCR 或上传，替代描述会与既有 key、OCR 文字共存在同一个图片块中。

PDF 会查找实际绘制的普通图片、嵌套 Form 内的图片和 inline 图片，同页重复使用的同一个图片对象只处理一次。不开启 OCR/上传时不解码图片。JPEG 和 JPEG2000 扫描页保留原始压缩数据，OCR 请求携带对应的 MIME 和扩展名；无法解码的其他图片产生 warning 并保留该页文字。OCR、上传抛出的异常仍使提取失败，供上游重试。

OCR 请求沿用 multipart `file` 协议，响应要求包含字符串 `data`，例如 `{"code":0,"data":"识别正文"}`；空字符串是有效结果。使用 UTF-8 读取响应，非 2xx、空响应或缺少 `data` 会失败。连接超时为 10 秒，读取超时为 120 秒。业务 `code` 的成功值因服务而异，目前未校验，沿用既有协议。OCR 文字按普通文本转义，不推测成标题或表格。

## 迁移与限制

- 正文统一为 Markdown，同时保留旧的 `[Image]...[ImageEnd]` 图片边界，兼容现有下游解析器。
- DOC / DOCX 原有的段落序号不再作为 `getPages()` 的分段方式；调用方应按 Markdown 结构分块。
- `mvn package` 生成可直接供 `data-extract/lib` 使用的 shaded JAR；只内嵌并重定位新增的 Flexmark、Jsoup 运行时，Tika、POI、HTTP 和日志依赖仍由应用提供。
- `data-extract` 应在存储层保留 Markdown 空行和图片块边界。
- `hasImage()` 表示存在图片，不代表已识别所有图片。`hasTable()` 表示解析器识别到了表格；PDF 的 Form XObject 不再被误判为表格，PDF 表格结构仍需版面识别。
- PDF 的 AcroForm / XFA 当前值以 `### Form fields` 下的 Markdown 列表附在控件所属物理页；选择框优先输出显示值，关闭状态、签名二进制和控制字符不会污染正文。
- PDF 的可见批注以 `### Annotations` 附在所属物理页；保留作者、主题、正文和经过协议、主机校验的 HTTP(S) / mailto 链接，Widget 与 Popup 不重复输出。PDF 2.0 带 UTF-8 BOM 的批注字符串会按 UTF-8 解码；单页条数、字段、URI 和总字符都有边界，异常元数据不会无限挤占正文。
- Tagged PDF 的 Figure / Formula 替代描述以 `### Accessibility descriptions` 附在所属物理页；`/ActualText` 仍只用于文本替换，两种语义不会混用。替代描述也按单页条数、单项和总字符限制资源。
- PDF 内容流正确的多栏文档会保留阅读顺序；内容流本身错误时仍需版面识别。扫描页拼接、图文精确穿插、公式和 PDF 表格识别不在这轮实现范围内。DOCX 正文表格能恢复 OOXML 的横向和纵向合并；旧 DOC 合并几何以及 Word 复杂编号仍受 Tika 输出能力限制，当前不保证生成原生嵌套列表。
- Mika 可在 JVM 内提取未加密 Word 2.x 的普通保存和 FastSave 文件，并按 FIB 的语言和字符集解码正文、脚注、页眉页脚和批注。域结果、项目符号、分页和旧式单元格控制符会转为 Markdown，WordBasic 宏流不会进入检索正文；旧图形数据不能解码时保留 `[Image][ImageEnd]` 并产生 warning。加密或损坏文件返回明确错误，可再用 LibreOffice 转换后重试。DOCX 批注以独立 `### Comments` 保留作者、锚定正文和回复层级，不再伪装成普通正文；AltChunk 正文会转换为 Markdown，AltChunk 内图仍在正文位置，普通嵌入附件不递归并入正文。
- 长文本不再经过 `Tika.parseToString()` 默认长度上限，但当前仍在内存构建完整结果；超大文件的流式处理需要后续升级。

## 验证

```sh
mvn test
mvn package -DskipTests
```

回归测试主要使用程序生成的 DOCX / XLSX / PPTX / PDF / PNG 和内嵌文本，并包含 Apache POI 的 ChartEx、旧 DOC 域、旧 DOC 图片替代文字和 Word 2.0 真实样本；OCR 测试使用临时本地 HTTP 服务，不连接业务系统。覆盖中文编码、长文本结尾、横向和纵向合并表格的实际渲染、嵌套表格和图片、PDF 物理页、阅读顺序、重叠文字、批注、表单和无障碍描述、图片限制、配置复用、Word 2.0 正文、子文档、FastSave、旧表格、代码页和错误边界，以及后端失败。
