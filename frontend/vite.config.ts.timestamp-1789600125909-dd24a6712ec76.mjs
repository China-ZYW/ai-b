// vite.config.ts
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "file:///D:/project/qoder/b-ai/b-ai/frontend/node_modules/vite/dist/node/index.js";
import vue from "file:///D:/project/qoder/b-ai/b-ai/frontend/node_modules/@vitejs/plugin-vue/dist/index.mjs";
var __vite_injected_original_import_meta_url = "file:///D:/project/qoder/b-ai/b-ai/frontend/vite.config.ts";
var backend = process.env.VITE_BACKEND || "http://localhost:8080";
var vite_config_default = defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", __vite_injected_original_import_meta_url))
    }
  },
  server: {
    host: "0.0.0.0",
    port: 5173,
    // 后端禁用了 CORS，故开发期统一走 Vite 代理转发 /api，规避跨域
    proxy: {
      "/api": {
        target: backend,
        changeOrigin: true,
        // SSE 流式：关闭代理缓冲，保证分步事件实时下发
        configure: (proxy) => {
          proxy.on("proxyRes", (proxyRes) => {
            const ct = proxyRes.headers["content-type"] || "";
            if (String(ct).includes("text/event-stream")) {
              proxyRes.headers["cache-control"] = "no-cache, no-transform";
              proxyRes.headers["x-accel-buffering"] = "no";
            }
          });
        }
      }
    }
  },
  build: {
    outDir: "dist",
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        // ECharts / Element Plus 体积大，单独分包便于强缓存
        manualChunks: {
          echarts: ["echarts"],
          elementPlus: ["element-plus", "@element-plus/icons-vue"],
          vendor: ["vue", "vue-router", "pinia", "axios"]
        }
      }
    }
  }
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcudHMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCJEOlxcXFxwcm9qZWN0XFxcXHFvZGVyXFxcXGItYWlcXFxcYi1haVxcXFxmcm9udGVuZFwiO2NvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9maWxlbmFtZSA9IFwiRDpcXFxccHJvamVjdFxcXFxxb2RlclxcXFxiLWFpXFxcXGItYWlcXFxcZnJvbnRlbmRcXFxcdml0ZS5jb25maWcudHNcIjtjb25zdCBfX3ZpdGVfaW5qZWN0ZWRfb3JpZ2luYWxfaW1wb3J0X21ldGFfdXJsID0gXCJmaWxlOi8vL0Q6L3Byb2plY3QvcW9kZXIvYi1haS9iLWFpL2Zyb250ZW5kL3ZpdGUuY29uZmlnLnRzXCI7aW1wb3J0IHsgZmlsZVVSTFRvUGF0aCwgVVJMIH0gZnJvbSAnbm9kZTp1cmwnXHJcbmltcG9ydCB7IGRlZmluZUNvbmZpZyB9IGZyb20gJ3ZpdGUnXHJcbmltcG9ydCB2dWUgZnJvbSAnQHZpdGVqcy9wbHVnaW4tdnVlJ1xyXG5cclxuLy8gXHU1NDBFXHU3QUVGXHU1NzMwXHU1NzQwXHVGRjA4XHU4MDU0XHU4QzAzXHVGRjA5XHVGRjFBXHU1M0VGXHU3NTI4XHU3M0FGXHU1ODgzXHU1M0Q4XHU5MUNGIFZJVEVfQkFDS0VORCBcdTg5ODZcdTc2RDZcdUZGMENcdTlFRDhcdThCQTRcdTY3MkNcdTY3M0EgODA4MFxyXG5jb25zdCBiYWNrZW5kID0gcHJvY2Vzcy5lbnYuVklURV9CQUNLRU5EIHx8ICdodHRwOi8vbG9jYWxob3N0OjgwODAnXHJcblxyXG4vLyBodHRwczovL3ZpdGVqcy5kZXYvY29uZmlnL1xyXG5leHBvcnQgZGVmYXVsdCBkZWZpbmVDb25maWcoe1xyXG4gIHBsdWdpbnM6IFt2dWUoKV0sXHJcbiAgcmVzb2x2ZToge1xyXG4gICAgYWxpYXM6IHtcclxuICAgICAgJ0AnOiBmaWxlVVJMVG9QYXRoKG5ldyBVUkwoJy4vc3JjJywgaW1wb3J0Lm1ldGEudXJsKSlcclxuICAgIH1cclxuICB9LFxyXG4gIHNlcnZlcjoge1xyXG4gICAgaG9zdDogJzAuMC4wLjAnLFxyXG4gICAgcG9ydDogNTE3MyxcclxuICAgIC8vIFx1NTQwRVx1N0FFRlx1Nzk4MVx1NzUyOFx1NEU4NiBDT1JTXHVGRjBDXHU2NTQ1XHU1RjAwXHU1M0QxXHU2NzFGXHU3RURGXHU0RTAwXHU4RDcwIFZpdGUgXHU0RUUzXHU3NDA2XHU4RjZDXHU1M0QxIC9hcGlcdUZGMENcdTg5QzRcdTkwN0ZcdThERThcdTU3REZcclxuICAgIHByb3h5OiB7XHJcbiAgICAgICcvYXBpJzoge1xyXG4gICAgICAgIHRhcmdldDogYmFja2VuZCxcclxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXHJcbiAgICAgICAgLy8gU1NFIFx1NkQ0MVx1NUYwRlx1RkYxQVx1NTE3M1x1OTVFRFx1NEVFM1x1NzQwNlx1N0YxM1x1NTFCMlx1RkYwQ1x1NEZERFx1OEJDMVx1NTIwNlx1NkI2NVx1NEU4Qlx1NEVGNlx1NUI5RVx1NjVGNlx1NEUwQlx1NTNEMVxyXG4gICAgICAgIGNvbmZpZ3VyZTogKHByb3h5KSA9PiB7XHJcbiAgICAgICAgICBwcm94eS5vbigncHJveHlSZXMnLCAocHJveHlSZXMpID0+IHtcclxuICAgICAgICAgICAgY29uc3QgY3QgPSBwcm94eVJlcy5oZWFkZXJzWydjb250ZW50LXR5cGUnXSB8fCAnJ1xyXG4gICAgICAgICAgICBpZiAoU3RyaW5nKGN0KS5pbmNsdWRlcygndGV4dC9ldmVudC1zdHJlYW0nKSkge1xyXG4gICAgICAgICAgICAgIHByb3h5UmVzLmhlYWRlcnNbJ2NhY2hlLWNvbnRyb2wnXSA9ICduby1jYWNoZSwgbm8tdHJhbnNmb3JtJ1xyXG4gICAgICAgICAgICAgIHByb3h5UmVzLmhlYWRlcnNbJ3gtYWNjZWwtYnVmZmVyaW5nJ10gPSAnbm8nXHJcbiAgICAgICAgICAgIH1cclxuICAgICAgICAgIH0pXHJcbiAgICAgICAgfVxyXG4gICAgICB9XHJcbiAgICB9XHJcbiAgfSxcclxuICBidWlsZDoge1xyXG4gICAgb3V0RGlyOiAnZGlzdCcsXHJcbiAgICBjaHVua1NpemVXYXJuaW5nTGltaXQ6IDEyMDAsXHJcbiAgICByb2xsdXBPcHRpb25zOiB7XHJcbiAgICAgIG91dHB1dDoge1xyXG4gICAgICAgIC8vIEVDaGFydHMgLyBFbGVtZW50IFBsdXMgXHU0RjUzXHU3OUVGXHU1OTI3XHVGRjBDXHU1MzU1XHU3MkVDXHU1MjA2XHU1MzA1XHU0RkJGXHU0RThFXHU1RjNBXHU3RjEzXHU1QjU4XHJcbiAgICAgICAgbWFudWFsQ2h1bmtzOiB7XHJcbiAgICAgICAgICBlY2hhcnRzOiBbJ2VjaGFydHMnXSxcclxuICAgICAgICAgIGVsZW1lbnRQbHVzOiBbJ2VsZW1lbnQtcGx1cycsICdAZWxlbWVudC1wbHVzL2ljb25zLXZ1ZSddLFxyXG4gICAgICAgICAgdmVuZG9yOiBbJ3Z1ZScsICd2dWUtcm91dGVyJywgJ3BpbmlhJywgJ2F4aW9zJ11cclxuICAgICAgICB9XHJcbiAgICAgIH1cclxuICAgIH1cclxuICB9XHJcbn0pXHJcbiJdLAogICJtYXBwaW5ncyI6ICI7QUFBdVMsU0FBUyxlQUFlLFdBQVc7QUFDMVUsU0FBUyxvQkFBb0I7QUFDN0IsT0FBTyxTQUFTO0FBRnlLLElBQU0sMkNBQTJDO0FBSzFPLElBQU0sVUFBVSxRQUFRLElBQUksZ0JBQWdCO0FBRzVDLElBQU8sc0JBQVEsYUFBYTtBQUFBLEVBQzFCLFNBQVMsQ0FBQyxJQUFJLENBQUM7QUFBQSxFQUNmLFNBQVM7QUFBQSxJQUNQLE9BQU87QUFBQSxNQUNMLEtBQUssY0FBYyxJQUFJLElBQUksU0FBUyx3Q0FBZSxDQUFDO0FBQUEsSUFDdEQ7QUFBQSxFQUNGO0FBQUEsRUFDQSxRQUFRO0FBQUEsSUFDTixNQUFNO0FBQUEsSUFDTixNQUFNO0FBQUE7QUFBQSxJQUVOLE9BQU87QUFBQSxNQUNMLFFBQVE7QUFBQSxRQUNOLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQTtBQUFBLFFBRWQsV0FBVyxDQUFDLFVBQVU7QUFDcEIsZ0JBQU0sR0FBRyxZQUFZLENBQUMsYUFBYTtBQUNqQyxrQkFBTSxLQUFLLFNBQVMsUUFBUSxjQUFjLEtBQUs7QUFDL0MsZ0JBQUksT0FBTyxFQUFFLEVBQUUsU0FBUyxtQkFBbUIsR0FBRztBQUM1Qyx1QkFBUyxRQUFRLGVBQWUsSUFBSTtBQUNwQyx1QkFBUyxRQUFRLG1CQUFtQixJQUFJO0FBQUEsWUFDMUM7QUFBQSxVQUNGLENBQUM7QUFBQSxRQUNIO0FBQUEsTUFDRjtBQUFBLElBQ0Y7QUFBQSxFQUNGO0FBQUEsRUFDQSxPQUFPO0FBQUEsSUFDTCxRQUFRO0FBQUEsSUFDUix1QkFBdUI7QUFBQSxJQUN2QixlQUFlO0FBQUEsTUFDYixRQUFRO0FBQUE7QUFBQSxRQUVOLGNBQWM7QUFBQSxVQUNaLFNBQVMsQ0FBQyxTQUFTO0FBQUEsVUFDbkIsYUFBYSxDQUFDLGdCQUFnQix5QkFBeUI7QUFBQSxVQUN2RCxRQUFRLENBQUMsT0FBTyxjQUFjLFNBQVMsT0FBTztBQUFBLFFBQ2hEO0FBQUEsTUFDRjtBQUFBLElBQ0Y7QUFBQSxFQUNGO0FBQ0YsQ0FBQzsiLAogICJuYW1lcyI6IFtdCn0K
