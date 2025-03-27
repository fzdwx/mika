package ai.minum;

import ai.minum.extract.ExtractConfig;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

class MikaTest {

    @Test
    void extract() throws FileNotFoundException {
        String path = "/home/like/project/mika/私人股权投资.pdf";
        FileInputStream stream = new FileInputStream(path);
        cost(() -> {
            ExtractConfig config = ExtractConfig
                    .defaultConfig()
                    .ocrUrl("http://192.168.50.191:15234/file/ocr")
                    .imageExtractMaxSize(Integer.MAX_VALUE)
                    .ocr(true);
            var result = Mika.extract("pdf", new BufferedInputStream(stream), config);
            System.out.println(result);
        });
    }

    static void cost(Runnable runnable) {
        long start = System.currentTimeMillis();
        runnable.run();
        long end = System.currentTimeMillis();
        System.out.println("cost time: " + (end - start) + "ms");
    }


    @Test
    void test222() throws FileNotFoundException {
        String path = "/home/like/project/mika/简历-Java开发.pdf";
        FileInputStream stream = new FileInputStream(path);
        try (PDDocument doc = Loader.loadPDF(stream.readAllBytes())) {
            int pageNum = 0;
            int imageCounter = 1;

            // 遍历所有页面
            for (PDPage page : doc.getPages()) {
                pageNum++;
                PDResources resources = page.getResources();

                // 获取页面中的所有XObject（图像和表单）
                Iterable<COSName> xObjectNames = resources.getXObjectNames();
                for (COSName name : xObjectNames) {
                    if (resources.isImageXObject(name)) {
                        // 提取图像对象
                        PDImageXObject image = (PDImageXObject) resources.getXObject(name);
                        BufferedImage bufferedImage = image.getImage();

                        // 保存为PNG文件
                        String outputPath = String.format("image_page%d_%d.png", pageNum, imageCounter++);
                        ImageIO.write(bufferedImage, "PNG", new File(outputPath));
                        System.out.println("保存图片至：" + outputPath);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
