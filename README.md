# Mika

Extract text from files.

```java
String path = "/home/like/project/mika/Tronly_操作手册_PM100年度保养计划的维护操作手册.doc";
FileInputStream stream = new FileInputStream(path);
ExtractConfig config = ExtractConfig
        .defaultConfig()
        .ocrUrl("http://192.168.50.191:15234/file/ocr")
        .ocr(false);
var result = Mika.extract(new BufferedInputStream(stream), config);
System.out.println(result);
```