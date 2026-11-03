<?php
/**
 * AiVerse 扩展市场 API（单文件，JSON 文件存储，零依赖）
 *
 * 接口：
 *   GET  ?action=get             返回扩展列表
 *   POST ?action=edit            编辑扩展（body JSON：{op, extension}）
 *
 * edit 的 op：
 *   add    新增（同名覆盖）
 *   update 更新（不存在则新增）
 *   delete 删除（按 name）
 *
 * extension 字段：
 *   name          扩展唯一名（ASCII 字母数字下划线，作为 MCP server 名与目录名）
 *   version       版本号
 *   description   介绍
 *   download_url  zip 下载地址
 *   type          扩展类型：mcp / prompt / other
 *   enabled       是否默认启用（可选，默认 true）
 *
 * 数据存储：同目录 extensions_data.json（首次自动创建）
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

$dataFile = __DIR__ . '/extensions_data.json';
$response = ['code' => 0, 'message' => 'ok', 'data' => null];

/** 读取扩展数据，文件不存在返回空列表。 */
function loadData(string $file): array {
    if (!file_exists($file)) return ['extensions' => []];
    $raw = file_get_contents($file);
    $decoded = json_decode($raw, true);
    return is_array($decoded) ? $decoded : ['extensions' => []];
}

/** 原子写扩展数据（文件锁 + 临时文件替换，避免并发写坏）。 */
function saveData(string $file, array $data): void {
    $tmp = $file . '.tmp';
    file_put_contents($tmp, json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES), LOCK_EX);
    rename($tmp, $file);
}

/** 校验扩展字段，返回错误信息或 null。 */
function validateExtension(?array $ext): ?string {
    if (!$ext) return '缺少 extension 对象';
    if (empty($ext['name']) || !preg_match('/^[A-Za-z0-9_]+$/', $ext['name'])) {
        return 'name 必填且只能为字母数字下划线';
    }
    if (empty($ext['download_url']) || !filter_var($ext['download_url'], FILTER_VALIDATE_URL)) {
        return 'download_url 必填且为合法 URL';
    }
    if (empty($ext['version'])) return 'version 必填';
    if (empty($ext['type'])) $ext['type'] = 'mcp';
    if (!in_array($ext['type'], ['mcp', 'prompt', 'other'], true)) {
        return 'type 只能是 mcp / prompt / other';
    }
    return null;
}

$action = $_GET['action'] ?? '';

try {
    if ($action === 'get') {
        $data = loadData($dataFile);
        $response['data'] = $data['extensions'];
    } elseif ($action === 'edit') {
        $input = json_decode(file_get_contents('php://input'), true);
        if (!is_array($input)) throw new Exception('请求体必须是 JSON');

        $op = $input['op'] ?? '';
        $ext = $input['extension'] ?? null;

        $err = validateExtension($ext);
        if ($err) throw new Exception($err);

        $data = loadData($dataFile);
        $index = -1;
        foreach ($data['extensions'] as $i => $item) {
            if (($item['name'] ?? '') === $ext['name']) { $index = $i; break; }
        }

        switch ($op) {
            case 'add':
                if ($index >= 0) {
                    // 同名覆盖（幂等新增）
                    $data['extensions'][$index] = $ext;
                } else {
                    $data['extensions'][] = $ext;
                }
                break;
            case 'update':
                if ($index >= 0) {
                    // 合并：只覆盖传入字段，保留未传字段（如 enabled）
                    $data['extensions'][$index] = array_merge($data['extensions'][$index], $ext);
                } else {
                    $data['extensions'][] = $ext;
                }
                break;
            case 'delete':
                if ($index >= 0) array_splice($data['extensions'], $index, 1);
                break;
            default:
                throw new Exception('op 只能是 add / update / delete');
        }

        saveData($dataFile, $data);
        $response['data'] = $data['extensions'];
    } else {
        throw new Exception('未知 action，仅支持 get / edit');
    }
} catch (Exception $e) {
    $response['code'] = 1;
    $response['message'] = $e->getMessage();
}

echo json_encode($response, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
