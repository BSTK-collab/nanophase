package com.nanophase.center.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nanophase.center.entity.NanophaseUser;
import com.nanophase.center.entity.NanophaseUserLog;
import com.nanophase.center.entity.NanophaseUserRole;
import com.nanophase.center.mapper.NanophaseUserMapper;
import com.nanophase.center.service.INanophaseUserLogService;
import com.nanophase.center.service.INanophaseUserRoleService;
import com.nanophase.center.service.INanophaseUserService;
import com.nanophase.center.warper.UserWarper;
import com.nanophase.common.constant.RedisConstant;
import com.nanophase.common.dto.NanophaseRoleDTO;
import com.nanophase.common.dto.NanophaseUserDTO;
import com.nanophase.common.constant.AuthConstant;
import com.nanophase.common.constant.CenterConstant;
import com.nanophase.common.dto.TokenDTO;
import com.nanophase.common.enums.ErrorCodeEnum;
import com.nanophase.common.handler.NanophaseException;
import com.nanophase.common.manager.AsyncManager;
import com.nanophase.common.util.JwtUtil;
import com.nanophase.common.util.NetworkUtil;
import com.nanophase.common.util.R;
import com.nanophase.feign.security.SecurityApi;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zhj
 * @since 2021-03-08
 */
@Slf4j
@Service
public class NanophaseUserServiceImpl extends ServiceImpl<NanophaseUserMapper, NanophaseUser> implements INanophaseUserService {

    @Autowired
    private INanophaseUserLogService iNanophaseUserLogService;
    @Autowired
    private SecurityApi securityApi;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private INanophaseUserRoleService iNanophaseUserRoleService;

    /**
     * 用户信息分页查询 查询字段信息
     */
    private static final String[] SELECT_USER_PAGE_COLUMNS = {"user_id", "user_name", "nick_name", "user_email", "user_gender", "user_deleted", "user_status", "user_type", "user_region", "user_remark", "user_phone"};

    /**
     * 用户注册
     *
     * @param nanophaseUser
     * @return R
     */
    @Override
    public R register(NanophaseUser nanophaseUser) {
        verifyRegisterParam(nanophaseUser);
        List<NanophaseUser> nanophaseUsers = this.list(new QueryWrapper<NanophaseUser>()
                .eq("user_email", nanophaseUser.getUserEmail())
                .eq("user_deleted", 0));
        // email作为登录账号 不能重复注册
        if (null != nanophaseUsers && nanophaseUsers.size() > 0) {
            throw new NanophaseException("This email has been register");
        }
        // 密码加密
        String encode = new BCryptPasswordEncoder().encode(nanophaseUser.getUserPassword());
        nanophaseUser.setUserPassword(encode);
        return R.success().put("data", this.save(nanophaseUser));
    }

    /**
     * 用户登录
     *
     * @param nanophaseUserDTO
     * @return R
     */
    @Deprecated
    @Override
    public R login(NanophaseUserDTO nanophaseUserDTO, HttpServletRequest request) {
        NanophaseUser nanophaseUser = verifyLoginParam(nanophaseUserDTO);
        // 用户所用机器的ip地址
        String ipAddress = NetworkUtil.getIpAddress(request);
        try {
//            List<String> roles = new ArrayList<>();
//            nanophaseUserDTO.setRoles(roles);
            // 远程调用 loadUserByUsername
            R result = securityApi.loadUserByUsername(nanophaseUserDTO);
            if (null == result || !(Integer.parseInt(result.get("code").toString()) == 200)) {
                throw new NanophaseException("远程调用失败");
            }

            // 调用成功 生成token
            String token = createToken(nanophaseUserDTO, ipAddress);
            TokenDTO tokenDTO = new TokenDTO();
            tokenDTO.setToken(token);
            tokenDTO.setPrefix(AuthConstant.TOKEN_PREFIX);
            redisTemplate.opsForValue().set(RedisConstant.USER_KEY.JWT_TOKEN_PREFIX + nanophaseUser.getUserId(), token,
                    RedisConstant.TOKEN_EXPIRES, TimeUnit.SECONDS);
            // 异步执行用户登录日志记录
            saveUserLoginLog(nanophaseUser, ipAddress, null);
            return R.success().put("data", tokenDTO);
        } catch (Exception e) {
            // 异步执行用户登录日志记录
            saveUserLoginLog(nanophaseUser, ipAddress, e);
            return R.error("登录失败");
        }
    }

