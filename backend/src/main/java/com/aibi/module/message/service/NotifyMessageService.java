package com.aibi.module.message.service;

import com.aibi.common.response.PageResult;
import com.aibi.module.message.dto.NotifyMessageVO;
import com.aibi.module.message.entity.NotifyMessage;
import com.aibi.module.message.mapper.NotifyMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 站内信收件箱服务（仅服务于"当前登录用户本人"）。
 *
 * <p>全部查询/写入都以 {@code (tenantId, uid)} 双重收敛：即使租户拦截器漏挡，也读不到别人的信。
 * 写操作（标记已读/删除）同样强制 {@code uid} 条件，避免越权改他人的信。
 */
@Service
public class NotifyMessageService {

    private final NotifyMessageMapper mapper;

    public NotifyMessageService(NotifyMessageMapper mapper) {
        this.mapper = mapper;
    }

    /** 收件箱列表（倒序），可按未读过滤；分页用 limit/offset。 */
    public PageResult<NotifyMessageVO> list(Long uid, Long tenantId, boolean unreadOnly, int limit, int offset) {
        int l = Math.min(Math.max(limit, 1), 200);
        int o = Math.max(offset, 0);
        LambdaQueryWrapper<NotifyMessage> q = base(uid, tenantId, unreadOnly).orderByDesc(NotifyMessage::getCreatedAt);
        List<NotifyMessage> items = mapper.selectList(q.last("LIMIT " + l + " OFFSET " + o));
        long total = mapper.selectCount(base(uid, tenantId, unreadOnly));
        return PageResult.of(items.stream().map(NotifyMessageVO::from).toList(), total, l, o);
    }

    /** 当前用户未读数。 */
    public long unreadCount(Long uid, Long tenantId) {
        return mapper.selectCount(base(uid, tenantId, true));
    }

    /** 标记单条为已读（仅限本人）。返回命中行数。 */
    public int markRead(Long id, Long uid, Long tenantId) {
        return mapper.update(null, new LambdaUpdateWrapper<NotifyMessage>()
                .eq(NotifyMessage::getId, id)
                .eq(NotifyMessage::getUid, uid)
                .eq(NotifyMessage::getTenantId, tenantId)
                .set(NotifyMessage::getIsRead, true));
    }

    /** 全部标记为已读（仅限本人）。 */
    public int markAllRead(Long uid, Long tenantId) {
        return mapper.update(null, new LambdaUpdateWrapper<NotifyMessage>()
                .eq(NotifyMessage::getUid, uid)
                .eq(NotifyMessage::getTenantId, tenantId)
                .set(NotifyMessage::getIsRead, true));
    }

    /** 删除单条（仅限本人）。 */
    public int delete(Long id, Long uid, Long tenantId) {
        return mapper.delete(new LambdaQueryWrapper<NotifyMessage>()
                .eq(NotifyMessage::getId, id)
                .eq(NotifyMessage::getUid, uid)
                .eq(NotifyMessage::getTenantId, tenantId));
    }

    private LambdaQueryWrapper<NotifyMessage> base(Long uid, Long tenantId, boolean unreadOnly) {
        LambdaQueryWrapper<NotifyMessage> q = new LambdaQueryWrapper<>();
        q.eq(NotifyMessage::getTenantId, tenantId).eq(NotifyMessage::getUid, uid);
        if (unreadOnly) {
            q.eq(NotifyMessage::getIsRead, false);
        }
        return q;
    }
}
