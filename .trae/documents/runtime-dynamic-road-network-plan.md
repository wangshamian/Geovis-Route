# 运行时动态加载自定义路网（不重启）实现计划

## Context

当前自定义路网（GeoJSON）只在建图阶段由 [GRGraphHopper.java#L158-L172](file:///d:/Code/Geovis-Route/gr-engine/src/main/java/org/heigit/geovisroute/routing/graphhopper/extensions/GRGraphHopper.java#L158-L172) 调用 [CustomRoadNetworkImporter.java](file:///d:/Code/Geovis-Route/gr-engine/src/main/java/org/heigit/geovisroute/routing/graphhopper/extensions/CustomRoadNetworkImporter.java) 导入，修改 GeoJSON 后必须删除 `graphs/<profile>` 并重启才能生效。

目标：服务运行期间通过接口上传 GeoJSON，立即作用于已加载的路网，无需重启；支持 `add`（新增道路）与 `overlay`（覆盖已有道路限高/限宽/限重等属性）两种模式。

已确认的运行时事实：

* `ors.engine.graphs_data_access: RAM_STORE`，图数据可写且 `flush()` 会落盘；启动时为加载已有图（约 1-2 秒/profile），不会重新导入 OSM。
* 每个 profile 有独立 `BaseGraph`，统一由 `RoutingProfileManager` 持有，`RoutingProfile#getGraphhopper()` 可取到 `GRGraphHopper`。
* 三个 profile 均启用了 CH（`graphs/*/nodes_ch_*`、`shortcuts_*`），未启用 LM / Core / Fast-Isochrone（无对应目录与 properties 键）。
* `GraphHopper.route()` 每次请求都会重新 `createRouter()`，`Router` 读取当前 `locationIndex` 与 `chGraphs` 字段，因此替换这两个字段后，后续请求立即使用新图。
* 图加载完成后 `BaseGraph` 处于 frozen 状态（CH 准备时冻结），追加边前必须先解冻。
* `LocationIndexTree` 索引校验和 = `nodes ^ edges`，图变更后必须整体重建并 flush，否则下次启动加载索引会校验失败。
* `RoutingCHGraphImpl.close()` 会连带关闭 `BaseGraph`，重建 CH 时不能 close 旧对象，只能替换引用并清理底层 DataAccess。

## 方案总览

```
POST /gr/custom-roads（原始 GeoJSON + 查询参数）
  → GraphUpdateLock 写锁
  → 逐 profile：解冻图 → 复用 importer 追加/覆盖 → 重建位置索引（仅拓扑变化时）→ 重建 CH → flush
  → 持久化 GeoJSON 记录（供重启/重建后重放）
  → 返回每 profile 统计
期间其他图相关请求：读锁获取失败，快速返回 503
```

## 具体改动

### 1. 引擎：允许运行时改图并重建索引 / CH

**`gr-engine/.../com/graphhopper/storage/BaseGraph.java`**

* 新增 `public synchronized void unfreeze()`：`store.setFrozen(false)`（`store` 为包内字段，同文件可访问）。

**`gr-engine/.../com/graphhopper/GraphHopper.java`**

* 新增 `public synchronized void rebuildLocationIndex()`：
  * 关闭当前 `locationIndex`；
  * 通过 `baseGraph.getDirectory().remove("location_index")` 清理旧 DataAccess（`GHDirectory.remove` 在不存在时会抛异常，用私有 helper 捕获忽略）；
  * 新建 `LocationIndexTree(baseGraph, baseGraph.getDirectory())`，沿用 `preciseIndexResolution` / `maxRegionSearch`，调用 `prepareIndex()`（内部会 flush），赋给 `locationIndex`。
* 新增 `public synchronized void rebuildCH()`：
  * 若 `corePreparationHandler.isEnabled()`、`lmPreparationHandler.isEnabled()`、`fastIsochroneFactory.isEnabled()` 任一为真，抛出 `UnsupportedOperationException`（当前部署不满足，避免静默使用过期预计算数据）；
  * 复用私有 `createCHConfigs(chPreparationHandler.getCHProfiles())` 生成配置；
  * 对每个配置先 `directory.remove("nodes_ch_"+name)`、`directory.remove("shortcuts_"+name)`（不存在则忽略）；
  * 调用现有 `prepareCH(false, configs)`（内部会 `freeze()`），按 `loadOrPrepareCH` 的方式重建 `chGraphs` 映射并写 profile 版本；
  * 不关闭旧 `RoutingCHGraphImpl`（其 `close()` 会关闭 BaseGraph）。

### 2. 引擎：导入器支持内存 GeoJSON、统计与存储重放

**`CustomRoadNetworkImporter.java`**

* 抽出 `public ImportSummary importGeoJson(String geoJson, CustomRoadNetworkProperties config)`；现有 `importRoads(config)` 读取文件后委托给它，保持启动行为不变。
* add / overlay 返回统计（新增节点数、边数、匹配要素数、跳过数），日志保留。
* 复用现有 `resolveNode` / `createEdge` / `applyToEdge`，无需重写路网写入逻辑。

**`CustomRoadNetworkProperties.java`**

* 新增 `storage_dir`（默认 `./custom-roads`），存放动态加载记录。

**新增 `CustomRoadStore`（`org.heigit.geovisroute.routing.graphhopper.extensions`）**

* `save(List<String> profiles, String mode, Map params, String geojson)`：写入 `dynamic-<timestamp>.json`，内容为 `{profiles, mode, snap_radius, match_tolerance, overlay_tags, geojson}`。
* `list(Path dir)`：读取全部记录（供 GET 接口与建图重放）。
* 建图重放时按当前 profile 名过滤，避免把只针对某个 profile 的路网写入其他 profile。

**`GRGraphHopper.importOSM()`**

* 先应用 `custom_roads.file`（现有逻辑），再读取 `storage_dir` 下全部记录，按当前 profile 过滤后逐个应用，日志输出“已重放动态路网 N 条”。

### 3. 并发控制：全局读写锁

**新增 `gr-engine/.../org/heigit/geovisroute/routing/GraphUpdateLock.java`**

* 静态 `ReentrantReadWriteLock(true)`，提供 `tryReadLock(timeout)`、`tryWriteLock()`、`unlockRead/unlockWrite`。

**新增 `gr-api/.../controllers` 同级 `servlet/filter/GraphUpdateLockFilter.java`**

* `@Component` + `@Order(Ordered.HIGHEST_PRECEDENCE)` + `OncePerRequestFilter`。
* 仅对图相关路径生效：`/directions`、`/isochrones`、`/snap`、`/matrix`、`/export`、`/offroad`、`/hybrid`、`/status`；`POST /custom-roads` 跳过（由接口自身持写锁）。
* `tryReadLock(2s)` 失败返回 503 `{"error":"路网更新中，请稍后重试"}`；成功则 `finally` 释放。
* 路径判断使用 `request.getServletPath()`（已去掉 `/gr` 上下文前缀）。

### 4. 更新编排

**新增 `gr-engine/.../extensions/RuntimeRoadNetworkUpdater.java`**

* 入参：`GRGraphHopper`、GeoJSON 文本、模式与参数。
* 流程：
  1. 记录变更前 nodes / edges；
  2. `unfreeze()`；
  3. `CustomRoadNetworkImporter.importGeoJson(...)`；
  4. 仅当 nodes 或 edges 增加时调用 `gh.rebuildLocationIndex()`（overlay 只改属性，无需重建索引）；
  5. `gh.rebuildCH()`；
  6. `baseGraph.flush()` + `properties.flush()`；
  7. `finally` 中确保图重新冻结，避免失败后留下可写状态；
  8. 返回统计与各阶段耗时。
* 注意：`overlay` 修改已有边属性同样会使 CH 权重过期，CH 必须重建。

### 5. API 接口

**新增 `gr-api/.../controllers/CustomRoadsAPI.java`**

* `@RestController`、`@Tag`，`@RequestMapping` 下：
  * `POST /custom-roads`
    * Query：`profiles`（可重复或逗号分隔，空/`all` = 全部已加载 profile）、`mode`（add|overlay，默认 add）、`snap_radius`（默认 25）、`match_tolerance`（默认 20）、`overlay_tags`（默认全部限制项）、`persist`（默认 true）。
    * Body：原始 GeoJSON（`@RequestBody String`）。
    * 获取写锁失败 → 409（已有更新在执行）；校验 profile 存在、mode 合法、容差 > 0；解析失败 → 400。
    * 逐 profile 调用 `RuntimeRoadNetworkUpdater`；`persist=true` 时调用 `CustomRoadStore.save`。
    * 响应示例：
      ```json
      {
        "mode": "add",
        "profiles": ["driving-car"],
        "nodes_added": 12,
        "edges_added": 11,
        "index_rebuild_ms": 4200,
        "ch_rebuild_ms": 31500,
        "details": [{"profile": "driving-car", "nodes_added": 12, "edges_added": 11}]
      }
      ```
  * `GET /custom-roads`：返回已保存记录列表（文件名、profiles、mode、时间、大小），供前端展示。
* 异常处理沿用 Spring 默认：`IllegalArgumentException` → 400，`UnsupportedOperationException` / 锁冲突 → 409，其他 → 500（日志打印完整堆栈）。

### 6. 配置

* `gr-config.yml` 的 `custom_roads` 增加 `storage_dir: ./custom-roads`，并更新注释：运行期接口加载的路网会写入该目录，删除 graphs 重建时自动重放。
* 无需改动 `thresholds` 或 endpoint 开关；接口默认可用。

### 7. 前端入口

**`gr-api/src/main/resources/static/gr-cesium-test.html`**

* 新增标签页“路网管理”（与“路线规划 / 等时线 / 越野路径 / 公路+越野”并列）。
* 面板内容：
  * 文件选择（`.geojson/.json`），读取文本后可预览文件名与大小；
  * profile 复选（默认全选 driving-car / driving-hgv / driving-military，可请求 `/status` 动态获取）；
  * 模式下拉（add / overlay）；
  * `snap_radius`、`match_tolerance`、`overlay_tags`（overlay 时显示）；
  * `persist` 复选框；
  * “加载到路网”按钮 + 结果区（每 profile 新增节点/边、索引与 CH 耗时、错误信息）；
  * “刷新已保存记录”按钮，调用 GET 展示列表。
* 请求：`fetch(`\${API\_BASE}/custom-roads?...`, { method:'POST', headers:{'Content-Type':'application/json'}, body: fileText })`；503 时提示“路网更新中，请稍后重试”。

### 8. 文档与测试

* 新增 `docs/api-reference/endpoints/custom-roads/index.zh.md`（如结构需要同步 `index.md`）：参数、示例、响应字段、限制说明。
* 单元测试（`gr-engine/src/test`）：
  * `CustomRoadNetworkImporter`：FeatureCollection / LineString / MultiLineString 解析、mode 非法报错、add 模式节点边计数；
  * `CustomRoadStore`：save/load、profile 过滤、损坏文件跳过。
* 手动集成验证见下。

## 验证步骤

1. 打包并启动服务，确认 `GET /gr/status` 为 ready。
2. **add 生效**：POST 一段与现有路网相连的新 GeoJSON 到 `profiles=driving-car`，记录响应统计；随后请求跨越该新路两端点的 directions，确认路线使用新路（对比加载前后距离/耗时）。
3. **重启保留**：重启服务，重复步骤 2 的路线，确认新路仍存在（图文件已 flush）。
4. **重建重放**：停止服务、删除 `graphs/driving-car`，重启后查看日志出现“已重放动态路网”，再次验证新路存在。
5. **overlay 生效**：对一条已有道路 overlay `maxheight=2.0`，用 driving-hgv 请求，确认该路段被规避。
6. **并发 503**：在一次更新执行窗口内请求 directions，确认返回 503；更新完成后恢复正常。
7. **基线回归**：三个 profile 的普通路线、等时线、越野/混合路由均正常。

## 风险与限制

* CH 重建是主要耗时：当前图约 187 万边，单 profile 预计数十秒，多 profile 叠加；更新期间图相关请求统一 503。
* `add` 模式沿用现有能力：只吸附到最近已有节点，不会在已有道路中段打断生成真实路口。
* 更新为全量写锁，不做双图并行；优点是实现简单、状态一致。
* 启用 LM / Core / Fast-Isochrone 时接口直接拒绝更新，需改为重建对应预计算结构后才能放开。
