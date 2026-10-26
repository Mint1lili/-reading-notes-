# 项目约定（阅读笔记系统）

## 注释规范（2026-10-21 确立）

- 中文 Javadoc，术语保留英文；类注释写"职责 + 需求编号/文档章节"，方法注释写契约与副作用，行内注释写"为什么"。
- 禁止在代码里写全限定类名（`java.util.Map`、`com.readingnotes.x.Y`），一律 import；`@lombok.Data` 也改 import。
- 实体字段必须有 `/** */` 语义说明（单位、取值范围、可空性、枚举值）。
- 枚举型状态字符串：READY/BUILDING/REBUILDING/FAILED/CANCELLED/DELETED 等，来自需求状态机，新增状态需同步前端 STATUS_LABEL。
- 详细规范见 docs/代码整理与注释规范.md。

## 构建与运行

- 后端运行 jar：backend/target/reading-notes-server-1.0.0.jar，配置走环境变量 DB_URL/DB_USER/DB_PASSWORD/APP_DATA_DIR/DOUBAO_API_KEY/CHROMA_BASE_URL。
- 本机无 Maven；编译校验用 javac + target/dependency/*.jar + lombok-1.18.32。
- Chroma 需 1.5.9（v2 API），默认 127.0.0.1:8000；不要写 localhost（可能解析到 ::1）。
- 测试隔离环境脚本在 data/testenv/，用 3307 端口的独立 MySQL，勿动本机 3306 的 reading_notes。
