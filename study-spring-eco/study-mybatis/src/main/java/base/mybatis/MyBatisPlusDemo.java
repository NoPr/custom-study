package base.mybatis;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * MyBatis Plus 核心：BaseMapper + 分页插件 + 乐观锁 + 逻辑删除
 *
 * <p>手写简化版 MyBatis Plus 核心功能，展示：</p>
 * <ol>
 *   <li>BaseMapper&lt;T&gt; 简化版（insert/updateById/selectById/selectList）</li>
 *   <li>分页插件 PaginationInterceptor 模拟：拦截 SQL → 改写 COUNT → 追加 LIMIT</li>
 *   <li>乐观锁 @Version 字段：UPDATE SET version=version+1 WHERE version=旧值</li>
 *   <li>逻辑删除 @TableLogic：UPDATE SET deleted=1 模拟</li>
 * </ol>
 *
 * @author study-tuling
 */
public class MyBatisPlusDemo {

    // ============================================================
    // 1. 注解定义
    // ============================================================

    /** 标记乐观锁版本字段 */
    @interface Version {}

    /** 标记逻辑删除字段 */
    @interface TableLogic {}

    /** 标记表名 */
    @interface TableName {
        String value();
    }

    /** 标记主键字段 */
    @interface TableId {}

    // ============================================================
    // 2. 分页对象
    // ============================================================

    /** 分页参数 + 结果 */
    static class Page<T> {
        long current;    // 当前页码
        long size;       // 每页大小
        long total;      // 总记录数
        long pages;      // 总页数
        List<T> records; // 当前页数据

        Page(long current, long size) {
            this.current = Math.max(1, current);
            this.size = Math.max(1, size);
            this.records = new ArrayList<>();
        }

        void setTotal(long total) {
            this.total = total;
            this.pages = (total + size - 1) / size;
        }

        @Override
        public String toString() {
            return String.format("Page{current=%d, size=%d, total=%d, pages=%d, records=%s}",
                    current, size, total, pages, records);
        }
    }

    // ============================================================
    // 3. 领域模型 + 模拟数据库
    // ============================================================

    @TableName("t_product")
    static class Product {
        @TableId
        Long id;
        String name;
        Double price;
        Integer stock;
        @Version
        Integer version;        // 乐观锁版本号
        @TableLogic
        Integer deleted;        // 逻辑删除标记：0=正常, 1=已删除

        Product() {}

        Product(Long id, String name, Double price, Integer stock) {
            this.id = id;
            this.name = name;
            this.price = price;
            this.stock = stock;
            this.version = 1;
            this.deleted = 0;
        }

        @Override
        public String toString() {
            return String.format("Product{id=%d, name='%s', price=%.2f, stock=%d, version=%d, deleted=%d}",
                    id, name, price, stock, version, deleted);
        }
    }

    /** 模拟数据库表（线程安全） */
    static class ProductTable {
        static final List<Product> rows = new CopyOnWriteArrayList<>();
        static long nextId = 1;

        static {
            rows.add(new Product(nextId++, "iPhone 15", 7999.0, 100));
            rows.add(new Product(nextId++, "MacBook Pro", 14999.0, 50));
            rows.add(new Product(nextId++, "AirPods Pro", 1999.0, 200));
            rows.add(new Product(nextId++, "iPad Air", 4999.0, 80));
            rows.add(new Product(nextId++, "Apple Watch", 2999.0, 150));
        }

        static List<Product> all() {
            // 排除逻辑删除的记录
            return rows.stream()
                    .filter(p -> p.deleted == 0)
                    .collect(Collectors.toList());
        }

        static Optional<Product> findById(Long id) {
            return rows.stream()
                    .filter(p -> p.id.equals(id) && p.deleted == 0)
                    .findFirst();
        }
    }

    // ============================================================
    // 4. BaseMapper<T> 简化版
    // ============================================================

    /**
     * BaseMapper 简化版：封装 CRUD 操作，不依赖于真实数据库
     * MyBatis Plus 的 BaseMapper 通过 MyBatis 的 SqlSession 执行 SQL
     */
    interface SimpleBaseMapper<T> {
        int insert(T entity);
        int updateById(T entity);
        T selectById(Long id);
        List<T> selectList();
        int deleteById(Long id);
    }

    // ============================================================
    // 5. 分页插件 PaginationInterceptor
    // ============================================================

    /**
     * 分页拦截器模拟：MyBatis Plus 分页插件核心原理
     *
     * <p>工作流程：</p>
     * <ol>
     *   <li>检测到 Page 参数时，先执行 COUNT 查询获取总记录数</li>
     *   <li>改写原始 SQL：追加 LIMIT offset, size</li>
     *   <li>执行改写后的 SQL 获取分页数据</li>
     *   <li>将结果设置到 Page 对象中返回</li>
     * </ol>
     */
    static class PaginationInterceptor {

