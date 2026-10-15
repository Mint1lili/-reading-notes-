# 团队协作 Git 流程（4 人 · 百景大赛 2026-10-31 截止）

> 目标：4 人并行开发互不阻塞、每笔提交可追溯、main 永远可跑。
> 配合 `.github/workflows/ci.yml`（push/PR 自动跑后端编译测试 + 前端构建 + 数据库迁移校验）。

## 1. 角色分工（对应模块）

| 成员 | 分支前缀 | 负责模块 |
|---|---|---|
| A | `feat/build-*` | F1 上传/删除/回收站 + F2 构建任务（PDFBox→切片→入库） |
| B | `feat/ai-*` | F3 AI 交互（摘要 map-reduce / 问答 SSE / 导图 / 反馈） |
| C | `feat/note-*` | F4 笔记（CRUD/标签/双向链接/回流/导出） |
| D | `feat/settings-*` | F5 设置（参数/Prompt 版本/用量/备份/日志）+ 评估 + 验收剧本 |

## 2. 分支模型（轻量版，够用不繁琐）

```
main          ← 永远可跑、可演示（比赛当天用这个分支）
 └─ dev       ← 集成分支：4 人的 feature 合到这里联调
     ├── feat/build-xxx      （A）
     ├── feat/ai-xxx         （B）
     ├── feat/note-xxx       （C）
     └── feat/settings-xxx   （D）
```

- 每人从 `dev` 拉自己的 feature 分支，**只在自己的模块内改**
- 公共文件（`application.yml`、`common/`、`pom.xml`、`api/index.js`）改动先发 PR 到 dev 让全组知道
- 接口契约以《API 接口文档 V1.0》为准；改接口先更新文档再写代码

## 3. 每日流程

```bash
# ① 开工：同步 dev
git checkout dev && git pull origin dev
git checkout -b feat/xxx-描述

# ② 写代码，小步提交（每完成一个接口一次 commit）
git add -A
git commit -m "feat(book): 实现上传接口 PDF 校验"

# ③ 下班前：提交并推送
git push origin feat/xxx-描述
# ④ 在 GitHub 上发 PR → dev（描述里写：做了什么 / 怎么测 / 联调需要什么）
```

## 4. Commit 规范

格式：`<type>(<scope>): <描述>`

| type | 含义 |
|---|---|
| feat | 新功能 / 新接口 |
| fix | 修 bug |
| docs | 文档（README/需求/接口文档） |
| refactor | 重构，不改行为 |
| test | 测试 |
| chore | 构建、依赖、配置 |

示例：
```
feat(book): 实现 PDF 上传与 50MB 校验
fix(ai): 修复 SSE 流式中途断网导致任务卡死
test(eval): 新增书本外拒答用例
docs(api): 补充跨书问答接口请求示例
```

## 5. 合入 main 的门槛（比赛前必须满足）

1. **CI 全绿**：PR 页面三个 job（Backend/Frontend/Database）全过
2. **本地可跑**：`mvn spring-boot:run` + `npm run dev` 正常启动
3. **演示剧本能走**：需求 V2.2 §12 的 8 个阶段至少手动过一遍
4. **接口文档同步**：本期接口如有变动，PR 描述里注明

## 6. 常见问题

- **改到别人的文件**：先 `git pull` 再改；改公共配置时在 PR 里 @ 全组
- **冲突**：`git merge dev` 解决后必须重新跑 CI 再提交
- **接口联调依赖**：A 先完成上传/构建，B/C/D 才能联调——README 分工表的顺序是硬依赖
- **不要**：`git push -f`、直接 push main、把 `data/`（运行时数据）提交上去（已在 .gitignore）

## 7. 首次推送（一人操作，全组复用）

```bash
# 在 GitHub 新建空仓库 reading-notes（不要勾选 README/license，本地已有）
git remote add origin https://github.com/<你的用户名>/reading-notes.git
git push -u origin main
git push -u origin dev        # 先建 dev 分支并推送
# 其余成员 clone：
git clone https://github.com/<你的用户名>/reading-notes.git
```

> 推送前先设置一次身份（git 要求）：
> `git config --global user.name "你的名字"`
> `git config --global user.email "你的邮箱"`
