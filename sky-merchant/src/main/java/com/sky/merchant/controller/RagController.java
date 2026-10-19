package com.sky.merchant.controller;

import com.sky.common.core.controller.BaseController;
import com.sky.common.core.domain.AjaxResult;
import com.sky.merchant.constant.RagLimits;
import com.sky.merchant.rag.IRagService;
import com.sky.merchant.rag.RagAnswer;
import com.sky.merchant.rag.RagIndexRequest;
import com.sky.merchant.rag.VectorStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 【Day20】RAG 知识库入口。
 *
 * 四个动作，按使用顺序排：
 *   POST   /merchant/rag/index      入库（docName + text）
 *   GET    /merchant/rag/ask        提问（question）→ 回答 + 引用
 *   GET    /merchant/rag/exists     这篇文档入过库没有
 *   DELETE /merchant/rag/document   删掉一篇文档的全部块
 *
 * 为什么 index 用 POST、ask 用 GET：
 * 按 HTTP 语义分 —— POST 改状态（写库），GET 只读。
 * 顺带一个实际好处：GET 的 ask 可以直接在浏览器地址栏敲，调试时不用开工具。
 *
 * 这一层只做三件事：收参数、调服务、包 AjaxResult。任何切分/检索/组装逻辑都不许下沉到这里 ——
 * 否则以后加一个定时重建索引的任务，就得把逻辑从 Controller 里再抄一遍。
 *
 * 鉴权：没有 @Anonymous，所以和 /merchant/nl2sql/** 一样需要登录态。
 * 知识库问答会烧 token 额度，不该对匿名开放。
 *
 * @author ruoyi
 */
@SuppressWarnings("JavadocBlankLines")
@RestController
@RequestMapping("/merchant/rag")
public class RagController extends BaseController
{
    private final IRagService ragService;

    private final VectorStore vectorStore;

    public RagController(IRagService ragService, VectorStore vectorStore)
    {
        this.ragService = ragService;
        this.vectorStore = vectorStore;
    }

    /**
     * 把一篇文档切分、向量化并入库。
     *
     * 幂等：同一个 docName 重复提交 = 整篇重建，块数不会翻倍。
     * 所以演示时可以直接连着提交两次，用来展示"重复入库不会累积"。
     *
     * @param request 文档名 + 全文
     * @return docName 与实际写入的块数（父 + 子）
     */
    @PostMapping("/index")
    public AjaxResult index(@RequestBody RagIndexRequest request)
    {
        int chunks = ragService.indexDocument(request.getDocName(), request.getText());

        // 用 LinkedHashMap 而不是 Map.of：Map.of 的 key 顺序不稳定，
        // 响应体在 Apifox 里每次顺序不一样，看着容易以为出了问题
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docName", request.getDocName());
        data.put("chunks", chunks);
        return success(data);
    }

    /**
     * 提问。
     *
     * 注意这里传的是整个 RagAnswer 对象，不是 answer.getAnswer()：
     * 传 String 会命中 success(String message) 那个重载，字符串会跑到 msg 字段里、
     * data 变成 null —— 前端拿不到任何东西，但 HTTP 状态码还是 200，查起来很费劲。
     * 同理也不能传 answer.toString()。
     *
     * @param question 自然语言问题
     * @return RagAnswer（answer / citations / contextChunks / contextChars / error）
     */
    @GetMapping("/ask")
    public AjaxResult ask(@RequestParam String question)
    {
        RagAnswer answer = ragService.ask(question);
        return success(answer);
    }

    /**
     * 这篇文档是否已经入过库。
     * 用途：启动时或重建前先问一句，避免重复烧 embedding 额度。
     */
    @GetMapping("/exists")
    public AjaxResult exists(@RequestParam String docName)
    {
        return success(vectorStore.exists(docName));
    }

    /**
     * 上传一个 markdown / txt 文件并入库。
     *
     * 为什么不落盘：这份文档唯一的用途就是变成块进数据库。落盘只是多一份会过期的副本，
     * 还要额外管清理、管路径穿越。读进内存、入完就丢，既省事又安全。
     *
     * 为什么只收纯文本：PDF / Word 直接读字节出来是乱码，乱码照样能切块、能算向量、能入库，
     * 全程不报错 —— 只是这个文档永远搜不到。宁可在门口拒掉。
     *
     * @param file    上传的文件，扩展名限 md / markdown / txt
     * @param docName 可选。不给就用文件名（去掉扩展名）当文档名
     */
    @PostMapping("/upload")
    public AjaxResult upload(@RequestParam("file") MultipartFile file,
                             @RequestParam(value = "docName", required = false) String docName)
    {
        if (file == null || file.isEmpty())
        {
            return error("上传的文件为空");
        }
        // 先卡大小再读内容：读进内存之后再判断，大文件已经把堆吃掉了
        if (file.getSize() > RagLimits.MAX_UPLOAD_BYTES)
        {
            return error("文件过大：上限 " + RagLimits.MAX_UPLOAD_BYTES + " 字节，实际 "
                    + file.getSize() + " 字节。大文档请先拆分再分别入库");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank())
        {
            return error("拿不到文件名");
        }
        String extension = extensionOf(originalName);
        if (!RagLimits.ALLOWED_EXTENSIONS.contains(extension))
        {
            return error("不支持的文件类型「" + extension + "」，只收 " + RagLimits.ALLOWED_EXTENSIONS);
        }

        String text;
        try
        {
            // 必须显式指定 UTF-8。用平台默认编码的话，在 GBK 的机器上中文会读成乱码，
            // 而乱码内容照样能切块、能算向量、能入库 —— 不报错，只是永远搜不到想搜的东西
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            return error("读取文件失败：" + e.getMessage());
        }

        String name = (docName != null && !docName.isBlank())
                ? docName.trim()
                : stripExtension(originalName);

        int chunks = ragService.indexDocument(name, text);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docName", name);
        data.put("fileName", originalName);
        data.put("bytes", file.getSize());
        data.put("chars", text.length());
        data.put("chunks", chunks);
        return success(data);
    }

    /**
     * 取扩展名并转小写。没有点号时返回空串（空串不在白名单里，自然被拒）。
     */
    private static String extensionOf(String fileName)
    {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 去掉扩展名，作为默认文档名。
     * dot 在 0 号位的情况（.gitignore 这种隐藏文件）保守地原样返回。
     */
    private static String stripExtension(String fileName)
    {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /**
     * 删除一篇文档的全部块（父块和子块一起删）。
     * 返回删除行数，方便确认"确实删掉了"而不是删了个不存在的东西。
     */
    @DeleteMapping("/document")
    public AjaxResult removeDocument(@RequestParam String docName)
    {
        int rows = vectorStore.deleteDocument(docName);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docName", docName);
        data.put("deletedRows", rows);
        return success(data);
    }
}
