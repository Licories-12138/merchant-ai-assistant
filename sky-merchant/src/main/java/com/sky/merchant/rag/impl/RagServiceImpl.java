package com.sky.merchant.rag.impl;

import lombok.extern.slf4j.Slf4j;

import com.sky.merchant.constant.RagLimits;
import com.sky.merchant.rag.*;
import com.sky.merchant.rag.splitter.MarkdownHeaderSplitter;
import com.sky.merchant.rag.splitter.RecursiveCharacterSplitter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAG 的主流程实现。
 *
 * 两个方法的职责完全分开：
 *   indexDocument —— 把一篇文档变成库里的一堆父子块（只写，不检索）
 *   ask           —— 从问题出发捞回父块、让模型作答（只读）
 *
 * 为什么这个类里有"脑子"而 MysqlVectorStore 里没有：
 * 切分、向量化、排序、折算父块，全是跟存储无关的算法。
 * 放在存储实现里的话，将来换 PostgreSQL 就得把算法重写一遍。
 */
@SuppressWarnings("JavadocBlankLines")
@Slf4j
@Service
public class RagServiceImpl implements IRagService
{
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final ChatClient chatClient;

    public RagServiceImpl(VectorStore vectorStore,
                          EmbeddingModel embeddingModel,
                          ChatClient.Builder builder)
    {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        // clone 是防御性的：Builder 是单例 Bean，DishAgentController 和 Nl2SqlServiceImpl 也在用，
        // 不 clone 的话这里对它的任何改动都会串到别的链路上去（Day8 踩过）
        this.chatClient = builder.clone().build();
    }

    // ============================================================
    // 入库
    // ============================================================

    @Override
    public int indexDocument(String docName, String text)
    {
        // 0) 入参校验：docName 是逻辑主键，空值没有意义；text 空了也没什么可入的
        if (docName == null || docName.isBlank())
        {
            throw new IllegalArgumentException("docName 不能为空");
        }
        if (text == null || text.isBlank())
        {
            throw new IllegalArgumentException("文档内容为空：" + docName);
        }

        // 1) 幂等：同一个 docName 重复调用 = 整篇重建，先删旧块
        //    不删的话，改过切分参数后新旧两套块会混在一起，检索时互相稀释
        vectorStore.deleteDocument(docName);

        // 2) 切父块（按 Markdown 标题层级，超长才降级成按字符切）
        //    切之前先剥掉文档头部的「标题 + 元信息 + 导语」：
        //    那段是元信息不是知识，却对任何问题都有中等相似度，于是长期霸占第 1 名。
        //    2026-10-19 实测：问「断点续传依靠哪三个要素」，它以 0.7113 稳居第 1，
        //    真正装答案的 3.2 节只有 0.6270 —— 这类「万能块」是 Hit@1 的头号杀手。
        List<String> parents = splitToTexts(
                new MarkdownHeaderSplitter(RagLimits.PARENT_SIZE, RagLimits.PARENT_OVERLAP),
                stripFrontMatter(text, docName));

        // 3) 父块逐个落库（要拿 id），同时切子块并填 parentId
        List<RagChunk> children = new ArrayList<>();
        TextSplitter childSplitter = new RecursiveCharacterSplitter(RagLimits.CHILD_SIZE, 0);
        int parentSeq = 0;
        int childSeq = 0;

        for (String parentText : parents)
        {
            // 3.1  造父块：buildChunk(docName, null, RagLimits.LEVEL_PARENT, parentSeq++, parentText)
            RagChunk parent = buildChunk(docName, null, RagLimits.LEVEL_PARENT, parentSeq++, parentText);

            // 3.2  存父块，拿到自增 id（返回值是 Long）
            Long parentId = vectorStore.saveParent(parent);
            // 3.3  切子块：splitToTexts(childSplitter, parentText)
            //           每个子块都要 setParentId(parentId)、setSeq(childSeq++)
            //           seq 必须全局递增，不能每个父块内部从 0 重来
            //           loadAllChildren 是按 seq 排序的，内层从 0 重来会让不同父块的子块交错，
            //           拼出来的上下文顺序就乱了
            //           一个父块切不出子块时，可以考虑把父块原文当作它唯一的子块
            //           否则这个父块永远检索不到（因为检索只在子块上做）
            for (String childText : splitToTexts(childSplitter, parentText)) {
                children.add(buildChunk(docName, parentId, RagLimits.LEVEL_CHILD, childSeq++, childText));
            }
        }

        // 4) 批量向量化：只给子块算，父块不算（父块只负责被读，不参与相似度比较）
        //    一批 EMBED_BATCH 条，别一次把几百条全塞进一次请求
        // 4.1  子块内容列表（顺序必须与 children 一一下标对应，第 5 步靠下标回填）
        List<String> contents = children.stream().map(RagChunk::getContent).toList();
        // 4.2  List<float[]> vectors = 按批调 embeddingModel.embed(batch)，拼起来
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < contents.size(); i += RagLimits.EMBED_BATCH){
            int end = Math.min(i + RagLimits.EMBED_BATCH, contents.size());
            vectors.addAll(embeddingModel.embed(contents.subList(i, end)));
        }
        // embed 返回的 List 顺序与入参一致，可以按下标直接对回去

