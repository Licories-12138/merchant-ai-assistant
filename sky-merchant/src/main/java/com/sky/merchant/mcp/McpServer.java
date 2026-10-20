package com.sky.merchant.mcp;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务端的协议层：只负责「看懂请求、分派、按规范回包」。
 *
 * 传输方式用 HTTP（Streamable HTTP 那一套的最小形态）：一个 POST 端点收 JSON-RPC 请求。
 * MCP 还有一种 stdio 传输（客户端把服务端当子进程启动，走标准输入输出）——
 * 那种适合本地工具，这里选 HTTP 是因为它落在现有 Web 服务里，能直接用 Apifox / curl 验，
 * 不需要为了看一眼结果去装一个 MCP 客户端。
 *
 * 只实现三个方法：
 *   initialize   握手，交换协议版本与能力
 *   tools/list   报出所有工具
 *   tools/call   执行某个工具
 * 另外顺带接受 notifications/initialized 这类通知（MCP 规范要求通知不返回响应体）。
 */
@SuppressWarnings("JavadocBlankLines")
@Service
public class McpServer
{
    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    /** 协议版本。写死一个日期版本号即可 —— 客户端按这个号判断能不能聊 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private static final String SERVER_NAME = "merchant-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";

    // JSON-RPC 2.0 的标准错误码，别自己发明数字
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final Map<String, McpTool> tools;

    public McpServer(List<McpTool> toolBeans)
    {
        // 用 LinkedHashMap 保持 bean 的注入顺序，tools/list 的输出才稳定（便于人眼对比和写断言）
        Map<String, McpTool> map = new LinkedHashMap<>();
        for (McpTool tool : toolBeans)
        {
            if (map.containsKey(tool.name()))
            {
                // 不直接启动失败：一个 MCP 服务挂掉整站起不来，代价太大。
                // 但一定要留日志 —— 重名意味着有一个工具永远调不到，这种问题只能靠日志发现。
                log.error("MCP 工具重名，后者覆盖前者：{}", tool.name());
            }
            map.put(tool.name(), tool);
        }
        this.tools = map;
        log.info("MCP 服务端就绪，已注册 {} 个工具：{}", map.size(), map.keySet());
    }

    /**
     * 处理一条 JSON-RPC 请求。
     *
     * @param request 已反序列化的请求体
     * @return 响应体；如果这是一个通知（notifications/xxx），返回 null，调用方应回 202 且不带 body
     */
    public Map<String, Object> handle(Map<String, Object> request)
    {
        if (request == null)
        {
            return error(null, PARSE_ERROR, "请求体为空或不是合法 JSON");
        }

        Object id = request.get("id");
        Object methodObj = request.get("method");
        if (!(methodObj instanceof String method) || method.isBlank())
        {
            return error(id, INVALID_REQUEST, "缺少 method 字段");
        }

        // 通知类消息（没有 id）不需要回包。MCP 规范里客户端握手完会发一条
        // notifications/initialized，回过包反而会被当成协议错误。
        if (method.startsWith("notifications/"))
        {
            log.debug("收到 MCP 通知：{}", method);
            return null;
        }

        try
        {
            return switch (method)
            {
                case "initialize" -> ok(id, initialize());
                case "tools/list" -> ok(id, listTools());
                case "tools/call" -> ok(id, callTool(params(request)));
                default -> error(id, METHOD_NOT_FOUND, "不支持的方法：" + method);
            };
        }
        catch (IllegalArgumentException e)
        {
            // 参数错属于协议层错误，用 JSON-RPC error 回
            return error(id, INVALID_PARAMS, e.getMessage());
        }
        catch (Exception e)
        {
            log.error("MCP 处理失败，method = {}", method, e);
            return error(id, INTERNAL_ERROR, "服务端异常：" + e.getMessage());
        }
    }

    /**
     * 握手。客户端拿这个确认「对面是什么服务、说哪种版本的协议、有哪些能力」。
     * capabilities 里声明了 tools，客户端才会去调 tools/list。
     */
    private Map<String, Object> initialize()
    {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", SERVER_NAME, "version", SERVER_VERSION)
        );
    }

    /** 报出所有工具。inputSchema 原样给客户端，它拿去约束模型的输出 */
    private Map<String, Object> listTools()
    {
        List<Map<String, Object>> list = new ArrayList<>(tools.size());
        for (McpTool tool : tools.values())
        {
            list.add(Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "inputSchema", tool.inputSchema()
            ));
        }
        return Map.of("tools", list);
    }

    /**
     * 执行工具。
     *
     * 注意这里对错误的处理分两层，这是 MCP 规范和「随手写个 HTTP 接口」最大的差别：
     *   协议层错误（没这个工具、参数格式不对）→ 抛出去，由 handle 包成 JSON-RPC error
     *   业务层失败（工具跑了但失败了）      → 正常返回，内容是 isError=true
     *
     * 为什么这么分：模型能读懂 isError 的内容并据此换策略；
     * 而 JSON-RPC error 在客户端那边往往被当成"连接故障"，模型根本看不到。
     */
    private Map<String, Object> callTool(Map<String, Object> params) throws Exception
    {
        if (params == null)
        {
            throw new IllegalArgumentException("缺少 params");
        }
        Object nameObj = params.get("name");
        if (!(nameObj instanceof String name) || name.isBlank())
        {
            throw new IllegalArgumentException("缺少 params.name（工具名）");
        }

        McpTool tool = tools.get(name);
        if (tool == null)
        {
            // 工具不存在属于协议层问题：客户端调错了名字，不是工具执行失败
            throw new IllegalArgumentException("未知工具：" + name + "，可用工具：" + tools.keySet());
        }

        Map<String, Object> arguments = asMap(params.get("arguments"));

        Object result;
        try
        {
            result = tool.invoke(arguments);
        }
        catch (Exception e)
        {
            // 业务失败 → isError，不走 JSON-RPC error。见方法注释
            log.warn("MCP 工具执行失败：{}", name, e);
            return content("工具执行失败：" + e.getMessage(), true);
        }

        // 用 fastjson2 而不是 Jackson：这个项目里没有 ObjectMapper bean（全局用的是 fastjson2），
        // 注入 Jackson 会直接启动失败 —— UnsatisfiedDependencyException，报"没有 ObjectMapper"
        String text = (result instanceof String s) ? s : JSON.toJSONString(result);
        return content(text, false);
    }

    /** 按 MCP 规范组装 tools/call 的结果：content 是内容块数组，isError 标业务成败 */
    private Map<String, Object> content(String text, boolean isError)
    {
        Map<String, Object> block = Map.of("type", "text", "text", text);
        return Map.of("content", List.of(block), "isError", isError);
    }

    /** 取 params，缺失时返回 null（由调用方判断是不是必需） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> params(Map<String, Object> request)
    {
        Object p = request.get("params");
        return (p instanceof Map) ? (Map<String, Object>) p : null;
    }

    /** arguments 缺失时给空 Map，让工具实现不用写 null 判断 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o)
    {
        return (o instanceof Map) ? (Map<String, Object>) o : Map.of();
    }

    private Map<String, Object> ok(Object id, Object result)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private Map<String, Object> error(Object id, int code, String message)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }
}
