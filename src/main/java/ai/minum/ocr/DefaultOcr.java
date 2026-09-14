package ai.minum.ocr;

import cn.hutool.json.JSONUtil;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class DefaultOcr {
    private String url;

    public static DefaultOcr of(String url) {
        DefaultOcr ocr = new DefaultOcr();
        ocr.url = url;
        return ocr;
    }

    public String doOrc(InputStream stream) {
        return doOrc(stream, ContentType.APPLICATION_OCTET_STREAM, "image.png");
    }

    private String doOrc(InputStream stream, ContentType contentType, String filename) {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setRetryHandler(new DefaultHttpRequestRetryHandler(2, false))
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(10_000).setConnectionRequestTimeout(10_000)
                        .setSocketTimeout(120_000).build())
                .build();
        HttpPost upload = new HttpPost(url);
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addBinaryBody(
                "file",
                stream,
                contentType,
                filename);
        HttpEntity multipart = builder.build();
        upload.setEntity(multipart);
        CloseableHttpResponse response = null;
        try {
            response = httpClient.execute(upload);
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("OCR backend returned HTTP " + status);
            }
            HttpEntity responseEntity = response.getEntity();
            if (responseEntity != null) {
                String responseString = EntityUtils.toString(responseEntity, StandardCharsets.UTF_8);
                OcrResult result = OcrResult.from(responseString);
                if (result == null || result.getData() == null) {
                    throw new IOException("OCR backend response is missing text data");
                }
                return result.getData();
            }
            throw new IOException("OCR backend returned an empty response");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                try {
                    stream.close();
                } catch (Exception ignore) {
                }
                try {
                    httpClient.close();
                } catch (Exception ignore) {
                }
                try {
                    if (response != null) {
                        response.close();
                    }
                } catch (Exception ignore) {
                }
            } catch (Exception ignore) {

            }
        }

    }

    public String doOrc(byte[] pictureData) {
        return doOrc(new ByteArrayInputStream(pictureData));
    }

    public String doOrc(byte[] pictureData, String mimeType) {
        String normalized = mimeType == null ? "application/octet-stream" : mimeType;
        String extension = switch (normalized.toLowerCase(java.util.Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/jp2" -> "jp2";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            case "image/tiff" -> "tiff";
            case "image/webp" -> "webp";
            default -> "png";
        };
        return doOrc(new ByteArrayInputStream(pictureData), ContentType.create(normalized),
                "image." + extension);
    }
}

class OcrResult {
    int code;
    String message;
    String data;

    public static OcrResult from(String s) {
        return JSONUtil.toBean(s, OcrResult.class);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}