        // 5) 回填向量（第 i 个向量对应第 i 个子块）
        //    数量必须严格相等：少了说明某批 embed 没返回够，此时按下标回填会错位，
        //    错位比抛异常更难查 —— 直接在这里挡住
        if (vectors.size() != children.size())
        {
            throw new IllegalStateException(
                    "向量数量与子块数量不一致：blocks=" + children.size() + ", vectors=" + vectors.size()
                            + "，请检查 embeddingModel.embed 是否漏返回");
        }
        for (int i = 0; i < children.size(); i++) {
            children.get(i).setEmbedding(vectors.get(i));
        }
        // 6) 一次批量写入子块（别循环单条 saveChildren）
        vectorStore.saveChildren(children);

        return parents.size() + children.size();
    }


    // ============================================================
    // 提问
    // ============================================================

    /** 引用里展示的原文片段长度。只影响给人看的 snippet，不影响送进模型的上下文 */
    private static final int SNIPPET_MAX = 80;

    private static final String SYSTEM_PROMPT = """
            你是门店经营系统的知识库助手。只能依据用户提供的资料回答问题。
            规则：
            1) 资料里没有的内容，直接回答"资料未覆盖"，不要猜测、不要用常识补充；
            2) 回答控制在 3 句话以内，先给结论再给依据；
            3) 不要输出"根据资料""片段 1"这类前置说明，直接说结论。
            """;

    @Override
    public RagAnswer ask(String question)
    {
        // 1) 入参校验
        if (question == null || question.isBlank())
        {
            throw new IllegalArgumentException("question 不能为空");
        }
        RagAnswer answer = new RagAnswer();

        // 2) 库里没东西就别调模型 —— 省一次往返，也避免模型凭空编
        List<RagChunk> children = vectorStore.loadAllChildren();
        if (children.isEmpty())
        {
            answer.setAnswer("");
            answer.setCitations(List.of());
            answer.setError("知识库为空，请先调用 indexDocument 入库");
            return answer;
        }

        // 3) 问题向量化：整条链路只有这一次 embedding 调用
        float[] queryVector = embeddingModel.embed(question);

        // 4) 打分 + 折算父块
        List<RetrievalHit> hits = rankByCosine(queryVector, children);
        if (hits.isEmpty())
        {
            answer.setAnswer("");
            answer.setCitations(List.of());
            answer.setError("未检索到相关片段");
            return answer;
        }

        // 5) 取前 TOP_K 个父块
        List<RetrievalHit> top = hits.subList(0, Math.min(RagLimits.TOP_K, hits.size()));

        // 6) 按 id 批量取父块原文（顺序不保证，所以要自己建 Map）
        List<Long> parentIds = top.stream().map(RetrievalHit::getParentId).toList();
        Map<Long, RagChunk> parentById = vectorStore.loadParents(parentIds).stream()
                .collect(Collectors.toMap(RagChunk::getId, Function.identity()));

        // 6.1 回填 snippet / parentSeq —— 父块原文是这一刻才拿到的，
        //     rankByCosine 里填不了这两个字段。少了这一步，下一步按 parentSeq 排序会 NPE。
        List<RetrievalHit> usable = new ArrayList<>();
        for (RetrievalHit hit : top)
        {
            RagChunk parent = parentById.get(hit.getParentId());
            if (parent == null)  continue;          // 父块在重建索引时被删掉了
            hit.setSnippet(parent.getContent());
            hit.setParentSeq(parent.getSeq());
            usable.add(hit);
        }

        // 7) 拼上下文 —— 按原文顺序，不是按相关度顺序
        //    相关度只用来决定"取哪几段"；取到之后按 seq 排回原文顺序，
        //    相邻小节读起来是连贯的一篇，否则模型看到的是打乱的书页
        List<RetrievalHit> ordered = usable.stream()
                .sorted(Comparator.comparing(RetrievalHit::getParentSeq))
                .toList();

        StringBuilder context = new StringBuilder();
        for (RetrievalHit hit : ordered)
        {
            context.append("【片段 ").append(hit.getParentSeq()).append("】\n")
                    .append(parentById.get(hit.getParentId()).getContent())
                    .append("\n\n");
        }

        // 8) 调模型
        String userPrompt = "资料：\n" + context + "\n问题：" + question;
        String text = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();
        answer.setAnswer(text == null ? "" : text);

        // 9) 引用按相关度降序（用 top 的原顺序，不是 ordered）
        //    引用是给人看的，相关度优先；上下文是给模型读的，连贯性优先 —— 两者排序不同是故意的
        List<RagCitation> citations = new ArrayList<>();
        for (RetrievalHit hit : usable)
        {
            RagCitation citation = new RagCitation();
            citation.setChunkId(hit.getParentId());
            citation.setDocName(hit.getDocName());
            citation.setSnippet(truncate(hit.getSnippet()));
            citation.setScore(hit.getScore());
            citations.add(citation);
        }
        answer.setCitations(citations);

        // 10) 成本观测：这两个数字是简历上"上下文压缩"的唯一证据来源
        answer.setContextChunks(ordered.size());
        answer.setContextChars(context.length());
        return answer;
    }

    /**
     * 向量路打分 + 折算父块。返回按相关度降序、已折算到父块的命中列表。
     *
     * 契约（必须严格满足，否则第 7 步拼上下文会 NPE 或串位）：
     *   1) 返回单位是父块，不是子块
     *   2) parentId / docName / parentSeq / snippet 四个字段必须填全，snippet 用父块原文
     *   3) score 填该父块名下最高的那个子块余弦分
     *   4) childId / childRank 填贡献了这个最高分的子块及其在子块全序里的名次
     *   5) 列表按 score 降序，同分用 parentId 做 tie-break（否则每次跑结果不一样，排障会疯）
     *
     * @param queryVector 问题向量，维度与块向量一致（当前 1024）
     * @param children    全量子块，embedding 已填
     */
    static List<RetrievalHit> rankByCosine(float[] queryVector, List<RagChunk> children)
    {
        // 0) 空输入直接返回，避免下游 NPE
        if (queryVector == null || queryVector.length == 0  || children == null || children.isEmpty()) {
            return List.of();
        }
        // 1) 逐子块算余弦分
        List<ScoredChild> scored = new ArrayList<>();
        for (RagChunk child : children) {
            float[] vec = child.getEmbedding();
            if (vec == null)  continue;                          // 没存向量的脏数据
            if (vec.length != queryVector.length)  continue;     // 换过 embedding 模型但没重建索引
            scored.add(new ScoredChild(child, cosine(queryVector, vec), 0));
        }
        if (scored.isEmpty())
        {
            return List.of();
        }
        // 2) 排全序并把名次写进对象
        scored.sort(Comparator.comparingDouble(ScoredChild::score).reversed());
        List<ScoredChild> ranked = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            ScoredChild sc = scored.get(i);
            ranked.add(new ScoredChild(sc.chunk(), sc.score(), i + 1));
        }

        // 3) 折算父块
        Map<Long, ScoredChild> bestByParent = foldToParents(ranked);

        // 4) 父块按分降序，同分用 parentId 兜底
        List<ScoredChild> bestList = new ArrayList<>(bestByParent.values());
        bestList.sort(Comparator.comparingDouble(ScoredChild::score).reversed()
                .thenComparingLong((ScoredChild sc) -> sc.chunk().getParentId()));

        // 5) 拼成结果结构
        return getRetrievalHits(bestList);
    }

    /**
     * 将 ScoredChild 列表转成 RetrievalHit 列表。
     * @param bestList 折算后的子块列表，包含 rank 字段
     * @return 检索结果列表
     */
    private static List<RetrievalHit> getRetrievalHits(List<ScoredChild> bestList) {
        List<RetrievalHit> hits = new ArrayList<>(bestList.size());
        for (ScoredChild best : bestList) {
            RagChunk child = best.chunk();          // 当前子块
            RetrievalHit hit = new RetrievalHit();  // 当前子块对应的检索结果对象
            hit.setParentId(child.getParentId());   // 当前子块的父块 id
            hit.setDocName(child.getDocName());     // 当前子块的文档名
            hit.setScore(best.score());             // 当前子块的余弦分
            hit.setChildId(child.getId());          // 当前子块的 id
            hit.setChildRank(best.rank());          // 当前子块在子块全序里的名次
            // snippet 与 parentSeq 留空 —— 父块原文还没取，由 ask 第 6 步回填
            hits.add(hit);
        }
        return hits;
    }

    /**
     * 截断到可读长度。snippet 只用于前端展示，全文由前端按 chunkId 再取。
     *
     * 长度提成常量、不再当参数传：只有一个调用点，参数化了也没人会传别的值，
     * 反而让 IDEA 报"参数永远是 80"。数字有名字，以后要改也知道改的是什么。
     */
    private static String truncate(String text)
    {
        if (text == null)  return "";
        return text.length() <= SNIPPET_MAX
                ? text
                : text.substring(0, SNIPPET_MAX) + "...";
    }


    // ============================================================
    // 工具
    // ============================================================

    /**
     * 剥掉 Markdown 文档头部的「一级标题 + 元信息 + 导语」，只留正文。
     *
     * 为什么需要这一步：
     * 文档开头那段（标题、日期、实验目录、"承接上一天……"、本日排期、产出）
     * 是元信息，不是知识。它对任何问题都有一点点像，于是长期霸占检索第 1 名，
     * 把真正装答案的那一节挤到第 2。而"第 1 名是谁"恰恰是演示和评测里最常被看的东西。
     *
     * 判定规则，三条同时满足才剥 —— 宁可不剥，也不要误删正文：
     *   1) 文档以 "# " 开头。这是「一级标题 + 正文」的笔记结构；
     *      以 "## " 开头的说明第一段就是正文，不碰
     *   2) 存在 "\n## "。有二级标题，说明正文确实从某一级标题往下开始
     *   3) 头部占全文不到 40%，且剩下的正文不少于 500 字
     *      这两条是保险：短文档里"头部"很可能就是全部内容
     *
     * 代价要说清楚：剥掉之后，问「这篇文档讲了什么」这种元问题会搜不到答案。
     * 门店知识库的提问都是具体业务问题，不吃这个代价；
     * 换成产品手册、FAQ 这类语料时要重新评估。
     */
    static String stripFrontMatter(String text, String docName)
    {
        if (!text.startsWith("# "))
        {
            return text;
        }
        int idx = text.indexOf("\n## ");
        if (idx < 0)
        {
            return text;
        }

        String head = text.substring(0, idx);
        String body = text.substring(idx + 1);
        if (head.length() > text.length() * 0.4 || body.length() < 500)
        {
            return text;
        }

        log.info("剥离文档头部元信息：docName={}，丢掉 {} 字，保留正文 {} 字",
                docName, head.length(), body.length());
        return body;
    }

    /**
     * 切分并丢掉空块。
     *
     * 为什么必须过滤空白块：切分器在标题边界、代码块边界可能吐出 "" 或纯换行，
     * 它们进了库会污染关键词路的分母（IDF 的计算基础），而且白占一次 embedding 调用。
     */
    private List<String> splitToTexts(TextSplitter splitter, String text)
    {
        List<String> chunks = new ArrayList<>();
        for (Document doc : splitter.split(new Document(text)))
        {
            String content = doc.getText();
            if (content != null && !content.isBlank())
            {
                chunks.add(content);
            }
        }
        return chunks;
    }

    /**
     * 造一个块对象。
     *
     * @param parentId 父块传 null，子块传真实父块 id
     */
    private RagChunk buildChunk(String docName, Long parentId, int level, int seq, String content)
    {
        RagChunk chunk = new RagChunk();
        chunk.setDocName(docName);
        chunk.setParentId(parentId);
        chunk.setLevel(level);
        chunk.setSeq(seq);
        chunk.setContent(content);
        // embedding 先留空，子块在第 5 步统一回填；父块永远不填
        chunk.setEmbedding(null);
        // 增量重建时才需要，当前全删全插用不上
        chunk.setContentHash(null);
        return chunk;
    }

    /**
     * 检索内部用的中间结构：一个子块 + 它的余弦分 + 它在子块全序里的名次。
     * 用 record 是因为它只是个三元组，不需要 setter。
     */
    private record ScoredChild(RagChunk chunk, double score, int rank) {
    }

    /**
     * 折算：子块 → 父块。
     *
     * 输入 ranked 是已按余弦降序、rank 已填好的子块全序列表。
     * 输出 Map<parentId, 该父块名下 rank 最小（也就是分最高）的那个子块>。
     *
     * 为什么必须折算：不折算的话 top-5 可能全落在同一个父块的 5 个子块上 ——
     * 花了 5 个名额只拿到 1 段原文，等于白检索了 4 次。
     * 折算之后名次的语义变成：一个父块的名次 = 它名下最优子块的名次。
     *
     * 一个父块没有子块时它不会出现在结果里，这是对的：检索只在子块上做，
     * 没子块的父块本来就检索不到。
     */
    private static Map<Long, ScoredChild> foldToParents(List<ScoredChild> ranked)
    {
        // LinkedHashMap 只是让输出沿着"最优子块名次"的顺序长出来；
        // 下游第 4 步还会按分重排一次，所以这里用 HashMap 结果也一样。
        Map<Long, ScoredChild> bestByParent = new LinkedHashMap<>();
        for (ScoredChild sc : ranked)
        {
            // ranked 是降序的，所以某个 parentId 第一次出现时拿到的
            // 一定是它名下排名最好的那个子块 —— putIfAbsent 正好把"第一次"锁住。
            // 换成 put 的话，后面分更低的子块会覆盖掉前面的高分，这是本方法唯一的陷阱。
            bestByParent.putIfAbsent(sc.chunk().getParentId(), sc);
        }
        return bestByParent;
    }

    /**
     * 余弦相似度。
     *
     * 三个必须防的点，少一个就出 NaN 或错分：
     *   1) 长度不等 —— 换了 embedding 模型但没重建索引，判 0，别硬算
     *   2) 模长为 0 —— 全零向量会除出 NaN，NaN 参与排序会让整张榜乱掉
     *   3) 不假设已归一化 —— 归一化是优化不是保证，老老实实除模长
     */
    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0d;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na  += (double) a[i] * a[i];
            nb  += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0d ? 0d : dot / denom;
    }
}
