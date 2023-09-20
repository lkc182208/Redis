package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    UserMapper userMapper;
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result findBlogById(Long id) {
        Blog blog = getById(id);
        //判断blog
        if(blog == null){
            return Result.fail("笔记不存在!");
        }
        //查询相关用户
        queryBlogUser(blog);

        return Result.ok(blog);
    }
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::queryBlogUser);
        return Result.ok(records);
    }

    //封装返回页面的user数据
    private void queryBlogUser(Blog blog) {
        //获取当前用户
        UserDTO userDto = UserHolder.getUser();
        if(userDto == null){
            //用户未登录，无需查询
            return;
        }

        Long currentUserId = userDto.getId();

        Long userId = blog.getUserId();
        User user = userMapper.selectById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

        //redis中查询用户点赞信息
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + blog.getId(), currentUserId.toString());
        if(score == null){
            blog.setIsLike(false);
        }else {
            blog.setIsLike(true);
        }
        //查询点赞数量
        Long size = stringRedisTemplate.opsForZSet().size(RedisConstants.BLOG_LIKED_KEY + blog.getId());
        //设置点赞数量
        blog.setLiked(size.intValue());
    }

    //保存点赞信息到redis
    @Override
    public Result likeBlog(Long id) {
        //1.获取用户id
        Long userId = UserHolder.getUser().getId();
        //2.判断用户是否点赞过
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
        if(score != null){
            //点赞过，此次为取消点赞
            Long row = stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
            if(row != 1){
                return Result.fail("取消点赞异常！");
            }
        }else {
            //没点赞过，此次点赞
            //保存到redis  key:笔记的id  value:用户id
            Boolean flag = stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + id, userId.toString(), new Date().getTime());
            if(BooleanUtil.isFalse(flag)){
                return Result.fail("点赞异常！");
            }
        }
        //返回结果
        return Result.ok();
    }

    //点赞用户排行榜
    @Override
    public Result queryBlogLikes(Long id) {
        //redis中查询笔记点赞的用户id
        Set<String> range = stringRedisTemplate.opsForZSet().reverseRange(RedisConstants.BLOG_LIKED_KEY + id, 0, 5);

        if(range == null){
            return Result.ok("该笔记无人点赞！");
        }
        //将查询到的id  String-->Long
        List<Long> list = range.stream().map(Long::valueOf).toList();
        //根据id查询用户信息
        List<User> userList = list.stream().map(s -> userMapper.selectById(s)).toList();
        //User -> UserDto
        List<UserDTO> userDTOS = userList.stream().map(s -> BeanUtil.copyProperties(s, UserDTO.class)).toList();

        return Result.ok(userDTOS);
    }

    //查询作者主页笔记信息
    @Override
    public Result queryBlogByUserId(Long userId, Integer current) {
        Page<Blog> page = query().eq("user_id", userId).page(new Page<Blog>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }
}
