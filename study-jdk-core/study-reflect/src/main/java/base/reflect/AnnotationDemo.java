package base.reflect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;

/**
 * 自定义注解 + 运行时注解处理演示
 *
 * 核心知识点：
 * 1. 自定义 @Table / @Column 注解 — 模拟 JPA 的 ORM 映射
 * 2. @Retention(RUNTIME) — 注解保留到运行时，反射可读取
 * 3. @Target — 限制注解使用范围（TYPE=类, FIELD=字段）
 * 4. 运行时通过反射读取注解，生成 SQL DDL 语句
 * 5. 注解处理器模式：遍历类 → 读取注解 → 执行业务逻辑
 */
public class AnnotationDemo {

    public static void main(String[] args) {
        System.out.println("=== 自定义注解 + 运行时处理 ===\n");

        User user = new User("admin", 25, "admin@example.com");
        System.out.println("实体对象: " + user);

        generateDDL(User.class);

        validateAnnotation(user);
    }

    /**
     * 运行时读取 @Table 和 @Column 注解，生成 CREATE TABLE DDL 语句
     * 这是 ORM 框架（如 Hibernate、MyBatis-Plus）的核心机制
     */
    static void generateDDL(Class<?> entityClass) {
        System.out.println("\n--- 生成 DDL ---");

        if (!entityClass.isAnnotationPresent(Table.class)) {
            System.out.println("类未标注 @Table 注解，跳过");
            return;
        }

        Table table = entityClass.getAnnotation(Table.class);
        String tableName = table.name();
        if (tableName.isEmpty()) {
            tableName = entityClass.getSimpleName().toLowerCase();
        }

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(tableName).append(" (\n");

        Field[] fields = entityClass.getDeclaredFields();
        boolean first = true;
        for (Field field : fields) {
            if (!field.isAnnotationPresent(Column.class)) {
                continue;
            }
            if (!first) {
                ddl.append(",\n");
            }
            first = false;

            Column column = field.getAnnotation(Column.class);
            String columnName = column.name().isEmpty() ? field.getName() : column.name();
            String type = column.type();

            ddl.append("  ").append(columnName).append(" ").append(type);

            if (!column.nullable()) {
                ddl.append(" NOT NULL");
            }
            if (column.primaryKey()) {
                ddl.append(" PRIMARY KEY");
            }
            if (column.length() > 0 && !column.primaryKey()) {
                ddl.append("(").append(column.length()).append(")");
            }
        }
        ddl.append("\n);");

        System.out.println(ddl);
    }

    /**
     * 运行时验证注解约束 — 模拟 JSR-303 Bean Validation
     */
    static void validateAnnotation(Object obj) {
        System.out.println("\n--- 验证注解约束 ---");

        Class<?> clazz = obj.getClass();
        if (!clazz.isAnnotationPresent(Table.class)) {
            return;
        }

        for (Field field : clazz.getDeclaredFields()) {
            if (!field.isAnnotationPresent(Column.class)) {
                continue;
            }
            field.setAccessible(true);
            try {
                Object value = field.get(obj);
                Column column = field.getAnnotation(Column.class);
                String columnName = column.name().isEmpty() ? field.getName() : column.name();

                if (!column.nullable() && value == null) {
                    System.out.println("[错误] 字段 " + columnName + " 不能为 null");
                } else {
                    System.out.println("[通过] " + columnName + " = " + value);
                }
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }
}

/**
 * 模拟实体类，标注了自定义 @Table 和 @Column 注解
 */
@Table(name = "t_user")
class User {
    @Column(name = "id", type = "INT", primaryKey = true)
    private Integer id = 1;

    @Column(name = "username", type = "VARCHAR", length = 50, nullable = false)
    private String username;

    @Column(name = "age", type = "INT")
    private int age;

    @Column(name = "email", type = "VARCHAR", length = 100)
    private String email;

    public User(String username, int age, String email) {
        this.username = username;
        this.age = age;
        this.email = email;
    }

    @Override
    public String toString() {
        return "User{id=" + id + ", username='" + username + "', age=" + age
                + ", email='" + email + "'}";
    }
}

/**
 * 自定义 @Table 注解 — 标注实体类对应的数据库表名
 *
 * @Retention(RUNTIME) → 注解保留到运行时，反射可读取
 * @Target(TYPE)       → 只能标注在类/接口上
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface Table {
    String name() default "";
}

/**
 * 自定义 @Column 注解 — 标注字段对应的数据库列信息
 *
 * 属性说明：
 * - name: 列名，默认使用字段名
 * - type: 列类型（SQL 类型）
 * - length: 列长度，仅对 VARCHAR 等类型有效
 * - nullable: 是否允许 NULL
 * - primaryKey: 是否主键
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface Column {
    String name() default "";
    String type() default "VARCHAR";
    int length() default 0;
    boolean nullable() default true;
    boolean primaryKey() default false;
}