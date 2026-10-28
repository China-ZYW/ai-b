package com.aibi.module.dashboard.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.dashboard.dto.DashboardTemplateForm;
import com.aibi.module.dashboard.dto.DashboardTemplateVO;
import com.aibi.module.dashboard.entity.DashboardTemplate;
import com.aibi.module.dashboard.mapper.DashboardTemplateMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 驾驶舱内置模板服务：列出全局模板供所有租户实例化；超管可增改自定义模板。
 *
 * <p>{@code dashboard_template} 为全局表（IGNORE 名单），不含 tenant_id，所有租户共享同一套模板。
 * 内置模板由 {@code DataSeeder} 播种（{@code builtin=1}）；超管新增的为 {@code builtin=0}。
 */
@Service
public class DashboardTemplateService {

    private final DashboardTemplateMapper templateMapper;
    private final DashboardJsonSupport json;

    public DashboardTemplateService(DashboardTemplateMapper templateMapper, DashboardJsonSupport json) {
        this.templateMapper = templateMapper;
        this.json = json;
    }

    public List<DashboardTemplateVO> list() {
        return templateMapper.selectList(Wrappers.<DashboardTemplate>lambdaQuery()
                        .orderByAsc(DashboardTemplate::getId))
                .stream().map(this::toVO).toList();
    }

    public DashboardTemplateVO get(Long id) {
        return toVO(require(id));
    }

    @Transactional
    public DashboardTemplateVO create(DashboardTemplateForm form) {
        Long dup = templateMapper.selectCount(Wrappers.<DashboardTemplate>lambdaQuery()
                .eq(DashboardTemplate::getCode, form.code()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板编码已存在: " + form.code());
        }
        DashboardTemplate t = new DashboardTemplate();
        t.setCode(form.code());
        t.setName(form.name());
        t.setCategory(form.category());
        t.setDescription(form.description());
        t.setIcon(form.icon());
        t.setTheme(blankTo(form.theme(), "dark"));
        t.setLayout(json.write(form.layout()));
        t.setBuiltin(0);   // 超管自定义模板
        templateMapper.insert(t);
        return toVO(t);
    }

    @Transactional
    public DashboardTemplateVO update(Long id, DashboardTemplateForm form) {
        require(id);
        templateMapper.update(null, Wrappers.<DashboardTemplate>lambdaUpdate()
                .eq(DashboardTemplate::getId, id)
                .set(DashboardTemplate::getCode, form.code())
                .set(DashboardTemplate::getName, form.name())
                .set(DashboardTemplate::getCategory, form.category())
                .set(DashboardTemplate::getDescription, form.description())
                .set(DashboardTemplate::getIcon, form.icon())
                .set(DashboardTemplate::getTheme, blankTo(form.theme(), "dark"))
                .set(DashboardTemplate::getLayout, json.write(form.layout())));
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        templateMapper.deleteById(id);
    }

    private DashboardTemplate require(Long id) {
        DashboardTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "驾驶舱模板不存在: " + id);
        }
        return t;
    }

    private DashboardTemplateVO toVO(DashboardTemplate t) {
        return new DashboardTemplateVO(t.getId(), t.getCode(), t.getName(), t.getCategory(),
                t.getDescription(), t.getIcon(), t.getTheme(), json.read(t.getLayout()));
    }

    private String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
