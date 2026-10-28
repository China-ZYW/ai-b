package com.aibi.module.dashboard.controller;

import com.aibi.module.dashboard.service.MaterialStorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 大屏背景素材公开访问（免认证）：路径 {@code /api/public/materials/**} 已在 {@code SecurityConfig} 放行，
 * 供公开分享页加载背景图（背景 URL 由上传接口返回，形如 {@code /api/public/materials/{uuid}.{ext}}）。
 */
@RestController
@RequestMapping("/api/public/materials")
public class PublicMaterialController {

    private final MaterialStorageService materialStorageService;

    public PublicMaterialController(MaterialStorageService materialStorageService) {
        this.materialStorageService = materialStorageService;
    }

    /** 免认证读取一张背景图（文件名白名单 + 目录前缀双重校验）。 */
    @GetMapping("/{file}")
    public ResponseEntity<Resource> serve(@PathVariable String file) {
        return materialStorageService.serve(file);
    }
}
