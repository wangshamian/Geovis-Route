# 自定义路网动态加载（Custom Roads）

本实例支持在服务运行期间把 GeoJSON 路网加载进已构建的路网图，**无需重启服务**。适用于临时道路、新建道路、演习区域道路，以及对已有道路覆盖限高/限宽/限重等通行限制的场景。

::: warning 适用范围
- 仅对 `driving-*` 路网 profile（`driving-car` / `driving-hgv` / `driving-military`）生效。
- 更新会重建整体位置索引与 CH 预计算结构，耗时随图规模增长；更新期间其他图相关请求返回 `503`。
- 启用 LM / Core / Fast-Isochrone 预计算的 profile 会拒绝更新（当前默认配置未启用）。
:::

## 1. 快速开始

### 1.1 新增道路

```bash
curl -X POST "http://localhost:8082/gr/custom-roads?profiles=driving-car&mode=add&snap_radius=25" \
  -H "Content-Type: application/json" \
  --data-binary @new-road.geojson
```

### 1.2 覆盖已有道路限制属性

```bash
curl -X POST "http://localhost:8082/gr/custom-roads?profiles=driving-hgv&mode=overlay&match_tolerance=20&overlay_tags=maxheight,maxweight" \
  -H "Content-Type: application/json" \
  --data-binary @restriction.geojson
```

### 1.3 使用演示页面

访问 <http://localhost:8082/gr/gr-cesium-test.html>，切换到「路网管理」标签页：

1. 选择 GeoJSON 文件；
2. 勾选目标 profile、选择模式、设置吸附半径或匹配容差；
3. 点击「加载到路网」，结果区展示每个 profile 新增节点/边数量、位置索引与 CH 重建耗时；
4. 「刷新已保存记录」可查看持久化的动态路网记录。

## 2. 请求参数

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `profiles` | string / 可重复 | 全部已加载 profile | 目标 profile，支持逗号分隔或重复传参；`all` 表示全部 |
| `mode` | string | `add` | `add`：新增道路；`overlay`：覆盖已有道路属性 |
| `snap_radius` | double | 25 | `add` 模式下新节点吸附到已有节点的最大距离（米），`0` 表示不吸附 |
| `match_tolerance` | double | 20 | `overlay` 模式下与已有 OSM 边的最大匹配距离（米） |
| `overlay_tags` | string / 可重复 | 全部限制字段 | `overlay` 模式要覆盖的字段 |
| `persist` | boolean | `true` | 是否把本次 GeoJSON 写入存储目录，供重启/重建后重放 |

请求体为原始 GeoJSON，支持 `FeatureCollection`、`Feature`、`LineString`、`MultiLineString`。

`add` 模式下要素的 `properties` 会作为 OSM 标签写入；未提供 `highway` 时默认按 `highway=track` 处理。

`overlay` 模式支持的字段：`maxheight`、`maxwidth`、`maxweight`、`maxlength`、`maxaxleload`、`maxgradient`、`maxforddepth`、`maxbridgeload`。

## 3. 响应

```json
{
  "mode": "add",
  "profiles": ["driving-car"],
  "nodes_added": 12,
  "edges_added": 11,
  "matched_edges": 0,
  "index_rebuild_ms": 4200,
  "ch_rebuild_ms": 31500,
  "total_ms": 35800,
  "stored_record": {
    "file_name": "dynamic-1763500000000-1a2b3c4d.json",
    "path": "D:\\Code\\Geovis-Route\\custom-roads\\dynamic-1763500000000-1a2b3c4d.json"
  },
  "details": [
    {
      "profile": "driving-car",
      "nodes_added": 12,
      "edges_added": 11,
      "total_nodes": 1456789,
      "total_edges": 1876543,
      "matched_features": 0,
      "matched_edges": 0,
      "skipped": 0,
      "import_ms": 120,
      "index_rebuild_ms": 4200,
      "ch_rebuild_ms": 31500,
      "total_ms": 35800
    }
  ]
}
```

## 4. 持久化与重放

- `persist=true` 时，请求内容与参数会写入 `custom_roads.storage_dir`（默认 `./custom-roads`）。
- 图数据本身会 `flush` 到 `graphs/<profile>`，重启后直接加载，动态道路仍然存在。
- 删除 `graphs/<profile>` 重新建图时，会读取存储目录并按 profile 过滤后自动重放，日志输出“已重放动态路网 N 条”。

## 5. 查询已保存记录

```bash
curl "http://localhost:8082/gr/custom-roads"
```

返回 `records` 数组，包含文件名、目标 profile、模式、参数、保存时间与文件大小。

## 6. 并发与失败处理

- 更新持有全局写锁，其他读取路网的请求最多等待 2 秒，仍无法获取读锁则返回 `503`：
  ```json
  {"error":"路网更新中，请稍后重试"}
  ```
- 已有更新正在执行时再次调用更新接口返回 `409`。
- 参数错误、GeoJSON 无法解析返回 `400`。
- 更新失败时返回 `500`，并把图恢复为冻结状态；已成功更新的前置 profile 不会回滚。

## 7. 已知限制

- `add` 模式只把新道路吸附到最近的已有节点，不会在已有道路中段打断生成真实路口；跨路中段连接会连接到该边的端点。
- 每次更新都会重建位置索引与 CH，图越大耗时越长（当前台湾数据约数十秒/profile）。
- 更新不重建 Subnetwork、Core、LM、Fast-Isochrone 等派生结构；启用这些预计算时接口直接拒绝更新。
- 本实例的图未挂载 HeavyVehicle 扩展存储（日志中 `hgvStorage=false`），overlay 只写入 `maxheight`、`maxwidth` 等 EncodedValue 并持久化；当前路由查询不读取这些值，因此覆盖结果暂不会改变限高/限宽类规避行为。
