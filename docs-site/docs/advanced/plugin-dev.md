# 插件开发指南（manifest + JSON-RPC）

AiVerse 的插件系统基于 **`plugin.json` 清单 + JSON-RPC 进程协议**：插件在清单里声明自己提供的内容，宿主按需把插件入口作为独立进程启动，双方通过 stdout/stdin 上的单行 JSON-RPC 2.0 通信；插件页面则通过页面桥 `aicode.js` 调用宿主能力。

宿主只加载清单中列出的内容；无法识别的字段和贡献类型会直接拒绝安装，不会静默忽略。本文描述的是当前这套 manifest + JSON-RPC 系统，旧的 QuickJS 插件系统（`package` / `main` / `permissions` / `$ptools` / ctx API）已删除，相关写法全部失效。

::: tip 文档约定
本文的代码与标识符用英文，说明用中文。事件名、槽位名、`host.*` 方法名、`contributes` 字段均以 AiVerse 源码为准（`PluginManifest.kt` / `PluginManifestParser.kt` / `newmodel/PluginContent.kt` / `runtime/`）。术语统一用「插件」。
:::

## 目录

1. [包结构](#包结构)
2. [plugin.json 全字段](#pluginjson-全字段)
3. [contributes 十三种贡献](#contributes-十三种贡献)
4. [runtime JSON-RPC 协议](#runtime-jsonrpc-协议)
5. [页面桥](#页面桥)
6. [打包、安装与调试](#打包安装与调试)
7. [发布到商店](#发布到商店)
8. [最小可用插件示例](#最小可用插件示例)
9. [容易踩的坑](#容易踩的坑)

---

## 包结构

一个插件就是一个目录，根目录放 `plugin.json`，其余内容按需组织：

```text
demo-kit/
├── plugin.json          # 唯一入口声明（必需）
├── dist/
│   └── main.js          # runtime 入口（有 tools / hooks / handler 时）
├── schemas/
│   └── hello.input.json # 工具的 JSON Schema
├── pages/
│   └── panel.html       # 页面入口（uiEntries / workspaceTools / configPage / overlay）
├── skills/
│   └── hello/SKILL.md   # 技能 Markdown
├── agents/
│   └── reviewer.md      # 子代理定义
└── mcp/
    └── context.json     # MCP 配置
```

### 路径约束

清单里所有指向包内文件的值（`runtime.entry`、`inputSchema`、`file`、`page`、`pageFile`、`icon` 路径、`configPage`）都必须是**安全的相对路径**：

- 不能是绝对路径（不以 `/` 开头），不能包含 `..` / `.` 段、空段、反斜杠或控制字符；
- 长度不超过 200，层级不超过 8；
- 页面文件必须是 `.html` / `.htm`；技能与子代理是 `.md`；工具 schema 与 MCP 是 `.json`。

### 禁止与大小限制

包是不可信输入，安装时逐条校验，任意一项超出会拒绝整个包：

| 项 | 限制 |
| --- | --- |
| 压缩包大小 | 16 MiB |
| 文件数量 | 512 个 |
| 单个文件 | 4 MiB |
| 解压后总大小 | 32 MiB |
| 路径 | 见上，且不能重复（含仅大小写不同） |

按内容类型的单文件上限：

| 内容 | 上限 |
| --- | --- |
| `plugin.json` | 256 KiB |
| 运行时入口 | 512 KiB |
| 技能 / 子代理 Markdown | 512 KiB |
| 页面 HTML | 512 KiB |
| 工具 JSON Schema | 256 KiB |
| MCP 配置 | 256 KiB |
| 清单图标位图（`.png` / `.webp`） | 256 KiB |

不要把可执行原生产物（`.so` / `.dex` / `.apk` / `.jar` / `.wasm` / `.class` 一类）打进包里——它们不属于声明式插件内容，客户端安装与商店审核都不保证放行。ZIP 里的符号链接与权限位会被忽略，所有条目都以普通文件写出，不会在磁盘生成链接或可执行文件。

### 商店下载限制

从插件商店下载时，压缩包同样不得超过 16 MiB，插件索引 `index.json` 不得超过 2 MiB。

---

## plugin.json 全字段

```json
{
  "manifestVersion": 1,
  "id": "community.demo-kit",
  "name": { "default": "Demo Kit", "zh-CN": "演示套件", "en": "Demo Kit" },
  "version": "1.0.0",
  "publisher": "community",
  "repository": "https://github.com/example/demo-kit",
  "description": { "default": "A minimal demo plugin.", "zh-CN": "最小演示插件。" },
  "license": "MIT",
  "icon": "package",
  "engines": { "pluginApi": "^1.0.0" },
  "runtime": {
    "type": "javascript",
    "entry": "dist/main.js",
    "resident": true
  },
  "contributes": {}
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `manifestVersion` | 是 | 固定为整数 `1` |
| `id` | 是 | 稳定插件标识，格式 `发布者.插件名` |
| `name` | 是 | 展示名称，本地化文本，最多 80 字符，不能换行 |
| `version` | 是 | 语义化版本，如 `1.0.0`、`1.1.0-beta.1` |
| `publisher` | 是 | 发布者标识，必须等于 `id` 的第一段 |
| `repository` | 是 | 源码仓库，必须 `https://`，不含账号、查询参数或片段 |
| `description` | 是 | 用途说明，本地化文本，最多 600 字符，允许换行 |
| `license` | 是 | 许可证名称，最多 100 字符 |
| `icon` | 否 | 插件图标：Feather 内置图标名，或包内 `.png` / `.webp` 相对路径 |
| `engines` | 是 | 目前仅接受 `{"pluginApi": "版本范围"}` |
| `runtime` | 有 tools / hooks / 带 handler 的 uiEntries 时必填 | 业务进程入口与启动方式 |
| `contributes` | 是 | 提供的内容，至少一项 |
| `configPage` | 否 | 插件配置页；也可声明在 `contributes.configPage` |

顶层只能出现上面这些键，出现其它键会以 `UNSUPPORTED_FIELD` 拒绝安装。

### id 与 publisher

- `id` 形如 `community.demo-kit`：两段均以小写字母开头，仅含小写字母、数字和连字符，每段最多 32 个字符（正则 `^[a-z][a-z0-9-]{0,31}\.[a-z][a-z0-9-]{0,31}$`）；
- `publisher` 必须等于 `id` 的第一段；
- `id` 同时用作安装目录名，安装后不应靠重命名区分版本；
- 局部 ID（技能、工具、页面、hook 等的 `id`）由字母开头，仅含字母、数字、下划线、连字符，最多 64 个字符，同一类型内不可重复。

### 本地化文本的两种写法

`name`、`description` 以及各贡献的 `title` / `description` 既可以是单个字符串，也可以是按语言的对象：

```json
{ "name": "演示套件" }
```

```json
{ "name": { "default": "Demo Kit", "zh-CN": "演示套件", "en": "Demo Kit" } }
```

对象写法里 `default` 是必写的默认项，其余键为语言标签（如 `zh-CN`、`en`），最多 16 种语言。取值顺序为：完整语言标签（`zh-CN`）→ 语言（`zh`）→ `default` → `en` → 第一项。标签大小写与 `-` / `_` 不敏感。

### 图标

`icon` 只接受两种值：

- **Feather 内置图标名**：小写字母开头，仅含小写字母、数字和连字符，最多 32 个字符，如 `package`、`terminal`、`book`、`settings`、`tool`、`cloud`、`monitor`；未知名称回退为 `package`。
- **包内图片**：以 `.png` 或 `.webp` 结尾的相对路径，如 `icons/logo.png`，必须位于包内，单文件不超过 256 KiB。

不支持 SVG、JPEG 等其它格式，清单里写这些后缀会直接拒绝安装。插件 HTML 页面内部仍可自行使用包内 SVG 图片，那与清单图标无关。

### version 与 engines.pluginApi

`version` 是语义化版本。`engines.pluginApi` 支持 `^1.0.0`、`~1.0.0`、`>=1.0.0 <2.0.0` 与精确版本 `1.0.0`；不支持 `1.x`、`*`、`||` 和区间简写。宿主当前提供的插件 API 版本为 `1.0.0`，声明的范围必须包含它，否则以 `API_INCOMPATIBLE` 拒绝。

### runtime

```json
{
  "runtime": {
    "type": "javascript",
    "entry": "dist/main.js",
    "command": ["node", "dist/main.js"],
    "dependencies": { "tool": "npm", "file": "package.json" },
    "resident": true
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `type` | 是 | `javascript`（默认用 `node` 启动）或 `python`（默认用 `python3` 启动） |
| `entry` | 是 | 包内入口文件；JavaScript 用 `.js` / `.mjs` / `.cjs`，Python 用 `.py`，最大 512 KiB |
| `command` | 否 | 覆盖默认启动命令的字符串数组，直接交给 `execve`，不经 shell；最多 16 段，每段最多 200 字符，不得为绝对路径或包含 `..` |
| `dependencies` | 否 | 依赖输入：`tool` 为 `npm` 或 `pip`，`file` 分别为 `.json`（如 `package.json`）或 `.txt`（如 `requirements.txt`） |
| `resident` | 否 | 为 `true` 时进程不参与空闲回收，首次启动后常驻；停用、卸载、版本替换或 App 进程结束仍会关闭 |

默认启动命令为 `node <entry>` 或 `python3 <entry>`。存在 `contributes.tools`、`contributes.hooks`，或 `uiEntries` 中有带 `handler` 的动作入口时必须声明 `runtime`；纯声明式插件（只有技能、子代理、MCP、页面等）可省略。

---

## contributes 十三种贡献

`contributes` 是一个对象，可用键恰好是下面 13 种。出现其它键以 `UNSUPPORTED_CONTRIBUTION` 拒绝；每类最多 64 项（`uiEntries` 另有每槽位上限），`contributes` 全空也无效。

| 键 | 类型 | 作用 |
| --- | --- | --- |
| `tools` | 数组 | AI 可调用工具 |
| `skills` | 数组 | 技能 Markdown |
| `agents` | 数组 | 子代理定义 |
| `workspaceTools` | 数组 | 右侧项目工具抽屉中的页面 |
| `uiEntries` | 数组 | 挂载到宿主槽位的入口 |
| `hooks` | 数组 | 生命周期 hook |
| `mcp` | 数组 | MCP 服务器配置 |
| `providers` | 数组 | OpenAI 兼容提供商 |
| `shizuku` | 对象 | 声明使用 Shizuku |
| `overlay` | 对象 | 声明系统浮层 |
| `backup` | 对象 | 声明接收备份与还原事件 |
| `modelAccess` | 对象 | 声明调用使用者已配置的 AI 供应商 |
| `configPage` | 字符串 | 插件详情中的配置页 |

### tools

```json
{
  "tools": [
    {
      "id": "hello",
      "title": { "default": "Hello", "zh-CN": "问好" },
      "description": { "default": "Say hello.", "zh-CN": "问个好。" },
      "inputSchema": "schemas/hello.input.json",
      "handler": "tools.hello"
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，决定 AI 工具名的一部分 |
| `title` | 是 | 展示名称，最多 80 字符 |
| `description` | 否 | 供 AI 参考的用途说明，最多 600 字符；省略时为空字符串 |
| `inputSchema` | 是 | 包内 `.json` JSON Schema 文件，最大 256 KiB，根必须是 `{"type": "object"}` |
| `handler` | 是 | runtime 导出的 handlers 表里的稳定键，如 `tools.hello` |

工具在 AI 侧的完整名称为 `plugin__<插件ID>__<工具ID>`，例如 `plugin__community.demo-kit__hello`；宿主据此把它与内置工具、MCP 工具区分开。

::: warning 必须声明 runtime
声明了 `tools` 却没有 `runtime`，安装会以 `FIELD_MISSING` 被拒绝——工具的 handler 要靠插件进程执行。声明不会立刻启动进程，首次调用时才启动。
:::

### skills / agents

```json
{
  "skills": [{ "id": "hello", "file": "skills/hello/SKILL.md" }],
  "agents": [{ "id": "reviewer", "file": "agents/reviewer.md" }]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，同一类型内唯一 |
| `file` | 是 | 包内 `.md` 相对路径，最大 512 KiB |

贡献后，模型与使用者看到的名字是 `<插件ID>:<局部ID>`（如 `community.demo-kit:hello`），命名空间由宿主生成，插件无法覆盖内置内容或其他插件的同名内容。

- 技能正文不能为空；描述会进系统提示词的技能清单，会被压成单行，避免借换行伪造清单条目。
- 子代理正文须是合法的子代理定义，且**不能声明 `provider` 或 `model`**——插件子代理一律继承父会话，避免动用使用者的付费渠道。

### workspaceTools

```json
{
  "workspaceTools": [
    {
      "id": "build-apk",
      "title": { "default": "Build APK", "zh-CN": "构建 APK" },
      "description": { "default": "Build the current project.", "zh-CN": "构建当前项目。" },
      "icon": "hammer",
      "category": "构建",
      "pageFile": "pages/build-apk.html"
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `title` | 是 | 本地化名称，最多 80 字符 |
| `description` | 否 | 本地化说明，最多 600 字符 |
| `icon` | 否 | 图标名，默认 `play`，最长 32 字符 |
| `category` | 否 | 分类名，默认 `通用`，最长 32 字符 |
| `pageFile` | 是 | 包内 `.html` / `.htm` 页面，最大 512 KiB |

`pageFile` 必须是页面文件，指向其它类型会被拒绝。

### uiEntries

```json
{
  "uiEntries": [
    {
      "id": "memory",
      "title": { "zh-CN": "记忆管理", "en": "Memory" },
      "slot": "settings.ai",
      "page": "pages/memory.html",
      "icon": "book"
    },
    {
      "id": "sync",
      "title": { "zh-CN": "同步笔记", "en": "Sync notes" },
      "slot": "drawer.footer",
      "icon": "refresh",
      "handler": "actions.sync",
      "confirm": true
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，同一插件的入口不可重名 |
| `title` | 是 | 本地化名称，最多 80 字符 |
| `slot` | 是 | 宿主槽位，仅可取下表的值 |
| `page` | 二选一 | 包内 `.html` / `.htm` 页面，最大 512 KiB |
| `handler` | 二选一 | runtime 的 handler 名；需要声明 `runtime` |
| `confirm` | 否 | 仅动作入口：执行前让宿主弹确认框 |
| `icon` | 否 | 图标名，默认 `book`，最长 32 字符 |

`page` 与 `handler` 必须且只能出现一个。每个插件**最多声明 2 条** `uiEntries`（`backup.data` 槽位最多 1 条），超出在安装时拒绝。动作入口返回 `{message}` 时宿主用 toast 显示。

**13 个槽位（12 个界面槽 + 1 个数据槽）：**

| `slot` | 位置 | 限制 |
| --- | --- | --- |
| `settings.general` | 设置页「通用」分组末尾 | 只接受 `page` |
| `settings.ai` | 设置页「AI 配置」分组末尾 | 只接受 `page` |
| `settings.environment` | 设置页「运行环境」分组末尾 | 只接受 `page` |
| `settings.permissions` | 设置页「权限与后台」分组末尾 | 只接受 `page` |
| `settings.data` | 设置页「数据与诊断」分组末尾 | 只接受 `page` |
| `settings.help` | 设置页「帮助与关于」分组末尾 | 只接受 `page` |
| `drawer.footer` | 侧栏底部，浏览器与设置之上 | `page` 或 `handler` |
| `chat.header.menu` | 聊天顶栏「更多」菜单底部 | `page` 或 `handler` |
| `chat.welcome` | 聊天空态建议区 | `page` 或 `handler` |
| `chat.session.menu` | 会话长按菜单 | `page` 或 `handler` |
| `files.tree.menu` | 文件树文件/目录长按菜单 | 只接受 `handler`（动作） |
| `editor.selection.menu` | 代码编辑器选中文本后的浮动菜单 | 只接受 `handler`（动作） |
| `backup.data` | 备份页「选择导出数据」组末尾 | 只接受数据选项 |

`settings.*` 槽位只接受页面入口；`files.tree.menu` 与 `editor.selection.menu` 是动作型槽位，只接受 `handler`；`backup.data` 只接受数据选项（只写 `id` / `title` / `icon`，不能写 `page` / `handler` / `confirm`）。入口排序由宿主决定，插件不能指定插入位置。

**宿主渲染范围**：当前已渲染 `settings.general` / `settings.ai` / `settings.environment` / `settings.permissions` / `settings.data` / `settings.help`，以及聊天页的 `chat.header.menu`（顶栏「更多」菜单底部）和 `chat.welcome`（空态建议区）。`drawer.footer`、`chat.session.menu`、`files.tree.menu`、`editor.selection.menu`、`backup.data` 已在清单中解析并通过安装校验，但宿主尚未渲染入口；这些槽位暂不会出现在界面上，后续版本再接入。

### hooks

```json
{
  "hooks": [
    { "id": "run-log", "event": "agent.run.finished", "handler": "hooks.runFinished" },
    { "id": "guard-bash", "event": "agent.tool.before", "handler": "hooks.guardBash", "mode": "transform" },
    { "id": "review", "event": "agent.permission.review", "handler": "hooks.review", "mode": "review" }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `event` | 是 | 必须是宿主支持的事件（见下表） |
| `handler` | 是 | runtime 导出的 handlers 表里的稳定键 |
| `mode` | 否 | `observe`（默认）、`transform`、`review` |

**三种模式：**

| 模式 | 行为 | 适用 |
| --- | --- | --- |
| `observe` | 宿主异步派发，插件只返回日志或自身结果，不影响主流程 | 记录、统计、通知 |
| `transform` | 宿主在固定拦截点等待结果，校验通过后应用；失败、超时或格式错误保持原值 | 拒绝危险调用、改写参数、脱敏结果、改写请求 |
| `review` | 仅在宿主判定需要使用者确认（ASK）后运行，返回 `allow` / `deny` / `pass` | 代审核 |

`transform` 与 `review` 事件必须在清单里显式声明对应 `mode`，声明错误会导致拒绝安装。

**事件表（共 19 个）：**

| 事件 | 时机 | 模式 |
| --- | --- | --- |
| `app.started` | App 进程启动完成；用于常驻插件初始化 | observe |
| `agent.run.started` | 一轮对话开始处理 | observe |
| `agent.run.finished` | 一轮对话结束（成功/失败/取消都会发出） | observe |
| `agent.request.started` | 一次模型请求即将发出 | observe |
| `agent.request.finished` | 一次模型响应结束 | observe |
| `agent.tool.started` | 一次工具调用真正开始执行 | observe |
| `agent.tool.finished` | 一次工具调用结束 | observe |
| `agent.compaction` | 上下文被压缩 | observe |
| `agent.tool.before` | 一次工具调用执行前，权限判定前 | transform |
| `agent.tool.after` | 一次工具调用成功结束后 | transform |
| `agent.http.before` | 每次实际发出 provider HTTP 请求前 | transform |
| `agent.permission.review` | 宿主判定需确认后、弹窗前 | review |
| `plugin.installed` | 插件安装或版本替换完成；只派发给该插件自身 | observe |
| `plugin.uninstalled` | 插件卸载生效前；安装与数据目录仍可访问 | observe |
| `plugin.enabled` | 插件启用生效后 | observe |
| `plugin.disabled` | 插件停用生效前；插件仍可运行 | observe |
| `backup.export.before` | 一次完整备份写入归档前 | observe（需 `backup`） |
| `backup.import.finished` | 一次还原成功后 | observe（需 `backup`） |

`agent.tool.before` 可返回 `decision: "deny"` 阻止执行，或 `arguments` 改写参数（改写后宿主会重新走完整权限判断），或 `delegate: {handler}` 选择插件执行方。`agent.tool.after` 可改写成功结果的 `data`。`agent.http.before` 可改写 `url` / `headers` / `body`；其敏感请求头在 payload 中只以占位符出现，插件原样回传占位符且请求仍发往原 origin 时，宿主才恢复真实值。

### 委托工具执行

声明 `agent.tool.before`、`mode: "transform"` 的插件可以返回 `delegate: {handler: "tools.remote"}`。before handler 只能选择执行方，不应在这里执行工具或产生工具副作用。宿主收齐最终参数并重新完成权限与模式检查后，才调用选中的执行 handler；`decision: "allow"` 也不能跳过检查。委托执行受原工具相同的权限边界约束，不扩大 PLAN 或 DENY 的权限。

多个 hook 按原顺序处理，首个合法委托固定归属其插件、版本与 handler；后续 hook 仍可改参数或拒绝，但不能抢占执行方。执行时使用最终参数。宿主生成独立执行标识并绑定运行、工具调用与插件，不能指定其他插件。插件停用、更新、执行错误、超时或取消均不会回退本地工具。委托保留原工具卡片、进度与 started/finished 通知，但不创建本地文件修改检查点。插件不得代审已委托给自身执行的原生工具调用。

使用 SDK 的 `toolDelegate` 包装执行 handler：异步生成器 `yield` 进度文本，`return` 最终 JSON 数据，抛异常表示失败。需要取消的网络请求、子进程等应监听 `signal` 并终止实际工作；宿主取消等待不等于能强制终止插件的任意外部副作用。

```ts
import { definePlugin, toolDelegate } from '@jieapi/plugin-sdk';

definePlugin({ handlers: {
  'hooks.route': (payload) => payload.toolName === 'readFile'
    ? { delegate: { handler: 'tools.remote' } }
    : {},
  'tools.remote': toolDelegate(async function* ({ arguments: args, signal }) {
    yield 'Reading remote file';
    const response = await fetch(String(args.path), { signal });
    if (!response.ok) throw new Error(`Read failed: ${response.status}`);
    return { content: await response.text() };
  })
} });
```

非 SDK 实现使用同一 handler 的 `start` / `next` / `cancel` 操作：请求包含 `executionId`、`runId`、`callId`、`toolName`，只有 start 包含最终 `arguments`。响应必须回传相同 executionId，格式为 `{type: "progress", chunk}`、`{type: "pending"}`、`{type: "completed", data}` 或 `{type: "error", message}`。next 应长轮询约 25 秒，单次 RPC 上限 35 秒；同一 executionId 不得重复启动或并发读取。SDK 每次只拉取一个增量，限制单条进度为 64 Ki 字符，避免无界队列。完成、失败或取消后的执行标识在当前进程内保留 31 分钟用于拒绝重复启动；进程重启不保证跨进程去重，执行方仍应对外部副作用使用业务幂等标识。宿主总执行上限 30 分钟，异常或取消后最多等待 2 秒发送 cancel；SDK 通过 AbortSignal 通知执行方并清理状态。

### 主动授权请求

插件可以通过 `hostCall('host.permissions.request', ...)` 请求 App 原生单次授权卡片；请求绑定来源插件与会话，不产生永久授权，也不能覆盖宿主工具的 DENY/PLAN。页面对应 `aicode.permissions.request(options)` / `cancel(requestId)`。卡片由 App 固定标注插件来源，返回 `allow` / `deny` / `cancel`，默认等待 2 分钟，可用 `timeoutMs` 设置 1 秒至 10 分钟；超时、工作区切换、会话移除或插件停用时取消。

`plugin.installed` / `plugin.enabled` 异步派发；`plugin.uninstalled` / `plugin.disabled` 在状态变更前同步等待（单次最多约 10 秒），给插件清理自己数据的机会，但插件不能阻止操作。这四个事件只派发给事件对应的插件。

`backup.export.before` 与 `backup.import.finished` 会带出完整备份包或写入暂存区，**必须同时声明 `contributes.backup`**，否则安装被拒绝。

### mcp

```json
{ "mcp": [{ "id": "project-context", "file": "mcp/project-context.json" }] }
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `file` | 是 | 包内 `.json` 文件，最大 256 KiB，内容为 `{"mcpServers": {...}}` 结构 |

配置里的 server 名会被宿主加上插件命名空间（形如 `plugin__<插件ID>__<局部ID>__<server名>`），避免覆盖使用者的全局 MCP 配置。

### providers

```json
{
  "providers": [
    {
      "id": "gateway",
      "name": { "zh-CN": "本地网关", "en": "Local Gateway" },
      "description": { "zh-CN": "本机 OpenAI 兼容服务" },
      "format": "openai",
      "baseUrl": "http://127.0.0.1:18080/v1"
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `name` | 是 | 本地化名称，最多 80 字符 |
| `description` | 否 | 本地化说明，最多 600 字符 |
| `format` | 是 | 目前仅支持 `openai` |
| `baseUrl` | 是 | `http` / `https` 绝对地址，不含账号、查询参数或片段，最长 2048 字符 |

插件负责启动兼容服务并保证宿主可访问；这类插件通常应把 `runtime.resident` 设为 `true`，让服务进程保持常驻。启用插件后，宿主会注册一个 id 为 `plugin:<插件ID>:<供应商ID>` 的提供商条目（带「插件」标记）；停用或卸载会移除该条目。宿主复用现有 OpenAI 适配器请求，不内置新的上游协议。

### shizuku / overlay / backup / modelAccess

这四种是**授权型声明**，都只需一个 `purpose` 用途说明（最多 200 字符），展示在安装预览与插件详情的授权界面；声明不会自动获得授权，需使用者在插件详情中单独开启。

```json
{
  "shizuku": { "purpose": "切换免打扰与查询系统设置" },
  "backup": { "purpose": "把额外数据随备份一起导出" },
  "modelAccess": { "purpose": "在团队群聊中轮流调用多个模型" },
  "overlay": {
    "purpose": "悬浮小组件与状态 HUD",
    "surfaces": [
      { "id": "pet", "page": "pages/pet.html", "width": 240, "height": 320, "draggable": true, "snap": "x" }
    ]
  }
}
```

- **shizuku**：授权后插件可在 Android 系统直接执行命令（页面桥 `shizuku.exec` / 进程 `host.shizuku.exec`）。系统中的 Shizuku 服务须正在运行。
- **backup**：授权后可接收 `backup.export.before` / `backup.import.finished` 事件，并在 `backup.data` 槽位声明一个数据选项。
- **modelAccess**：授权后插件可调用使用者已配置的 AI 供应商（进程 `host.model.*`、页面 `aicode.model.*`）。声明本身不授予 Key 读取权限——请求经宿主适配器发出，会计入对应渠道的用量。未声明时调用返回 `MODEL_ACCESS_NOT_DECLARED`。
- **overlay**：声明系统浮层，`surfaces` 至少 1 个、最多 3 个。每个 surface：

  | 字段 | 必填 | 说明 |
  | --- | --- | --- |
  | `id` | 是 | 局部 ID，同一插件内唯一 |
  | `page` | 是 | 包内 `.html` / `.htm` 页面 |
  | `width` | 否 | 默认宽度（dp），默认 240，范围 48–720 |
  | `height` | 否 | 默认高度（dp），默认 320，范围 48–1200 |
  | `draggable` | 否 | 是否允许拖动，默认 `true` |
  | `snap` | 否 | 松手吸附轴：`none`、`x`、`y`、`both`，默认 `none` |

  浮层需单独授权，且 App 需获得「显示在其他应用上层」的系统权限。

### configPage

```json
{ "configPage": "pages/config.html" }
```

也可写在 `contributes.configPage`。必须是包内 `.html` / `.htm` 文件，最大 512 KiB。配置页只出现在「设置 → 插件」的插件详情中，不会混入设置主菜单。配置页适用于填写 API Key、开关和选项，仅在插件启用且首次切到配置标签时加载；停留在原生管理标签时不会提前执行页面脚本。停用、卸载或替换安装会使旧页面桥失效并取消该桥的在途任务、文件订阅和模型流；重新启用后需要重新打开页面，旧桥不会恢复授权。

---

## runtime JSON-RPC 协议

插件入口作为独立进程运行，宿主与它通过 **stdout / stdin 上的单行 JSON-RPC 2.0** 通信：每一行是一条完整的 JSON-RPC 消息。插件主动发出的请求（带 `method`）是调用宿主 API；无 `id` 的是通知。宿主下发的调用主要是工具调用（`tool.invoke`）与 hook 派发，插件用 `handlers` 表里的键路由。

### host.* 方法表

进程侧可调用以下宿主方法（`id` + `method` + `params`，宿主回 `result` 或 `error`）：

**聊天与会话**

| 方法 | 说明 |
| --- | --- |
| `host.chat.openSession` | 打开（跳转到）一个会话 |
| `host.chat.getSession` | 读取会话信息 |
| `host.chat.renameSession` | 重命名会话 |
| `host.chat.pinSession` | 置顶 / 取消置顶会话 |
| `host.chat.deleteSession` | 删除会话 |
| `host.chat.getRunState` | 读取会话当前运行状态 |
| `host.chat.waitForRun` | 等待一轮运行结束（可带超时） |
| `host.chat.listApprovals` | 列出待审批请求 |
| `host.chat.resolveToolApproval` | 处理工具审批 |
| `host.chat.resolveQuestion` | 处理向使用者提问 |
| `host.chat.resolvePlanApproval` | 处理计划审批 |

（同一服务还提供 `host.chat.listSessions` / `createSession` / `listMessages` / `sendMessage` / `setDraft` / `stop`，用于读写当前工作区的会话与消息。）

**模型句柄**

| 方法 | 说明 |
| --- | --- |
| `host.model.listProviders` | 列出使用者已配置的供应商 |
| `host.model.listModels` | 列出某供应商的模型 |
| `host.model.create` | 创建模型句柄 |
| `host.model.start` | 发起一次流式请求 |
| `host.model.read` | 读取流式事件（text / reasoning / retry / keySwitch） |
| `host.model.cancel` | 取消一次流 |
| `host.model.close` | 关闭模型句柄 |

需要 `contributes.modelAccess` 授权；插件拿不到 Key，请求经宿主适配器发出并计入渠道用量。

**剪贴板 / 分享 / 打开链接 / 应用信息 / 日志**

| 方法 | 说明 |
| --- | --- |
| `host.clipboard.read` | 读取系统剪贴板 |
| `host.clipboard.write` | 写入系统剪贴板 |
| `host.share.text` | 调起系统分享文本 |
| `host.share.image` | 调起系统分享工作区图片 |
| `host.share.file` | 调起系统分享工作区任意文件 |
| `host.ui.openUrl` | 用系统浏览器打开 `http` / `https` 链接 |
| `host.app.info` | 读取应用信息 |
| `host.log` | 写宿主日志，便于排查 |
| `host.app.isForeground` | 查询 App 前后台状态 |

**工具**

| 方法 | 说明 |
| --- | --- |
| `host.tools.list` | 列出宿主 AI 工具与 MCP server 状态（只读） |
| `host.tools.execute` | 执行一个内置 / MCP 工具（插件工具 `plugin__` 不能经此调用） |
| `host.tools.pending` | 查询待审批的工具执行 |
| `host.tools.resolve` | 处理工具审批（需确认时 execute 会挂起） |

**MCP / HTTP**

| 方法 | 说明 |
| --- | --- |
| `host.mcp.status` | 查询 MCP server 连接状态 |
| `host.http.request` | 由宿主代理的 HTTP 请求；不注入宿主凭据、不跟随重定向 |

进程侧还可用 `host.fs.*`（`read` / `write` / `list` / `delete` / `stat` / `mkdir` / `rename` / `copy` / `append` / `readBinary` / `writeBinary`）、`host.bash`、`host.notify`、`host.tip`、`host.shizuku.exec`、`host.overlay.*`、`host.terminal.*`、`host.secrets.*` 等方法，与页面桥能力一一对应（见下节）。

---

## 页面桥

插件页面（`uiEntries` 的 `page`、`workspaceTools` 的 `pageFile`、`configPage`、overlay 的 `page`）由宿主 WebView 加载，通过页面桥调用宿主能力。页面里引入：

```html
<script src="/__aicode/aicode.js"></script>
```

页面资源只经虚拟域加载：`/plugin/` 映射插件安装目录，`/__aicode/` 提供宿主 UI 资产，**不暴露本地文件系统**。

### 调用方式

所有方法返回 Promise。核心入口是 `window.aicode.call(method, params)`，常用的能力都封装成了命名空间：

```js
const ctx = await window.aicode.context();
await window.aicode.toast('已同步');
const out = await window.aicode.bash('ls -1', { timeout: 10 });
await window.aicode.ui.openUrl('https://example.com');
```

宿主页面桥统一暴露 `window.aiverse`，并为兼容旧页面提供别名 `window.aicode`（两者指向同一对象）。`window.aicode.events.on(handler)` 用于监听插件进程与宿主推送的事件，返回取消订阅函数。

### 方法表

| 命名空间 / 方法 | 说明 |
| --- | --- |
| `aicode.context()` | 读取页面上下文 |
| `aicode.toast(message)` | 弹 toast |
| `aicode.tip(options)` | 在对话内显示可关闭提示条（可带点击回调） |
| `aicode.app.info()` / `aicode.app.isForeground()` | 应用信息 / 前后台状态 |
| `aicode.ui.close()` / `openUrl(url)` / `setOverlay(visible, rect)` / `onBack(handler)` | 关闭页面、打开链接、页面内全屏弹层、注册返回处理 |
| `aicode.fs.read/write/list/delete/stat/mkdir/rename/copy/append/readBinary/writeBinary/watch` | 文件操作。路径为执行环境的真实绝对路径；`~` 展开到 home；插件私有数据用 `data://` 虚拟根。`fs.read` 支持可选 `offset` / `limit`（字节）分段读取大文件，返回额外字段 `returned`（本次返回字节数）与 `nextOffset`（下一窗口起点，读到尾等于 `size`）；`size` 始终是文件总字节数，不受 `offset` / `limit` 影响 |
| `aicode.chat.listSessions/createSession/listMessages/sendMessage/setDraft/stop/openSession/getSession/renameSession/pinSession/deleteSession/getRunState/waitForRun/listApprovals/resolveToolApproval/resolveQuestion/resolvePlanApproval` | 会话与消息；读取范围限定当前工作区 |
| `aicode.model.listProviders/listModels/create/close/start/read/cancel/stream` | 模型句柄（需 `modelAccess`） |
| `aicode.tools.list/execute/pending/resolve` | 宿主工具目录与执行；只允许内置 / MCP 工具，受授权策略约束 |
| `aicode.mcp.status()` | MCP server 状态 |
| `aicode.http.request(options)` | 宿主代理的 HTTP 请求 |
| `aicode.clipboard.read()` / `write(text)` | 系统剪贴板 |
| `aicode.share.text(text, options)` | 系统分享 |
| `aicode.share.image(path, options)` / `file(path, options)` | 分享工作区图片 / 任意文件 |
| `aicode.secrets.get/set/delete/list` | 插件私有的加密键值存储；`list` 只返回键名 |
| `aicode.bash(command, options)` | 在当前工作区执行一次性 Shell 命令，返回 `{ output, exitCode, truncated }` |
| `aicode.terminal.run/poll/stop/close/write/list` | 共享终端标签：命令后台运行，页面关闭也不停止 |
| `aicode.shizuku.exec(command, options)` | 经 Shizuku 在 Android 系统执行命令（需声明 `shizuku` 并授权） |
| `aicode.overlay.create/update/close/self/setDragRegion/moveBy/resize/setVisibilityPolicy/onMessage/postMessage` | 宿主托管的系统浮层 |
| `aicode.invoke(handler, args)` | 调用同一插件常驻进程的 handler（不支持跨插件） |
| `aicode.log.write(level, message, options)` | 写宿主日志 |
| `aicode.applyTheme(variables)` | 应用宿主主题变量到页面 |

### 宿主 UI 资源

`/__aicode/` 下提供宿主内置的 UI 资产，页面可直接引用：

| 资源 | 用途 |
| --- | --- |
| `aicode.js` | 页面桥脚本 |
| `vue.global.prod.js` | 全局 Vue 构建，供页面写声明式 UI |
| `aicode-ui-vue.js` | 基于 Vue 的宿主 UI 组件库（需先加载 `vue.global.prod.js`） |
| `ui.css` | 宿主 UI 样式 |
| `theme.css` | 跟随明暗主题动态生成的 CSS 变量 |
| `eruda.min.js` | 页面内调试控制台 |
| `markdown/` | Markdown 渲染相关资源 |

引用示例：

```html
<script src="/__aicode/vue.global.prod.js"></script>
<script src="/__aicode/aicode-ui-vue.js"></script>
<link rel="stylesheet" href="/__aicode/ui.css">
```

页面响应附带 CSP，禁止远程脚本、iframe 与 object；页面资源禁用浏览器缓存，插件覆盖与 APK 更新后 WebView 不会继续用旧内容。

---

## 打包、安装与调试

### 打包

在插件目录中执行，注意命令末尾的 `.`（保证 `plugin.json` 位于压缩包根目录）：

```bash
cd demo-kit
zip -r ../demo-kit-1.0.0.zip .
```

容器中未安装 `zip` 时可用 Python：

```bash
python3 -c "import shutil; shutil.make_archive('../demo-kit-1.0.0', 'zip', '.')"
```

打包后检查压缩包根目录应为：

```text
demo-kit-1.0.0.zip
├── plugin.json
├── dist/
├── schemas/
└── ...
```

若打开 zip 后看到的是 `demo-kit/plugin.json`，说明多嵌套了一层目录，导入时会提示压缩包根目录里没有 `plugin.json`。建议排除 `node_modules`、`.git`、构建缓存和本地测试数据；依赖通过 `runtime.dependencies` 声明，由使用者点「准备依赖」安装，或由作者在发布前 bundle 进入口。

### 安装

打开「设置 → 插件」，点右上角 **+** 导入 `.zip`。安装确认页会列出插件提供的能力，确认后启用。同一 `id` 的新版本会覆盖安装；停用只关闭能力、不删除数据；卸载会清理插件目录和 `data://` 数据。

### 开发者模式

反复打包导入效率低时，使用开发者侧载：

1. 在「设置 → 插件」点右上角设置按钮，在插件设置中开启「开发者侧载」；
2. 把项目目录放入容器的 `~/.aiverse/plugin-dev/<项目目录>/`，每个目录须包含 `plugin.json`；
3. 回到插件页，在「开发中」标签页打开项目详情，点「安装」或「重载」。开启期间保存文件会自动重载，下拉该列表可重新扫描目录。

侧载不参与在线更新，普通 `.zip` 导入仍可用；侧载打包会跳过 `node_modules`、`.git` 等目录，但最终仍受安装限制约束。远程 SSH 模式下，`~/.aiverse/plugin-dev` 位于远端服务器上。

### 排查清单

| 现象 | 常见原因 |
| --- | --- |
| 压缩包根目录里没有 `plugin.json` | 打包多嵌套了一层目录 |
| 清单缺少 / 无效字段 | 提示会写出具体路径，按上文逐字段对照 |
| 此版本不支持的字段 / 内容类型 | 用了未实现的能力；未知字段不会被忽略 |
| hooks 或 uiEntries 声明了 handler 但未声明 runtime | 带 handler 的贡献均需 `runtime` |
| `uiEntries` 的 `slot` 无效 | 拼错槽位名会使整个清单无效，按上面 13 个槽位表核对 |
| `uiEntries` 的 `page` 不是 HTML | `page` 必须指向包内 `.html` / `.htm` |
| `uiEntries` 同时声明或缺少入口类型 | `page`、`handler` 必须且只能出现一个 |
| `backup.data` 写了 page / handler / confirm | 该槽位只接受数据选项 |
| 声明了备份事件但未声明 `contributes.backup` | 安装会被拒绝 |
| 内容无效 | 技能 / 子代理正文为空、子代理声明了 `provider` / `model`、schema 根不是 `type: object`、MCP 文件无有效 server |
| 清单引用的文件不存在 | 路径与压缩包实际路径不符，注意大小写 |
| 路径不安全 | 含 `..`、绝对路径、反斜杠或层级过深 |
| 文件太大 / 重复文件 | 对照上文大小上限；重复含仅大小写不同 |

日志：插件进程启动、调用失败、hook 超时和浮层创建失败均写入应用日志，打开「设置 → 日志」搜索插件 ID 或 `Plugin` 关键字。handler 可返回 `logs`（最多 50 行、单行 500 字符）主动写日志。调试建议先用最小插件验证清单与运行时，再逐项加能力；改了 `plugin.json` 必须重载或重新导入，宿主不监听清单变化。

---

## 发布到商店

插件通过插件索引（`index.json`）分发，插件包统一放在商店仓库 **main 分支的 `packages/` 目录**（文件名 `{id}_{version}.zip`），**不使用 GitHub Release**。整体流程：

1. **上传图标**：在插件商店的发布入口上传插件图标；
2. **填写作者名**：首次上传时会弹出对话框要求填写「作者名」（uploader），**必填且首次填写后固定**，用于插件所有者校验；
3. **暂存仓库**：把插件提交为待审条目（`.zip` 文件名建议用 `{id}_{version}.zip`）；
4. **审核**：提交由**后端人工审核**，通过后才进入商店；
5. **发布到 main**：审核通过后，后端用 GitHub Contents API 把插件包写入 `packages/{id}_{version}.zip`，并更新仓库根目录的 `index.json` 条目，均提交到 main 分支；
   > 包文件名 `{id}_{version}.zip` 中的 `id` 第一段必须是作者名、不能以 `aiverse` 开头（`aiverse.*` 为官方保留），版本号用下划线连接且不带 `v` 前缀，例如 `yhdmgf.desktop-pet_1.1.0.zip`；不符该格式会在上传/审核环节被拒。
5. **进入商店**：客户端从 main 分支拉 `index.json` 列表，再从 `packages/` 下载对应 `{id}_{version}.zip` 安装。

> 客户端只从 main 分支的 `packages/` 下载，仓库或目录不存在时会直接提示未找到（HTTP 404）。更新插件时递增 `version` 并重新走一遍发布流程即可。

### 插件所有者校验

为防止插件 ID 被冒名顶替，商店服务端实施**所有者校验**机制：

- **首次上传**：填写「作者名」（uploader）作为插件所有者标识，记录在待审核队列；
- **后续更新**：必须匹配首次上传时填写的作者名，否则会被拒绝（HTTP 403「该插件属于其他作者，你不能更新」）；
- **管理员特权**：管理员登录后台审核时，不受所有者限制，可以更新任何插件；
- **所有者转移**：管理员可在后台「ID 管理」功能里手动修改插件所有者（转移所有权）。

> 注意：作者名（uploader）和 plugin.json 里的 `author` 字段是两个概念：
> - `uploader`：上传者标识，用于所有者校验，只在后台审核队列显示；
> - `author`：插件作者，在商店和客户端显示，必须符合正则 `^[a-z][a-z0-9-]*$` 且与 `id` 第一段一致。
>
> 两者可以不同（例如：张三开发插件，李四帮忙上传，则 `author=张三`、`uploader=李四`）。

> 说明：上传 → 暂存 → 人工审核 → 写入 `packages/` 与 `index.json` 这条链路运行在插件商店的服务端，不在 App 客户端里；客户端只负责从 `main` 拉取 `index.json` 并下载 `packages/` 里的包。

### index.json 的字段与校验

`index.json` 由商店服务端维护，客户端拉取后按下列规则严格校验，任一条不满足会拒绝整个索引：

| 字段 | 约束 |
| --- | --- |
| `schema_version` | 必须为 `1` |
| 插件条目数 | 不超过上限 `MAX_PLUGINS` |
| `id` | 符合插件 ID 规则，且全表不得重复 |
| `author` | 正则 `^[a-z][a-z0-9-]*$`，且必须与 `id` 第一段（发布者）一致 |
| `repository` | 必须是 `https` 且 host 为 `github.com` |
| `version` | 禁止 `v` 前缀，须为合法语义化版本 |
| `min_app_version` | 须为合法语义化版本 |
| `logo` / `homepage` | 必须是 `https` |
| `tags` | 取值只能来自 `provider` / `tool` / `skill` / `agent` / `mcp` / `ui` / `integration` / `other`，最多 4 个且不得重复 |

> 「官方」标记不是 `tags` 里的一个值，而是按索引里 `author` 是否在官方白名单判定。

**审计拦截**：上传与审核阶段（商店服务端）会拦截可疑内容，包括路径越界（`..`、绝对路径）、可执行产物，以及代码里的 `eval`、`Runtime`、`su`、`pm install`、`sh -c` 等高危调用。这是商店侧的审核行为，不是客户端安装时的强制校验。

**放行**：Node / Python 的正常能力是放行的，例如 `child_process`、`spawn`、`fs`、`http` 等标准库与 API 的常规使用不算违规——插件本就运行在自己的进程里，靠宿主的授权策略约束危险动作。

**幂等**：重复提交同一 `id` + `version` 的条目是幂等的，不会产生重复条目；更新插件时递增 `version` 再提交即可。

商店索引更新后，在商店页**下拉刷新**即可拉到最新列表。

---

### 插件的更新机制

已安装插件的升级由宿主自动检测，无需手动比对版本：

- **启动自动检查**：App 启动后在后台静默检查一次所有已装插件是否有可升级版本。检查按来源逐个进行，单个来源失败只跳过该来源，整个过程异常吞掉，不打扰用户。
- **主界面提示条**：检测到可升级插件后，聊天页底部出现「检测到 N 个插件可更新」提示条，配高对比的「去安装」按钮。提示条可关闭，关闭后本次启动不再提示；点击按钮跳转到插件管理页。
- **一键更新全部**：插件管理页「已安装」Tab 提供「全部更新（N）」按钮，串行逐个完成下载 → 预览 → 校验 → 安装。单个插件失败不阻断其余插件，全部结束后提示失败数量。

---

## 最小可用插件示例

一个最小但完整的插件：一个 AI 工具 + 一个设置页 + 一句技能。目录结构：

```text
hello-plugin/
├── plugin.json
├── schemas/
│   └── hello.input.json
├── dist/
│   └── main.js
└── pages/
    └── panel.html
```

**plugin.json**

```json
{
  "manifestVersion": 1,
  "id": "community.hello-plugin",
  "name": { "default": "Hello Plugin", "zh-CN": "问好插件" },
  "version": "1.0.0",
  "publisher": "community",
  "repository": "https://github.com/example/hello-plugin",
  "description": { "default": "A minimal hello plugin.", "zh-CN": "最小问好插件。" },
  "license": "MIT",
  "icon": "package",
  "engines": { "pluginApi": "^1.0.0" },
  "runtime": { "type": "javascript", "entry": "dist/main.js" },
  "contributes": {
    "tools": [
      {
        "id": "hello",
        "title": { "default": "Hello", "zh-CN": "问好" },
        "description": { "default": "Return a greeting.", "zh-CN": "返回一句问候。" },
        "inputSchema": "schemas/hello.input.json",
        "handler": "tools.hello"
      }
    ],
    "uiEntries": [
      {
        "id": "panel",
        "title": { "default": "Hello Panel", "zh-CN": "问好面板" },
        "slot": "settings.general",
        "page": "pages/panel.html",
        "icon": "book"
      }
    ]
  }
}
```

**schemas/hello.input.json**

```json
{
  "type": "object",
  "properties": {
    "name": { "type": "string", "description": "Who to greet." }
  },
  "required": []
}
```

**dist/main.js**

```js
// 与宿主之间的 JSON-RPC 消息循环：读一行、处理、写一行。
const readline = require('readline');

const handlers = {
  'tools.hello': async (params) => {
    const name = (params && params.name) || 'world';
    return { data: { message: `Hello, ${name}!` } };
  }
};

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + '\n');
}

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch (e) { return; }
  if (!msg || typeof msg.method !== 'string') return;

  // 工具调用与 hook 派发都按 handlers 表的键路由。
  const handler = handlers[msg.method];
  if (!handler) {
    if (msg.id != null) {
      send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: 'method not found' } });
    }
    return;
  }
  Promise.resolve(handler(msg.params || {})).then(
    (result) => { if (msg.id != null) send({ jsonrpc: '2.0', id: msg.id, result }); },
    (err) => {
      if (msg.id != null) {
        send({ jsonrpc: '2.0', id: msg.id, error: { code: -32000, message: String(err && err.message || err) } });
      }
    }
  );
});
```

**pages/panel.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Hello Panel</title>
  <script src="/__aicode/aicode.js"></script>
  <link rel="stylesheet" href="/__aicode/ui.css">
</head>
<body>
  <main style="padding:16px; font-family: system-ui, sans-serif;">
    <h2>问好插件</h2>
    <p id="ctx">读取上下文中…</p>
    <button id="greet">打个招呼</button>
  </main>
  <script>
    (async () => {
      try {
        const ctx = await window.aicode.context();
        document.getElementById('ctx').textContent = '工作区已就绪';
      } catch (e) {
        document.getElementById('ctx').textContent = '上下文不可用：' + e.message;
      }
      document.getElementById('greet').addEventListener('click', async () => {
        await window.aicode.toast('Hello from plugin!');
      });
    })();
  </script>
</body>
</html>
```

打包后即可在「设置 → 插件」导入，或放入 `~/.aiverse/plugin-dev/hello-plugin/` 用开发者模式侧载。

---

## 容易踩的坑

- **slot 拼错致清单无效**：`slot` 只接受上文 12 个值，写错（如 `settings.General`、`drawer_footer`）会让整个 `uiEntries` 判为无效并拒绝安装。
- **contributes 全空无效**：`contributes` 必须至少有一项贡献，空对象会被拒绝。
- **最多 2 条 uiEntries**：每个插件最多声明 2 条 `uiEntries`（`backup.data` 最多 1 条），超出在安装时拒绝。
- **backup.data 不能带 page**：`backup.data` 槽位只接受数据选项（`id` / `title` / `icon`），写 `page` / `handler` / `confirm` 都无效。
- **声明 tools 必须 runtime**：有 `tools`、`hooks`，或 `uiEntries` 带 `handler` 时必须声明 `runtime`，否则 `FIELD_MISSING`。
- **页面文件必须存在**：`page` / `pageFile` / `configPage` / overlay `page` 指向的 HTML 必须真实存在于包内，且是 `.html` / `.htm`。
- **常驻插件 resident:true**：需要长期监听 `app.started`、维持 provider 兼容服务或保持状态的服务型插件，应把 `runtime.resident` 设为 `true`，否则进程可能被空闲回收。
- **改版本号才触发更新**：在线更新依赖 `version` 变化；发布更新时务必递增版本号。
- **商店索引更新后下拉刷新**：索引更新后商店列表不会自动变化，需手动下拉刷新。
- **子代理别绑 provider / model**：插件子代理声明 `provider` 或 `model` 会被拒绝，一律继承父会话。
- **runtime 进程依赖 Linux 容器**：`runtime` 声明的入口是跑在宿主内置 Linux 容器里的独立进程（Node / Python）。**容器未安装/未初始化时进程无法启动**，所有经 runtime 的调用（`tool.invoke`、`hook`、`ui.invoke`）都会返回 `PLUGIN_RUNTIME_UNAVAILABLE`，而纯页面型插件（无 runtime）不受影响。遇到这个错误先确认设备上「终端」功能已把容器初始化好，别急着改插件代码。
- **插件 ID 第一段必须是作者名**：`id` 形如 `作者名.插件名`，宿主强制校验 `id` 第一段等于 `publisher`（见 [`id` 与 `publisher`](#id-与-publisher)）。`aiverse.*` 是官方保留命名空间，第三方插件用它会被商店判为冒充官方并拒绝。发布包文件名也须是 `作者名.插件名_版本号.zip`。
