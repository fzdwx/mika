package ai.minum.extract;

public interface ImageUploader {

    /**
     * 上传图片
     *
     * @param imageResult
     * @return 文件 key
     */
    String upload(ImageResult imageResult) throws Exception;
}
