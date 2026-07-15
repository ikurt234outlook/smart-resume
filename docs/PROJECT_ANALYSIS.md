# 项目分析

更新时间：2026-07-15

## 结论

Smart Resume 属于中高复杂度的全栈简历工作台项目。它不是轻量简历生成器，而是覆盖账号访问、结构化简历编辑、模板、版本、回收站、公开分享、PDF/DOCX 导出、AI 简历聊天、AI 评分、翻译、面试练习、投递管理和浏览器扩展的完整产品。

从工程形态看，它由 Spring Boot 后端、React/Vite 前端、Chrome/Edge 浏览器扩展、PostgreSQL、Flyway、Docker/Nginx 部署和 Trellis/CodeStable 项目治理组成。功能范围和持久化模型都明显大于普通前后端项目，维护时应按中高复杂度项目处理。

`develop` 是长期集成分支，功能开发在独立任务分支完成并通过 PR 合入。

## 项目定位

Smart Resume 是一个私有、多用户简历工作区。它将简历创建、编辑、优化、分享、导出和面试练习放在同一个工作台里。

核心能力包括：

- 多用户登录、注册和管理员控制注册开关。
- 简历创建、编辑、复制、删除、恢复、版本管理和分享。
- 结构化简历编辑器、实时预览、A4 预览和模板管理。
- AI 配置、简历聊天、简历评分、目标岗位分析、翻译和面试辅助。
- 面试工作区、目标公司上下文、AI 回答建议、聊天历史和报告。
- 投递记录管理。
- BOSS 直聘浏览器扩展，用于抓取岗位、保存投递和生成 AI 求职信。
- 浏览器快速 PDF 导出和后端高质量 PDF/DOCX 导出。

## 技术组成

### 后端

后端位于 `backend/`，技术栈包括 Java 21、Spring Boot 3.5.14、PostgreSQL、Flyway、MyBatis-Flex、Spring AI 和 Playwright。

主要模块：

- `com.smartresume.system`：账号、系统设置、访问控制和 token。
- `com.smartresume.resume`：简历、简历章节、版本、导入、恢复和物理删除。
- `com.smartresume.template`：模板目录和模板管理。
- `com.smartresume.share`：公开分享、密码访问、访问日志和公开导出。
- `com.smartresume.export`：PDF、DOCX、Markdown 到文档的导出能力。
- `com.smartresume.ai`：AI 配置、聊天、评分、目标岗位分析、翻译、求职信、历史和 Spring AI provider。
- `com.smartresume.interview`：面试会话、消息、题库、AI 辅助和报告。
- `com.smartresume.application`：投递记录管理。
- `com.smartresume.common`：统一响应、异常、配置、认证拦截和本地化。

数据库迁移位于 `backend/src/main/resources/db/migration/`，当前可见迁移从 `V1` 到 `V30`，说明数据模型已经经历多轮演进。

### 前端

前端位于 `frontend/`，使用 pnpm 10 管理依赖，技术栈包括 React 19、TypeScript、Vite 8、Ant Design 6、React Router 7、i18next、html2canvas、jspdf、react-markdown 和 dnd-kit。

主要区域：

- `frontend/src/app/`：应用 provider 和路由。
- `frontend/src/pages/`：工作台、认证、公开分享、模板、面试、投递等页面。
- `frontend/src/features/resume/`：简历编辑、预览、模板、导出和 Markdown 解析。
- `frontend/src/features/ai/`：AI 配置、聊天、评分、目标岗位分析等类型和 UI/API。
- `frontend/src/features/interview/`：面试 API、hooks、题库和流程工具。
- `frontend/src/features/system/`：系统访问、会话和设置相关能力。
- `frontend/src/lib/`：HTTP、SSE、Markdown、auth token、响应式 hook 等基础设施。

前端不是简单页面集合，而是一个工作台型应用。`WorkspacePage.tsx`、面试页、模板页和 Markdown/导出相关工具是维护重点。

### 浏览器扩展

浏览器扩展位于 `browser-extension/`，使用 TypeScript 和 Vite 构建，面向 Chrome/Edge。

它负责在 BOSS 直聘页面提取岗位信息，调用 Smart Resume 服务保存投递记录，并基于选定简历生成 AI 求职信。扩展和主应用之间通过 service worker、本地存储、access token 和后端 API 协作。

### 数据库与迁移

项目使用 PostgreSQL 作为主存储，Flyway 管理迁移。

当前迁移覆盖：

- 初始 schema。
- 简历模板和布局。
- AI 配置、聊天消息、聊天记忆和历史。
- 分享密码和访问日志。
- 面试会话、消息、轮次、公司上下文、AI 辅助和题库。
- 多用户支持和默认管理员用户。
- 快照 hash、软删除和投递记录。
- AI 求职信。
- AI 简历目标岗位分析及历史。

