package base.minio;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MinIO 桶策略与版本控制: Policy(IAM策略) 权限结构(Effect/Principal/Action/Resource)
 * + Bucket Policy vs ACL + Versioning(保留旧版本+DeleteMarker)
 * + 生命周期规则(Lifecycle Rule自动过期) + 对象锁定(WORM Compliance Mode)
 *
 * <p><b>IAM Policy 权限结构 (JSON):</b>
 * <pre>
 * {
 *   "Version": "2012-10-17",
 *   "Statement": [{
 *     "Effect": "Allow" | "Deny",
 *     "Principal": "*" | "arn:aws:iam::account:user/xxx",
 *     "Action": ["s3:GetObject", "s3:PutObject"],
 *     "Resource": ["arn:aws:s3:::bucket-name/*"],
 *     "Condition": { "IpAddress": { "aws:SourceIp": "10.0.0.0/8" } }
 *   }]
 * }
 * </pre>
 *
 * <p><b>Policy vs ACL:</b>
 * <ul>
 *   <li>ACL: 对象级权限, 仅预定义组 (READ/WRITE/FULL_CONTROL), 粒度粗</li>
 *   <li>Policy: 桶级/账号级, JSON 格式, 支持 Condition 条件, 粒度细, 推荐使用</li>
 * </ul>
 *
 * <p><b>Versioning:</b> 开启后每次 Put 生成新 VersionId, Delete 时只插入 DeleteMarker,
 * 不会真正删除数据, 可通过指定 VersionId 恢复.
 *
 * <p><b>对象锁定 (WORM):</b> Compliance Mode 下任何人(包括 root)都无法删除,
 * Governance Mode 下有特殊权限可删除.
 *
 * @author study-tuling
 */
public class PolicyVersionDemo {

    /* ======================== 数据模型 ======================== */

    /** 权限效果 */
    enum Effect {
        Allow("允许"),
        Deny("拒绝");

        final String label;

        Effect(String label) { this.label = label; }
    }

    /** S3 操作 */
    enum S3Action {
        s3_GetObject("下载对象"),
        s3_PutObject("上传对象"),
        s3_DeleteObject("删除对象"),
        s3_ListBucket("列出桶"),
        s3_GetBucketPolicy("获取桶策略"),
        s3_PutBucketPolicy("设置桶策略"),
        s3_GetObjectVersion("获取对象版本"),
        s3_DeleteObjectVersion("删除对象版本"),
        s3_BypassGovernanceRetention("绕过治理保留期");

        final String label;

        S3Action(String label) { this.label = label; }
    }

    /** IAM 策略声明 */
    static class PolicyStatement {
        Effect effect;
        List<String> principals;
        List<S3Action> actions;
        List<String> resources;
        Map<String, String> conditions; // 简化条件: key=value

        PolicyStatement(Effect effect, List<String> principals,
                        List<S3Action> actions, List<String> resources) {
            this.effect = effect;
            this.principals = principals;
            this.actions = actions;
            this.resources = resources;
            this.conditions = new LinkedHashMap<>();
        }
    }

    /** 桶策略 (一组 Statement) */
    static class BucketPolicy {
        String bucketName;
        List<PolicyStatement> statements;

        BucketPolicy(String bucketName) {
            this.bucketName = bucketName;
            this.statements = new ArrayList<>();
        }
    }

    /** 对象版本 */
    static class ObjectVersion {
        String versionId;
        byte[] data;
        String eTag;
        Instant createdAt;
        boolean isDeleteMarker;

        ObjectVersion(String versionId, byte[] data, String eTag) {
            this.versionId = versionId;
            this.data = data;
            this.eTag = eTag;
            this.createdAt = Instant.now();
            this.isDeleteMarker = false;
        }

        /** 创建 DeleteMarker (数据为空) */
        static ObjectVersion deleteMarker(String versionId) {
            ObjectVersion dm = new ObjectVersion(versionId, new byte[0], "dm-" + versionId);
            dm.isDeleteMarker = true;
            return dm;
        }

        @Override
        public String toString() {
            if (isDeleteMarker) {
                return String.format("DeleteMarker(v%s, %s)", versionId, createdAt);
            }
            return String.format("Version(v%s, %d bytes, %s)", versionId,
                    data != null ? data.length : 0, createdAt);
        }
    }

