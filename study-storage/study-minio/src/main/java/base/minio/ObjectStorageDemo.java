package base.minio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MinIO / S3 API 对象存储核心模拟: Bucket(桶)创建 → Object Put/Get/Delete → Multipart Upload(分片3步骤)
 * → Pre-signed URL(临时下载签名) → 对象元数据(Metadata/ETag) + ETag(MD5分段摘要)
 *
 * <p>S3 对象存储模型:
 * <pre>
 * Bucket (桶, 全局唯一命名空间)
 *   └── Object (对象, key = 路径)
 *         ├── Data (二进制数据)
 *         ├── Metadata (用户自定义元数据 kv)
 *         ├── ETag (MD5 摘要, 分段上传时为组合摘要)
 *         └── VersionId (版本 ID, 开启版本控制后每次上传生成)
 * </pre>
 *
 * <p>Multipart Upload 三步骤:
 * <ol>
 *   <li>Initiate: 创建 UploadId, 返回给客户端</li>
 *   <li>UploadPart: 逐片上传, 每片返回 ETag (MD5)</li>
 *   <li>Complete: 提交所有分片 ETag 列表, 服务端合并</li>
 * </ol>
 *
 * <p>Pre-signed URL: 将签名信息编码到 URL 查询参数, 持有者可在有效期内访问私有对象
 *
 * @author study-tuling
 */
public class ObjectStorageDemo {

    /* ======================== 数据模型 ======================== */

    /** 桶元数据 */
    static class Bucket {
        String name;
        Instant createdAt;
        boolean versioningEnabled;

        Bucket(String name) {
            this.name = name;
            this.createdAt = Instant.now();
        }

        @Override
        public String toString() {
            return String.format("Bucket{name='%s', createdAt=%s, versioning=%s}",
                    name, createdAt, versioningEnabled);
        }
    }

    /** 对象元数据 */
    static class S3Object {
        String key;
        byte[] data;
        String eTag;
        Map<String, String> metadata;
        Instant lastModified;

        S3Object(String key, byte[] data) {
            this.key = key;
            this.data = data;
            this.eTag = S3Storage.computeMD5(data);
            this.metadata = new LinkedHashMap<>();
            this.lastModified = Instant.now();
        }
    }

    /** 分片上传结果 (单片的 ETag 信息) */
    static class PartETag {
        int partNumber;
        String eTag;

        PartETag(int partNumber, String eTag) {
            this.partNumber = partNumber;
            this.eTag = eTag;
        }

        @Override
        public String toString() {
            return String.format("Part#%d(ETag=%s)", partNumber, eTag);
        }
    }

    /** Multipart Upload 会话 */
    static class MultipartUpload {
        String uploadId;
        String bucketName;
        String objectKey;
        Map<Integer, byte[]> parts;
        Instant initiatedAt;

        MultipartUpload(String uploadId, String bucketName, String objectKey) {
            this.uploadId = uploadId;
            this.bucketName = bucketName;
            this.objectKey = objectKey;
            this.parts = new TreeMap<>();
            this.initiatedAt = Instant.now();
        }
    }

    /* ======================== 存储引擎 ======================== */

    static class S3Storage {
        final Map<String, Bucket> buckets = new LinkedHashMap<>();
        final Map<String, Map<String, S3Object>> objects = new LinkedHashMap<>();
        final Map<String, MultipartUpload> multipartUploads = new ConcurrentHashMap<>();

        /* ---------- Bucket 操作 ---------- */

        Bucket createBucket(String name) {
            if (buckets.containsKey(name)) {
                throw new IllegalArgumentException("Bucket 已存在: " + name);
            }
            Bucket bucket = new Bucket(name);
            buckets.put(name, bucket);
            objects.put(name, new LinkedHashMap<>());
            System.out.printf("  [Bucket] 创建桶 '%s' 成功%n", name);
            return bucket;
        }

        List<String> listBuckets() {
            return new ArrayList<>(buckets.keySet());
        }

        /* ---------- Object 基本操作 ---------- */

