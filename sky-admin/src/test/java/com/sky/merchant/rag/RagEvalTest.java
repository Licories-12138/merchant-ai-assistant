package com.sky.merchant.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 的 10 题评测集：只为拿到「一个准确率数字」，不调参、不做对照实验。
 *
 * ============================================================
 * 为什么判分要这么设计
 * ============================================================
 * 判分只做一件事：**答案里有没有出现该题的关键事实**（原文精确包含）。
 *
 * 为什么不用 bigram 覆盖率之类的自动指标：那东西会误判 ——
 * 昨天在测试线上踩过，覆盖率高但答案根本没答到点上。
 *
 * 为什么不判「语义是否等价」：那需要人或者另一个模型当裁判。
 * 人判太慢，模型判要翻倍调用而且它也会错。
 *
 * 所以这里的定位是：**机器只负责跑和记，判分留给人的眼睛。**
 * expectedFacts 只选专有名词、参数名、数字 —— 这些模型不会改写，
 * 精确包含就能判；而「讲得对不对」这种判断，脚本给不了，硬给就是自欺。
 *
 * 输出里每题都会打印答案全文，人工复核时照着 expectedFacts 看即可。
 *
 * ============================================================
 * 前提
 * ============================================================
 * 库里必须有 Day15 这篇文档。没有的话测试直接失败并给出提示，
 * 因为「对空库评测出的准确率」没有任何意义。
 */
@SuppressWarnings("JavadocBlankLines")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("RAG · 10 题评测")
class RagEvalTest
{
    private static final String DOC = "Day15";

    @Autowired
    private IRagService ragService;

    @Autowired
    private VectorStore vectorStore;

    /** 一道评测题：问题 + 关键事实（答案里必须出现这些，才会被机器判为命中） */
    private record EvalCase(String question, List<String> facts)
    {
        EvalCase(String question, String... facts)
        {
            this(question, List.of(facts));
        }
    }

    /**
     * 10 题覆盖 Day15 的各个小节：持久化三件套、AOF 三档、混合持久化、
     * 首次同步握手、断点续传三要素、版本特性、实验结论。
     * expectedFacts 全部是参数名 / 日志原文 / 数字，模型改写不了。
     */
    private static final List<EvalCase> CASES = List.of(
            new EvalCase("redis.conf 里 RDB 的默认 save 规则有哪三条？",
                    "3600", "300", "10000"),
            new EvalCase("appendfsync 有哪三档？默认是哪一档？",
                    "always", "everysec", "no"),
            new EvalCase("appendfsync 用 everysec 时，最多会丢多少数据？",
                    "1 秒"),
            new EvalCase("混合持久化里那个 base 文件是什么格式？",
                    "RDB"),
            new EvalCase("从节点第一次连主库，日志里哪一句说明不能增量续传？",
                    "Partial resynchronization not possible", "cached master"),
            new EvalCase("断点续传到底依靠哪三个要素？",
                    "replid", "offset", "repl_backlog"),
            new EvalCase("主库判断能不能增量续传，要核对哪两件事？",
                    "replid", "offset"),
            new EvalCase("repl_backlog 默认多大？为什么它决定了能不能续传？",
                    // 判分点只取「默认大小」和「判断依据」，不要求出现「环形」：
                    // 题目问的是"多大 + 为什么"，不是"什么结构"。第一版把「环形」也算进必命中，
                    // 结果一条答得完全正确的回答被自动判成待复核 —— 那是出题的问题，不是系统的问题。
                    "1MB", "offset"),
            new EvalCase("全量同步之前为什么会先延迟约 5 秒？",
                    "repl-diskless-sync-delay"),
            new EvalCase("实验 B 断链重连之后，哪个计数器增加、哪个不变？",
                    "sync_partial_ok", "sync_full")
    );

    @Test
    @DisplayName("跑一遍 10 题，输出准确率与逐题明细")
    void runEval()
    {
        // 前置：库空就不测。对空库算出来的准确率是自欺
        assertTrue(vectorStore.exists(DOC),
                "库里没有文档 " + DOC + "，请先入库再评测（上传 Day15 笔记或调 /merchant/rag/index）");

        int fullHit = 0;
        long totalMs = 0;
        List<String> report = new ArrayList<>();

        for (int i = 0; i < CASES.size(); i++)
        {
            EvalCase c = CASES.get(i);
            long start = System.nanoTime();

            RagAnswer ans = ragService.ask(c.question());
            assertNotNull(ans, "第 " + (i + 1) + " 题返回 null");
            assertTrue(ans.getError() == null, "第 " + (i + 1) + " 题报错：" + ans.getError());

            long ms = (System.nanoTime() - start) / 1_000_000L;
            totalMs += ms;

            String text = ans.getAnswer() == null ? "" : ans.getAnswer();
            List<String> miss = new ArrayList<>();
            for (String f : c.facts())
            {
                if (!containsLoosely(text, f))
                {
                    miss.add(f);
                }
            }
            boolean allHit = miss.isEmpty();
            if (allHit)
            {
                fullHit++;
            }

            report.add(String.format(
                    "第 %2d 题 | %s | 命中 %d/%d | %5d ms | 上下文 %4d 字 | 引用 %d 条%n"
                            + "   问：%s%n"
                            + "   答：%s%n"
                            + "   缺：%s%n",
                    i + 1,
                    allHit ? "通过" : "待复核",
                    c.facts().size() - miss.size(), c.facts().size(),
                    ms, ans.getContextChars(),
                    ans.getCitations() == null ? 0 : ans.getCitations().size(),
                    c.question(),
                    text,
                    miss.isEmpty() ? "-" : String.join("、", miss)));

            // 没全命中的题，把召回的片段开头也打出来 —— 否则只能说"这题挂了"，说不出"为什么挂"。
            // 是"原文没被召回"，还是"召回了但模型没引用"，看这一行就能分清。
            if (!allHit && ans.getCitations() != null)
            {
                StringBuilder cites = new StringBuilder("   召回：");
                for (RagCitation ct : ans.getCitations())
                {
                    String sn = ct.getSnippet() == null ? "" : ct.getSnippet().replaceAll("\\s+", " ");
                    cites.append(String.format("%n     - [%.3f] %s", ct.getScore(),
                            sn.length() > 60 ? sn.substring(0, 60) : sn));
                }
                report.add(cites.toString());
            }
        }

        System.out.println();
        System.out.println("================ RAG 10 题评测结果 ================");
        report.forEach(System.out::println);
        System.out.println("---------------------------------------------------");
        System.out.printf("自动判分：%d / %d = %.0f%%（标准：答案含全部关键事实）%n",
                fullHit, CASES.size(), fullHit * 100.0 / CASES.size());
        System.out.printf("总耗时：%d ms，平均每题 %d ms%n", totalMs, totalMs / CASES.size());
        System.out.println("⚠️ 「待复核」不代表答错 —— 可能是同义改写导致关键词没精确命中，请人工看答案。");
        System.out.println("===================================================");
    }

    /**
     * 宽松包含：去掉所有空白再比，避免模型写出 "1 秒" / "1秒" / "repl_backlog " 这类差异。
     * 英文统一转小写。
     */
    private static boolean containsLoosely(String text, String fact)
    {
        return normalize(text).contains(normalize(fact));
    }

    private static String normalize(String s)
    {
        return s.replaceAll("\\s+", "").toLowerCase();
    }
}