    /** 对象锁定模式 */
    enum RetentionMode {
        GOVERNANCE("治理模式: 有特殊权限可删除"),
        COMPLIANCE("合规模式: 任何人包括 root 都无法删除");

        final String description;

        RetentionMode(String description) { this.description = description; }
    }

    /** 对象锁定配置 */
    static class ObjectLock {
        RetentionMode mode;
        Instant retainUntilDate;

        ObjectLock(RetentionMode mode, Instant retainUntilDate) {
            this.mode = mode;
            this.retainUntilDate = retainUntilDate;
        }
    }

    /** 生命周期规则 */
    static class LifecycleRule {
        String ruleId;
        String prefix;           // 匹配前缀
        int expirationDays;      // 过期天数
        boolean enabled;

        LifecycleRule(String ruleId, String prefix, int expirationDays) {
            this.ruleId = ruleId;
            this.prefix = prefix;
            this.expirationDays = expirationDays;
            this.enabled = true;
        }
    }

    /* ======================== Versioning 存储引擎 ======================== */

    static class VersionedBucket {
        final String name;
        boolean versioningEnabled;
        BucketPolicy policy;
        ObjectLock defaultLock;
        List<LifecycleRule> lifecycleRules;

        /** key → 版本链表 (最新在前) */
        final Map<String, List<ObjectVersion>> versions;

        VersionedBucket(String name) {
            this.name = name;
            this.versioningEnabled = false;
            this.versions = new LinkedHashMap<>();
            this.lifecycleRules = new ArrayList<>();
        }

        /** PutObject with Versioning */
        ObjectVersion putObject(String key, byte[] data, String eTag) {
            String versionId = UUID.randomUUID().toString().substring(0, 8);
            ObjectVersion version = new ObjectVersion(versionId, data, eTag);

            versions.computeIfAbsent(key, k -> new ArrayList<>()).add(0, version);
            System.out.printf("  [PutObject v%s] %s/%s, %d bytes%n", versionId, name, key, data.length);
            return version;
        }

        /** GetObject: 返回最新非 DeleteMarker 版本 */
        ObjectVersion getObject(String key) {
            List<ObjectVersion> versionList = versions.get(key);
            if (versionList == null || versionList.isEmpty()) return null;

            for (ObjectVersion v : versionList) {
                if (!v.isDeleteMarker) return v;
            }
            return null; // 全部是 DeleteMarker
        }

        /** GetObjectVersion: 按 VersionId 精确获取 */
        ObjectVersion getObjectVersion(String key, String versionId) {
            List<ObjectVersion> versionList = versions.get(key);
            if (versionList == null) return null;

            for (ObjectVersion v : versionList) {
                if (v.versionId.equals(versionId)) return v;
            }
            return null;
        }

        /** DeleteObject: 插入 DeleteMarker (不做物理删除) */
        ObjectVersion deleteObject(String key) {
            String versionId = UUID.randomUUID().toString().substring(0, 8);
            ObjectVersion deleteMarker = ObjectVersion.deleteMarker(versionId);

            versions.computeIfAbsent(key, k -> new ArrayList<>()).add(0, deleteMarker);
            System.out.printf("  [DeleteMarker v%s] %s/%s%n", versionId, name, key);
            return deleteMarker;
        }

        /** 永久删除指定版本 */
        void deleteVersion(String key, String versionId) {
            List<ObjectVersion> versionList = versions.get(key);
            if (versionList != null) {
                versionList.removeIf(v -> v.versionId.equals(versionId));
                System.out.printf("  [DeleteVersion] %s/%s, versionId=%s%n", name, key, versionId);
            }
        }

        /** 列出所有版本 */
        void listVersions(String key) {
            List<ObjectVersion> versionList = versions.get(key);
            System.out.printf("  版本列表 for %s/%s:%n", name, key);
            if (versionList == null || versionList.isEmpty()) {
                System.out.println("    (无版本)");
                return;
            }
            for (ObjectVersion v : versionList) {
                String marker = v.isDeleteMarker ? " [DELETE MARKER]" : "";
                System.out.printf("    - %s%s%n", v, marker);
            }
        }

