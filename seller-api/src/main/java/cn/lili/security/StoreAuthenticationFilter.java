package cn.lili.security;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.PermissionEnum;
import cn.lili.common.security.enums.SecurityEnum;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.common.utils.ResponseUtil;
import com.google.gson.Gson;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.util.PatternMatchUtils;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.naming.NoPermissionException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Chopper
 */
@Slf4j
public class StoreAuthenticationFilter extends BasicAuthenticationFilter {

    private final Cache cache;

    public StoreAuthenticationFilter(AuthenticationManager authenticationManager,
                                     Cache cache) {
        super(authenticationManager);
        this.cache = cache;
    }

    @SneakyThrows
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        //???header?????????jwt
        String jwt = request.getHeader(SecurityEnum.HEADER_TOKEN.getValue());
        //????????????token ???return
        if (StrUtil.isBlank(jwt)) {
            chain.doFilter(request, response);
            return;
        }
        //???????????????????????????context
        UsernamePasswordAuthenticationToken authentication = getAuthentication(jwt, response);
        //?????????????????????
        if (authentication != null) {
            customAuthentication(request, response, authentication);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }


    /**
     * ??????token??????
     *
     * @param jwt
     * @param response
     * @return
     */
    private UsernamePasswordAuthenticationToken getAuthentication(String jwt, HttpServletResponse response) {

        try {
            Claims claims
                    = Jwts.parser()
                    .setSigningKey(SecretKeyUtil.generalKeyByDecoders())
                    .parseClaimsJws(jwt).getBody();
            //???????????????claims??????????????????
            String json = claims.get(SecurityEnum.USER_CONTEXT.getValue()).toString();
            AuthUser authUser = new Gson().fromJson(json, AuthUser.class);

            //??????redis??????????????????
            if (cache.hasKey(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.STORE) + jwt)) {
                //????????????
                List<GrantedAuthority> auths = new ArrayList<>();
                auths.add(new SimpleGrantedAuthority("ROLE_" + authUser.getRole().name()));
                //??????????????????
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(authUser.getUsername(), null, auths);
                authentication.setDetails(authUser);
                return authentication;
            }
            ResponseUtil.output(response, 403, ResponseUtil.resultMap(false, 403, "?????????????????????????????????"));
            return null;
        } catch (ExpiredJwtException e) {
            log.debug("user analysis exception:", e);
        } catch (Exception e) {
            log.error("user analysis exception:", e);
        }
        return null;
    }


    /**
     * ?????????????????????
     *
     * @param request        ??????
     * @param response       ??????
     * @param authentication ????????????
     */
    private void customAuthentication(HttpServletRequest request, HttpServletResponse response, UsernamePasswordAuthenticationToken authentication) throws NoPermissionException {
        AuthUser authUser = (AuthUser) authentication.getDetails();
        String requestUrl = request.getRequestURI();


        //?????????????????????????????? ?????????
        if (!authUser.getIsSuper()) {
            //????????????????????????
            Map<String, List<String>> permission = (Map<String, List<String>>) cache.get(CachePrefix.PERMISSION_LIST.getPrefix(UserEnums.STORE) + authUser.getId());

            //????????????(GET ??????)??????
            if (request.getMethod().equals(RequestMethod.GET.name())) {
                //?????????????????????????????????????????????????????????????????????api
                if (match(permission.get(PermissionEnum.SUPER.name()), requestUrl)
                        ||match(permission.get(PermissionEnum.QUERY.name()), requestUrl)) {
                } else {
                    ResponseUtil.output(response, ResponseUtil.resultMap(false, 400, "????????????"));
                    log.error("?????????????????????{},??????????????????{}", requestUrl, JSONUtil.toJsonStr(permission));
                    throw new NoPermissionException("????????????");
                }
            }
            //???get???????????????????????? ????????????
            else {
                if (!match(permission.get(PermissionEnum.SUPER.name()), requestUrl)) {
                    ResponseUtil.output(response, ResponseUtil.resultMap(false, 400, "????????????"));
                    log.error("?????????????????????{},??????????????????{}", requestUrl, JSONUtil.toJsonStr(permission));
                    throw new NoPermissionException("????????????");
                }
            }
        }
    }

    /**
     * ????????????
     *
     * @param permissions ????????????
     * @param url         ????????????
     * @return ??????????????????
     */
    boolean match(List<String> permissions, String url) {
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        return PatternMatchUtils.simpleMatch(permissions.toArray(new String[0]), url);
    }

}

