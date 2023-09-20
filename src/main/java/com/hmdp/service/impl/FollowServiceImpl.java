package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.Wrapper;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    CacheService cacheService;
    //关注和取关
    @Override
    public Result follow(Long followId, Boolean flag) {
        //获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return Result.fail("没有登录不能进行此操作");
        }
        //获取登录用户的id
        Long userId = user.getId();
        //1.判断是关注还是取关
        if(BooleanUtil.isTrue(flag)){
            //查询不能有重复信息
            Follow follow = query().eq("user_id", userId).eq("follow_user_id", followId).one();
            if(follow != null){
                return Result.fail("不能重复关注");
            }
            //2.关注
            follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followId);
            follow.setCreateTime(LocalDateTime.now());
            //插入记录
            boolean save = save(follow);
            if(save){
                //保存到redis中
                cacheService.sAdd(RedisConstants.FOLLOW_USER_KEY + userId,followId.toString());
            }
        }else {
            //3.取关，查询数据库中有没有   user_id  follow_user_id
            Follow follow = query().eq("user_id", userId).eq("follow_user_id", followId).one();
            if(follow == null){
                return Result.fail("用户取关异常！");
            }
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followId));
            if(!remove){
                return Result.fail("用户取关异常！");
            }
            //redis中移除
            cacheService.sRemove(RedisConstants.FOLLOW_USER_KEY+userId,followId.toString());
        }
        return Result.ok();
    }
    //查看关注状态
    @Override
    public Result isFollow(Long followId) {
        //获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return Result.fail("没有登录不能查看登录状态");
        }
        //获取用户id
        Long userId = user.getId();

        //查询数据
        Long count = query().eq("user_id", userId).eq("follow_user_id", followId).count();

        return Result.ok(count > 0);
    }
    //实现共同关注

    @Resource
    UserMapper userMapper;
    @Override
    public Result commonFollow(Long followId) {
        //检验用户是否登录
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null){
            return Result.fail("请登录后才能查看共同关注的信息");
        }
        //获取当前登录用户的id
        Long userId = userDTO.getId();

        //从redis中获取共同关注的信息 根据userid 和 followid
        Set<String> set = cacheService.sIntersect(RedisConstants.FOLLOW_USER_KEY + userId, RedisConstants.FOLLOW_USER_KEY + followId);
        if(set.size() == 0 || set.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //string -> Long
        List<Long> list = set.stream().map(Long::valueOf).toList();
        //查询用户数据
        List<UserDTO> userList = list.stream().map(id -> userMapper.selectById(id)).map(user -> BeanUtil.copyProperties(user, UserDTO.class)).toList();

        return Result.ok(userList);
    }
}
