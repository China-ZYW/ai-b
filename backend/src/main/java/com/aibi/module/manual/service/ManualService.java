package com.aibi.module.manual.service;

import com.aibi.module.manual.dto.ManualSaveForm;
import com.aibi.module.manual.dto.ManualVO;
import com.aibi.module.manual.dto.PublishedManualVO;
import com.aibi.module.manual.entity.UserManual;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 用户手册服务：平台级全局单例文档的编辑（草稿）/发布/阅读。
 *
 * <p>全库只维护一行；{@link #singleton()} 保证读取时若不存在则惰性初始化一条空草稿，
 * 故即便 DataSeeder 未播种（如老库升级）功能也可用。
 */
public interface ManualService extends IService<UserManual> {

    /** 超管编辑视图：草稿 + 已发布快照（含对比所需字段）。 */
    ManualVO getAdminView();

    /** 保存草稿（仅写 title/content，status 置 DRAFT，不影响已发布快照）。 */
    ManualVO saveDraft(ManualSaveForm form, Long uid);

    /** 发布：把当前草稿拷贝到发布列，version+1，status 置 PUBLISHED。 */
    ManualVO publish(Long uid);

    /** 用户阅读视图：仅已发布快照；从未发布时返回 {@code null}（前端提示"手册尚未发布"）。 */
    PublishedManualVO getPublished();

    /** 取全局单例行；不存在则初始化一条空草稿并落库。 */
    UserManual singleton();
}