        /** 执行生命周期规则: 删除过期版本 (超过 expirationDays) */
        void applyLifecycleRules() {
            Instant now = Instant.now();
            for (LifecycleRule rule : lifecycleRules) {
                if (!rule.enabled) continue;
                int expired = 0;
                for (Map.Entry<String, List<ObjectVersion>> entry : versions.entrySet()) {
                    if (!entry.getKey().startsWith(rule.prefix)) continue;
                    Iterator<ObjectVersion> it = entry.getValue().iterator();
                    while (it.hasNext()) {
                        ObjectVersion v = it.next();
                        long ageDays = Duration.between(v.createdAt, now).toDays();
                        if (ageDays >= rule.expirationDays) {
                            it.remove();
                            expired++;
                        }
                    }
                }
                if (expired > 0) {
                    System.out.printf("  [Lifecycle] 规则 '%s' 过期了 %d 个版本 (前缀=%s, TTL=%d天)%n",
                            rule.ruleId, expired, rule.prefix, rule.expirationDays);
                }
            }
        }
    }

    /* ======================== 策略评估引擎 ======================== */

    static class PolicyEvaluator {
        /**
         * 评估请求是否被允许.
         *
         * <p>评估逻辑:
         * <ol>
         *   <li>检查是否有显式 Deny → 直接拒绝</li>
         *   <li>检查是否有显式 Allow → 允许</li>
         *   <li>默认: 隐式拒绝</li>
         * </ol>
         */
        static boolean evaluate(BucketPolicy policy, String principal, S3Action action, String resource) {
            boolean hasAllow = false;

            for (PolicyStatement stmt : policy.statements) {
                // 检查 Principal 匹配
                if (!matchesPrincipal(stmt.principals, principal)) continue;
                // 检查 Action 匹配
                if (!stmt.actions.contains(action)) continue;
                // 检查 Resource 匹配
                if (!matchesResource(stmt.resources, resource)) continue;

                if (stmt.effect == Effect.Deny) {
                    System.out.printf("  [Policy] DENY: principal=%s, action=%s, resource=%s%n",
                            principal, action, resource);
                    return false;
                }
                if (stmt.effect == Effect.Allow) {
                    hasAllow = true;
                }
            }

            if (hasAllow) {
                System.out.printf("  [Policy] ALLOW: principal=%s, action=%s, resource=%s%n",
                        principal, action, resource);
            } else {
                System.out.printf("  [Policy] IMPLICIT DENY: principal=%s, action=%s, resource=%s%n",
                        principal, action, resource);
            }
            return hasAllow;
        }

        private static boolean matchesPrincipal(List<String> principals, String principal) {
            return principals.contains("*") || principals.contains(principal);
        }

        private static boolean matchesResource(List<String> resources, String resource) {
            for (String r : resources) {
                if (r.endsWith("*")) {
                    String prefix = r.substring(0, r.length() - 1);
                    if (resource.startsWith(prefix)) return true;
                } else if (r.equals(resource)) {
                    return true;
                }
            }
            return false;
        }
    }

    /* ======================== 演示入口 ======================== */

    public static void main(String[] args) {
        System.out.println("========== MinIO 桶策略与版本控制模拟 ==========\n");

        policyDemo();
        aclVsPolicyDemo();
        versioningDemo();
        lifecycleDemo();
        objectLockDemo();

        System.out.println("\n========== 演示完毕 ==========");
    }

    /** IAM Policy 权限结构 & 评估 */
    static void policyDemo() {
        System.out.println("--- 1. Bucket Policy 权限策略 ---");

        BucketPolicy policy = new BucketPolicy("my-bucket");

        // Statement 1: 允许 admin 用户完全访问
        PolicyStatement adminStmt = new PolicyStatement(Effect.Allow,
                List.of("arn:aws:iam::123:user/admin"),
                List.of(S3Action.s3_GetObject, S3Action.s3_PutObject, S3Action.s3_DeleteObject),
                List.of("arn:aws:s3:::my-bucket/*"));
        policy.statements.add(adminStmt);

        // Statement 2: 拒绝来自 192.168.1.x 的访问
        PolicyStatement denyStmt = new PolicyStatement(Effect.Deny,
                List.of("*"),
                List.of(S3Action.s3_DeleteObject),
                List.of("arn:aws:s3:::my-bucket/secret/*"));
        denyStmt.conditions.put("aws:SourceIp", "192.168.1.0/24");
        policy.statements.add(denyStmt);

        // Statement 3: 允许所有人读取 public 前缀
        PolicyStatement publicRead = new PolicyStatement(Effect.Allow,
                List.of("*"),
                List.of(S3Action.s3_GetObject),
                List.of("arn:aws:s3:::my-bucket/public/*"));
        policy.statements.add(publicRead);

        System.out.println("  策略结构:");
        System.out.println("    Statement[0]: Allow admin 完全访问 my-bucket/*");
        System.out.println("    Statement[1]: Deny 所有人删除 my-bucket/secret/*");
        System.out.println("    Statement[2]: Allow 所有人读取 my-bucket/public/*");

        // 评估测试
        System.out.println("\n  权限评估测试:");
        PolicyEvaluator.evaluate(policy, "arn:aws:iam::123:user/admin",
                S3Action.s3_GetObject, "arn:aws:s3:::my-bucket/data.json");
        PolicyEvaluator.evaluate(policy, "arn:aws:iam::123:user/guest",
                S3Action.s3_DeleteObject, "arn:aws:s3:::my-bucket/secret/key.txt");
        PolicyEvaluator.evaluate(policy, "*",
                S3Action.s3_GetObject, "arn:aws:s3:::my-bucket/public/logo.png");
        PolicyEvaluator.evaluate(policy, "arn:aws:iam::123:user/guest",
                S3Action.s3_PutObject, "arn:aws:s3:::my-bucket/private/data.json");
        System.out.println();
    }

