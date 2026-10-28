package com.aibi.module.embed.controller;

import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.embed.dto.ChatEmbedVO;
import com.aibi.module.embed.service.ChatEmbedService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 对话问数嵌入管理接口（租户内）：创建 / 列出 / 撤销嵌入。
 *
 * <p>权限点 {@code chat:embed:write}。创建成功后返回含 {@code token} 的实体，
 * 前端据此拼出 iframe 嵌入代码；撤销后该令牌立即失效。
 */
@RestController
@RequestMapping("/api/chat-embed")
public class ChatEmbedController {

    private final ChatEmbedService service;

    public ChatEmbedController(ChatEmbedService service) {
        this.service = service;
    }

    /** 创建请求。{@code datasourceIds} 多选数据源；{@code expireAt} 为 ISO 时间串（如 2026-12-31T23:59:59），留空表示长期有效。 */
    public record CreateForm(String name, List<Long> datasourceIds, String allowedOrigins, String expireAt) {
    }

    @GetMapping
    @RequiresPermission("chat:embed:write")
    public R<List<ChatEmbedVO>> list() {
        return R.ok(service.listViewos());
    }

    @PostMapping
    @RequiresPermission("chat:embed:write")
    public R<ChatEmbedVO> create(@RequestBody CreateForm form) {
        if (form == null || form.datasourceIds() == null || form.datasourceIds().isEmpty()) {
            return R.fail(ErrorCode.PARAM_ERROR, "datasourceIds 不能为空（至少选择一个数据源）");
        }
        LocalDateTime expire = null;
        if (form.expireAt() != null && !form.expireAt().isBlank()) {
            try {
                expire = LocalDateTime.parse(form.expireAt().trim());
            } catch (DateTimeParseException e) {
                return R.fail(ErrorCode.PARAM_ERROR, "expireAt 格式应为 ISO 时间，如 2026-12-31T23:59:59");
            }
        }
        return R.ok(service.toVO(service.create(form.name(), form.datasourceIds(), form.allowedOrigins(), expire)));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("chat:embed:write")
    public R<Boolean> revoke(@PathVariable Long id) {
        service.revoke(id);
        return R.ok(true);
    }
}
