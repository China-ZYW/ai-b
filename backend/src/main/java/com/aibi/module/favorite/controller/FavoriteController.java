package com.aibi.module.favorite.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.UserContext;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.favorite.dto.FavoriteRequest;
import com.aibi.module.favorite.dto.FavoriteVO;
import com.aibi.module.favorite.service.QueryFavoriteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问数收藏接口（M4 / 开发计划 T4.4）：收藏的增删查，全部以当前租户 + 当前用户为边界。
 *
 * <p><b>权限点（L2）</b>：三个端点此前均无注解。收藏虽为自作用域资源（风险最低的一类），
 * 但权限字典已定义 {@code favorite:item:read/write/delete} 且授予全角色（含 BILLING），
 * 补齐注解可让"未挂权限点"的运维告警归零，避免真实缺口淹没在噪声里。
 */
@RestController
@RequestMapping("/api/favorite")
public class FavoriteController {

    private final QueryFavoriteService favoriteService;

    public FavoriteController(QueryFavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /** 我的收藏列表（租户由拦截器自动隔离）。 */
    @GetMapping
    @RequiresPermission("favorite:item:read")
    public R<List<FavoriteVO>> list() {
        return R.ok(favoriteService.listFavorites(UserContext.userId()));
    }

    /** 新增收藏，返回其 id。 */
    @PostMapping
    @RequiresPermission("favorite:item:write")
    public R<Long> create(@RequestBody @Valid FavoriteRequest request) {
        return R.ok(favoriteService.createFavorite(UserContext.userId(), request));
    }

    /** 删除收藏。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("favorite:item:delete")
    public R<Void> delete(@PathVariable Long id) {
        favoriteService.deleteFavorite(UserContext.userId(), id);
        return R.ok();
    }
}
