# 智读 · 知识库驱动的个人阅读读书笔记智能分析系统

> 百景大赛 · 开放创新赛道参赛项目（2026 东莞工业 AI 应用创新挑战）
> 版本：V1.0（阶段 1–6 已完成：CRUD / 标签 / 双向链接 / 知识回流 / Markdown+PDF 导出 / 回收站）

## 项目定位

B/S 单用户本地部署：上传 PDF 电子书 → 构建私有 RAG 知识库 → 全书摘要 / 书本内问答（拒答兜底）/ 跨书问答 / 思维导图 → AI 产物一键沉淀为笔记 → 笔记回流参与检索（个人知识闭环）。

**核心亮点**：三层 RAG 兜底架构（元数据过滤 → 向量召回 → Rerank → 阈值拒答）、笔记回流闭环（笔记向量入库参与检索）、map-reduce 分层摘要、MySQL + Chroma 双存储一致性维护、50 题内置评测一键回归。

## 技术栈（需求 §10 硬约束）

| 层 | 技术 |
|---|---|
| 后端 | SpringBoot 3.2（JDK 17）+ Spring JDBC + PDFBox + Java HttpClient |
| 向量库 | Chroma Server 模式（`chroma run --port 8000`，1024 维 cosine；**要求 1.x 版本，1.5.9 已实测**——后端按 1.x v2 API 寻址，0.5.x 旧版不兼容） |
| 数据库 | MySQL 8.0+（InnoDB / utf8mb4） |
| AI | 豆包大模型 API（火山引擎 Ark）：`Doubao-embedding-text-240715` + Chat 模型 |
| 前端 | Vue 3 + Vite + Element Plus + Pinia + Vue Router |

## 目录结构

```
reading-notes/
├── backend/                  # SpringBoot 后端（端口 8080）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/readingnotes/
│       │   ├── common/       # Result / ErrorCode / BusinessException / 全局异常 / Trace
│       │   ├── config/       # MySQL / Chroma / 豆包 / RAG / CORS / 异步 / trace 过滤器
│       │   ├── ai/           # DoubaoClient / ChromaClient / RagChunker / RagPipeline
│       │   ├── controller/   # Health / Book / Note / Tag / File 五个 Controller
│       │   ├── service/ repository/ entity/ dto/ vo/ task/   # 业务分层（task：导出清理定时任务）
│       │   └── ReadingNotesApplication.java
│       └── resources/
│           ├── application.yml
│           └── sql/mysql/      # MySQL 迁移脚本（0001 建表 + 0002-0004 种子数据）
├── frontend/                 # Vue3 前端（端口 5173）
│   └── src/
│       ├── api/              # axios 封装 + 接口定义
│       ├── router/ views/    # 书库 / AI读书 / 笔记 / 评估 / 设置
│       └── App.vue
├── data/                     # 运行时数据（uploads / logs / backups / config）
└── README.md
```

## 启动步骤（三步）

### 1. 初始化数据库（MySQL 8）

**首次部署**，在 Navicat / 命令行执行 `backend/src/main/resources/sql/mysql/` 下的脚本（或直接运行项目根目录《数据库建库脚本-MySQL版.sql》一键版）：

```bash
mysql -u root -p < "数据库建库脚本-MySQL版.sql"     # 自动建库 reading_notes + 建表 + 种子数据
```

> 内置：18 张业务表 + 8 个 Prompt 模板 + 50 题评测集 + 6 个系统标签 + 5 本演示书占位。
> 后端连接默认 `localhost:3306 / reading_notes / root / 空密码`，可用环境变量覆盖：`DB_URL`、`DB_USER`、`DB_PASSWORD`（生产必须设置）。

### 2. 配置豆包 API Key（环境变量，安全要求 §6.2）

```bash
setx DOUBAO_API_KEY "你的火山引擎豆包 API Key"
```

> Key 只从环境变量读取，绝不写入代码/配置/前端。未配置时 AI 功能返回明确中文提示，系统不崩溃。
> （可选）`setx DOUBAO_CHAT_MODEL "doubao-pro-32k"` 切换对话模型。

### 3. 启动三个进程

