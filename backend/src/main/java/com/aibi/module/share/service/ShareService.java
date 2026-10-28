package com.aibi.module.share.service;

import com.aibi.common.tenant.PlanType;
import com.aibi.module.share.dto.ShareAccessResponse;
import com.aibi.module.share.dto.ShareRequest;
import com.aibi.module.share.dto.ShareVO;
import com.aibi.module.share.entity.ShareLink;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 分享链接服务（M7 / 开发计划 T4.7）：创建带 token/密码/有效期/水印的分享，并提供公开只读访问。四层架构之业务层接口。
 *
 * <p>继承 {@link IService} 获得 MyBatis-Plus 通用 CRUD。创建者侧按 {@code tenant_id}(拦截器自动)+{@code uid} 隔离；
 * 公开访问凭 {@code token} 匿名只读（实现内走关闭租户注入的 Mapper 方法）。
 */
public interface ShareService extends IService<ShareLink> {

    /** 创建分享链接，返回其视图（含公开访问 url）。 */
    ShareVO createShare(Long uid, PlanType plan, ShareRequest request);

    /** 我的分享列表（不含快照与密码明文）。 */
    List<ShareVO> listShares(Long uid);

    /** 删除分享（仅限本租户本人）。 */
    void deleteShare(Long uid, Long id);

    /**
     * 公开访问一个分享：校验有效期与密码，命中则累加访问次数并返回快照视图。
     *
     * @param token    公开令牌
     * @param password 访问密码（分享未设密码时忽略）
     */
    ShareAccessResponse access(String token, String password);
}
