# Mika

Extract text from files.

```java
String path = "/path/to/hello.docx";
FileInputStream stream = new FileInputStream(path);
var result = Mika.extract(new BufferedInputStream(stream),false);
System.out.println(result);
```