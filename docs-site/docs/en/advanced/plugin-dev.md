# Plugin Development Guide (manifest + JSON-RPC)

AiVerse's plugin system is built on a **`plugin.json` manifest + JSON-RPC process protocol**: a plugin declares what it provides in the manifest, the host launches the plugin's entry as a separate process on demand, and the two sides communicate via single-line JSON-RPC 2.0 over stdout/stdin; plugin pages call host capabilities through the page bridge `aicode.js`.

The host only loads what the manifest lists; unrecognized fields and contribution types cause the install to be rejected outright rather than silently ignored. This document describes the current manifest + JSON-RPC system; the old QuickJS plugin system (`package` / `main` / `permissions` / `$ptools` / ctx API) has been removed and all related approaches no longer work.

Document conventions

Code and identifiers in this document are in English and explanations are in English. Event names, slot names, `host.*` method names and the `contributes` fields follow the AiVerse source (`PluginManifest.kt` / `PluginManifestParser.kt` / `newmodel/PluginContent.kt` / `runtime/`). The term "plugin" is used consistently throughout.

## Contents

1.  [Package structure](#package-structure)
2.  [plugin.json all fields](#pluginjson-all-fields)
3.  [The thirteen contributes types](#the-thirteen-contributes-types)
4.  [runtime JSON-RPC protocol](#runtime-jsonrpc-protocol)
5.  [Page bridge](#page-bridge)
6.  [Packaging, installing & debugging](#packaging-installing--debugging)
7.  [Publishing to the store](#publishing-to-the-store)
8.  [Minimal working plugin example](#minimal-working-plugin-example)
9.  [Common pitfalls](#common-pitfalls)

\---

## Package Structure

A plugin is just a directory with `plugin.json` at its root; organize the rest as needed:

text

```
demo-kit/
├── plugin.json          # The single entry declaration (required)
├── dist/
│   └── main.js          # runtime entry (when there are tools / hooks / a handler)
├── schemas/
│   └── hello.input.json # JSON Schema for the tool
├── pages/
│   └── panel.html       # Page entry (uiEntries / workspaceTools / configPage / overlay)
├── skills/
│   └── hello/SKILL.md   # Skill Markdown
├── agents/
│   └── reviewer.md      # Sub-agent definition
└── mcp/
    └── context.json     # MCP config
```

### Path Constraints

Every value in the manifest that points to a file inside the package (`runtime.entry`, `inputSchema`, `file`, `page`, `pageFile`, `icon` paths, `configPage`) must be a **safe relative path**:

-   It cannot be absolute (must not start with `/`), and cannot contain `..` / `.` segments, empty segments, backslashes or control characters;
-   Length at most 200, at most 8 levels deep;
-   Page files must be `.html` / `.htm`; skills and sub-agents `.md`; tool schemas and MCP `.json`.

### Prohibitions & Size Limits

A package is untrusted input and is validated item by item at install time; exceeding any single limit rejects the whole package:

Item

Limit

Archive size

16 MiB

File count

512

Single file

4 MiB

Total uncompressed size

32 MiB

Paths

See above, and must be unique (including differing only by case)

Per-content-type single-file caps:

Content

Cap

`plugin.json`

256 KiB

Runtime entry

512 KiB

Skill / sub-agent Markdown

512 KiB

Page HTML

512 KiB

Tool JSON Schema

256 KiB

MCP config

256 KiB

Manifest icon bitmaps (`.png` / `.webp`)

256 KiB

Do not pack executable native artifacts (`.so` / `.dex` / `.apk` / `.jar` / `.wasm` / `.class` and the like) into the package — they are not declarative plugin content, and neither client install nor store review guarantees they'll be allowed through. Symlinks and permission bits inside the ZIP are ignored; every entry is written out as a plain file, and no links or executables are created on disk.

### Store Download Limits

When downloading from the plugin store, the archive likewise must not exceed 16 MiB, and the plugin index `index.json` must not exceed 2 MiB.

\---

## plugin.json All Fields

json

```
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

Field

Required

Description

`manifestVersion`

Yes

Fixed to the integer `1`

`id`

Yes

Stable plugin identifier, format `publisher.plugin-name`

`name`

Yes

Display name, localized text, max 80 characters, no line breaks

`version`

Yes

Semantic version, e.g. `1.0.0`, `1.1.0-beta.1`

`publisher`

Yes

Publisher identifier, must equal the first segment of `id`

`repository`

Yes

Source repository, must be `https://`, without account, query params or fragments

`description`

Yes

Purpose description, localized text, max 600 characters, line breaks allowed

`license`

Yes

License name, max 100 characters

`icon`

No

Plugin icon: a Feather built-in icon name, or a relative path to an in-package `.png` / `.webp`

`engines`

Yes

Currently only `{"pluginApi": "version range"}` is accepted

`runtime`

Required when there are tools / hooks / uiEntries with a handler

Business process entry and launch method

`contributes`

Yes

Provided content, at least one item

`configPage`

No

Plugin config page; may also be declared in `contributes.configPage`

Only the keys above may appear at the top level; any other key causes the install to be rejected with `UNSUPPORTED_FIELD`.

### id & publisher

-   `id` looks like `community.demo-kit`: both segments start with a lowercase letter and contain only lowercase letters, digits and hyphens, max 32 characters each (regex `^[a-z][a-z0-9-]{0,31}\.[a-z][a-z0-9-]{0,31}$`);
-   `publisher` must equal the first segment of `id`;
-   `id` is also used as the install directory name, so versions shouldn't be distinguished by renaming after install;
-   Local IDs (the `id` of skills, tools, pages, hooks, etc.) start with a letter and contain only letters, digits, underscores and hyphens, max 64 characters, and must be unique within their type.

### The Two Forms of Localized Text

`name`, `description` and each contribution's `title` / `description` can be either a single string or a per-language object:

json

```
{ "name": "演示套件" }
```

json

```
{ "name": { "default": "Demo Kit", "zh-CN": "演示套件", "en": "Demo Kit" } }
```

In the object form `default` is the required default entry and the remaining keys are language tags (such as `zh-CN`, `en`), up to 16 languages. The lookup order is: full language tag (`zh-CN`) → language (`zh`) → `default` → `en` → first item. Tag case and `-` / `_` don't matter.

### Icon

`icon` accepts only two kinds of values:

-   **Feather built-in icon name**: starts with a lowercase letter, contains only lowercase letters, digits and hyphens, max 32 characters, e.g. `package`, `terminal`, `book`, `settings`, `tool`, `cloud`, `monitor`; unknown names fall back to `package`.
-   **In-package image**: a relative path ending in `.png` or `.webp`, e.g. `icons/logo.png`, must be inside the package, single file up to 256 KiB.

SVG, JPEG and other formats aren't supported; listing those suffixes in the manifest causes the install to be rejected outright. Plugin HTML pages can still use in-package SVG images internally on their own — that has nothing to do with the manifest icon.

### version & engines.pluginApi

`version` is a semantic version. `engines.pluginApi` supports `^1.0.0`, `~1.0.0`, `>=1.0.0 &lt;2.0.0` and the exact version `1.0.0`; `1.x`, `*`, `||` and range shorthand aren't supported. The plugin API version the host currently provides is `1.0.0`; the declared range must include it, otherwise the install is rejected with `API_INCOMPATIBLE`.

### runtime

json

```
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

Field

Required

Description

`type`

Yes

`javascript` (launched with `node` by default) or `python` (launched with `python3` by default)

`entry`

Yes

In-package entry file; `.js` / `.mjs` / `.cjs` for JavaScript, `.py` for Python, max 512 KiB

`command`

No

String array overriding the default launch command, handed straight to `execve` without a shell; max 16 segments, each at most 200 characters, must not be an absolute path or contain `..`

`dependencies`

No

Dependency input: `tool` is `npm` or `pip`, and `file` is `.json` (e.g. `package.json`) or `.txt` (e.g. `requirements.txt`) respectively

`resident`

No

When `true`, the process is exempt from idle reclamation and stays resident after its first start; it still closes on disable, uninstall, version replacement or when the app process ends

The default launch command is `node ` or `python3 `. You must declare `runtime` when there's `contributes.tools`, `contributes.hooks`, or an action entry with a `handler` in `uiEntries`; purely declarative plugins (only skills, sub-agents, MCP, pages, etc.) can omit it.

\---

## The Thirteen contributes Types

`contributes` is an object whose usable keys are exactly the 13 below. Any other key causes a rejection with `UNSUPPORTED_CONTRIBUTION`; each type allows at most 64 entries (`uiEntries` has an additional per-slot cap), and a completely empty `contributes` is also invalid.

Key

Type

Purpose

`tools`

Array

Tools the AI can call

`skills`

Array

Skill Markdown

`agents`

Array

Sub-agent definitions

`workspaceTools`

Array

Pages in the right-side project tools drawer

`uiEntries`

Array

Entries mounted into host slots

`hooks`

Array

Lifecycle hooks

`mcp`

Array

MCP server config

`providers`

Array

OpenAI-compatible providers

`shizuku`

Object

Declares use of Shizuku

`overlay`

Object

Declares a system overlay

`backup`

Object

Declares receiving backup & restore events

`modelAccess`

Object

Declares calling the user's configured AI providers

`configPage`

String

Config page in the plugin details

### tools

json

```
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

Field

Required

Description

`id`

Yes

Local ID; determines part of the AI tool name

`title`

Yes

Display name, max 80 characters

`description`

No

Purpose description for the AI's reference, max 600 characters; empty string when omitted

`inputSchema`

Yes

In-package `.json` JSON Schema file, max 256 KiB, root must be `{"type": "object"}`

`handler`

Yes

Stable key in the runtime-exported handlers table, e.g. `tools.hello`

The tool's full name on the AI side is `plugin____`, e.g. `plugin__community.demo-kit__hello`; the host uses that to distinguish it from built-in and MCP tools.

runtime must be declared

Declaring `tools` without `runtime` causes the install to be rejected with `FIELD_MISSING` — a tool's handler has to run inside the plugin process. Declaring doesn't start the process right away; it starts on first call.

### skills / agents

json

```
{
  "skills": [{ "id": "hello", "file": "skills/hello/SKILL.md" }],
  "agents": [{ "id": "reviewer", "file": "agents/reviewer.md" }]
}
```

Field

Required

Description

`id`

Yes

Local ID, unique within its type

`file`

Yes

In-package `.md` relative path, max 512 KiB

After contributing, the name the model and the user see is `:` (e.g. `community.demo-kit:hello`); the namespace is generated by the host, and the plugin cannot override built-in content or another plugin's same-named content.

-   A skill body must not be empty; the description goes into the skill list of the system prompt and gets flattened to a single line, to prevent faking extra list entries via line breaks.
-   A sub-agent body must be a valid sub-agent definition and **must not declare `provider` or `model`** — plugin sub-agents always inherit the parent session, so they can't tap the user's paid channels.

### workspaceTools

json

```
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

Field

Required

Description

`id`

Yes

Local ID

`title`

Yes

Localized name, max 80 characters

`description`

No

Localized description, max 600 characters

`icon`

No

Icon name, default `play`, max 32 characters

`category`

No

Category name, default `通用` (General), max 32 characters

`pageFile`

Yes

In-package `.html` / `.htm` page, max 512 KiB

`pageFile` must be a page file; pointing it at another type causes a rejection.

### uiEntries

json

```
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

Field

Required

Description

`id`

Yes

Local ID; entries within the same plugin must not share a name

`title`

Yes

Localized name, max 80 characters

`slot`

Yes

Host slot; only the values in the table below are accepted

`page`

One of two

In-package `.html` / `.htm` page, max 512 KiB

`handler`

One of two

runtime handler name; requires declaring `runtime`

`confirm`

No

Action entries only: makes the host show a confirmation dialog before executing

`icon`

No

Icon name, default `book`, max 32 characters

Exactly one of `page` and `handler` must appear. Each plugin may declare **at most 2** `uiEntries` (the `backup.data` slot allows at most 1); exceeding that causes rejection at install time. When an action entry returns `{message}`, the host shows it as a toast.

**13 slots (12 UI slots + 1 data slot):**

`slot`

Location

Restriction

`settings.general`

End of the "General" group on the settings page

Accepts only `page`

`settings.ai`

End of the "AI config" group on the settings page

Accepts only `page`

`settings.environment`

End of the "Runtime environment" group on the settings page

Accepts only `page`

`settings.permissions`

End of the "Permissions & background" group on the settings page

Accepts only `page`

`settings.data`

End of the "Data & diagnostics" group on the settings page

Accepts only `page`

`settings.help`

End of the "Help & about" group on the settings page

Accepts only `page`

`drawer.footer`

Bottom of the sidebar, above Browser and Settings

`page` or `handler`

`chat.header.menu`

Bottom of the chat top bar's "More" menu

`page` or `handler`

`chat.welcome`

Chat empty-state suggestions area

`page` or `handler`

`chat.session.menu`

Session long-press menu

`page` or `handler`

`files.tree.menu`

File/directory long-press menu in the file tree

Accepts only `handler` (action)

`editor.selection.menu`

Floating menu after selecting text in the code editor

Accepts only `handler` (action)

`backup.data`

End of the "Choose export data" group on the backup page

Accepts only data options

`settings.*` slots accept only page entries; `files.tree.menu` and `editor.selection.menu` are action-type slots and accept only `handler`; `backup.data` accepts only data options (write only `id` / `title` / `icon`, not `page` / `handler` / `confirm`). Entry ordering is decided by the host; plugins cannot specify an insertion position.

**Host rendering status:** The host currently renders `settings.general` / `settings.ai` / `settings.environment` / `settings.permissions` / `settings.data` / `settings.help`, plus `chat.header.menu` (bottom of the chat top bar's "More" menu) and `chat.welcome` (empty-state suggestions area). `drawer.footer`, `chat.session.menu`, `files.tree.menu`, `editor.selection.menu`, and `backup.data` are parsed and accepted at install time, but the host does not render entries for them yet.

### hooks

json

```
{
  "hooks": [
    { "id": "run-log", "event": "agent.run.finished", "handler": "hooks.runFinished" },
    { "id": "guard-bash", "event": "agent.tool.before", "handler": "hooks.guardBash", "mode": "transform" },
    { "id": "review", "event": "agent.permission.review", "handler": "hooks.review", "mode": "review" }
  ]
}
```

Field

Required

Description

`id`

Yes

Local ID

`event`

Yes

Must be an event the host supports (see the table below)

`handler`

Yes

Stable key in the runtime-exported handlers table

`mode`

No

`observe` (default), `transform`, `review`

**Three modes:**

Mode

Behavior

Use cases

`observe`

The host dispatches asynchronously; the plugin only returns logs or its own result and doesn't affect the main flow

Logging, statistics, notifications

`transform`

The host waits at a fixed interception point for the result and applies it after validation; failures, timeouts or malformed results keep the original value

Blocking dangerous calls, rewriting arguments, redacting results, rewriting requests

`review`

Runs only after the host decides user confirmation (ASK) is needed; returns `allow` / `deny` / `pass`

Review on behalf of

`transform` and `review` events must explicitly declare the matching `mode` in the manifest; getting the declaration wrong causes rejection at install time.

**Event table (19 total):**

Event

Timing

Mode

`app.started`

App process has finished starting; used for resident-plugin initialization

observe

`agent.run.started`

A round of conversation begins processing

observe

`agent.run.finished`

A round of conversation ends (emitted on success, failure and cancellation)

observe

`agent.request.started`

A model request is about to be sent

observe

`agent.request.finished`

A model response ends

observe

`agent.tool.started`

A tool call actually starts executing

observe

`agent.tool.finished`

A tool call ends

observe

`agent.compaction`

Context was compacted

observe

`agent.tool.before`

Before a tool call executes, before the permission check

transform

`agent.tool.after`

After a tool call finishes successfully

transform

`agent.http.before`

Before each actual provider HTTP request is sent

transform

`agent.permission.review`

After the host decides confirmation is needed, before the dialog

review

`plugin.installed`

Plugin installed or version replaced; dispatched only to that plugin itself

observe

`plugin.uninstalled`

Before a plugin uninstall takes effect; install and data directories are still accessible

observe

`plugin.enabled`

After a plugin enable takes effect

observe

`plugin.disabled`

Before a plugin disable takes effect; the plugin can still run

observe

`backup.export.before`

Before a full backup is written to the archive

observe (requires `backup`)

`backup.import.finished`

After a restore succeeds

observe (requires `backup`)

`agent.tool.before` can return `decision: "deny"` to block execution, or `arguments` to rewrite arguments (the host re-runs the full permission check after rewriting), or `delegate: {handler}` to pick the executing party. `agent.tool.after` can rewrite the `data` of a successful result. `agent.http.before` can rewrite `url` / `headers` / `body`; sensitive request headers appear in the payload only as placeholders, and the host restores the real values only if the plugin returns the placeholders unchanged and the request still goes to the original origin.

### Delegating Tool Execution

A plugin declaring `agent.tool.before` with `mode: "transform"` can return `delegate: {handler: "tools.remote"}`. The before handler can only choose the executing party — it shouldn't execute the tool or produce tool side effects here. The host calls the chosen executing handler only after collecting the final arguments and re-completing the permission and mode checks; `decision: "allow"` can't skip the checks either. Delegated execution is subject to the same permission boundaries as the original tool and doesn't broaden PLAN or DENY permissions.

Multiple hooks are processed in their original order; the first valid delegation is fixed to its plugin, version and handler, and later hooks can still rewrite arguments or deny, but can't take over the executing party. The final arguments are used at execution time. The host generates a separate execution identifier and binds the run, the tool call and the plugin to it; you can't target another plugin. Disabling, updating, execution errors, timeouts or cancellation of the plugin do not fall back to the local tool. Delegation keeps the original tool card, progress and started/finished notifications, but does not create a local file-modification checkpoint. A plugin must not review on behalf of native tool calls that were delegated to itself.

Using the SDK's `toolDelegate` to wrap the executing handler: an async generator `yield`s progress text and `return`s the final JSON data; throwing an exception means failure. Network requests, subprocesses and the like that support cancellation should listen on `signal` and terminate the actual work; the host canceling its wait doesn't mean it can forcibly terminate the plugin's arbitrary external side effects.

ts

```
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

Non-SDK implementations use the same handler's `start` / `next` / `cancel` operations: the request contains `executionId`, `runId`, `callId`, `toolName`, and only `start` carries the final `arguments`. Responses must return the same executionId, in the format `{type: "progress", chunk}`, `{type: "pending"}`, `{type: "completed", data}` or `{type: "error", message}`. `next` should long-poll for about 25 seconds, with a 35-second cap per RPC; the same executionId must not be started twice or read concurrently. The SDK pulls one increment at a time and caps a single progress entry at 64 Ki characters to avoid an unbounded queue. After completion, failure or cancellation, the execution identifier is retained for 31 minutes within the current process to reject duplicate starts; a process restart doesn't guarantee cross-process deduplication, so the executing party should still use business-idempotent identifiers for external side effects. The host's overall execution cap is 30 minutes, and after an exception or cancellation it waits at most 2 seconds to send a cancel; the SDK notifies the executing party via AbortSignal and cleans up state.

### Proactive Authorization Requests

A plugin can call `hostCall('host.permissions.request', ...)` to request the app's native one-shot authorization card; the request is bound to the originating plugin and session, produces no permanent authorization, and can't override the host's own DENY/PLAN tools. On the page it's `aicode.permissions.request(options)` / `cancel(requestId)`. The card is fixedly labeled by the app with the plugin's origin and returns `allow` / `deny` / `cancel`, waiting 2 minutes by default, configurable from 1 second to 10 minutes via `timeoutMs`; it cancels on timeout, workspace switch, session removal, or plugin disable.

`plugin.installed` / `plugin.enabled` are dispatched asynchronously; `plugin.uninstalled` / `plugin.disabled` wait synchronously before the state changes (at most about 10 seconds each), giving the plugin a chance to clean up its own data, but the plugin can't block the operation. These four events are dispatched only to the plugin they concern.

`backup.export.before` and `backup.import.finished` carry out a full backup package or write to a staging area, and **must be declared together with `contributes.backup`**, otherwise the install is rejected.

### mcp

json

```
{ "mcp": [{ "id": "project-context", "file": "mcp/project-context.json" }] }
```

Field

Required

Description

`id`

Yes

Local ID

`file`

Yes

In-package `.json` file, max 256 KiB, with `{"mcpServers": {...&#125;&#125;` structure

Server names in the config get the plugin namespace added by the host (of the form `plugin______`), to avoid overriding the user's global MCP config.

### providers

json

```
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

Field

Required

Description

`id`

Yes

Local ID

`name`

Yes

Localized name, max 80 characters

`description`

No

Localized description, max 600 characters

`format`

Yes

Currently only `openai` is supported

`baseUrl`

Yes

Absolute `http` / `https` address, without account, query params or fragments, max 2048 characters

The plugin is responsible for starting the compatible service and making sure the host can reach it; such plugins should usually set `runtime.resident` to `true` so the service process stays resident. After the plugin is enabled, the host registers a provider entry with the id `plugin::` (tagged "plugin"); disabling or uninstalling removes that entry. The host reuses its existing OpenAI adapter to make requests and doesn't build in a new upstream protocol.

### shizuku / overlay / backup / modelAccess

These four are **authorization-type declarations**; each needs only a `purpose` description (max 200 characters), shown on the install preview and the plugin details' authorization screen. Declaring doesn't grant authorization automatically — the user must enable it separately in the plugin details.

json

```
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

-   **shizuku**: once authorized, the plugin can execute commands directly in the Android system (page bridge `shizuku.exec` / process `host.shizuku.exec`). The system's Shizuku service must be running.
-   **backup**: once authorized, it can receive the `backup.export.before` / `backup.import.finished` events and declare one data option in the `backup.data` slot.
-   **modelAccess**: once authorized, the plugin can call the user's configured AI providers (process `host.model.*`, page `aicode.model.*`). The declaration itself doesn't grant key-read permission — requests are sent through the host's adapter and count against that channel's usage. When not declared, calls return `MODEL_ACCESS_NOT_DECLARED`.
-   **overlay**: declares a system overlay; `surfaces` needs at least 1 and at most 3 entries. Each surface:

Field

Required

Description

`id`

Yes

Local ID, unique within the plugin

`page`

Yes

In-package `.html` / `.htm` page

`width`

No

Default width (dp), default 240, range 48–720

`height`

No

Default height (dp), default 320, range 48–1200

`draggable`

No

Whether dragging is allowed, default `true`

`snap`

No

Snap axis on release: `none`, `x`, `y`, `both`, default `none`

The overlay needs separate authorization, and the app must have the "display over other apps" system permission.

### configPage

json

```
{ "configPage": "pages/config.html" }
```

It can also be written in `contributes.configPage`. It must be an in-package `.html` / `.htm` file, max 512 KiB. The config page appears only in the plugin details under "Settings → Plugins" and is never mixed into the main settings menu. Config pages are for entering API keys, toggles and options, and are loaded only when the plugin is enabled and you switch to the config tab for the first time; while you stay on the native management tab, page scripts aren't executed early. Disabling, uninstalling or replacing the install invalidates the old page bridge and cancels that bridge's in-flight tasks, file subscriptions and model streams; after re-enabling, the page must be reopened — the old bridge doesn't restore its authorization.

\---

## runtime JSON-RPC Protocol

The plugin entry runs as a separate process, and the host communicates with it via **single-line JSON-RPC 2.0 over stdout / stdin**: every line is a complete JSON-RPC message. Requests the plugin initiates (with `method`) are calls into the host API; those without an `id` are notifications. The calls the host sends down are mainly tool invocations (`tool.invoke`) and hook dispatches, which the plugin routes using keys in its `handlers` table.

### host.\* Method Table

The process side can call the following host methods (`id` + `method` + `params`; the host replies with `result` or `error`):

**Chat & sessions**

Method

Description

`host.chat.openSession`

Open (jump to) a session

`host.chat.getSession`

Read session info

`host.chat.renameSession`

Rename a session

`host.chat.pinSession`

Pin / unpin a session

`host.chat.deleteSession`

Delete a session

`host.chat.getRunState`

Read the session's current run state

`host.chat.waitForRun`

Wait for a round of running to end (with an optional timeout)

`host.chat.listApprovals`

List pending approval requests

`host.chat.resolveToolApproval`

Handle a tool approval

`host.chat.resolveQuestion`

Handle a question to the user

`host.chat.resolvePlanApproval`

Handle a plan approval

(The same service also offers `host.chat.listSessions` / `createSession` / `listMessages` / `sendMessage` / `setDraft` / `stop` for reading and writing the current workspace's sessions and messages.)

**Model handles**

Method

Description

`host.model.listProviders`

List the providers the user has configured

`host.model.listModels`

List a provider's models

`host.model.create`

Create a model handle

`host.model.start`

Start a streaming request

`host.model.read`

Read stream events (text / reasoning / retry / keySwitch)

`host.model.cancel`

Cancel a stream

`host.model.close`

Close a model handle

Requires `contributes.modelAccess` authorization; the plugin can't get the key — requests go through the host adapter and count against channel usage.

**Clipboard / share / open link / app info / logs**

Method

Description

`host.clipboard.read`

Read the system clipboard

`host.clipboard.write`

Write to the system clipboard

`host.share.text`

Invoke the system share for text

`host.share.image`

Invoke the system share for a workspace image

`host.share.file`

Invoke the system share for any workspace file

`host.ui.openUrl`

Open an `http` / `https` link in the system browser

`host.app.info`

Read app info

`host.log`

Write to the host log, handy for troubleshooting

`host.app.isForeground`

Query the app's foreground/background state

**Tools**

Method

Description

`host.tools.list`

List host AI tools and MCP server status (read-only)

`host.tools.execute`

Execute a built-in / MCP tool (plugin tools `plugin__` can't be called this way)

`host.tools.pending`

Query pending tool executions awaiting approval

`host.tools.resolve`

Handle a tool approval (execute hangs while confirmation is needed)

**MCP / HTTP**

Method

Description

`host.mcp.status`

Query MCP server connection status

`host.http.request`

An HTTP request proxied by the host; doesn't inject host credentials and doesn't follow redirects

The process side can also use methods like `host.fs.*` (`read` / `write` / `list` / `delete` / `stat` / `mkdir` / `rename` / `copy` / `append` / `readBinary` / `writeBinary`), `host.bash`, `host.notify`, `host.tip`, `host.shizuku.exec`, `host.overlay.*`, `host.terminal.*`, `host.secrets.*`, which map one-to-one to the page bridge capabilities (see the next section).

\---

## Page Bridge

Plugin pages (the `page` of `uiEntries`, the `pageFile` of `workspaceTools`, `configPage`, the overlay's `page`) are loaded by the host WebView and call host capabilities through the page bridge. Include in the page:

html

```

```

Page resources are served only through the virtual domain: `/plugin/` maps to the plugin's install directory and `/__aicode/` serves the host UI assets — the local file system is **not exposed**.

### How to Call

All methods return Promises. The core entry is `window.aicode.call(method, params)`, and the commonly used capabilities are wrapped into namespaces:

js

```
const ctx = await window.aicode.context();
await window.aicode.toast('已同步');
const out = await window.aicode.bash('ls -1', { timeout: 10 });
await window.aicode.ui.openUrl('https://example.com');
```

The host page bridge uniformly exposes `window.aiverse`, and provides the alias `window.aicode` for compatibility with older pages (both point at the same object). `window.aicode.events.on(handler)` listens for events pushed by the plugin process and the host, and returns an unsubscribe function.

### Method Table

Namespace / method

Description

`aicode.context()`

Read the page context

`aicode.toast(message)`

Show a toast

`aicode.tip(options)`

Show a dismissible tip bar inside the conversation (with an optional click callback)

`aicode.app.info()` / `aicode.app.isForeground()`

App info / foreground-background state

`aicode.ui.close()` / `openUrl(url)` / `setOverlay(visible, rect)` / `onBack(handler)`

Close the page, open a link, show a full-screen in-page overlay, register a back handler

`aicode.fs.read/write/list/delete/stat/mkdir/rename/copy/append/readBinary/writeBinary/watch`

File operations. Paths are real absolute paths in the execution environment; `~` expands to home; plugin-private data uses the `data://` virtual root. `fs.read` accepts optional `offset` / `limit` (bytes) to read a large file in windows, and returns two extra fields: `returned` (bytes returned this call) and `nextOffset` (start of the next window, equal to `size` at EOF). `size` is always the total file size in bytes, unaffected by `offset` / `limit`

`aicode.chat.listSessions/createSession/listMessages/sendMessage/setDraft/stop/openSession/getSession/renameSession/pinSession/deleteSession/getRunState/waitForRun/listApprovals/resolveToolApproval/resolveQuestion/resolvePlanApproval`

Sessions and messages; read scope is limited to the current workspace

`aicode.model.listProviders/listModels/create/close/start/read/cancel/stream`

Model handles (requires `modelAccess`)

`aicode.tools.list/execute/pending/resolve`

Host tool catalog and execution; only built-in / MCP tools allowed, subject to the authorization policy

`aicode.mcp.status()`

MCP server status

`aicode.http.request(options)`

An HTTP request proxied by the host

`aicode.clipboard.read()` / `write(text)`

System clipboard

`aicode.share.text(text, options)`

System share

`aicode.share.image(path, options)` / `file(path, options)`

Share a workspace image / any workspace file

`aicode.secrets.get/set/delete/list`

Plugin-private encrypted key-value store; `list` returns only key names

`aicode.bash(command, options)`

Run a one-off Shell command in the current workspace, returning `{ output, exitCode, truncated }`

`aicode.terminal.run/poll/stop/close/write/list`

Shared terminal tabs: commands run in the background and don't stop when the page closes

`aicode.shizuku.exec(command, options)`

Execute a command in the Android system via Shizuku (requires declaring `shizuku` and authorization)

`aicode.overlay.create/update/close/self/setDragRegion/moveBy/resize/setVisibilityPolicy/onMessage/postMessage`

Host-hosted system overlays

`aicode.invoke(handler, args)`

Call a handler of the same plugin's resident process (cross-plugin isn't supported)

`aicode.log.write(level, message, options)`

Write to the host log

`aicode.applyTheme(variables)`

Apply host theme variables to the page

### Host UI Resources

`/__aicode/` provides host built-in UI assets that pages can reference directly:

Resource

Purpose

`aicode.js`

Page bridge script

`vue.global.prod.js`

Global Vue build, for writing declarative UI in pages

`aicode-ui-vue.js`

Vue-based host UI component library (requires loading `vue.global.prod.js` first)

`ui.css`

Host UI styles

`theme.css`

CSS variables generated dynamically to follow the light/dark theme

`eruda.min.js`

In-page debugging console

`markdown/`

Markdown rendering related resources

Reference example:

html

```



```

Page responses carry a CSP that forbids remote scripts, iframes and object; page resources have browser caching disabled, so after plugin updates and APK updates the WebView won't keep serving stale content.

\---

## Packaging, Installing & Debugging

### Packaging

Run this in the plugin directory, noting the trailing `.` (which keeps `plugin.json` at the archive root):

bash

```
cd demo-kit
zip -r ../demo-kit-1.0.0.zip .
```

When `zip` isn't installed in the container, use Python:

bash

```
python3 -c "import shutil; shutil.make_archive('../demo-kit-1.0.0', 'zip', '.')"
```

After packaging, check that the archive root looks like:

text

```
demo-kit-1.0.0.zip
├── plugin.json
├── dist/
├── schemas/
└── ...
```

If you see `demo-kit/plugin.json` after opening the zip, an extra directory level got nested in, and import will report that there's no `plugin.json` in the archive root. It's a good idea to exclude `node_modules`, `.git`, build caches and local test data; dependencies are declared via `runtime.dependencies` and installed when the user clicks "Prepare dependencies", or bundled into the entry by the author before publishing.

### Installing

Open "Settings → Plugins" and click **+** in the top-right corner to import a `.zip`. The install confirmation page lists the capabilities the plugin provides; confirm to enable it. A newer version with the same `id` overwrites the install; disabling only turns off capabilities without deleting data; uninstalling cleans up the plugin directory and `data://` data.

### Developer Mode

When repeatedly packaging and importing is inefficient, use developer sideloading:

1.  In "Settings → Plugins", click the settings button in the top-right corner and turn on "Developer sideload" in the plugin settings;
2.  Put the project directory into the container's `~/.aiverse/plugin-dev//`, and each directory must contain a `plugin.json`;
3.  Back on the plugins page, open the project details in the "In development" tab and click "Install" or "Reload". While it's on, saving files triggers an automatic reload, and pulling down that list rescans the directories.

Sideloading doesn't participate in online updates, and plain `.zip` import still works; sideload packaging skips directories like `node_modules` and `.git`, but is ultimately still subject to the install limits. In remote SSH mode, `~/.aiverse/plugin-dev` lives on the remote server.

### Troubleshooting Checklist

Symptom

Common cause

No `plugin.json` in the archive root

Packaging nested an extra directory level

Missing / invalid field in the manifest

The message spells out the exact path; compare field by field against the above

Fields / contribution types this version doesn't support

You used an unimplemented capability; unknown fields aren't ignored

hooks or uiEntries declare a handler but no runtime

Contributions with handlers all require `runtime`

Invalid `slot` in `uiEntries`

A misspelled slot name invalidates the whole manifest; check against the 13-slot table above

`page` in `uiEntries` isn't HTML

`page` must point to an in-package `.html` / `.htm`

`uiEntries` declares both or neither entry type

Exactly one of `page`, `handler` must appear

`backup.data` has a page / handler / confirm

That slot accepts only data options

Backup events declared without `contributes.backup`

The install is rejected

Invalid content

Empty skill / sub-agent body, sub-agent declares `provider` / `model`, schema root isn't `type: object`, MCP file has no valid server

File referenced by the manifest doesn't exist

The path doesn't match the actual path in the archive; watch out for case

Unsafe path

Contains `..`, an absolute path, a backslash, or is too deeply nested

File too large / duplicate files

Compare against the size caps above; duplicates include differing only by case

Logs: plugin process starts, call failures, hook timeouts and overlay creation failures are all written to the app log — open "Settings → Logs" and search for the plugin ID or the `Plugin` keyword. A handler can return `logs` (up to 50 lines, 500 characters per line) to proactively write logs. For debugging, validate the manifest and runtime with a minimal plugin first, then add capabilities one at a time; after changing `plugin.json` you must reload or re-import, since the host doesn't watch manifest changes.

\---

## Publishing to the Store

Plugins are distributed via a plugin index (`index.json`), with plugin packages placed in the store repository's **`packages/` directory on the `main` branch** (file name `{id}_{version}.zip`) — **GitHub Releases aren't used**. The overall flow:

1.  **Upload the icon**: upload the plugin icon at the store's publish entry;
2.  **Enter the author name**: the first upload pops a dialog asking for an "author name" (uploader), which is **required and fixed after the first entry**, used for plugin-owner verification;
3.  **Stage in the repository**: submit the plugin as a pending-review entry (name the `.zip` file `{id}_{version}.zip`);
4.  **Review**: submissions are **reviewed manually on the backend** and only enter the store after passing;
5.  **Publish to main**: after approval, the backend uses the GitHub Contents API to write the package to `packages/{id}_{version}.zip` and update the `index.json` entry at the repository root, both committed to the `main` branch;

\> In the package file name `{id}_{version}.zip`, the first segment of `id` must be the author name and must not start with `aiverse` (`aiverse.*` is officially reserved); the version uses underscores and no `v` prefix, e.g. `yhdmgf.desktop-pet_1.1.0.zip`. Not matching that format gets rejected at upload/review.

1.  **Enter the store**: the client pulls the `index.json` list from the `main` branch, then downloads the matching `{id}_{version}.zip` from `packages/` to install.

\> The client only downloads from the `packages/` directory on the `main` branch, and reports "not found" (HTTP 404) straight away if the repository or directory is missing. To update a plugin, just bump `version` and go through the publish flow again.

### Plugin-Owner Verification

To prevent plugin IDs from being impersonated, the store backend implements an **owner verification** mechanism:

-   **First upload**: you enter an "author name" (uploader) as the plugin-owner identifier, recorded in the review queue;
-   **Subsequent updates**: they must match the author name entered at first upload, otherwise they're rejected (HTTP 403 "This plugin belongs to another author; you can't update it");
-   **Admin privilege**: when an admin logs into the backend to review, they aren't subject to the owner restriction and can update any plugin;
-   **Owner transfer**: an admin can manually change a plugin's owner (transfer ownership) in the backend's "ID management" feature.

\> Note that the author name (uploader) and the `author` field in plugin.json are two different concepts: > - `uploader`: the uploader identifier, used for owner verification, shown only in the backend review queue; > - `author`: the plugin author, shown in the store and the client, must match the regex `^[a-z][a-z0-9-]*$` and be consistent with the first segment of `id`. > > The two can differ (e.g. Alice develops the plugin and Bob helps upload, giving `author=Alice` and `uploader=Bob`).

\> Note: the chain of upload → staging → manual review → writing to `packages/` and `index.json` runs on the plugin store's backend, not inside the app client; the client is only responsible for pulling `index.json` from `main` and downloading the packages in `packages/`.

### index.json Fields & Validation

`index.json` is maintained by the store backend; the client validates it strictly per the rules below after pulling it, and any single failing rule rejects the whole index:

Field

Constraint

`schema_version`

Must be `1`

Plugin entry count

Not more than the cap `MAX_PLUGINS`

`id`

Follows the plugin ID rules, and must not repeat across the table

`author`

Matches the regex `^[a-z][a-z0-9-]*$`, and must be consistent with the first segment of `id` (the publisher)

`repository`

Must be `https` with host `github.com`

`version`

No `v` prefix allowed; must be a valid semantic version

`min_app_version`

Must be a valid semantic version

`logo` / `homepage`

Must be `https`

`tags`

Values only from `provider` / `tool` / `skill` / `agent` / `mcp` / `ui` / `integration` / `other`, at most 4 and without repeats

\> The "Official" marker isn't a value in `tags`; it's decided by whether the index entry's `author` is on the official whitelist.

**Audit blocking**: at the upload and review stages (store backend), suspicious content is blocked, including path traversal (`..`, absolute paths), executable artifacts, and high-risk calls in the code such as `eval`, `Runtime`, `su`, `pm install`, `sh -c`. This is store-side review behavior, not a hard check at client install time.

**What's allowed**: Node / Python's normal capabilities are allowed — routine use of standard libraries and APIs such as `child_process`, `spawn`, `fs`, `http` isn't a violation — plugins run in their own process by design, and dangerous actions are constrained by the host's authorization policy.

**Idempotency**: resubmitting an entry with the same `id` + `version` is idempotent and doesn't produce a duplicate; to update a plugin, bump `version` and submit again.

After the store index updates, a **pull-to-refresh** on the store page fetches the latest list.

\---

### The Plugin Update Mechanism

Upgrades for installed plugins are detected automatically by the host, with no need to compare versions manually:

-   **Automatic check on startup**: after the app starts, it quietly checks once in the background whether any installed plugins have an upgradable version. The check runs per source; a failure for one source only skips that source, and any exception in the whole process is swallowed without bothering the user.
-   **Main-screen notice bar**: when an upgradable plugin is detected, a "Detected N plugins to update" notice bar appears at the bottom of the chat page with a high-contrast "Install now" button. The bar can be dismissed, and after dismissal it won't be shown again this launch; tapping the button jumps to the plugin management page.
-   **Update all at once**: the plugin management page's "Installed" tab offers an "Update all (N)" button that serially completes download → preview → verify → install for each plugin. A single plugin failing doesn't block the rest, and a count of failures is shown once everything finishes.

\---

## Minimal Working Plugin Example

A minimal but complete plugin: one AI tool + one settings page + one skill. Directory structure:

text

```
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

json

```
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

json

```
{
  "type": "object",
  "properties": {
    "name": { "type": "string", "description": "Who to greet." }
  },
  "required": []
}
```

**dist/main.js**

js

```
// JSON-RPC message loop with the host: read a line, process it, write a line.
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

  // Tool calls and hook dispatches are both routed by keys in the handlers table.
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

html

```
&lt;!DOCTYPE html>


  
  
  Hello Panel
  
  


  
    问好插件
    读取上下文中…
    打个招呼
  
  
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
  


```

Once packaged, you can import it under "Settings → Plugins", or drop it into `~/.aiverse/plugin-dev/hello-plugin/` and sideload it via developer mode.

\---

## Common Pitfalls

-   **A misspelled slot invalidates the manifest**: `slot` accepts only the 12 values above; writing it wrong (e.g. `settings.General`, `drawer_footer`) makes the entire `uiEntries` invalid and rejects the install.
-   **A completely empty contributes is invalid**: `contributes` needs at least one contribution; an empty object is rejected.
-   **At most 2 uiEntries**: each plugin may declare at most 2 `uiEntries` (at most 1 for `backup.data`); exceeding that is rejected at install time.
-   **backup.data can't carry a page**: the `backup.data` slot accepts only data options (`id` / `title` / `icon`); writing `page` / `handler` / `confirm` has no effect.
-   **Declaring tools requires runtime**: with `tools`, `hooks`, or a `handler` on a `uiEntries` entry, `runtime` must be declared, otherwise `FIELD_MISSING`.
-   **Page files must exist**: the HTML that `page` / `pageFile` / `configPage` / the overlay's `page` point to must actually exist in the package and be `.html` / `.htm`.
-   **Resident plugins and resident:true**: service-type plugins that need to listen for `app.started` over the long term, maintain a provider-compatible service or hold state should set `runtime.resident` to `true`, otherwise the process may be reclaimed while idle.
-   **Only a version bump triggers an update**: online updates depend on `version` changing; always bump the version when publishing an update.
-   **Pull to refresh after the store index updates**: the store list doesn't change automatically after the index updates; you need to pull down to refresh manually.
-   **Don't bind provider / model on sub-agents**: a plugin sub-agent declaring `provider` or `model` is rejected; they always inherit the parent session.
-   **The runtime process depends on the Linux container**: the entry declared in `runtime` runs as a separate process inside the host's built-in Linux container (Node / Python). **If the container isn't installed/initialized, the process can't start**, and every call that goes through the runtime (`tool.invoke`, `hook`, `ui.invoke`) returns `PLUGIN_RUNTIME_UNAVAILABLE`, while purely page-based plugins (without a runtime) are unaffected. When you hit this error, first confirm the "Terminal" feature on the device has initialized the container properly before rushing to change plugin code.
-   **The first segment of the plugin ID must be the author name**: `id` takes the form `author-name.plugin-name`, and the host enforces that the first segment of `id` equals `publisher` (see [`id` & `publisher`](#id--publisher)). `aiverse.*` is an officially reserved namespace, and a third-party plugin using it is judged by the store as impersonating the official one and rejected. The published package file name must also be `author-name.plugin-name_version.zip`.
