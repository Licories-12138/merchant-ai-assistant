package com.sky.merchant.mcp;

import java.util.Map;

/**
 * 一个可以被 MCP 客户端发现并调用的工具。
 *
 * 和 @Tool 的区别（这是 MCP 存在的理由）：
 *   @Tool 标注的方法住在 Agent 同一个进程里，由 Spring AI 反射调用，换个框架就作废。
 *   McpTool 实现住在「服务端」，通过 JSON-RPC 暴露 —— 谁支持 MCP 谁就能用，
 *   客户端是 Spring AI、Claude Desktop 还是 Cursor 都无所谓。
 *
 * 返回值约定：**返回结构化数据，不要返回已经组织好的自然语言**。
 * 因为调用方可能要把这份数据塞进自己的提示词，也可能要渲染成表格；
 * 替它把话说死（比如返回"宫保鸡丁 38 元，在售"）就锁死了它的用途。
 *
 * 新增一个工具 = 新增一个 @Component implements McpTool。
 * McpServer 通过构造器注入 List<McpTool> 自动收齐，注册代码不用动。
 */
@SuppressWarnings({"JavadocBlankLines", "JavadocDeclaration"})
public interface McpTool
{
    /**
     * 工具名。客户端靠它调 tools/call，**必须全局唯一**，建议用小写下划线。
     * 重名会在 McpServer 构造时暴露（这里选择让后者覆盖前者并留日志，而不是直接启动失败）。
     */
    String name();

    /**
     * 给客户端（进而给模型）看的一句话说明。
     *
     * 这段文字决定模型会不会在正确的时机选中这个工具，所以它比代码注释重要得多：
     * 要写清「做什么」和「什么时候该用」，最好带一两个例句。
     */
    String description();

    /**
     * 参数的 JSON Schema。客户端拿它去约束模型的输出，服务端也可以拿它做入参校验。
     *
     * 最小合法形态：{"type":"object","properties":{...},"required":[...]}
     */
    Map<String, Object> inputSchema();

    /**
     * 执行。参数已经从 JSON 反序列化成 Map，缺省参数由实现自己兜底。
     *
     * 可以抛异常：抛出来会被 McpServer 包成 isError=true 的正常响应（不是 JSON-RPC 错误），
     * 因为"工具跑失败了"属于业务结果，不是协议错误。
     *
     * @param arguments 参数表；没有参数时是空 Map，不会是 null
     * @return 任意可被 Jackson 序列化的对象；返回 String 时原样当文本用
     */
    Object invoke(Map<String, Object> arguments) throws Exception;
}