        /** 模拟 SQL 改写过程 */
        @SuppressWarnings("unchecked")
        <T> Page<T> paginate(List<T> allData, Page<T> page) {
            System.out.println("    [分页插件] 原始数据总数: " + allData.size());

            // 步骤 1：模拟 COUNT 查询 → 获取总记录数
            long total = allData.size();
            page.setTotal(total);
            System.out.println("    [分页插件] COUNT SQL: SELECT COUNT(*) FROM table → " + total);
            System.out.println("    [分页插件] 总页数: " + page.pages);

            // 步骤 2：改写 SQL → 追加 LIMIT
            long offset = (page.current - 1) * page.size;
            long limit = page.size;
            System.out.println("    [分页插件] 改写 SQL 追加 LIMIT: LIMIT " + offset + ", " + limit);

            // 步骤 3：执行分页查询（模拟 LIMIT）
            int fromIndex = (int) Math.min(offset, allData.size());
            int toIndex = (int) Math.min(fromIndex + limit, allData.size());
            List<T> pageData = allData.subList(fromIndex, toIndex);

            page.records = new ArrayList<>(pageData);
            System.out.println("    [分页插件] 当前页数据量: " + page.records.size());
            return page;
        }

        /** 打印分页 SQL 改写演示 */
        static void demoSQLRewrite() {
            System.out.println("=== 分页插件 SQL 改写演示 ===");
            String originalSQL = "SELECT id, name, price FROM t_product WHERE deleted = 0 ORDER BY id";

            System.out.println("原始 SQL:");
            System.out.println("  " + originalSQL);

            System.out.println("\n步骤 1 - COUNT SQL:");
            System.out.println("  SELECT COUNT(*) FROM t_product WHERE deleted = 0");

            System.out.println("\n步骤 2 - 分页 SQL (第2页, 每页2条):");
            System.out.println("  " + originalSQL + " LIMIT 2, 2");

            System.out.println("\n不同数据库方言的 LIMIT 写法:");
            System.out.println("  MySQL:         LIMIT 2, 2");
            System.out.println("  PostgreSQL:    LIMIT 2 OFFSET 2");
            System.out.println("  Oracle:        OFFSET 2 ROWS FETCH NEXT 2 ROWS ONLY");
            System.out.println("  SQL Server:    OFFSET 2 ROWS FETCH NEXT 2 ROWS ONLY");
            System.out.println();
        }
    }

    // ============================================================
    // 6. 乐观锁 @Version
    // ============================================================

    /**
     * 乐观锁机制：基于版本号的 CAS 更新
     *
     * <p>核心 SQL 模式：</p>
     * <pre>
     * UPDATE t_product
     * SET name = ?, price = ?, stock = ?, version = version + 1
     * WHERE id = ? AND version = ?
     * </pre>
     *
     * <p>如果 WHERE 条件中的 version 与当前数据库中的 version 不一致，
     * 说明数据已被其他事务修改，更新影响行数为 0，触发乐观锁冲突。</p>
     */
    static class OptimisticLockHandler {

        /** 模拟乐观锁更新：只在新旧 version 一致时才更新成功 */
        static int updateWithOptimisticLock(Product entity) {
            Optional<Product> dbRecord = ProductTable.findById(entity.id);

            if (dbRecord.isEmpty()) {
                System.out.println("      [乐观锁] 记录不存在，更新失败");
                return 0;
            }

            Product current = dbRecord.get();

            // 关键判断：version 必须一致才能更新
            if (!current.version.equals(entity.version)) {
                System.out.printf("      [乐观锁] 版本冲突！当前 version=%d, 提交 version=%d%n",
                        current.version, entity.version);
                return 0;
            }

            // CAS 更新：version + 1
            current.name = entity.name;
            current.price = entity.price;
            current.stock = entity.stock;
            current.version = current.version + 1; // 版本号自增
            System.out.printf("      [乐观锁] 更新成功，version: %d → %d%n",
                    entity.version, current.version);
            return 1;
        }

