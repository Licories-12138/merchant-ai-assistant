package com.sky.merchant.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由注册验收：确认 RagController 的四个接口真的挂在文档里写的路径上。
 *
 * 为什么要单独测这个：
 * 路径写错（/indexs、/rag/asks、少个前缀）不会有任何编译错误，
 * 表现只是"调的时候 404"。而 404 在若依里还可能是被 Security 拦的，
 * 两种原因现象一样，排查时容易往鉴权上查半天。
 * 把"实际注册了什么"直接打出来，这个问题就不存在了。
 *
 * 这个测试不打库、不调模型，只读 Spring 维护的映射表。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("RAG 接口路由验收")
class RagControllerRouteTest
{
    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("四个接口都注册在 /merchant/rag 下，方法也匹配")
    void routesShouldBeRegistered()
    {
        Set<String> actual = new HashSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet())
        {
            for (String pattern : info.getPatternValues())
            {
                if (!pattern.startsWith("/merchant/rag"))
                {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods())
                {
                    actual.add(method.name() + " " + pattern);
                }
            }
        }

        System.out.println();
        System.out.println("---------- 已注册的 /merchant/rag 路由 ----------");
        actual.stream().sorted().forEach(System.out::println);
        System.out.println("-----------------------------------------------");

        assertTrue(actual.contains("POST /merchant/rag/index"), "缺入库接口：" + actual);
        assertTrue(actual.contains("GET /merchant/rag/ask"), "缺问答接口：" + actual);
        assertTrue(actual.contains("GET /merchant/rag/exists"), "缺存在判断接口：" + actual);
        assertTrue(actual.contains("DELETE /merchant/rag/document"), "缺删除接口：" + actual);
    }
}
