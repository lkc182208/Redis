package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import com.hmdp.service.impl.FollowServiceImpl;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    IFollowService followService;

    @PutMapping("/{followId}/{flag}")
    public Result follow(@PathVariable("followId") Long followId,@PathVariable("flag") Boolean flag){
        return followService.follow(followId,flag);
    }
    @GetMapping("/or/not/{followId}")
    public Result isFollow(@PathVariable("followId") Long followId){
        return followService.isFollow(followId);
    }
    @GetMapping("/common/{followId}")
    public Result commonFollow(@PathVariable("followId") Long followId){
        return followService.commonFollow(followId);
    }
}
