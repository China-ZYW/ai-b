package com.aibi.module.chat.controller;

import com.aibi.common.response.R;
import com.aibi.module.chat.skill.AskSkillRouter;
import com.aibi.module.chat.skill.SkillVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问数技能清单（V4 Skill 模式）：{@code GET /api/chat/skills}。
 *
 * <p>透出平台当前可用的答问能力（编码 / 名称 / 说明 / 优先级 / 是否免大模型 / 是否兜底），
 * 供前端在结果面板标注"本次由哪个技能回答"，也便于管理员核对租户启停配置是否生效。
 *
 * <p>只读且无敏感信息，故任何已认证用户可访问——它描述的是能力而非数据。
 */
@RestController
@RequestMapping("/api/chat")
public class AskSkillController {

    private final AskSkillRouter router;

    public AskSkillController(AskSkillRouter router) {
        this.router = router;
    }

    @GetMapping("/skills")
    public R<List<SkillVO>> skills() {
        return R.ok(router.descriptors());
    }
}
