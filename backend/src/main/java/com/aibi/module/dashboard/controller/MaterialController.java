package com.aibi.module.dashboard.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.dashboard.dto.MaterialUploadResult;
import com.aibi.module.dashboard.service.MaterialStorageService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 大屏背景素材上传（范围 B，需登录租户）：接收图片，返回可经免认证公开端点访问的 URL，
 * 供编辑器写入大屏 {@code background = url(...)}。
 *
 * <p><b>权限点（L2）</b>：此前只有登录态约束，任何已登录用户（含只读成员、计费联系人）都能向
 * 服务端磁盘/对象存储写文件——既是存储滥用面，也是"上传内容可被公开 URL 访问"的合规风险。
 * 上传能力只服务于大屏编辑，故收敛到 {@code dashboard:item:write}（与驾驶舱编辑权限同源）。
 */
@RestController
@RequestMapping("/api/dashboard")
public class MaterialController {

    private final MaterialStorageService materialStorageService;

    public MaterialController(MaterialStorageService materialStorageService) {
        this.materialStorageService = materialStorageService;
    }

    /** 上传背景图（png/jpg/jpeg/webp/gif/svg），返回公开访问 URL。 */
    @PostMapping("/materials/upload")
    @RequiresPermission("dashboard:item:write")
    public R<MaterialUploadResult> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(materialStorageService.store(file));
    }
}
