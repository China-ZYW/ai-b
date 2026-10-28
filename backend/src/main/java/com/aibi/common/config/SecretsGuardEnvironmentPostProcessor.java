package com.aibi.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 安存治理 T1：凭据守卫（EnvironmentPostProcessor，启动最早期执行，先于 DataSource/任何 Bean 创建）。
 *
 * <p>两项职责：
 * <ol>
 *   <b>①自动加载本地凭据文件 local.env</b>——按以下顺序查找（找到即止）：
 *   环境变量 {@code AIBI_ENV_FILE} 指定路径 → 工作目录/local.env → 工作目录/backend/local.env →
 *   自工作目录向上逐级查找（最多 5 级，兼容从仓库根启动 mvn spring-boot:run）。
 *   文件格式为 {@code KEY=VALUE} 行（# 注释与空行忽略，值可带成对引号）；文件内容仅作为
 *   <b>最低优先级</b>属性源注入——真实环境变量始终覆盖它，因此生产 systemd 的
 *   {@code EnvironmentFile} 与本地文件互不干扰。<b>该文件必须保持 gitignore，绝不入库。</b>
 *   <b>②必填凭据 fail-fast 校验</b>——任一缺失即抛中文指引并终止启动，避免带着空凭据
 *   半死不活地起来（表现为运行期 500，比启动失败难排查得多）。校验项：
 *   元数据库 url/username/password、{@code aibi.jwt.secret}（≥32 字节）、
 *   {@code aibi.datasource.cipher-key}。可用 {@code aibi.secrets.validate=false}
 *   （环境变量 AIBI_SECRETS_VALIDATE）临时跳过，仅限排障。
 * </ol>
 *
 * <p>注册方式：{@code META-INF/spring.factories} 的 {@code EnvironmentPostProcessor} 键
 * （Spring Boot 3 仍走 spring.factories 而非 AutoConfiguration.imports）。
 */
public class SecretsGuardEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 跳过的场景：测试上下文（@SpringBootTest 等）不强制本地 env 文件。 */
    private static final String TEST_MARKER_PROFILE = "test";

    @Override
    public int getOrder() {
        // ★必须晚于 ConfigDataEnvironmentPostProcessor（其 DEFAULT_ORDER = HIGHEST_PRECEDENCE + 10）：
        // 本守卫要在 application.yml 已加载进 environment 后才能解析 ${AIBI_DB_URL:} 等占位符
        // 并校验解析结果；local.env 属性源用 addLast 注入，真实环境变量（更早的属性源）始终优先。
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // ① 加载 local.env（最低优先级，真实环境变量覆盖）
        Path envFile = resolveEnvFile(environment);
        if (envFile != null) {
            Map<String, Object> props = parseEnvFile(envFile);
            if (!props.isEmpty()) {
                environment.getPropertySources().addLast(
                        new MapPropertySource("aibiLocalEnv", props));
            }
        }

        // ② fail-fast 校验（测试上下文跳过；开关可关）
        String flag = environment.getProperty("aibi.secrets.validate", "true");
        if ("false".equalsIgnoreCase(flag) || isTestContext(environment)) {
            return;
        }
        List<String> problems = new java.util.ArrayList<>();
        requireNonEmpty(environment, problems, "spring.datasource.url",
                "元数据库连接串（AIBI_DB_URL）");
        requireNonEmpty(environment, problems, "spring.datasource.username",
                "元数据库账号（AIBI_DB_USERNAME）");
        requireNonEmpty(environment, problems, "spring.datasource.password",
                "元数据库密码（AIBI_DB_PASSWORD）");
        String jwt = environment.getProperty("aibi.jwt.secret", "");
        if (jwt.isBlank()) {
            problems.add("JWT 签名密钥缺失（JWT_SECRET）");
        } else if (jwt.length() < 32) {
            problems.add("JWT 签名密钥过短（JWT_SECRET 需 ≥32 字节，当前 " + jwt.length() + "）");
        }
        requireNonEmpty(environment, problems, "aibi.datasource.cipher-key",
                "数据源凭证主密钥（AIBI_CIPHER_KEY；轮换前须先完成存量密文双 key 重加密）");

        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "%n================ aibi 启动失败：凭据校验未通过 ================%n%s%n" +
                    "注入方式（二选一）：%n" +
                    "  本地开发：在 backend/local.env 写入 KEY=VALUE（参照 backend/local.env.example，该文件已 gitignore）%n" +
                    "  生产部署：systemd EnvironmentFile=/opt/aibi/aibi.env（权限 600，不入库）%n" +
                    "临时跳过校验（仅排障）：设置环境变量 AIBI_SECRETS_VALIDATE=false%n" +
                    "=============================================================",
                    String.join(System.lineSeparator() + "  - ", problems)));
        }
    }

    private static void requireNonEmpty(ConfigurableEnvironment env, List<String> problems,
                                        String key, String cnName) {
        String v = env.getProperty(key, "");
        if (v.isBlank()) {
            problems.add(cnName + "（属性 " + key + "）");
        }
    }

    private static boolean isTestContext(ConfigurableEnvironment env) {
        return env.acceptsProfiles(org.springframework.core.env.Profiles.of(TEST_MARKER_PROFILE));
    }

    /** 按约定顺序定位 local.env；找不到返回 null（后续校验会兜底失败并给出指引）。 */
    private static Path resolveEnvFile(ConfigurableEnvironment env) {
        String explicit = env.getProperty("AIBI_ENV_FILE");
        if (explicit == null) {
            explicit = System.getenv("AIBI_ENV_FILE");
        }
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth <= 5 && cwd != null; depth++) {
            Path direct = cwd.resolve("local.env");
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path backend = cwd.resolve("backend").resolve("local.env");
            if (Files.isRegularFile(backend)) {
                return backend;
            }
            cwd = cwd.getParent();
        }
        return null;
    }

    /** 解析 KEY=VALUE；容忍 BOM/注释行/行首尾空白/成对引号。非法行静默忽略。 */
    private static Map<String, Object> parseEnvFile(Path file) {
        Map<String, Object> props = new HashMap<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String val = line.substring(eq + 1).strip();
                if (val.length() >= 2 && ((val.startsWith("\"") && val.endsWith("\""))
                        || (val.startsWith("'") && val.endsWith("'")))) {
                    val = val.substring(1, val.length() - 1);
                }
                if (!key.isEmpty()) {
                    props.put(key, val);
                }
            }
        } catch (IOException ignored) {
            // 读取失败等价于"文件不存在"：交给校验器输出统一指引
        }
        return props;
    }

    /** 供单测/工具复用：与 Spring 无关的最小加载入口。 */
    static Map<String, Object> loadEnvFile(Path file) {
        return parseEnvFile(file);
    }
}
