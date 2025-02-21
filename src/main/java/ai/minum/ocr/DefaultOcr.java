package ai.minum.ocr;

import cn.hutool.json.JSONUtil;
import lombok.Data;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class DefaultOcr {
    private String url;

    public static DefaultOcr of(String url) {
        DefaultOcr ocr = new DefaultOcr();
        ocr.url = url;
        return ocr;
    }

    public String doOrc(InputStream stream) {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost upload = new HttpPost(url);
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addBinaryBody(
                "file",
                stream,
                ContentType.APPLICATION_OCTET_STREAM,
                "image.png");
        HttpEntity multipart = builder.build();
        upload.setEntity(multipart);
        CloseableHttpResponse response = null;
        try {
            response = httpClient.execute(upload);
            HttpEntity responseEntity = response.getEntity();
            if (responseEntity != null) {
                String content = "\n[Image]";
                String responseString = EntityUtils.toString(responseEntity);
                OcrResult result = OcrResult.from(responseString);
                content = content + result.getData();
                return content;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                try {
                    stream.close();
                }catch (Exception ignore){}
                try {
                    httpClient.close();
                }catch (Exception ignore){}
                try {
                    if (response != null) {
                        response.close();
                    }
                }catch (Exception ignore){}
            } catch (Exception ignore) {

            }
        }

        return "";
    }

    public String doOrc(byte[] pictureData) {
        return doOrc(new ByteArrayInputStream(pictureData));
    }
}

@Data
class OcrResult {
    int code;
    String message;
    String data;

    public static OcrResult from(String s) {
        return JSONUtil.toBean(s, OcrResult.class);
    }
}