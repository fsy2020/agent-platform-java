# CLAUDE.md

给 Claude Code 的项目约定。本文件在每次运行时都会被读取，请保持简短。

## 输出语言

**一律使用简体中文**，包括 PR review、代码评审意见、Issue 回复、提交信息与提交说明。
代码标识符、API 字段名、日志文案、错误码保持英文或既有形式，不要翻译。
引用日志、报错、命令行输出时保留原文，不要转写成中文。

## 项目

企业内部智能体（Agent）管理平台。前端沿用 Python 版原样复用，后端用
Spring Boot 3.3.5 + Java 21 重写，刻意用足 IoC / AOP / 反射等语言与框架特性。

- 后端 `backend/`：Spring Boot + JPA + H2 文件库
- 前端 `frontend/`：React 18 + Vite + Ant Design
- 设计答疑见 `docs/DESIGN-QA.md`

## 构建与运行

```bash
cd backend && mvn spring-boot:run     # 8000 端口，首次启动自动灌演示数据
cd backend && mvn clean package       # 完整构建，提 PR 前至少跑一次
cd frontend && npm install && npm run dev   # 5173 端口，/api 已代理到 8000
cd frontend && npm run build          # tsc -b && vite build
```

演示账号：`admin/admin123`（全权）、`ops/ops123`（运维）、`viewer/viewer123`（只读）。
未配置 LLM API Key 时整条链路自动降级为规则引擎，功能仍可完整演示。

## 架构要点

编排入口是 `AgentRuntime`，主循环为 ReAct 风格的 route ⇄ execute：

```
prepare ──> route ──> execute ──> route（带着新证据重新决策）
              │          ├──> finalize（证据够了 / 达上限 / 被护栏阻断）
              │          └──> 挂起（等待人工审批）
              └──> finalize（answer / clarify 无需执行）
```

三条执行路径，复杂度由低到高：单个 MCP 工具 → Skill（人写的静态 workflow）
→ 子 Agent（域内 ReAct）。子 Agent 有 `local`（本进程）与 `a2a`（远程，JSON-RPC 2.0）
两种传输，经 `AgentGateway` 分派。

两套传输层策略接口是同构的，新增实现时照着已有的写：

- 工具：`ToolTransport` → `McpGateway`
- 子 Agent：`AgentTransport` → `AgentGateway`

## 代码约定

- **注释解释「为什么」，不复述「做了什么」**。现有代码大量使用这种风格，
  新增代码请保持一致；review 时也按这个标准看待注释。
- 执行路径等有限集合用**密封接口 + record**建模（见 `RoutePlan`），
  分派处用 switch 模式匹配，靠编译期穷尽性检查兜底，不要退回带
  `String type` 的贫血对象加 `default` 分支。
- 配置用 `record + @ConfigurationProperties`，在紧凑构造器里给默认值，
  不要散落 `@Value`。
- DTO 一律用 record。字段保持 camelCase，由 Jackson 全局 SNAKE_CASE
  策略转换出网。
- **凭证（`authConfig` 等）绝不出现在任何 DTO 中**，读接口只回显
  「是否已配置」的布尔值。

## Review 时请重点关注

- 护栏、审批、工具调用三道关卡是否仍由 `AgentRuntime` 持有 ——
  传输层不得绕过它们自行发起调用
- 远程（a2a）路径**不得重试**：Agent 委派大概率有副作用，
  重试等于重复执行写操作；工具调用才可以重试
- 密钥、token 是否可能经由日志、DTO 或异常信息泄漏
- 涉及 ThreadLocal（`IdentityContext` / `TraceContext`）的改动，
  是否在跨线程处重建、在 finally 中清理
