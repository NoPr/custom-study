# MyBatis Plus 实战

## 1. 分页插件 SQL 改写流程

```mermaid
graph TD
    A["用户调用 mapper.selectPage(page, wrapper)"] --> B{"PaginationInterceptor<br/>拦截到 Page 参数?"}
    B -->|否| C["正常执行"]
    B -->|是| D["1. 提取原始 SQL"]
    D --> E["2. 执行 COUNT 查询"]
    E --> F["SELECT COUNT(*) FROM (原始SQL) AS _count"]
    F --> G["3. 设置 Page.total = COUNT 结果"]
    G --> H["4. 计算总页数 pages = (total + size - 1) / size"]
    H --> I{"5. 当前页 > 总页数?"}
    I -->|是| J["返回空列表"]
    I -->|否| K["6. 改写 SQL 追加 LIMIT"]
    K --> L["原始SQL + LIMIT offset, size"]
    L --> M["7. 执行分页查询"]
    M --> N["8. 结果设置到 Page.records"]
    N --> O["返回 Page 对象"]
```

### 不同数据库方言的 LIMIT

| 数据库 | LIMIT 语法 | 示例（第2页，每页10条） |
|--------|------------|------------------------|
| MySQL | `LIMIT offset, size` | `LIMIT 10, 10` |
| PostgreSQL | `LIMIT size OFFSET offset` | `LIMIT 10 OFFSET 10` |
| Oracle 12c+ | `OFFSET offset ROWS FETCH NEXT size ROWS ONLY` | `OFFSET 10 ROWS FETCH NEXT 10 ROWS ONLY` |
| SQL Server 2012+ | `OFFSET offset ROWS FETCH NEXT size ROWS ONLY` | `OFFSET 10 ROWS FETCH NEXT 10 ROWS ONLY` |

## 2. 乐观锁 CAS 更新流程

```mermaid
sequenceDiagram
    participant A as 线程 A
    participant B as 线程 B
    participant DB as 数据库

    A->>DB: SELECT version FROM product WHERE id=1
    DB-->>A: version=1
    B->>DB: SELECT version FROM product WHERE id=1
    DB-->>B: version=1

    A->>DB: UPDATE product<br/>SET price=7599, version=version+1<br/>WHERE id=1 AND version=1
    DB-->>A: 影响行数=1（成功！）

    B->>DB: UPDATE product<br/>SET price=6999, version=version+1<br/>WHERE id=1 AND version=1
    DB-->>B: 影响行数=0（失败！version 已变为 2）

    Note over B: MyBatis Plus 抛出<br/>OptimisticLockException<br/>线程 B 需要重试
```

### 乐观锁核心 SQL

```sql
-- MyBatis Plus 自动生成（@Version 注解标注 version 字段）
UPDATE t_product
SET name = #{name},
    price = #{price},
    version = version + 1    -- 版本号自增
WHERE id = #{id}
  AND version = #{version}  -- CAS 条件：版本号必须匹配
```

## 3. 逻辑删除状态图

```mermaid
stateDiagram-v2
    [*] --> 正常: INSERT 时 deleted=0
    正常 --> 已删除: UPDATE SET deleted=1 WHERE id=? AND deleted=0
    已删除 --> [*]: 物理清除（运维）

    state 正常 {
        [*] --> 可查询: WHERE deleted=0
        可查询 --> 可更新: UPDATE WHERE deleted=0
    }

    state 已删除 {
        [*] --> 不可见: 应用层 WHERE deleted=0 过滤
        不可见 --> 可恢复: UPDATE SET deleted=0 WHERE id=?
    }
```

### 逻辑删除 SQL 改写原理

```
原始 DELETE:
  DELETE FROM t_product WHERE id = #{id}

MyBatis Plus 改写为:
  UPDATE t_product SET deleted = 1 WHERE id = #{id} AND deleted = 0

原始 SELECT:
  SELECT * FROM t_product

MyBatis Plus 改写为:
  SELECT * FROM t_product WHERE deleted = 0
```

## 4. BaseMapper 核心方法

| 方法 | 说明 | 对应 SQL |
|------|------|----------|
| `insert(T entity)` | 插入一条记录 | `INSERT INTO table(...) VALUES(...)` |
| `deleteById(Serializable id)` | 根据 ID 删除 | `DELETE FROM table WHERE id=?` (或逻辑删除改写) |
| `updateById(T entity)` | 根据 ID 更新 | `UPDATE table SET ... WHERE id=?` |
| `selectById(Serializable id)` | 根据 ID 查询 | `SELECT ... FROM table WHERE id=?` |
| `selectList(Wrapper<T> wrapper)` | 条件查询列表 | `SELECT ... FROM table WHERE ...` |
| `selectPage(Page<T> page, Wrapper<T> wrapper)` | 分页查询 | `SELECT ... FROM table WHERE ... LIMIT ?,?` |

## 5. MyBatis Plus 核心注解

| 注解 | 作用 | 示例 |
|------|------|------|
| `@TableName("t_user")` | 指定表名 | `@TableName("t_user")` |
| `@TableId(type = IdType.AUTO)` | 主键策略 | `@TableId(type = IdType.ASSIGN_ID)` 雪花算法 |
| `@TableField("user_name")` | 字段映射（驼峰自动） | `@TableField(fill = FieldFill.INSERT)` |
| `@Version` | 乐观锁版本号 | `private Integer version;` |
| `@TableLogic` | 逻辑删除 | `private Integer deleted;` |
| `@EnumValue` | 枚举值映射 | `@EnumValue private int code;` |

## 6. 项目引入依赖

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-boot-starter</artifactId>
    <version>3.5.5</version>
</dependency>

<!-- 代码生成器 -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-generator</artifactId>
    <version>3.5.5</version>
</dependency>

<!-- 分页插件需要 -->
@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
```