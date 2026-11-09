# 客户端本地缓存（local-first 读取）

本地文件：`docs/local-cache.md`
实现日期：2026-11-09
范围：`shared`（Android + wasmJs）。`contract` 与 `go-server` 不变。

## 1. 结论

客户端采用“在线写、缓存读”：

- 写：创建、更新、删除、命令与学习确认直接请求服务端；服务端确认后才更新本地缓存。
- 读：`Esp32Repository` 暴露缓存 Flow，`AppViewModel` 以 `StateFlow` 转发，界面直接订阅；列表数据不复制进 `AppUiState`，也不从网络响应直接渲染。
- 刷新：每个 tab 支持下拉刷新；一次刷新并行请求 `GET /devices`、`GET /groups`、`GET /schedules`，全部成功后在一个事务中整体替换缓存。
- 边界：`AppViewModel` 只保存界面状态并调用 `Esp32Repository`；HTTP 客户端、连接切换、写路径与 `LocalStore` 都封装在 Repository 内。
- 无 outbox、无变更日志、无推送、无客户端冲突合并。

## 2. 数据流

```text
UI -> AppViewModel.refresh() -> Esp32Repository.refresh()
                                     |
                                     v
                GET /devices, /groups, /schedules (parallel)
                                     |
                                     v
                   LocalStore.replaceAll() (single transaction)
                                     |
                                     v
       Room Flow -> Esp32Repository -> AppViewModel StateFlow -> Compose

UI -> AppViewModel.saveX() -> Esp32Repository.saveX()
                                     |
                                     v
                     POST/PATCH/DELETE /api/v1/...
                                     |
                                     v
                     server response -> LocalStore.upsertX()
```

## 3. 存储

| 平台 | 实现 | 持久性 |
| --- | --- | --- |
| Android | Room 3.0.3 + BundledSQLiteDriver 2.7.1 | 持久化 `esp32-control-cache.db` |
| wasmJs | `InMemoryLocalStore` | 页面刷新后丢失；后续再接 Room/OPFS |

`LocalStore` 只由 `Esp32Repository` 访问；`AppViewModel` 不直接调用 `LocalStore` 或 HTTP 客户端。`contract` 不新增 DTO，`go-server` 不感知缓存。

## 4. 刷新语义

- **原子快照**：三个列表请求全部成功才替换；任一失败保留旧缓存。
- **健康独立**：`GET /health` 与列表并行，失败只影响网关状态，不阻塞缓存更新。
- **单飞**：并发刷新复用进行中的任务；写后刷新允许取消旧任务并立即重启。
- **写优先**：刷新开始后若有写成功，旧快照在提交前被丢弃（`writeEpoch` 校验），避免旧数据覆盖新写入。
- **服务端隔离**：缓存记录来源 `serverUrl`；地址变化时清空缓存。

## 5. 失败行为

| 场景 | 行为 |
| --- | --- |
| 有缓存、刷新失败 | 继续显示缓存；Snackbar 报错 |
| 无缓存、刷新失败 | 列表区域显示错误与“重试” |
| 写失败 | 不修改缓存；表单或详情显示错误 |
| 写返回 404/410 | 本地删除该项并强制刷新 |

## 6. 平台说明

- Android：KSP 配置为 `kspAndroid`；Room schema 不导出，缓存可销毁重建（`fallbackToDestructiveMigration`）。
- wasmJs：Room 目前没有可用的 wasmJs 目标；使用内存实现，行为一致但不跨刷新持久化。

## 7. 依赖验收

| 依赖 | 维护/平台 | 许可证 | 运行时代价 | 结论 |
| --- | --- | --- | --- | --- |
| Room 3.0.3 | AndroidX；Android/KMP | Apache-2.0 | KSP 生成代码 + 运行时 | 采用；用户指定 Room 3 |
| sqlite-bundled 2.7.1 | AndroidX | Apache-2.0 | APK 增加原生 SQLite | 采用；不依赖宿主 SQLite |
| KSP 2.3.13 | Google；构建期 | Apache-2.0 | 仅构建期 | 采用；Room 代码生成 |

不自研 SQLite 封装：Room 已覆盖 schema、DAO、事务与 Flow，手写会形成第二套数据访问层。

## 8. 验证

```bash
./gradlew :contract:testAndroidHostTest :shared:compileKotlinWasmJs \
  :androidApp:assembleDebug :webApp:wasmJsBrowserDistribution

(cd go-server && CGO_ENABLED=1 go test ./...)
(cd mock && go test ./...)
```