数据模型较多，后续改表时必须考虑 Flyway 顺序、已有数据兼容、测试库 schema 和前端 DTO。

### 部署与治理

项目支持多种运行方式：

- 本地开发：Spring Boot + Vite。
- 一键脚本：`start.sh` / `build.sh`。
- 完整 Docker Compose：PostgreSQL、后端、前端和 Nginx。
- 本地 frontend/backend-only Compose：连接已有 PostgreSQL。

项目还包含：

- `AGENTS.md`：分支策略和 Trellis 说明。
- `.trellis/`：Trellis 工作流。
- `codestable/`：CodeStable 架构和参考资料。
- `.githooks/`：分支策略 hook。

这说明它不只是运行代码的项目，也有明确的开发流程约束。

## 主要用户流程

### 账号和工作区

用户登录后进入私有工作区。默认管理员账号是 `admin` / `admin123`，但复用旧数据库或已改密码时不一定仍有效。系统支持注册开关和系统访问控制。

### 简历编辑

用户可以创建简历，维护结构化信息和章节，实时预览，切换模板，调整 A4 预览，并保存版本。简历还支持复制、删除、回收站恢复和物理删除。

### AI 简历能力

用户配置 AI provider 后，可以进行简历聊天、评分、目标岗位分析、翻译和优化建议。AI 能力由 Spring AI provider 层适配 OpenAI、DeepSeek 和 Ollama 等后端。

### 分享和导出

用户可以创建公开分享链接，选择是否密码保护，查看访问记录，并导出 PDF/DOCX。导出有两条路径：

- 浏览器端 `html2canvas` + `jspdf` 快速导出。
- 后端 Playwright/Chromium 高质量 PDF 导出，以及 DOCX 导出。

### 面试练习

用户可以创建面试会话，设定目标公司上下文，进行面试聊天，获取 AI 回答建议和报告。面试模块还有题库、轮次、消息状态、暂停/继续等状态机式能力。

### 投递和浏览器扩展

浏览器扩展从 BOSS 直聘岗位页读取岗位信息，登录 Smart Resume 后，可将岗位保存为投递记录，并根据简历生成 AI 求职信。这使项目超出普通 Web 应用边界，涉及浏览器扩展生命周期、跨页面数据抓取和服务端 API 兼容。

## 复杂度判断

### 代码体量：中高

当前仓库约 424 个被 `rg --files` 识别的文件。后端 Java 源码约 1.53 万行，前端和扩展 TypeScript/CSS 源码约 2.4 万行。相较轻量项目，模块数、页面数和业务对象都更多。

### 架构复杂度：中高

项目不是微服务，但它有多个工程边界：

- Spring Boot 后端。
- React/Vite 前端。
- 浏览器扩展。
- PostgreSQL。
- Flyway 迁移。
- Nginx 反向代理。
- Playwright/Chromium 导出。
- AI provider 适配。
- Trellis/CodeStable 工作流。

这些边界使得配置、构建、部署和回归都需要分层验证。

### 数据复杂度：中高

Smart Resume 的数据模型覆盖用户、简历、章节、版本、模板、分享、访问日志、AI 配置、AI 历史、聊天记忆、面试、题库、投递和求职信。数据对象之间存在较多引用和所有权关系。

尤其要注意：

- 多用户隔离。
- 软删除和回收站。
- 简历版本快照。
- 公开分享权限。
- AI 聊天和简历版本之间的关系。
- 面试会话和消息状态。
- Flyway 迁移兼容性。

### 部署复杂度：中等偏高

完整生产部署包括 PostgreSQL、后端、前端和 Nginx。Docker Compose 已经封装，但仍需正确配置数据库密码、token secret、端口、域名、HTTPS、日志卷和健康检查。

本地 frontend/backend-only Compose 不包含数据库和 Nginx，需要连接已有 PostgreSQL。此时必须区分：

- 后端容器访问数据库的地址。
- 浏览器访问后端 API 的地址。
- Vite 构建时写入的 `VITE_API_BASE_URL`。
- Nginx 同源代理下的相对路径。

### 测试成熟度：中等

项目存在较多后端测试，覆盖 AI、投递、导出、面试、简历、分享和系统访问等服务。前端也有 Markdown 解析相关测试。

建议继续扩大测试覆盖，尤其是：

- 端到端工作区流程。
- 分享权限和密码访问。
- 多用户隔离。
- PDF/DOCX 导出一致性。
- 浏览器扩展与服务端 API 协作。

## 主要复杂点

### 1. 多用户与权限边界

简历、模板、版本、AI 历史、面试、投递和分享都需要正确绑定当前用户或公开访问 token。任何查询如果漏掉用户边界，都可能造成数据越权。

### 2. 简历版本与快照

简历编辑、版本、分享、导出和 AI 分析都依赖简历内容快照。改 resume schema 或章节结构时，需要同步检查版本恢复、分享详情、导出和 AI 输入。

