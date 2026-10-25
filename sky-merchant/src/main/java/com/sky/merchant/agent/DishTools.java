package com.sky.merchant.agent;

import java.math.BigDecimal;
import java.util.List;
import com.sky.merchant.service.IDishService;
import com.sky.merchant.constant.RedisKeys;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import com.sky.merchant.domain.Dish;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 自然语言点单 Agent 的工具集：菜品查询
 * 和 llm-demo 的 OrderTools 是同一套机制，区别只有一个：
 * 数据从「内存假数据」换成了「真实 MySQL 的 tb_dish 表」。
 * 模型不执行代码，它只返回"我想调 searchDishByName，参数是 宫保鸡丁"，
 * Spring AI 真正去执行这里的方法，查完库把结果喂回模型，模型再组织成人话。
 */
@Component
public class DishTools
{
    @Autowired
    private IDishService dishService;
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;
    @Autowired
    private ChatContextStore chatContextStore;

    /**
     * 按菜名模糊查询菜品。
     * 复用 DishMapper.selectDishList，SQL 里 name like concat('%', #{name}, '%')。
     */
    @Tool(description = "根据菜名关键词模糊查询菜品，返回菜名、售价和在售状态。例如用户问「宫保鸡丁多少钱」或「有哪些鱼香肉丝」时调用")
    public String searchDishByName(
            @ToolParam(description = "菜名关键词，例如「宫保鸡丁」或「鱼香肉丝」") String name,
            ToolContext toolContext)
    {
        System.out.println(">>> [Agent] 模型调用 searchDishByName，参数 name = " + name);

        Dish query = new Dish();
        query.setName(name);
        // 增加热门搜索次数
        redisTemplate.opsForZSet().incrementScore(RedisKeys.DISH_HOT_KEY, name.trim(), 1);
        List<Dish> list = dishService.selectDishList(query);

        // 新增：把工具真实返回打出来，用来判断模型是不是真的用了查库结果
        System.out.println(">>> [Agent] searchDishByName 命中 "
                + (list == null ? 0 : list.size()) + " 条: "
                + (list == null ? "" : list.stream()
                                       .map(this::formatDish)
                                       .reduce((a, b) -> a + "；" + b)
                                       .orElse("")));

        if (list == null || list.isEmpty())
        {
            return "没有找到名字包含「" + name + "」的菜品";
        }
        // 查到了才写：上面空列表已经提前 return，走到这里 list 必然非空；
        // putAll 内部对空列表也会直接返回，属于双保险
        chatContextStore.putAll(ctxSessionId(toolContext), list, name);

        return list.stream()
                .map(this::formatDish)
                .reduce((a, b) -> a + "；" + b)
                .orElse("");
    }

    /**
     * 查在售菜品列表。
     * tb_dish.status 的取值以字典 dish_status 为准：0 = 停售，1 = 在售（启用）。
     * 所以「在售」= status 1（DishServiceImpl.listOnSaleDishes 里也是 setStatus(1L)）。
     */
    @Tool(description = "查询当前在售的菜品列表，返回菜名和售价。例如用户问「现在有什么菜」或「在卖的菜有哪些」时调用")
    public String listOnSaleDishes()
    {
        System.out.println(">>> [Agent] 模型调用 listOnSaleDishes，查询在售菜品");

        List<Dish> list = dishService.listOnSaleDishes();

        if (list == null || list.isEmpty())
        {
            return "当前没有在售的菜品";
        }
        return list.stream()
                .map(this::formatDish)
                .reduce((a, b) -> a + "；" + b)
                .orElse("");
    }

    /**
     * 从 ToolContext 里取会话 id。
     * ToolContext 模型看不见（JsonSchemaGenerator 明确把它排除在 JSON Schema 之外），
     * 它是框架在调用工具时旁路塞进来的 —— 工具靠它才知道「这次查库属于哪个会话」。
     *
     * 为什么只判 toolContext 本身、不判 getContext()：
     * ToolContext 构造器用 Collections.unmodifiableMap 包了一层，传 null 当场 NPE，
     * 所以实例一旦造出来，context 就永远不是 null，判它是死代码（IDE 会直接标出来）。
     * 而 toolContext 本身仍要判一次 —— 框架的校验只在 MethodToolCallback.call 那条路上兜底，
     * 单测里直接调这个方法时拿不到框架塞的值。
     *
     * 返回 null 时 putAll 会直接跳过：宁可这轮不记上下文，也不许把事实写进
     * 一个 null 拼出来的 key（那会变成一个所有会话共用的脏桶）。
     */
    private String ctxSessionId(ToolContext toolContext)
    {
        if (toolContext == null)
        {
            return null;
        }
        Object value = toolContext.getContext().get(ChatContextStore.SESSION_ID_KEY);
        return value == null ? null : value.toString();
    }

    /**
     * 把 Dish 对象转成模型能读懂的字符串。
     * 返回对象也行，但返回字符串最稳，模型组织语言时信息不丢。
     */
    private String formatDish(Dish d)
    {
        // 状态文案按字典 dish_status：1 = 在售（启用），0 = 停售。
        // listOnSaleDishes 只查 status=1，走不到"停售"分支；
        // searchDishByName 是模糊查，可能带出任意状态的菜，那时才会显示"停售"。
        String statusText = "1".equals(String.valueOf(d.getStatus())) ? "在售" : "停售";
        BigDecimal price = d.getPrice() == null ? BigDecimal.ZERO : d.getPrice();
        String desc = d.getDescription() == null ? "" : ("，简介：" + d.getDescription());
        return "「" + d.getName() + "」售价 " + price + " 元，状态" + statusText + desc;
    }
}
