package com.sky.merchant.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 协议层的测试：不起 Spring、不连库、不调模型，只验「请求进来怎么分派、怎么回包」。
 *
 * 为什么这一层值得单独测：
 * 它全是「格式约定」——方法名、错误码、字段形状。写错了不会崩，只会让客户端认不出来，
 * 表现出来是「连上了但工具列表是空的」「调工具一直超时」，排查方向很容易跑偏。
 * 而格式对不对，用假工具就能精确断言，比接真客户端试快一百倍。
 *
 * 用两个假工具（echo / boom）代替真业务工具：测协议时不该被业务数据干扰。
 */
@SuppressWarnings("JavadocBlankLines")
@DisplayName("MCP 协议层")
class McpServerTest
{
    /** 正常工具：把参数原样回显，方便断言"参数有没有原班传到工具里" */
    private static final McpTool ECHO = new McpTool()
    {
        @Override
        public String name()
        {
            return "echo";
        }

        @Override
        public String description()
        {
            return "回显工具，仅用于测试";
        }

        @Override
        public Map<String, Object> inputSchema()
        {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of("text", Map.of("type", "string")));
            schema.put("required", List.of());
            return schema;
        }

        @Override
        public Object invoke(Map<String, Object> arguments)
        {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("echo", arguments.get("text"));
            return r;
        }
    };

    /** 会抛异常的工具：验证"业务失败走 isError，不走 JSON-RPC error" */
    private static final McpTool BOOM = new McpTool()
    {
        @Override
        public String name()
        {
            return "boom";
        }

        @Override
        public String description()
        {
            return "必定失败的工具，仅用于测试";
        }

        @Override
        public Map<String, Object> inputSchema()
        {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }

        @Override
        public Object invoke(Map<String, Object> arguments)
        {
            throw new IllegalStateException("数据库连接失败");
        }
    };

    private McpServer server;

    @BeforeEach
    void setUp()
    {
        server = new McpServer(List.of(ECHO, BOOM));
    }

    @Test
    @DisplayName("initialize：回协议版本、能力声明、服务信息")
    void initializeShouldDeclareCapabilities()
    {
        Map<String, Object> resp = server.handle(request(1, "initialize", null));

        assertEquals("2.0", resp.get("jsonrpc"));
        assertEquals(1, resp.get("id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");

        assertNotNull(result.get("protocolVersion"), "必须给出协议版本，客户端靠它判断能不能聊");
        // capabilities 里声明了 tools，客户端才会去调 tools/list
        assertTrue(((Map<?, ?>) result.get("capabilities")).containsKey("tools"),
                "必须声明 tools 能力，否则客户端不会来问工具列表");

        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) result.get("serverInfo");
        assertNotNull(info.get("name"));
        assertNotNull(info.get("version"));
    }

    @Test
    @DisplayName("tools/list：报出全部工具，每个都带 name / description / inputSchema")
    void listToolsShouldExposeSchema()
    {
        Map<String, Object> resp = server.handle(request(2, "tools/list", null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) ((Map<String, Object>) resp.get("result")).get("tools");

        assertEquals(2, tools.size());
        for (Map<String, Object> tool : tools)
        {
            // 三个字段缺一不可：少了 name 调不到，少了 description 模型不会选它，
            // 少了 inputSchema 客户端没法约束参数
            assertNotNull(tool.get("name"));
            assertNotNull(tool.get("description"));
            assertNotNull(tool.get("inputSchema"));
        }
        assertEquals("echo", tools.get(0).get("name"), "顺序应保持注册顺序，便于人眼核对");
    }

    @Test
    @DisplayName("tools/call：参数原样传到工具，结果序列化成 content[0].text")
    void callToolShouldPassArgumentsThrough() throws Exception
    {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "echo");
        params.put("arguments", Map.of("text", "你好"));

        Map<String, Object> resp = server.handle(request(3, "tools/call", params));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertEquals(Boolean.FALSE, result.get("isError"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).get("type"));

        String text = (String) content.get(0).get("text");
        // 结构化对象被序列化进 text —— 这是 MCP 的约定：结果统一装在文本块里
        assertTrue(text.contains("你好"), "参数应原样传到工具并出现在结果里，实际：" + text);
    }

    @Test
    @DisplayName("tools/call 未知工具：走 JSON-RPC error(-32602)，不是 isError")
    void unknownToolShouldBeProtocolError()
    {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "不存在的工具");

        Map<String, Object> resp = server.handle(request(4, "tools/call", params));

        assertNull(resp.get("result"), "未知工具属于协议层错误，不该有 result");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertEquals(-32602, error.get("code"));
        assertTrue(((String) error.get("message")).contains("不存在的工具"));
    }

    @Test
    @DisplayName("工具执行失败：走 isError=true，不是 JSON-RPC error")
    void toolFailureShouldBeBusinessError()
    {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "boom");

        Map<String, Object> resp = server.handle(request(5, "tools/call", params));

        // 这条是整个协议层最容易写错的地方：
        // 工具跑失败了，对客户端来说是一次"成功的调用"，只是结果是失败。
        // 如果这里回 JSON-RPC error，多数客户端会把它当成连接故障直接抛异常，
        // 模型根本看不到"数据库连接失败"这句话，也就没法换策略。
        assertNull(resp.get("error"), "业务失败不该走 JSON-RPC error");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertEquals(Boolean.TRUE, result.get("isError"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertTrue(((String) content.get(0).get("text")).contains("数据库连接失败"),
                "失败原因要原样带给客户端，不能吞掉");
    }

    @Test
    @DisplayName("通知消息：返回 null，调用方回 202 不带 body")
    void notificationShouldNotReturnBody()
    {
        // JSON-RPC 通知的特征是没有 id、也不需要响应。
        // 握手完客户端会发 notifications/initialized，回过包反而会被判定协议错误
        Map<String, Object> resp = server.handle(request(null, "notifications/initialized", null));
        assertNull(resp);
    }

    @Test
    @DisplayName("缺少 method：走 JSON-RPC error(-32600)")
    void missingMethodShouldBeInvalidRequest()
    {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", 6);

        Map<String, Object> resp = server.handle(req);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertEquals(-32600, error.get("code"));
    }

    @Test
    @DisplayName("不支持的方法：走 JSON-RPC error(-32601)")
    void unknownMethodShouldBeMethodNotFound()
    {
        Map<String, Object> resp = server.handle(request(7, "resources/list", null));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertEquals(-32601, error.get("code"));
        assertFalse(((String) error.get("message")).isBlank());
    }

    /** 拼一条 JSON-RPC 请求 */
    private Map<String, Object> request(Object id, String method, Map<String, Object> params)
    {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        if (id != null)
        {
            req.put("id", id);
        }
        req.put("method", method);
        if (params != null)
        {
            req.put("params", params);
        }
        return req;
    }
}