    /**
     * 异步保存用户登录日志
     *
     * @param nanophaseUser 用户信息
     * @param ipAddress     ip地址
     * @param exception     异常信息
     */
    private void saveUserLoginLog(NanophaseUser nanophaseUser, String ipAddress, Exception exception) {
        AsyncManager.getInstance().execute(() -> {
            try {
                // 保存用户登录记录
                NanophaseUserLog nanophaseUserLog = new NanophaseUserLog();
                nanophaseUserLog.setNanophaseUserId(nanophaseUser.getUserId());
                nanophaseUserLog.setNanophaseUserEmail(nanophaseUser.getUserEmail());
                nanophaseUserLog.setCreateDate(LocalDateTime.now());
                nanophaseUserLog.setIpAddr(ipAddress);
                nanophaseUserLog.setLoginStatus(0);

                // 保存用户登录异常的信息
                if (null != exception) {
                    nanophaseUserLog.setEMessage(exception.getMessage());
                    nanophaseUserLog.setLoginStatus(1);
                }
                boolean save = iNanophaseUserLogService.save(nanophaseUserLog);
                if (!save) {
                    // 保存用户登录记录失败
                    throw new NanophaseException("保存用户登录日志异常");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, null);
    }

    /**
     * 创建token
     *
     * @param nanophaseUserDTO user信息
     * @param ipAddress        用户登录时的ip
     * @return
     */
    private String createToken(NanophaseUserDTO nanophaseUserDTO, String ipAddress) {
        return JwtUtil.createJwt(nanophaseUserDTO, ipAddress);
    }

    /**
     * 根据用户名查询用户密码
     *
     * @param username 登录账号 这里是email
     * @return
     */
    @Override
    public NanophaseUserDTO selectUserByName(String username) {
        NanophaseUser nanophaseUser = this.getOne(new QueryWrapper<NanophaseUser>().eq("user_email", username)
                .eq("user_deleted", 0).select("user_password", "user_status", "user_email", "user_id"));
        if (null != nanophaseUser) {
            NanophaseUserDTO nanophaseUserDTO = new NanophaseUserDTO();
            nanophaseUserDTO.setUserPassword(nanophaseUser.getUserPassword());
            nanophaseUserDTO.setUserStatus(nanophaseUser.getUserStatus());
            return nanophaseUserDTO;
        }
        return null;
    }

    /**
     * 分页查询用户信息
     *
     * @param nanophaseUserDTO
     * @return R
     */
    @Override
    public R getUserPage(NanophaseUserDTO nanophaseUserDTO) {
        Page<NanophaseUser> page = new Page<>(nanophaseUserDTO.getCurrent(), nanophaseUserDTO.getSize());
        page.setOrders(nanophaseUserDTO.getOrderItems());
        QueryWrapper<NanophaseUser> queryWrapper = new QueryWrapper<>();
        // 是否删除
        Integer userDeleted = nanophaseUserDTO.getUserDeleted();
        if (userDeleted == null) {
            userDeleted = 0;
        }
        queryWrapper.eq("user_deleted", userDeleted);

        // 真实姓名
        queryWrapper.like(StringUtils.isNotBlank(nanophaseUserDTO.getUserName()), "user_name", nanophaseUserDTO.getUserName());

        // 用户昵称
        queryWrapper.like(StringUtils.isNotBlank(nanophaseUserDTO.getNickName()), "nick_name", nanophaseUserDTO.getNickName());

        // 用户邮箱 精确匹配
        queryWrapper.eq(StringUtils.isNotBlank(nanophaseUserDTO.getUserEmail()), "user_email", nanophaseUserDTO.getUserEmail());

        // 是否禁用
        queryWrapper.eq(null != nanophaseUserDTO.getUserStatus(), "user_status", nanophaseUserDTO.getUserStatus());

        // 用户类型
        queryWrapper.eq(StringUtils.isNotBlank(nanophaseUserDTO.getUserType()), "user_type", nanophaseUserDTO.getUserType());

        // 所在区域
        queryWrapper.eq(StringUtils.isNotBlank(nanophaseUserDTO.getUserRegion()), "user_region", nanophaseUserDTO.getUserRegion());

        // 查询指定字段
        queryWrapper.select(SELECT_USER_PAGE_COLUMNS);
        Page<NanophaseUser> resultPage = this.page(page, queryWrapper);
        List<NanophaseUser> result = resultPage.getRecords();
        if (null != result && result.size() > 0) {
            // 隐藏电话号码的中间四位数 但是在内存中仍然可以看到
            result.forEach(user -> user.setUserPhone(updateUserPhone(user.getUserPhone())));
        }
        resultPage.setRecords(result);

        // TODO: 2021/3/29 用户的角色信息 --待封装

        return R.success().put("data", resultPage);
    }

    /**
     * 修改用户信息
     *
     * @param userDTO
     * @return
     */
    @Override
    public R updateUser(NanophaseUserDTO userDTO) {
        verifyUpdateParam(userDTO);
        // 更新 用户关联的角色信息
        insertOrUpdateUserRole(userDTO);
        return R.success().put("data", this.updateById(UserWarper.INSTANCE.targetToSource(userDTO)));
    }

    /**
     * 更新 用户关联的角色信息
     *
     * @param userDTO
     */
    private void insertOrUpdateUserRole(NanophaseUserDTO userDTO) {
        List<NanophaseRoleDTO> roles = userDTO.getRoles();
        if (null == roles || roles.size() == 0) {
            return;
        }
        // 根据中间表的主键判断新增与修改
        List<NanophaseUserRole> userRoles = new ArrayList<>();
        for (NanophaseRoleDTO role : roles) {
            NanophaseUserRole nanophaseUserRole = new NanophaseUserRole();
            nanophaseUserRole.setNanophaseUserId(userDTO.getUserId());
            nanophaseUserRole.setNanophaseRoleId(role.getRoleId());
            nanophaseUserRole.setId(role.getRoleUserId());
            userRoles.add(nanophaseUserRole);
        }
        boolean b = iNanophaseUserRoleService.saveOrUpdateBatch(userRoles);
        if (!b) {
            throw new NanophaseException("修改异常");
        }
    }

    /**
     * 解禁用户 或者 禁用用户
     *
     * @param userDTO
     * @return
     */
    @Override
    public R updateUserStatus(NanophaseUserDTO userDTO) {
        NanophaseUser nanophaseUser = this.getById(userDTO.getUserId());
        if (null == nanophaseUser) {
            throw new NanophaseException("该用户不存在");
        }
        NanophaseUser user = new NanophaseUser();
        user.setUserStatus(userDTO.getUserStatus());
        user.setUserId(userDTO.getUserId());
        // TODO: 2021/3/29 修改用户的关联信息等

        return R.success().put("data", this.updateById(user));
    }

    /**
     * 用户修改业务---校验参数是否合法
     *
     * @param userDTO
     */
    private void verifyUpdateParam(NanophaseUserDTO userDTO) {
        Long userId = userDTO.getUserId();
        if (null == userId) {
            throw new NanophaseException("参数异常");
        }
        NanophaseUser nanophaseUser = this.getById(userId);
        if (null == nanophaseUser) {
            throw new NanophaseException("该用户不存在");
        }
        String userEmail = userDTO.getUserEmail();
        if (!userEmail.equals(nanophaseUser.getUserEmail())) {
            throw new NanophaseException("注册账号不能修改");
        }
        if (nanophaseUser.getUserStatus() == 1) {
            throw new NanophaseException("该用户已禁用");
        }
        userDTO.setUserStatus(null);
    }

    /**
     * 隐藏phone的中间四位数
     *
     * @param userPhone 用户手机号码
     * @return 15100002222 -> 151****2222
     */
    private String updateUserPhone(String userPhone) {
        if (StringUtils.isNotEmpty(userPhone) && userPhone.length() == CenterConstant.User.PHONE_SIZE) {
            userPhone = userPhone.substring(0, 3) + "****" + userPhone.substring(7);
        }
        return userPhone;
    }

    /**
     * 校验用户登录参数
     *
     * @param nanophaseUserDTO
     */
    private NanophaseUser verifyLoginParam(NanophaseUserDTO nanophaseUserDTO) {
        if (StringUtils.isBlank(nanophaseUserDTO.getUserEmail())) {
            throw new NanophaseException("Email cannot be empty");
        }
        if (StringUtils.isBlank(nanophaseUserDTO.getUserPassword())) {
            throw new NanophaseException("Password cannot be empty");
        }
        // 校验用户名密码是否正确
        NanophaseUser nanophaseUser = this.getOne(new QueryWrapper<NanophaseUser>()
                .eq("user_email", nanophaseUserDTO.getUserEmail())
                .eq("user_deleted", 0));
        if (null == nanophaseUser) {
            throw new NanophaseException("This email was not found, please register");
        }
        // 账号被禁用
        if (nanophaseUser.getUserStatus().equals(CenterConstant.User.USER_STATUS_1)) {
            throw new NanophaseException(ErrorCodeEnum.USER_DISABLED.getMsg());
        }
        boolean matches = new BCryptPasswordEncoder().matches(nanophaseUserDTO.getUserPassword(), nanophaseUser.getUserPassword());
        if (!matches) {
            // 用户名或密码错误
            throw new NanophaseException("Email or password wrong");
        }
        return nanophaseUser;
    }

    /**
     * 校验用户必传参数 TODO 暂定使用email登录
     *
     * @param nanophaseUser
     */
    private void verifyRegisterParam(NanophaseUser nanophaseUser) {
        if (StringUtils.isBlank(nanophaseUser.getUserPassword())) {
            throw new NanophaseException("password cannot be empty, please check");
        }

        if (StringUtils.isBlank(nanophaseUser.getNickName())) {
            throw new NanophaseException("nick name cannot be empty, please check");
        }

        if (StringUtils.isBlank(nanophaseUser.getUserEmail())) {
            throw new NanophaseException("email cannot be empty, please check");
        }
        if (StringUtils.isNotBlank(nanophaseUser.getUserPhone())) {
            nanophaseUser.setUserPhone(nanophaseUser.getUserPhone());
        }
    }
}
