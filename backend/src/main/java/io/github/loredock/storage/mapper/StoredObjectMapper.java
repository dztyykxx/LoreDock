package io.github.loredock.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.storage.model.entity.StoredObjectEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对象元数据 Mapper；基础读写全部复用 MyBatis-Plus Java API。
 */
@Mapper
public interface StoredObjectMapper extends BaseMapper<StoredObjectEntity> {
}