        /** PutObject: 上传对象, 返回 ETag */
        String putObject(String bucketName, String key, byte[] data, Map<String, String> userMetadata) {
            checkBucket(bucketName);
            S3Object obj = new S3Object(key, data);
            if (userMetadata != null) {
                obj.metadata.putAll(userMetadata);
            }
            objects.get(bucketName).put(key, obj);
            System.out.printf("  [PutObject] %s/%s, size=%d bytes, ETag=%s%n",
                    bucketName, key, data.length, obj.eTag);
            return obj.eTag;
        }

        /** GetObject: 下载对象 */
        S3Object getObject(String bucketName, String key) {
            checkBucket(bucketName);
            S3Object obj = objects.get(bucketName).get(key);
            if (obj == null) {
                throw new NoSuchElementException("对象不存在: " + bucketName + "/" + key);
            }
            System.out.printf("  [GetObject] %s/%s, size=%d bytes, ETag=%s%n",
                    bucketName, key, obj.data.length, obj.eTag);
            return obj;
        }

        /** DeleteObject: 删除对象 */
        void deleteObject(String bucketName, String key) {
            checkBucket(bucketName);
            S3Object removed = objects.get(bucketName).remove(key);
            if (removed != null) {
                System.out.printf("  [DeleteObject] %s/%s 已删除%n", bucketName, key);
            } else {
                System.out.printf("  [DeleteObject] %s/%s 不存在, 幂等返回%n", bucketName, key);
            }
        }

        /** ListObjects: 列出桶内对象 */
        List<String> listObjects(String bucketName) {
            checkBucket(bucketName);
            return new ArrayList<>(objects.get(bucketName).keySet());
        }

        /* ---------- Multipart Upload ---------- */

        /** Initiate Multipart Upload */
        String initiateMultipartUpload(String bucketName, String key) {
            checkBucket(bucketName);
            String uploadId = UUID.randomUUID().toString();
            MultipartUpload upload = new MultipartUpload(uploadId, bucketName, key);
            multipartUploads.put(uploadId, upload);
            System.out.printf("  [Multipart] Initiate upload '%s' for %s/%s%n", uploadId, bucketName, key);
            return uploadId;
        }

        /** UploadPart: 上传分片, 返回 PartETag */
        PartETag uploadPart(String uploadId, int partNumber, byte[] partData) {
            MultipartUpload upload = multipartUploads.get(uploadId);
            if (upload == null) {
                throw new IllegalArgumentException("Upload 不存在: " + uploadId);
            }
            upload.parts.put(partNumber, partData);
            String partETag = computeMD5(partData);
            PartETag result = new PartETag(partNumber, partETag);
            System.out.printf("  [Multipart] Upload Part#%d, size=%d bytes, ETag=%s%n",
                    partNumber, partData.length, partETag);
            return result;
        }

        /** CompleteMultipartUpload: 合并所有分片 */
        String completeMultipartUpload(String uploadId, List<PartETag> partETags) {
            MultipartUpload upload = multipartUploads.remove(uploadId);
            if (upload == null) {
                throw new IllegalArgumentException("Upload 不存在: " + uploadId);
            }

            // 合并所有分片数据
            int totalSize = upload.parts.values().stream().mapToInt(p -> p.length).sum();
            byte[] merged = new byte[totalSize];
            int offset = 0;
            List<String> partETagList = new ArrayList<>();
            for (Map.Entry<Integer, byte[]> entry : upload.parts.entrySet()) {
                byte[] part = entry.getValue();
                System.arraycopy(part, 0, merged, offset, part.length);
                offset += part.length;
                partETagList.add(computeMD5(part));
            }

            // 计算组合 ETag: MD5(MD5(p1)+MD5(p2)+...+MD5(pn)) + "-" + n
            StringBuilder combinedHex = new StringBuilder();
            for (String partETagHex : partETagList) {
                combinedHex.append(partETagHex);
            }
            String combinedETag = computeMD5(combinedHex.toString().getBytes(StandardCharsets.UTF_8))
                    + "-" + partETagList.size();

            // 存储合并后的对象
            S3Object obj = new S3Object(upload.objectKey, merged);
            obj.eTag = combinedETag;
            objects.get(upload.bucketName).put(upload.objectKey, obj);

            System.out.printf("  [Multipart] Complete upload, 合并 %d 片, 总大小=%d bytes, 组合ETag=%s%n",
                    upload.parts.size(), totalSize, combinedETag);
            return combinedETag;
        }