### 3. 导出链路

项目同时支持浏览器 PDF、后端 PDF 和 DOCX。三条导出路径使用不同技术栈，容易出现样式、分页、字体、Markdown 渲染和中文兼容差异。

### 4. AI provider 抽象

AI 能力覆盖聊天、评分、目标岗位分析、翻译、面试、AI 回答建议和求职信。底层 provider 支持 OpenAI、DeepSeek 和 Ollama。新增 provider 或改 prompt 时，要确认所有 AI 功能是否都兼容。

### 5. 面试状态机

面试包含会话、消息、轮次、暂停/继续、下一轮、结束、AI 辅助和报告。它不是简单聊天表，状态流转需要清晰维护。

### 6. 前端工作台状态

前端页面承载大量工作区状态，包括简历编辑、模板、预览、AI 流式事件、面试消息和投递列表。跨页面共享的 API 类型和 DTO 变化需要同步。

### 7. 浏览器扩展

扩展依赖 BOSS 页面结构、Chrome extension API、本地存储、服务端 URL、access token 和后端投递/求职信接口。它的失败模式和普通 Web 页面不同。

### 8. Docker/Vite 配置

Vite 的 `VITE_API_BASE_URL` 在 Docker 构建阶段写入前端静态资源。只重启前端容器不会应用新的 API 地址，必须重建前端镜像，除非后续实现运行时配置注入。

## 适合直接做的小改动

以下改动通常风险较低：

- 修改文档、截图说明和启动说明。
- 调整页面文案、空状态和提示语。
- 修改非核心样式。
- 补充 API 错误提示。
- 增加前端 Markdown 渲染测试。
- 补充部署 FAQ。

## 需要谨慎设计的改动

以下改动建议先写设计并做回归：

- 修改数据库 schema 或 Flyway 迁移。
- 修改用户认证、token、注册或管理员逻辑。
- 修改简历 DTO、章节结构、版本快照或模板模型。
- 修改公开分享权限和导出接口。
- 修改 AI provider、prompt、流式事件或聊天记忆。
- 修改面试会话状态流转。
- 修改 PDF/DOCX 导出渲染。
- 修改浏览器扩展与后端 API 协议。
- 修改 Docker/Nginx/Vite API 地址注入方式。

## 建议验证方式

后端：

```bash
cd backend
./mvnw test
./mvnw spring-boot:run
```

前端：

```bash
cd frontend
corepack enable
pnpm install --frozen-lockfile
pnpm lint
pnpm build
```

浏览器扩展：

```bash
cd browser-extension
npm install
npm run typecheck
npm run build
```

完整 Docker：

```bash
cp .env.example .env
docker compose up --build -d
```

本地 frontend/backend-only Compose 需要已有 PostgreSQL，并确认 `DB_HOST`、`SMART_RESUME_DB_URL` 和 `VITE_API_BASE_URL` 都指向正确网络位置。

功能回归建议至少覆盖：

- 登录和默认管理员初始化。
- 创建、编辑、复制、删除、恢复简历。
- 保存版本和恢复版本。
- 模板切换和 A4 预览。
- 浏览器 PDF、后端 PDF 和 DOCX 导出。
- 公开分享、密码保护和访问日志。
- AI 配置、简历聊天、评分、目标岗位分析和翻译。
- 面试创建、消息流、AI 回答建议和报告。
- 投递记录和浏览器扩展保存岗位。

## 维护建议

1. 修改数据库字段前，先检查 Flyway、实体、DTO、Mapper、服务、前端类型和测试。
2. 所有用户数据查询都要确认用户隔离条件。
3. 简历内容结构变化必须同步验证编辑器、预览、模板、导出、分享和 AI 输入。
4. AI prompt 和 provider 改动要覆盖 OpenAI/DeepSeek/Ollama 等路径。
5. PDF/DOCX 导出改动要用中文简历、长内容和分页样例回归。
6. 浏览器扩展改动要在真实 BOSS 页面或保存的 DOM 场景里验证。
7. 遵守 `AGENTS.md` 分支策略：日常开发以 `develop` 为长期分支，功能从专门分支开发，不直接向 `develop` 或 `master` 做普通开发提交。
8. 提交前不要把 `.idea/` 或未确认的 `codestable/` 变化混入无关文档改动。

## 总体评价

Smart Resume 是一个功能完整、边界较多的简历产品工程。它的复杂度来自产品面和数据面，而不只是技术栈本身：简历编辑、版本、分享、导出、AI、面试、投递和扩展都围绕同一套用户数据工作。

因此，它适合采用严格的分层验证方式维护。文档和文案可以快速改；涉及数据库、权限、简历结构、导出、AI 流程、面试状态和扩展协议的改动，应按中高复杂度功能开发处理，先明确数据契约，再实现和回归。
