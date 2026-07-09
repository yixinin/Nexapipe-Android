# Debug Session: proxy-restart-failure

## Status
[OPEN]

## Symptom
关闭 VPN 后再连接报错：
```
Connection failed
java.lang.Exception: Failed to start proxy
    at com.nexa.pipe.ui.VpnViewModel$connect$1.invokeSuspend(VpnViewModel.kt:136)
```

## Hypotheses
1. **Proxy 实例未保存**：`nativeStartProxy()` 创建的 `LocalProxy` 未存入 `ProxyState`，`nativeStopProxy()` 无法调用 `proxy.stop()`，导致监听端口未被释放。
2. **端口占用**：第一次启动的 `TcpListener` 未被关闭，再次 `bind()` 同一端口失败。
3. **EndpointGroup 未关闭**：虽然 `ProxyState.endpoint_group` 始终为 None，但 `LocalProxy` 内部持有 `Arc<EndpointGroup>`，若未主动关闭可能持有连接资源。
4. **状态竞态**：`VpnViewModel.disconnect()` 与 `connect()` 可能在不同协程中并发执行 stop/start，导致 start 在 stop 完成前执行。
5. **Runtime/Endpoint 重复初始化**：`OnceCell` 已设置无法重新创建，但复用时内部状态异常。

## Instrumentation Plan
- 在 `nativeStartProxy()` 入口/出口打印端口、节点数、已有 proxy 指针。
- 在 `nativeStopProxy()` 打印 state 中 conn_pool/endpoint_group/proxy 是否存在。
- 在 `LocalProxy::new()` 打印 bind 成功/失败及地址。
- 在 `VpnViewModel.connect()` 和 `disconnect()` 打印调用序及状态。

## Evidence
(TBD)

## Fix
(TBD)

## Verification
(TBD)
