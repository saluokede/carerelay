package com.carerelay.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carerelay.user.AppUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 约定：所有 Mapper 都放在 com.carerelay.<domain>.mapper 包下，
 * 以匹配启动类上的 @MapperScan("com.carerelay.**.mapper")。
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
