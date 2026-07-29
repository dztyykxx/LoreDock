package io.github.loredock.storage.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对象元数据 Mapper；基础读写全部复用 MyBatis-Plus Java API。
 */
@Mapper
public interface StoredObjectMapper extends BaseMapper<StoredObjectEntity> {
}