        /** 模拟并发冲突场景 */
        static void demoConcurrencyConflict() {
            System.out.println("=== 乐观锁并发冲突演示 ===");

            // 线程 A 和线程 B 同时读到 version=1 的同一行数据
            Product threadAData = ProductTable.findById(1L).orElse(null);
            Product threadBData = ProductTable.findById(1L).orElse(null);

            System.out.printf("  线程A 读取: %s (version=%d)%n",
                    threadAData != null ? threadAData.name : "null",
                    threadAData != null ? threadAData.version : 0);
            System.out.printf("  线程B 读取: %s (version=%d)%n",
                    threadBData != null ? threadBData.name : "null",
                    threadBData != null ? threadBData.version : 0);

            // 线程 A 先更新成功
            System.out.println("\n  线程A 执行更新:");
            Product updateA = new Product(1L, "iPhone 15 Pro", 8999.0, 100);
            updateA.version = threadAData != null ? threadAData.version : 1;
            int resultA = updateWithOptimisticLock(updateA);

            // 线程 B 后更新 → version 已变，更新失败
            System.out.println("\n  线程B 执行更新:");
            Product updateB = new Product(1L, "iPhone 15 Plus", 6999.0, 100);
            updateB.version = threadBData != null ? threadBData.version : 1;
            int resultB = updateWithOptimisticLock(updateB);

            System.out.printf("\n  结果: 线程A 成功=%s, 线程B 成功=%s (B 需要重试)%n",
                    resultA > 0, resultB > 0);

            Product finalRecord = ProductTable.findById(1L).orElse(null);
            System.out.printf("  最终数据: %s%n", finalRecord);
            System.out.println();
        }
    }

    // ============================================================
    // 7. 逻辑删除 @TableLogic
    // ============================================================

    /**
     * 逻辑删除：不物理删除记录，仅标记 deleted 字段
     *
     * <p>MyBatis Plus 逻辑删除原理：</p>
     * <ul>
     *   <li>DELETE 操作 → 改写为 UPDATE SET deleted=1</li>
     *   <li>SELECT 操作 → 自动追加 WHERE deleted=0</li>
     *   <li>被删除的记录在应用层不可见，但数据库中仍保留</li>
     * </ul>
     */
    static class LogicDeleteHandler {

        /** 逻辑删除：将 DELETE 改写为 UPDATE */
        static int logicDelete(Long id) {
            Optional<Product> dbRecord = ProductTable.findById(id);

            if (dbRecord.isEmpty()) {
                System.out.println("      [逻辑删除] 记录不存在");
                return 0;
            }

            Product product = dbRecord.get();
            product.deleted = 1;
            System.out.printf("      [逻辑删除] 原始 SQL: DELETE FROM t_product WHERE id=%d%n", id);
            System.out.printf("      [逻辑删除] 改写 SQL: UPDATE t_product SET deleted=1 WHERE id=%d AND deleted=0%n", id);
            return 1;
        }

        /** 演示逻辑删除的效果 */
        static void demoLogicDelete() {
            System.out.println("=== 逻辑删除演示 ===");

            System.out.println("删除前全表数据 (排除 deleted=1):");
            ProductTable.all().forEach(p -> System.out.println("  " + p));

            long deleteId = 3L; // 逻辑删除 AirPods Pro
            Product before = ProductTable.findById(deleteId).orElse(null);
            System.out.printf("\n逻辑删除 id=%d (%s)%n", deleteId,
                    before != null ? before.name : "null");
            logicDelete(deleteId);

            System.out.println("\n删除后查询 (WHERE deleted=0):");
            List<Product> afterDelete = ProductTable.all();
            afterDelete.forEach(p -> System.out.println("  " + p));

            // 物理表中数据仍存在
            System.out.println("\n物理表中所有数据 (含删除):");
            ProductTable.rows.forEach(p -> System.out.println("  " + p));
            System.out.println();
        }
    }

    // ============================================================
    // 8. MapperProxy（整合分页 + 乐观锁 + 逻辑删除）
    // ============================================================

    /** ProductMapper 接口 */
    interface ProductMapper {
        Product selectById(Long id);
        List<Product> selectList();
        int insert(Product entity);
        int updateById(Product entity);
        int deleteById(Long id);
        Page<Product> selectPage(Page<Product> page);
    }

    /** MapperProxy 实现 */
    static class ProductMapperProxy implements InvocationHandler {
        final PaginationInterceptor paginationInterceptor = new PaginationInterceptor();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (Object.class.equals(method.getDeclaringClass())) {
                return method.invoke(this, args);
            }

            String methodName = method.getName();

            return switch (methodName) {
                case "selectById" -> {
                    Long id = (Long) args[0];
                    System.out.println("  [selectById] 自动追加 WHERE deleted=0");
                    yield ProductTable.findById(id).orElse(null);
                }
                case "selectList" -> {
                    System.out.println("  [selectList] 自动追加 WHERE deleted=0");
                    yield ProductTable.all();
                }
                case "insert" -> {
                    Product p = (Product) args[0];
                    p.id = ProductTable.nextId++;
                    p.version = 1;
                    p.deleted = 0;
                    ProductTable.rows.add(p);
                    System.out.println("  [insert] 自动设置 id=" + p.id + ", version=1, deleted=0");
                    yield 1;
                }
                case "updateById" -> {
                    Product p = (Product) args[0];
                    System.out.println("  [updateById] 触发乐观锁 @Version 检查");
                    yield OptimisticLockHandler.updateWithOptimisticLock(p);
                }
                case "deleteById" -> {
                    Long id = (Long) args[0];
                    System.out.println("  [deleteById] 改写为逻辑删除 UPDATE SET deleted=1");
                    yield LogicDeleteHandler.logicDelete(id);
                }
                case "selectPage" -> {
                    @SuppressWarnings("unchecked")
                    Page<Product> page = (Page<Product>) args[0];
                    System.out.println("  [selectPage] 分页查询 page=" + page.current + ", size=" + page.size);
                    yield paginationInterceptor.paginate(ProductTable.all(), page);
                }
                default -> throw new UnsupportedOperationException("未知方法: " + methodName);
            };
        }

