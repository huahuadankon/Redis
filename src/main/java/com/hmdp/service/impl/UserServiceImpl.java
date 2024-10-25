package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result senCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        /*session.setAttribute("code", code);*/
        //保存到redis中,set key value ex 120
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送
        log.info("发送验证码成功,"+code);
        return Result.ok(code);

    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号格式，以及与验证码是否匹配
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }
        String formCode = loginForm.getCode();
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        if(formCode==null && formCode!=code){
            return Result.fail("验证码错误，请重新输入");
        }
        //查询手机号是否存在，存在则登录，不存在则插入数据库
        User user = query().eq("phone", loginForm.getPhone()).one();
        if(user==null){
           user = ceateUserWithPhone(loginForm.getPhone());
        }
        //将用户信息保存到Session
        /*session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));*/
        //将用户信息保存的Redis,采用随机token作为key,采用hash结构的key
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String token =LOGIN_USER_KEY+UUID.randomUUID();
        //因为userDTO中的id是Long型，stringRedis的value只能是string，类型，这里做了类型转换
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().
                setIgnoreNullValue(true)
                .setFieldValueEditor(
                        (fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString())

        );
        stringRedisTemplate.opsForHash().putAll(token,map);
        //设置token的有效期
        stringRedisTemplate.expire(token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User ceateUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