    /** Bucket Policy vs ACL */
    static void aclVsPolicyDemo() {
        System.out.println("--- 2. Bucket Policy vs ACL 对比 ---");

        System.out.println("| 特性          | ACL (访问控制列表)            | Bucket Policy (IAM策略)        |");
        System.out.println("|--------------|------------------------------|--------------------------------|");
        System.out.println("| 粒度          | 对象级                        | 桶级 / 账号级                  |");
        System.out.println("| 权限组        | READ/WRITE/FULL_CONTROL 预定义| 自定义 Action 组合              |");
        System.out.println("| 条件支持      | 不支持                        | 支持 (IP/时间/VPC等)             |");
        System.out.println("| 格式          | XML                          | JSON                           |");
        System.out.println("| 适用场景      | 简单场景、向后兼容            | 复杂权限管理、企业级             |");
        System.out.println("| 推荐度        | 逐步淘汰                      | 推荐使用                        |");
        System.out.println();
    }

    /** Versioning: 保留旧版本 + DeleteMarker */
    static void versioningDemo() {
        System.out.println("--- 3. 对象版本控制 (Versioning) ---");

        VersionedBucket bucket = new VersionedBucket("versioned-bucket");
        bucket.versioningEnabled = true;

        // 上传 v1
        bucket.putObject("config.json", "{\"version\":1}".getBytes(), "etag-v1");
        // 上传 v2 (覆盖)
        bucket.putObject("config.json", "{\"version\":2}".getBytes(), "etag-v2");
        // 上传 v3
        bucket.putObject("config.json", "{\"version\":3}".getBytes(), "etag-v3");

        // 查看所有版本
        bucket.listVersions("config.json");

        // Delete → 插入 DeleteMarker
        System.out.println();
        bucket.deleteObject("config.json");

        // GetObject → 返回 null (被 DeleteMarker 遮住)
        ObjectVersion latest = bucket.getObject("config.json");
        System.out.printf("  GetObject 结果: %s%n", latest != null ? latest : "(null, 被 DeleteMarker 遮住)");

        // 查看版本列表 (含 DeleteMarker)
        bucket.listVersions("config.json");

        // 恢复旧版本: 取 v2 的数据重新上传
        System.out.println();
        System.out.println("  恢复 v2 版本数据...");
        ObjectVersion v2 = bucket.versions.get("config.json").stream()
                .filter(v -> v.versionId.startsWith("etag-v2"))
                .findFirst().orElse(null);
        if (v2 != null) {
            System.out.printf("  恢复: versionId=%s, data=%s%n",
                    v2.versionId, new String(v2.data));
        }
        System.out.println();
    }