        /** AbortMultipartUpload: 取消分片上传 */
        void abortMultipartUpload(String uploadId) {
            MultipartUpload removed = multipartUploads.remove(uploadId);
            if (removed != null) {
                System.out.printf("  [Multipart] Abort upload '%s', 已清理 %d 片%n",
                        uploadId, removed.parts.size());
            }
        }

        /* ---------- Pre-signed URL ---------- */

        /**
         * 生成 Pre-signed URL: 临时代签名的对象下载链接.
         * 真实场景: HMAC-SHA256 签名 + 过期时间 + Base64Encode.
         * 此处简化为: bucket/key?signature=xxx&expires=xxx
         */
        String generatePresignedUrl(String bucketName, String key, long expireSeconds) {
            checkBucket(bucketName);
            if (!objects.get(bucketName).containsKey(key)) {
                throw new NoSuchElementException("对象不存在: " + bucketName + "/" + key);
            }
            String signature = computeMD5((bucketName + "/" + key + System.currentTimeMillis()).getBytes());
            long expires = System.currentTimeMillis() / 1000 + expireSeconds;
            String url = String.format("https://%s.s3.local/%s?X-Amz-Signature=%s&X-Amz-Expires=%d",
                    bucketName, key, signature, expires);
            System.out.printf("  [PreSignedURL] 生成(%ds有效): %s%n", expireSeconds, url);
            return url;
        }

        /* ---------- 辅助方法 ---------- */

        private void checkBucket(String bucketName) {
            if (!buckets.containsKey(bucketName)) {
                throw new IllegalArgumentException("Bucket 不存在: " + bucketName);
            }
        }

