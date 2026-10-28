package com.aibi.module.attribution.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.attribution.AttributionService;
import com.aibi.module.attribution.DimensionResolver;
import com.aibi.module.attribution.dto.AttributionOptionVO;
import com.aibi.module.attribution.dto.AttributionReq;
import com.aibi.module.attribution.dto.AttributionVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 归因分析接口：回答"指标为什么涨/跌"。
 *
 * <p><b>为什么是独立接口而不是塞进 {@code /api/chat}</b>：一次归因要返回趋势、量价因子、维度贡献
 * 三份结构与语义都不同的产物，还要把三套 SQL 分别透出供审计复算；而问数技能契约
 * （{@code SkillPlan}）只能承载单一 {@code QueryResult}。拆成独立接口后，前端快照页在识别到
 * 波动类问题时单独调用本接口渲染「维度归因」模块，既不动问数主链路，也保留了逐份审计能力。
 *
 * <p><b>权限</b>：归因会按维度成员展开业务表现（"华南区拖累 -38%"），属敏感经营信息，
 * 故两个端点均挂权限点（种子见 {@code RbacSeeder}）；维度列表已在该层之下由
 * {@link DimensionResolver} 剔除敏感列，形成"权限 + 字段级"双重护栏。
 */
@RestController
@RequestMapping("/api/attribution")
public class AttributionController {

    private final AttributionService attributionService;

    public AttributionController(AttributionService attributionService) {
        this.attributionService = attributionService;
    }

    /**
     * 执行一次归因分析。
     *
     * @param req 归因请求（指标 + 维度 + 对比基准 + 期间）
     * @return 归因结果（贡献明细 / 量价因子 / 三套 SQL / 校验提示）
     */
    @PostMapping("/run")
    @RequiresPermission("attribution:run:execute")
    public R<AttributionVO> run(@RequestBody @Valid AttributionReq req) {
        return R.ok(attributionService.run(req));
    }

    /**
     * 列出某指标可用于归因的维度（已剔除语义层标记的敏感列）。
     *
     * @param metricId 指标 id
     */
    @GetMapping("/dimensions")
    @RequiresPermission("attribution:dimension:read")
    public R<AttributionOptionVO> dimensions(@RequestParam Long metricId) {
        return R.ok(attributionService.dimensions(metricId));
    }
}