| 顺序 | 服务 | 命令 |
|---|---|---|
| ① | Chroma 向量库 | `chroma run --path ./data/chroma --host 127.0.0.1 --port 8000` |
| ② | 后端 | `cd backend && mvn spring-boot:run` |
| ③ | 前端 | `cd frontend && npm install && npm run dev` |

> **Chroma 版本要求：`chromadb==1.5.9`**
> ```bash
> pip install chromadb==1.5.9
> ```
> 两点必须注意，否则后端会连不上：
> 1. **不能用 0.5.x**：chromadb 1.x 起服务端只实现 v2 API，访问 `/api/v1/*` 统一返回
>    `410 {"error":"Unimplemented","message":"The v1 API is deprecated. Please use /v2 apis"}`，
>    而 0.5.x 客户端又不认识 v2 路径，两者不可混用。
>    若团队已装 0.5.x，可通过环境变量 `CHROMA_API_VERSION=v1` 让后端回退到旧路径（此时无需改代码）。
> 2. **必须显式指定 `--host 127.0.0.1`**：Chroma 1.x 默认只监听 IPv6 的 `localhost`（`[::1]:8000`），
>    而 JVM 解析 `localhost` 优先走 IPv4，会出现「curl 能连、Java 连不上」的现象。

打开 `http://localhost:5173`，健康检查：`http://127.0.0.1:8080/api/v1/health`（显示 Chroma 与豆包 key 状态）。

## 已实现 vs 待开发

### 已实现（可编译可运行）
- 统一响应信封 / 错误码体系（API 文档 §2/§3）/ 全局异常（业务 400/参数 400/大文件 413/兜底 500）
- trace_id 全链路（响应头 X-Trace-Id）
- MySQL 数据源（HikariCP 连接池，凭据环境变量注入，密码不落配置库）
- 豆包客户端：Chat（含 SSE 流式）/ Embedding（批 16 + 429 指数退避 1s→2s→4s）
- Chroma 客户端：集合创建/upsert/query（cosine + where 过滤）/delete/健康检查
- 语义切片器（600 字 / 章节优先 / 1500 不切 / 3000 二次切 / 100 重叠）
- RAG 流水线：查询理解（章节/页码正则）→ 向量召回 Top-10 → 阈值兜底拒答
- 书籍管理 F1：书列表/详情（LibraryView 已接通）
- **笔记管理 F4（C 模块，已完整交付）**：CRUD（乐观锁 50004）/ 标签（自动建）/ 双向链接 / 软删回收站（soft_delete 过滤 + 恢复 / 永久删除）/ 知识回流三步事务（笔记向量入库参与检索）/ Markdown + PDF 导出（下载路由 + 过期清理定时任务）/ 前端 NotesView 全交互（搜索 / 筛选 / 标签树 / 回收站 / 双链 / 回流开关 / 导出）
- 前端：路由 + 5 个页面骨架 + axios 封装（统一解包 + 错误提示）

### 当前进度（4 人分工）

| 负责人 | 模块 | 进度 |
|---|---|---|
| A | F1 上传/删除事务/回收站 + F2 构建任务（PDFBox 解析→章节识别→切片→入库→断点续传） | 书基础 CRUD 已通；上传/构建/回收站按阶段推进中 |
| B | F3 AI 交互（摘要 map-reduce / 问答 SSE / 跨书 / 导图 / 反馈版本） | 阶段 5–6 已交付并实测 |
| C | F4 笔记（CRUD/标签/双向链接/回流三步事务/导出/回收站） | **已全部完成**（含 PDF 导出、回收站视图、N+1 优化、外键友好提示） |
| D | F5 设置（模型/RAG 参数/Prompt 版本/用量/备份/日志）+ 评估 + 验收剧本 | 评估/剧本按阶段推进中 |

## 关键参数速查（附录 A）

| 参数 | 默认 | 位置 |
|---|---|---|
| 切片 | 600 字 / 重叠 100 / 章节 3000 二次切 | RagChunker |
| 召回 | Top-10 → Rerank Top-5 → 阈值 0.5 | RagPipeline + application.yml |
| 多轮 | N=3 轮 | application.yml |
| 构建 | 串行单 worker，批次断点续传 | AsyncConfig |
| 安全 | 单 IP 20 次/分钟（待实现） | 需求 §6.2 |
