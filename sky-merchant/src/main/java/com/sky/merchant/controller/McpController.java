package com.sky.merchant.controller;

import com.sky.merchant.mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * MCP 服务端的 HTTP 入口。
 *
 * 为什么用 POST 单端点而不是 RESTful 的一堆路径：
 * MCP 的传输层规定「一个端点收 JSON-RPC 消息」，方法名放在 body 的 method 字段里。
 * 拆成 /tools、/tools/{name} 这种 REST 风格看着更整齐，但那样客户端就调不通了 ——
 * 协议不是这么定的，接口形状要服从协议。
 *
 * 鉴权：**故意不加 @Anonymous**，跟其他业务接口一样要登录态。
 * 工具执行会读真实业务数据，对外裸奔等于把库开了个洞。
 * 真要对外开放，正确做法是给 MCP 单独发 token 并做限流，而不是复用后台登录。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/merchant/mcp")
public class McpController
{
    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpServer mcpServer;

    public McpController(McpServer mcpServer)
    {
        this.mcpServer = mcpServer;
    }

    /**
     * 收一条 JSON-RPC 请求。
     *
     * 两种返回：
     *   普通请求 → 200 + JSON-RPC 响应体
     *   通知消息（notifications/xxx）→ 202 + 空 body
     *
     * 202 这个细节别省：MCP 客户端发完 notifications/initialized 之后会等响应，
     * 如果这里回 200 带一个空 JSON，有些客户端会解析失败并判定握手失败。
     *
     * @param request JSON-RPC 请求体
     * @return JSON-RPC 响应，或 202 空响应
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> handle(@RequestBody Map<String, Object> request)
    {
        log.debug("收到 MCP 请求：{}", request);

        Map<String, Object> response = mcpServer.handle(request);
        if (response == null)
        {
            // 通知类消息不返回 body
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(response);
    }
}
