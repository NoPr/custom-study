package base.clone;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * {@code @Package} base.clone
 * {@code @Project} custom-collection
 * {@code @Filename} DeepCopyMapper
 * {@code @Author} 18991/NoPr
 * {@code @Date} 2026/5/13  20:15
 * {@code @Description} mapstruct 深拷贝映射器接口
 */


@Mapper //核心注解：MapStruct Mapper
public interface DeepCopyMapper {

    /**
     * 深拷贝映射器实例（固定写法）
     */
    DeepCopyMapper INSTANCE = Mappers.getMapper(DeepCopyMapper.class);

    /**
     * 深拷贝
     * @param deepCopy 源对象
     * @return 目标对象
     */
    DeepCopy copyDeepCopy(DeepCopy deepCopy);
}


class DeepCopyMapperTest {

    public static void main(String[] args) {
        // 1. 创建原对象
        DeepCopy original = new DeepCopy();
        original.setName("原始对象");

        Info info = new Info();
        info.setInfoDesc("深拷贝嵌套对象");

        Tags tags = new Tags();
        tags.setTagName("深拷贝标签");

        tags.setInfo(info);
        original.setTags(tags);
        // 以上为原对象的嵌套对象关系

        // 2. 调用深拷贝映射器
        DeepCopy deepCopy = DeepCopyMapper.INSTANCE.copyDeepCopy(original);
        System.out.println(deepCopy);


    }

}