        /** 计算 MD5 十六进制摘要 (模拟 ETag) */
        static String computeMD5(byte[] data) {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(data);
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /* ======================== 演示入口 ======================== */

    public static void main(String[] args) {
        System.out.println("========== MinIO S3 对象存储核心模拟 ==========\n");

        S3Storage s3 = new S3Storage();

        bucketDemo(s3);
        objectCRUDDemo(s3);
        multipartUploadDemo(s3);
        presignedUrlDemo(s3);
        metadataETagDemo(s3);

        System.out.println("\n========== 演示完毕 ==========");
    }

    /** 桶(Bucket)创建与列表 */
    static void bucketDemo(S3Storage s3) {
        System.out.println("--- 1. Bucket 操作 ---");
        s3.createBucket("my-bucket");
        s3.createBucket("backup-bucket");
        System.out.printf("  桶列表: %s%n%n", s3.listBuckets());
    }

    /** Object Put/Get/Delete 基础 CRUD */
    static void objectCRUDDemo(S3Storage s3) {
        System.out.println("--- 2. Object 基本操作 (Put/Get/Delete) ---");

        // Put
        byte[] content = "Hello, MinIO S3 Object Storage!".getBytes(StandardCharsets.UTF_8);
        s3.putObject("my-bucket", "docs/readme.txt", content,
                Map.of("Content-Type", "text/plain", "Author", "admin"));

        byte[] jsonContent = "{\"id\":1,\"name\":\"张三\"}".getBytes(StandardCharsets.UTF_8);
        s3.putObject("my-bucket", "data/user.json", jsonContent, null);

        // Get
        S3Object obj = s3.getObject("my-bucket", "docs/readme.txt");
        System.out.printf("  读取内容: %s%n", new String(obj.data, StandardCharsets.UTF_8));

        // Delete
        s3.deleteObject("my-bucket", "data/user.json");
        System.out.printf("  剩余对象: %s%n%n", s3.listObjects("my-bucket"));
    }

    /** Multipart Upload 分片上传: Initiate → UploadPart → Complete (3步骤) */
    static void multipartUploadDemo(S3Storage s3) {
        System.out.println("--- 3. Multipart Upload (分片上传) ---");

        String bucketName = "my-bucket";
        String objectKey = "bigfiles/video.mp4";

        // 模拟一个 20MB 的大文件, 分 5 片上传 (每片 4MB)
        int totalSize = 20 * 1024 * 1024; // 20MB
        int partSize = 4 * 1024 * 1024;    // 4MB per part
        int totalParts = totalSize / partSize;

        // 步骤1: Initiate
        String uploadId = s3.initiateMultipartUpload(bucketName, objectKey);

        // 步骤2: UploadPart (逐个分片)
        List<PartETag> partETags = new ArrayList<>();
        Random random = new Random(42);
        for (int i = 1; i <= totalParts; i++) {
            byte[] partData = new byte[partSize];
            random.nextBytes(partData);
            PartETag partETag = s3.uploadPart(uploadId, i, partData);
            partETags.add(partETag);
        }
        System.out.printf("  已上传 %d 片, PartETags: %s%n", partETags.size(), partETags);

        // 步骤3: Complete
        String finalETag = s3.completeMultipartUpload(uploadId, partETags);
        System.out.printf("  最终组合 ETag: %s%n", finalETag);

        // 验证: Get 合并后的对象
        S3Object merged = s3.getObject(bucketName, objectKey);
        System.out.printf("  合并对象大小: %d bytes (期望 %d)%n%n", merged.data.length, totalSize);
    }

    /** Pre-signed URL 临时签名下载 */
    static void presignedUrlDemo(S3Storage s3) {
        System.out.println("--- 4. Pre-signed URL (临时签名下载链接) ---");

        String url1 = s3.generatePresignedUrl("my-bucket", "docs/readme.txt", 3600);
        System.out.printf("  私有对象临时下载: %s%n", url1);

        String url2 = s3.generatePresignedUrl("my-bucket", "docs/readme.txt", 60);
        System.out.printf("  短时效(60s)链接: %s%n", url2);

        System.out.println("  签名机制: MD5(bucket+key+timestamp) 作为临时凭证, 过期后自动失效");
        System.out.println("  真实场景: AWS Signature V4 = HMAC-SHA256 + AccessKey + SecretKey + Region + Service\n");
    }

    /** 对象元数据 Metadata + ETag (MD5 摘要) */
    static void metadataETagDemo(S3Storage s3) {
        System.out.println("--- 5. 对象元数据 & ETag 摘要 ---");

        // 上传时携带元数据
        byte[] imageData = new byte[1024];
        new Random(7).nextBytes(imageData);
        s3.putObject("my-bucket", "images/logo.png", imageData,
                Map.of("Content-Type", "image/png",
                       "x-amz-meta-owner", "team-alpha",
                       "x-amz-meta-version", "v2.1"));

        S3Object obj = s3.getObject("my-bucket", "images/logo.png");
        System.out.printf("  对象大小: %d bytes%n", obj.data.length);
        System.out.printf("  ETag (MD5): %s%n", obj.eTag);
        System.out.printf("  最后修改: %s%n", obj.lastModified);
        System.out.println("  用户元数据:");
        for (Map.Entry<String, String> entry : obj.metadata.entrySet()) {
            System.out.printf("    %s = %s%n", entry.getKey(), entry.getValue());
        }

        // ETag 完整性校验
        String recalcMD5 = S3Storage.computeMD5(obj.data);
        System.out.printf("  ETag 校验: %s == %s -> %s%n",
                obj.eTag, recalcMD5, obj.eTag.equals(recalcMD5) ? "PASS" : "FAIL");
        System.out.println("  多分段 ETag 格式: MD5(MD5(p1)+MD5(p2)+...+MD5(pn))-n\n");
    }
}