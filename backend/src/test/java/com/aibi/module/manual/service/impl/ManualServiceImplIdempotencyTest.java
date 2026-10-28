package com.aibi.module.manual.service.impl;

import com.aibi.module.manual.dto.ManualSaveForm;
import com.aibi.module.manual.dto.ManualVO;
import com.aibi.module.manual.entity.UserManual;
import com.aibi.module.manual.mapper.UserManualMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 用户手册的幂等护栏（v3.21 / §38）。
 *
 * <p><b>为什么需要</b>：{@code user_manual.version} 是<b>用户可见的「手册版本」</b>
 * （发布页直接展示），它表达的是"这份手册一共发过几版"。而「发布」按钮此前没有任何护栏——
 * 连点两次，内容一个字没变，版本号却从 3 跳到 5；草稿保存同理，会把 {@code updated_at}
 * 刷成"刚被人动过"，让"最后一次是谁什么时候改的"失真。
 *
 * <p>与配置中心（{@code ConfigService}）共用同一条约定：
 * <b>带"内容版本号"的事实记录，空提交不递增</b>。反过来，
 * {@code token_version} / {@code perm_version} 是<b>安全计数器</b>——必须每次操作都递增，
 * 绝不可套用本护栏（否则改密码不踢下线、改权限不刷新会话）。这条边界也由本测试守住：
 * 手册的 version 是内容版本，不是会话版本。
 *
 * <p>测试手法：{@link UserManualMapper} 用 Mockito 替身；{@code singleton()} 直接覆写为返回
 * 固定行，把"从库里取那唯一一行"这个与本次改动无关的步骤隔离掉——被测的是幂等判定本身。
 */
class ManualServiceImplIdempotencyTest {

    /** 可注入替身的手册服务：{@code baseMapper} 是 ServiceImpl 的 protected 字段，子类可直接赋值。 */
    private static class TestableManualService extends ManualServiceImpl {

        private final UserManual row;

        TestableManualService(UserManualMapper mapper, UserManual row) {
            this.baseMapper = mapper;
            this.row = row;
        }

        @Override
        public UserManual singleton() {
            return row;
        }
    }

    private UserManualMapper mapper;
    private UserManual row;
    private TestableManualService service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserManualMapper.class);
        row = new UserManual();
        row.setId(1L);
        service = new TestableManualService(mapper, row);
    }

    /** 造一份"已发布，内容 abc / 标题 T，版本 4"的手册。 */
    private void publishedAbc() {
        row.setTitle("T");
        row.setContent("abc");
        row.setPublishedTitle("T");
        row.setPublishedContent("abc");
        row.setStatus("PUBLISHED");
        row.setVersion(4);
    }

    @Test
    @DisplayName("发布：草稿与已发布版一致 → 不写库、版本号不变（连点发布不再虚增版本）")
    void publishWithNoChangeIsNoOp() {
        publishedAbc();

        ManualVO vo = service.publish(9L);

        assertEquals(4, vo.version(), "内容没变，版本号必须保持 4");
        assertEquals("PUBLISHED", vo.status());
        verify(mapper, never()).updateById(any());
    }

    @Test
    @DisplayName("发布：草稿有未发布改动 → 写库一次、版本号 +1、发布快照同步")
    void publishWithChangeBumpsVersion() {
        publishedAbc();
        row.setContent("abcd");   // 超管改了草稿但没发布
        row.setStatus("DRAFT");

        ManualVO vo = service.publish(9L);

        assertNotNull(vo);
        assertEquals(5, vo.version(), "真实发布才递增版本号");
        assertEquals("abcd", vo.publishedContent(), "发布快照必须跟上草稿");
        assertEquals("PUBLISHED", vo.status());
        assertNotNull(vo.publishedAt());
        assertEquals(9L, row.getUpdatedBy());
        verify(mapper, times(1)).updateById(any());
    }

    @Test
    @DisplayName("发布：只改了标题（正文未动）也算一次真实发布")
    void publishTitleOnlyChangeBumpsVersion() {
        publishedAbc();
        row.setTitle("T2");
        row.setStatus("DRAFT");

        ManualVO vo = service.publish(9L);

        assertEquals(5, vo.version());
        assertEquals("T2", vo.publishedTitle());
        assertEquals("abc", vo.publishedContent(), "正文没改也要原样带过去");
        verify(mapper, times(1)).updateById(any());
    }

    @Test
    @DisplayName("发布：从未发布过（publishedContent 为空）→ 不能被判成「无变化」，必须发出去")
    void firstPublishIsNeverNoOp() {
        row.setTitle("T");
        row.setContent("abc");
        row.setStatus("DRAFT");
        row.setVersion(0);
        // publishedContent / publishedTitle 故意留 null = 从未发布

        ManualVO vo = service.publish(9L);

        assertEquals(1, vo.version(), "首发布必须把版本推到 1");
        assertEquals("abc", vo.publishedContent());
        verify(mapper, times(1)).updateById(any());
    }

    @Test
    @DisplayName("草稿保存：标题/正文/状态三者都没变 → 不写库（不刷 updated_at）")
    void saveDraftWithNoChangeIsNoOp() {
        publishedAbc();

        ManualVO vo = service.saveDraft(new ManualSaveForm("T", "abc"), 9L);

        assertEquals("PUBLISHED", vo.status(), "草稿与发布版一致 → 状态回到 PUBLISHED");
        assertNull(row.getUpdatedBy(), "空提交不应把 updated_by 刷成本次操作人");
        verify(mapper, never()).updateById(any());
    }

    @Test
    @DisplayName("草稿保存：正文改了 → 写库、状态转 DRAFT、updated_by 落库")
    void saveDraftWithChangeWrites() {
        publishedAbc();

        ManualVO vo = service.saveDraft(new ManualSaveForm("T", "abcd"), 9L);

        assertEquals("DRAFT", vo.status(), "草稿与发布版不一致 → DRAFT");
        assertEquals("abcd", row.getContent());
        assertEquals(9L, row.getUpdatedBy());
        assertEquals(4, vo.version(), "保存草稿不动版本号（版本只在发布时+1）");
        verify(mapper, times(1)).updateById(any());
    }

    @Test
    @DisplayName("草稿保存：把改动撤销回与发布版一致 → 状态回到 PUBLISHED（这算一次真实变更）")
    void saveDraftRevertingToPublishedWritesStatusBack() {
        publishedAbc();
        row.setContent("abcd");   // 先有一版未发布的改动
        row.setStatus("DRAFT");

        ManualVO vo = service.saveDraft(new ManualSaveForm("T", "abc"), 9L);

        assertEquals("PUBLISHED", vo.status(), "撤销改动后草稿与发布版一致，应回到 PUBLISHED");
        verify(mapper, times(1)).updateById(any());
    }

    @Test
    @DisplayName("草稿保存：content 传 null 视为空串（与 entity 口径一致），不会被误判成「正文变了」")
    void saveDraftNullContentBecomesEmptyString() {
        row.setTitle("T");
        row.setContent("");
        row.setPublishedTitle("T");
        row.setPublishedContent("");   // 已发布的就是空正文
        row.setStatus("PUBLISHED");
        row.setVersion(2);

        ManualVO vo = service.saveDraft(new ManualSaveForm("T", null), 9L);

        assertEquals(2, vo.version());
        verify(mapper, never()).updateById(any());
    }
}
