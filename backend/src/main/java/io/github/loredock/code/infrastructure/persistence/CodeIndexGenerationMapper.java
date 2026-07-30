package io.github.loredock.code.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 代码索引 generation Mapper；数据库记录只能指向已由文件系统发布端口验证的目录。 */
@Mapper
public interface CodeIndexGenerationMapper extends BaseMapper<CodeIndexGenerationEntity> {
}
