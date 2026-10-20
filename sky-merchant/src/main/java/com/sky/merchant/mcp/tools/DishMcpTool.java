package com.sky.merchant.mcp.tools;

import com.sky.merchant.domain.Dish;
import com.sky.merchant.mcp.McpTool;
import com.sky.merchant.service.IDishService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具：按菜名查菜品。
 *
 * 这是「同一份业务能力，两种暴露方式」的对照实验 ——
 * DishTools 里已经有一个 @Tool 版本的 searchDishByName，两者调的是同一个 IDishService，
 * 差别只在**返回什么**：
 *
 *   @Tool 版  → 返回 "宫保鸡丁 38 元，在售；鱼香肉丝 32 元，在售"（已经组织好的中文句子）
 *   MCP 版    → 返回 {"total":2,"dishes":[{"name":"宫保鸡丁","price":38,"onSale":true}]}
 *
 * 为什么跨进程时必须给结构化的：
 * 调用方可能要把数据渲染成表格、塞进自己的提示词、或者只取其中一列算平均值。
 * 返回中文句子等于替它把话说死了，而这些用途全被堵住。
 *
 * 顺带一个好处：结构化数据能直接断言。@Tool 版返回自然语言，测试只能做 contains 匹配。
 */
@SuppressWarnings("JavadocBlankLines")
@Component
public class DishMcpTool implements McpTool
{
    private static final Logger log = LoggerFactory.getLogger(DishMcpTool.class);

    /** 单次最多返回多少条。上限写死，防止客户端传个 limit=100000 */
    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 20;

    private final IDishService dishService;

    public DishMcpTool(IDishService dishService)
    {
        this.dishService = dishService;
    }

    @Override
    public String name()
    {
        return "query_dish";
    }

    @Override
    public String description()
    {
        // 这段话是给模型看的：既要写清楚"做什么"，也要写清楚"什么时候该用"。
        // 描述写得含糊，模型就不会在正确的时机选中这个工具。
        return "按菜名关键词查询菜品，返回结构化的菜品列表（名称、价格、是否在售）。"
                + "不传关键词时返回当前全部在售菜品。"
                + "例如用户问「宫保鸡丁多少钱」「现在有什么菜在卖」时调用。";
    }

    @Override
    public Map<String, Object> inputSchema()
    {
        // JSON Schema。客户端拿它约束模型的输出，也可以拿它做入参校验。
        // 注意 required 是空数组 —— 两个参数都可选，不传关键词就退化成"列在售菜品"
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("keyword", Map.of(
                "type", "string",
                "description", "菜名关键词，模糊匹配。不传则返回全部在售菜品"));
        props.put("limit", Map.of(
                "type", "integer",
                "description", "最多返回几条，默认 " + DEFAULT_LIMIT + "，上限 " + MAX_LIMIT));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    public Object invoke(Map<String, Object> arguments)
    {
        String keyword = str(arguments.get("keyword"));
        int limit = limit(arguments.get("limit"));

        List<Dish> dishes;
        if (keyword == null)
        {
            // 没给关键词 → 查在售列表。这条分支让工具在"现在有什么菜"这种泛问下也能用
            dishes = dishService.listOnSaleDishes();
        }
        else
        {
            Dish query = new Dish();
            query.setName(keyword);
            dishes = dishService.selectDishList(query);
        }

        log.info("MCP 工具 query_dish 执行：keyword={}，命中 {} 条", keyword, dishes == null ? 0 : dishes.size());

        // 用 LinkedHashMap 而不是 Map.of：Map.of 不接受 null 值，
        // 而 price / name 理论上可能为 null（库里字段允许空），一旦为 null 会直接抛 NPE
        Map<String, Object> result = new LinkedHashMap<>();
        if (dishes == null || dishes.isEmpty())
        {
            result.put("total", 0);
            result.put("returned", 0);
            result.put("dishes", List.of());
            return result;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Dish dish : dishes)
        {
            if (rows.size() >= limit)
            {
                break;
            }
            rows.add(toMap(dish));
        }

        result.put("total", dishes.size());      // 命中总数
        result.put("returned", rows.size());     // 实际返回几条（可能被 limit 截断）
        result.put("dishes", rows);
        return result;
    }

    /**
     * 只挑三个字段。为什么不全给：
     * image / stock / version 是内部字段，跨进程暴露出去只会让客户端困惑，
     * 也让将来改库里结构变成破坏兼容的变更。
     */
    private Map<String, Object> toMap(Dish dish)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", dish.getName());
        m.put("price", dish.getPrice());
        // tb_dish.status：0 = 停售，1 = 在售
        m.put("onSale", Long.valueOf(1L).equals(dish.getStatus()));
        return m;
    }

    /** 取字符串参数：null / 空白都归一成 null，调用方只需判一次 */
    private String str(Object o)
    {
        if (o == null)
        {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 取 limit 参数。
     * JSON 里的数字反序列化出来可能是 Integer，也可能是 Long（看数值大小），
     * 所以按 Number 取，别直接强转 Integer —— 那是经典的 ClassCastException 来源。
     */
    private int limit(Object o)
    {
        if (!(o instanceof Number n))
        {
            return DEFAULT_LIMIT;
        }
        int v = n.intValue();
        if (v <= 0)
        {
            return DEFAULT_LIMIT;
        }
        return Math.min(v, MAX_LIMIT);
    }
}