        @SuppressWarnings("unchecked")
        static ProductMapper createProxy() {
            return (ProductMapper) Proxy.newProxyInstance(
                    ProductMapper.class.getClassLoader(),
                    new Class<?>[]{ProductMapper.class},
                    new ProductMapperProxy());
        }
    }

    // ============================================================
    // 9. 演示入口
    // ============================================================

    static void demoBaseMapper() {
        System.out.println("==================== BaseMapper CRUD 演示 ====================");
        System.out.println();
        ProductMapper mapper = ProductMapperProxy.createProxy();

        System.out.println("--- selectList: 查询全部 ---");
        List<Product> all = mapper.selectList();
        all.forEach(p -> System.out.println("  " + p));
        System.out.println();

        System.out.println("--- selectById(1): 主键查询 ---");
        Product p1 = mapper.selectById(1L);
        System.out.println("  " + p1);
        System.out.println();

        System.out.println("--- insert: 插入新商品 ---");
        Product newProduct = new Product(null, "Vision Pro", 25999.0, 20);
        int insertResult = mapper.insert(newProduct);
        System.out.println("  插入结果: " + insertResult + ", 新记录: " + newProduct);
        System.out.println();

        System.out.println("--- updateById: 更新商品（乐观锁） ---");
        Product toUpdate = mapper.selectById(1L);
        toUpdate.price = 7599.0; // 降价
        int updateResult = mapper.updateById(toUpdate);
        System.out.println("  更新结果: " + updateResult);
        System.out.println();

        System.out.println("--- deleteById(5): 逻辑删除 ---");
        int deleteResult = mapper.deleteById(5L);
        System.out.println("  删除结果: " + deleteResult);
        System.out.println();
    }

    static void demoPagination() {
        System.out.println("==================== 分页插件演示 ====================");
        System.out.println();
        ProductMapper mapper = ProductMapperProxy.createProxy();

        Page<Product> page1 = new Page<>(1, 2);
        System.out.println("--- 查询第 1 页（每页 2 条）---");
        page1 = mapper.selectPage(page1);
        System.out.println("结果: " + page1);
        System.out.println();

        Page<Product> page2 = new Page<>(2, 2);
        System.out.println("--- 查询第 2 页（每页 2 条）---");
        page2 = mapper.selectPage(page2);
        System.out.println("结果: " + page2);
        System.out.println();

        Page<Product> page3 = new Page<>(3, 2);
        System.out.println("--- 查询第 3 页（每页 2 条）---");
        page3 = mapper.selectPage(page3);
        System.out.println("结果: " + page3);
        System.out.println();
    }

    static void demoOptimisticLock() {
        OptimisticLockHandler.demoConcurrencyConflict();
    }

    static void demoLogicDelete() {
        LogicDeleteHandler.demoLogicDelete();
    }

    public static void main(String[] args) {
        demoBaseMapper();
        demoPagination();
        demoOptimisticLock();
        PaginationInterceptor.demoSQLRewrite();
        demoLogicDelete();

        System.out.println("==================== MyBatis Plus 核心特性对比 ====================");
        System.out.println();
        System.out.println("| 特性           | MyBatis                    | MyBatis Plus                       |");
        System.out.println("|----------------|----------------------------|------------------------------------|");
        System.out.println("| CRUD           | 手写 SQL/XML               | BaseMapper 自动提供                |");
        System.out.println("| 分页           | 手动 LIMIT                 | Page + PaginationInterceptor       |");
        System.out.println("| 乐观锁         | 手动版本号逻辑              | @Version 自动处理                  |");
        System.out.println("| 逻辑删除       | 手动标记字段               | @TableLogic 自动改写               |");
        System.out.println("| 条件构造器     | 无                         | LambdaQueryWrapper/Wrapper         |");
        System.out.println("| 主键策略       | 手动                       | @TableId 多种策略(雪花/自增/UUID)   |");
        System.out.println("| 自动填充       | 无                         | @TableField(fill=...) MetaObjectHandler |");
    }
}