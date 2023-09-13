package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.platform.commons.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    CacheService cacheService;

    @Override
    public Result saveCode(String phone, HttpSession session) {
        //检验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //如果不符合
            return Result.fail("手机号格式错误！");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //存入redis 手机号为key  值为code  过期时间为两分钟
        cacheService.setEx(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.info("发送短信验证码成功，验证码为：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        //检验登录参数
        if (loginForm == null) {
            return Result.fail("登录参数异常！");
        }
        String phone = loginForm.getPhone();
        //检验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        //redis中获取验证码
        String code = cacheService.get(LOGIN_CODE_KEY + phone);

        if (StringUtils.isBlank(code)) {
            return Result.fail("验证码已过期！");
        }
        //验证码不正确
        if (!code.equals(loginForm.getCode())) {
            return Result.fail("验证码不正确！");
        }
        //查询用户是否存在
        User user = query().eq("phone", phone).one();

        if (user == null) {
            //注册
            user = registerUser(phone);
        }
        //保存用户信息到redis
        String token = RandomUtil.randomString(10);

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        Map stringObjectMap = BeanUtil.beanToMap(userDTO);

        stringObjectMap.put("id",stringObjectMap.get("id").toString());

        cacheService.hPutAll(RedisConstants.LOGIN_USER_KEY+token,stringObjectMap);

        //设置有效期   30分钟
        cacheService.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User registerUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(PasswordEncoder.getNickName());
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());

        return user;
    }
}
