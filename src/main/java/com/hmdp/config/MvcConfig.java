package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Autowired
    LoginInterceptor loginInterceptor;
    @Autowired
    RefreshTokenInterceptor refreshTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        List<String> list = new ArrayList<>();
        list.add("/user/login");
        list.add("/user/code");
        list.add("/blog/hot");
        list.add("/shop-type/**");
        list.add("/shop/**");
        list.add("/voucher/**");
        registry.addInterceptor(loginInterceptor).excludePathPatterns(list).order(1);

        registry.addInterceptor(refreshTokenInterceptor).addPathPatterns("/**").order(0);
    }

}
