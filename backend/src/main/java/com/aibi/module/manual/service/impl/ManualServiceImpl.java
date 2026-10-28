package com.aibi.module.manual.service.impl;

import com.aibi.module.manual.dto.ManualSaveForm;
import com.aibi.module.manual.dto.ManualVO;
import com.aibi.module.manual.dto.PublishedManualVO;
import com.aibi.module.manual.entity.UserManual;
import com.aibi.module.manual.mapper.UserManualMapper;
import com.aibi.module.manual.service.ManualService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 用户手册服务实现（MyBatis-Plus 四层架构之 ServiceImpl）。
 *
 * <p>{@code user_manual} 在租户 IGNORE 名单，故 CRUD 不追加 tenant_id，天然是平台级全局单例。
 * {@link #singleton()} 取 id 最小的一行（正常只有一行），不存在则惰性建一条空草稿——
 * 保证老库升级（DataSeeder 未补种）时功能仍可用。
 */
@Service
public class ManualServiceImpl extends ServiceImpl<UserManualMapper, UserManual> implements ManualService {

    private static final Logger log = LoggerFactory.getLogger(ManualServiceImpl.class);

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String DEFAULT_TITLE = "AI 数据助理用户手册";

    @Override
    public UserManual singleton() {
        List<UserManual> rows = lambdaQuery().orderByAsc(UserManual::getId).last("LIMIT 1").list();
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        UserManual fresh = new UserManual();
        fresh.setTitle(DEFAULT_TITLE);
        fresh.setContent("");
        fresh.setStatus(STATUS_DRAFT);
        fresh.setVersion(0);
        save(fresh);
        log.info("用户手册单例不存在，已惰性初始化空草稿: id={}", fresh.getId());
        return fresh;
    }

    @Override
    public ManualVO getAdminView() {
        return toAdminVO(singleton());
    }

    @Override
    @Transactional
    public ManualVO saveDraft(ManualSaveForm form, Long uid) {
        UserManual m = singleton();
        String title = form.title().trim();
        String content = form.content() == null ? "" : form.content();
        // 只要草稿与发布版不一致就是 DRAFT；一致则回到 PUBLISHED（例如撤销了未发布的改动）
        boolean sameAsPublished = m.getPublishedContent() != null
                && m.getPublishedContent().equals(content)
                && m.getPublishedTitle() != null && m.getPublishedTitle().equals(title);
        String status = sameAsPublished ? STATUS_PUBLISHED : STATUS_DRAFT;
        // 幂等护栏：标题 / 正文 / 状态三者都没变 → 不写库。
        // 「保存」按钮可被连点，且发布前还会先自动保存一次草稿（见前端 onPublish）——
        // 空写会把 updated_at / updated_by 刷成"刚被人动过"的样子，
        // 让"这份手册最后一次是谁什么时候改的"失真。
        if (Objects.equals(m.getTitle(), title)
                && Objects.equals(m.getContent(), content)
                && Objects.equals(m.getStatus(), status)) {
            log.debug("手册草稿无变化，跳过写入: id={}", m.getId());
            return toAdminVO(m);
        }
        m.setTitle(title);
        m.setContent(content);
        m.setStatus(status);
        m.setUpdatedBy(uid);
        updateById(m);
        log.info("保存用户手册草稿: id={}, title={}, status={}", m.getId(), m.getTitle(), m.getStatus());
        return toAdminVO(m);
    }

    @Override
    @Transactional
    public ManualVO publish(Long uid) {
        UserManual m = singleton();
        String content = m.getContent() == null ? "" : m.getContent();
        // 幂等护栏：草稿与已发布版完全一致、且当前就是 PUBLISHED → 这次「发布」没有内容要发布。
        // version 是用户可见的「手册版本」（发布页展示），连点两次发布不该让 3 变成 4——
        // 那会让"手册一共发过几版"这件事失真。
        if (STATUS_PUBLISHED.equals(m.getStatus())
                && m.getPublishedContent() != null
                && m.getPublishedContent().equals(content)
                && Objects.equals(m.getPublishedTitle(), m.getTitle())) {
            log.info("手册内容与已发布版一致，跳过发布（版本号保持 {}）: id={}", m.getVersion(), m.getId());
            return toAdminVO(m);
        }
        m.setPublishedTitle(m.getTitle());
        m.setPublishedContent(m.getContent());
        m.setVersion((m.getVersion() == null ? 0 : m.getVersion()) + 1);
        m.setStatus(STATUS_PUBLISHED);
        m.setUpdatedBy(uid);
        m.setPublishedAt(LocalDateTime.now());
        updateById(m);
        log.info("发布用户手册: id={}, version={}", m.getId(), m.getVersion());
        return toAdminVO(m);
    }

    @Override
    public PublishedManualVO getPublished() {
        UserManual m = singleton();
        if (m.getPublishedContent() == null) {
            return null;   // 从未发布
        }
        return new PublishedManualVO(m.getPublishedTitle(), m.getPublishedContent(),
                m.getVersion(), m.getPublishedAt());
    }

    private ManualVO toAdminVO(UserManual m) {
        return new ManualVO(m.getTitle(), m.getContent(), m.getStatus(),
                m.getVersion() == null ? 0 : m.getVersion(),
                m.getPublishedContent() != null,
                m.getPublishedTitle(), m.getPublishedContent(),
                m.getUpdatedAt(), m.getPublishedAt());
    }
}