    /** 生命周期规则 (Lifecycle Rule) */
    static void lifecycleDemo() {
        System.out.println("--- 4. 生命周期规则 (Lifecycle Rule) ---");

        VersionedBucket bucket = new VersionedBucket("lifecycle-bucket");
        bucket.versioningEnabled = true;

        // 添加生命周期规则
        bucket.lifecycleRules.add(new LifecycleRule("expire-logs", "logs/", 30));
        bucket.lifecycleRules.add(new LifecycleRule("expire-temp", "temp/", 7));

        System.out.println("  生命周期规则:");
        for (LifecycleRule rule : bucket.lifecycleRules) {
            System.out.printf("    %s: prefix=%s, TTL=%d天%n",
                    rule.ruleId, rule.prefix, rule.expirationDays);
        }

        // 模拟创建一些不同年龄的对象
        bucket.putObject("logs/app.log", "old-log-data".getBytes(), "etag-log1");
        bucket.putObject("logs/error.log", "error-data".getBytes(), "etag-log2");
        bucket.putObject("temp/cache.dat", "cache-data".getBytes(), "etag-temp");
        bucket.putObject("data/perm.dat", "permanent-data".getBytes(), "etag-perm");

        // 手动制造过期版本: 将 logs/app.log 的版本时间改为 60 天前
        List<ObjectVersion> logVersions = bucket.versions.get("logs/app.log");
        if (logVersions != null && !logVersions.isEmpty()) {
            logVersions.get(0).createdAt = Instant.now().minus(Duration.ofDays(60));
        }
        // 将 temp/cache.dat 的版本时间改为 10 天前
        List<ObjectVersion> tempVersions = bucket.versions.get("temp/cache.dat");
        if (tempVersions != null && !tempVersions.isEmpty()) {
            tempVersions.get(0).createdAt = Instant.now().minus(Duration.ofDays(10));
        }

        System.out.println("\n  执行生命周期清理...");
        bucket.applyLifecycleRules();

        System.out.println("\n  清理后对象列表:");
        for (Map.Entry<String, List<ObjectVersion>> entry : bucket.versions.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                System.out.printf("    %s: %d 个版本%n", entry.getKey(), entry.getValue().size());
            }
        }
        System.out.println();
    }

    /** 对象锁定 (WORM Compliance Mode) */
    static void objectLockDemo() {
        System.out.println("--- 5. 对象锁定 (WORM) ---");

        System.out.println("  锁定模式:");
        System.out.printf("    GOVERNANCE: %s%n", RetentionMode.GOVERNANCE.description);
        System.out.printf("    COMPLIANCE: %s%n", RetentionMode.COMPLIANCE.description);

        // 模拟锁定检查
        ObjectLock governanceLock = new ObjectLock(RetentionMode.GOVERNANCE,
                Instant.now().plus(Duration.ofDays(365)));
        ObjectLock complianceLock = new ObjectLock(RetentionMode.COMPLIANCE,
                Instant.now().plus(Duration.ofDays(365 * 3)));

        System.out.println("\n  删除尝试模拟:");
        tryDelete("admin", governanceLock, true);   // 有 BypassGovernance 权限
        tryDelete("normal-user", governanceLock, false); // 无权限
        tryDelete("admin", complianceLock, true);   // Compliance 不可绕过
        System.out.println();

        System.out.println("| 模式         | 可删除条件                          | 典型场景        |");
        System.out.println("|-------------|-------------------------------------|-----------------|");
        System.out.println("| GOVERNANCE  | 需 s3:BypassGovernanceRetention 权限| 内部审计可覆盖  |");
        System.out.println("| COMPLIANCE  | 任何人(含root)都无法在锁定期内删除  | SEC 合规/医疗   |");
        System.out.println("| 无锁定       | 随时可删除                          | 普通数据        |");
        System.out.println();
    }

    private static void tryDelete(String user, ObjectLock lock, boolean hasBypassPermission) {
        Instant now = Instant.now();
        if (lock == null) {
            System.out.printf("    [%s] 无锁定 → 删除成功%n", user);
            return;
        }
        if (now.isBefore(lock.retainUntilDate)) {
            if (lock.mode == RetentionMode.COMPLIANCE) {
                System.out.printf("    [%s] COMPLIANCE 锁定 (至%s) → 拒绝删除%n",
                        user, lock.retainUntilDate);
            } else if (hasBypassPermission) {
                System.out.printf("    [%s] GOVERNANCE 锁定 (至%s) + Bypass权限 → 允许删除%n",
                        user, lock.retainUntilDate);
            } else {
                System.out.printf("    [%s] GOVERNANCE 锁定 (至%s) 无Bypass权限 → 拒绝删除%n",
                        user, lock.retainUntilDate);
            }
        } else {
            System.out.printf("    [%s] 锁定已过期 → 允许删除%n", user);
        }
    }
}